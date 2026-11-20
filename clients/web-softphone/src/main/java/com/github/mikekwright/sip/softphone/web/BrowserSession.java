package com.github.mikekwright.sip.softphone.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.mikekwright.sip.softphone.Softphone;
import com.github.mikekwright.sip.softphone.SoftphoneConfig;
import com.github.mikekwright.sip.softphone.SoftphoneListener;
import com.github.mikekwright.sip.softphone.call.Call;
import com.github.mikekwright.sip.softphone.call.CallDirection;
import com.github.mikekwright.sip.softphone.call.CallListener;
import com.github.mikekwright.sip.softphone.call.CallOptions;
import com.github.mikekwright.sip.softphone.call.CallState;
import com.github.mikekwright.sip.softphone.media.MediaFormat;
import com.github.mikekwright.sip.softphone.media.MediaStreamSpec;
import com.github.mikekwright.sip.softphone.media.audio.AudioSink;
import com.github.mikekwright.sip.softphone.media.video.EncodedVideoFrame;
import com.github.mikekwright.sip.softphone.media.video.VideoSink;
import com.github.mikekwright.sip.softphone.sip.flow.RegistrationState;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.session.TerminationReason;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;
import com.github.mikekwright.sip.softphone.sip.transport.SipMessageInterceptor;
import com.github.mikekwright.sip.softphone.sip.transport.TransportAddress;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * One browser tab: a {@link Softphone} of its own, bridged to the WebSocket. Implements the protocol described in
 * {@code docs/clients/web-softphone-protocol.md}:
 * <ul>
 *   <li>JSON commands from the browser map onto {@link Softphone} / {@link Call} methods;</li>
 *   <li>call, registration and SIP trace events become JSON messages;</li>
 *   <li>browser PCM feeds the call's {@link com.github.mikekwright.sip.softphone.media.audio.AudioSource}, decoded RTP
 *       audio is streamed back as PCM; encoded video frames pass through unchanged in both directions.</li>
 * </ul>
 * The browser therefore only does capture/rendering and WebCodecs encoding; every protocol decision is made by the
 * library.
 */
final class BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);
    private static final byte KIND_AUDIO = 0x01;
    private static final byte KIND_VIDEO = 0x02;

    private final Channel channel;
    private final WebSoftphoneOptions defaults;
    private final ObjectMapper json = new ObjectMapper();
    private final BrowserAudioSource audioIn = new BrowserAudioSource(8000);
    private volatile Softphone phone;
    private volatile Call call;
    private ScheduledFuture<?> statsTimer;

    BrowserSession(Channel channel, WebSoftphoneOptions defaults) {
        this.channel = channel;
        this.defaults = defaults;
        statsTimer = channel.eventLoop().scheduleAtFixedRate(this::sendStats, 2, 2, TimeUnit.SECONDS);
    }

    // ============================================================================================ inbound

    void onText(String text) {
        JsonNode msg;
        try {
            msg = json.readTree(text);
        } catch (Exception e) {
            error("invalid JSON: " + e.getMessage());
            return;
        }
        String type = msg.path("type").asText("");
        try {
            switch (type) {
                case "configure" -> configure(msg.path("account"));
                case "register" -> requirePhone().register().whenComplete((r, e) -> {
                    if (e != null) {
                        error("register failed: " + e.getMessage());
                    }
                });
                case "unregister" -> requirePhone().unregister();
                case "call" -> placeCall(msg.path("target").asText(), msg.path("video").asBoolean(false));
                case "answer" -> answer(msg.path("video").asBoolean(true));
                case "reject" -> requireCall().reject(603);
                case "hangup" -> requireCall().hangup();
                case "hold" -> requireCall().hold().whenComplete((v, e) -> {
                    if (e != null) {
                        error("hold failed: " + e.getMessage());
                    }
                });
                case "resume" -> requireCall().resume().whenComplete((v, e) -> {
                    if (e != null) {
                        error("resume failed: " + e.getMessage());
                    }
                });
                case "dtmf" -> requireCall().sendDtmf(msg.path("digits").asText());
                case "message" -> requirePhone().sendMessage(msg.path("target").asText(), msg.path("text").asText())
                        .whenComplete((r, e) -> {
                            if (e != null || (r != null && !r.isSuccess())) {
                                error("MESSAGE failed: " + (e != null ? e.getMessage() : r.statusCode()));
                            }
                        });
                case "keyframe" -> {
                    Call c = call;
                    if (c != null) {
                        c.requestKeyFrame();
                    }
                }
                default -> error("unknown message type '" + type + "'");
            }
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    void onBinary(ByteBuf data) {
        if (data.readableBytes() < 1) {
            return;
        }
        byte kind = data.getByte(data.readerIndex());
        Call c = call;
        if (kind == KIND_AUDIO) {
            byte[] bytes = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), bytes);
            audioIn.push(bytes, 1);
        } else if (kind == KIND_VIDEO && c != null && data.readableBytes() > 10) {
            int idx = data.readerIndex();
            boolean key = (data.getByte(idx + 1) & 1) != 0;
            long ts = data.getLong(idx + 2);
            byte[] frame = new byte[data.readableBytes() - 10];
            data.getBytes(idx + 10, frame);
            String codec = c.videoFormat().map(MediaFormat::encodingName).orElse("VP8");
            c.sendVideoFrame(new EncodedVideoFrame(frame, key, ts, codec));
        }
    }

    void close() {
        if (statsTimer != null) {
            statsTimer.cancel(false);
        }
        Softphone p = phone;
        phone = null;
        if (p != null) {
            p.stop();
        }
    }

    // ============================================================================================ commands

    private void configure(JsonNode a) {
        Softphone old = phone;
        phone = null;
        call = null;
        if (old != null) {
            old.stop().join();
        }
        SoftphoneConfig.Builder b = SoftphoneConfig.builder()
                .user(text(a, "user", "alice"))
                .domain(text(a, "domain", defaults.sipHost() != null ? defaults.sipHost() : "127.0.0.1"))
                .displayName(text(a, "displayName", null))
                .password(text(a, "password", null))
                .authUser(text(a, "authUser", null))
                .registrar(text(a, "registrar", null))
                .outboundProxy(text(a, "outboundProxy", null))
                .transport(TransportType.valueOf(text(a, "transport", "udp").toUpperCase(Locale.ROOT)))
                .sipPort(a.path("sipPort").asInt(0))
                .bindHost(defaults.sipBind())
                .advertisedHost(defaults.sipHost())
                .rtpPorts(defaults.rtpMin(), defaults.rtpMax())
                .userAgent("web-softphone/1.0");
        if (a.path("register").asBoolean(false) && text(a, "registrar", null) == null) {
            b.registrar("sip:" + text(a, "domain", "127.0.0.1"));
        }
        Softphone p = Softphone.create(b.build());
        p.addListener(new PhoneEvents());
        p.addInterceptor(new Trace());
        p.start().whenComplete((started, err) -> {
            if (err != null) {
                error("cannot start SIP: " + err.getMessage());
                return;
            }
            phone = p;
            ObjectNode ready = obj("ready");
            ready.put("localUri", p.localUri().toString());
            ready.put("sipPort", p.stack().transport().transport(p.config().transport()).localAddress().getPort());
            ready.put("transport", p.config().transport().name().toLowerCase(Locale.ROOT));
            send(ready);
            if (a.path("register").asBoolean(false)) {
                p.register();
            }
        });
    }

    private void placeCall(String target, boolean video) {
        if (call != null && call.state() != CallState.ENDED) {
            throw new IllegalStateException("a call is already active");
        }
        Call c = requirePhone().call(target, CallOptions.audioOnly().withVideo(video));
        attach(c);
        sendCallState(c, CallState.CALLING, null);
    }

    private void answer(boolean video) {
        Call c = requireCall();
        c.answer(CallOptions.audioOnly().withVideo(video && c.remoteOffersVideo())).whenComplete((v, e) -> {
            if (e != null) {
                error("answer failed: " + e.getMessage());
            }
        });
    }

    private void attach(Call c) {
        call = c;
        audioIn.clear();
        c.setAudioSource(audioIn);
        c.setAudioSink(new AudioSink() {
            @Override
            public void write(short[] samples, int sampleRate) {
                if (!channel.isActive()) {
                    return;
                }
                ByteBuf buf = Unpooled.buffer(1 + samples.length * 2);
                buf.writeByte(KIND_AUDIO);
                for (short s : samples) {
                    buf.writeShortLE(s);
                }
                channel.writeAndFlush(new BinaryWebSocketFrame(buf));
            }
        });
        c.setVideoSink(new VideoSink() {
            @Override
            public void onFrame(EncodedVideoFrame frame) {
                if (!channel.isActive() || (!channel.isWritable() && !frame.keyFrame())) {
                    return; // the browser cannot keep up: drop delta frames
                }
                ByteBuf buf = Unpooled.buffer(10 + frame.data().length);
                buf.writeByte(KIND_VIDEO);
                buf.writeByte(frame.keyFrame() ? 1 : 0);
                buf.writeLong(frame.timestampMicros());
                buf.writeBytes(frame.data());
                channel.writeAndFlush(new BinaryWebSocketFrame(buf));
            }
        });
        c.addListener(new CallEvents());
    }

    // ============================================================================================ events

    private final class PhoneEvents implements SoftphoneListener {
        @Override
        public void onIncomingCall(Call incoming) {
            Call active = call;
            if (active != null && active.state() != CallState.ENDED) {
                incoming.reject(486); // one call at a time per browser tab
                return;
            }
            attach(incoming);
            ObjectNode m = obj("incoming");
            m.put("callId", incoming.id());
            m.put("from", incoming.remoteParty().toString());
            m.put("video", incoming.remoteOffersVideo());
            send(m);
            sendCallState(incoming, CallState.INCOMING, null);
        }

        @Override
        public void onRegistrationStateChanged(RegistrationState state, int statusCode) {
            ObjectNode m = obj("registration");
            m.put("state", state.name());
            m.put("status", statusCode);
            send(m);
        }

        @Override
        public void onMessage(NameAddress from, String contentType, String body) {
            ObjectNode m = obj("message");
            m.put("from", from.toString());
            m.put("text", body);
            send(m);
        }
    }

    private final class CallEvents implements CallListener {
        @Override
        public void onStateChanged(Call c, CallState state) {
            if (state != CallState.ENDED) {
                sendCallState(c, state, null);
            }
        }

        @Override
        public void onMediaChanged(Call c) {
            ObjectNode m = obj("media");
            MediaStreamSpec audio = c.audioSpec().orElse(null);
            MediaStreamSpec video = c.videoSpec().orElse(null);
            int rate = audio == null ? 8000 : audio.sendFormat().clockRate();
            audioIn.setRate(rate);
            m.put("audioRate", rate);
            m.put("audioCodec", audio == null ? null : audio.sendFormat().encodingName() + "/" + rate);
            m.put("videoCodec", video == null ? null : video.sendFormat().encodingName().toUpperCase(Locale.ROOT));
            m.put("sendVideo", video != null && video.send());
            m.put("receiveVideo", video != null && video.receive());
            send(m);
        }

        @Override
        public void onHoldChanged(Call c, boolean localHold, boolean remoteHold) {
            if (c.state() == CallState.CONNECTED) {
                sendCallState(c, CallState.CONNECTED, null);
            }
        }

        @Override
        public void onDtmf(Call c, char digit, int durationMillis) {
            ObjectNode m = obj("dtmf");
            m.put("digit", String.valueOf(digit));
            send(m);
        }

        @Override
        public void onMessage(Call c, String contentType, String body) {
            ObjectNode m = obj("message");
            m.put("from", c.remoteParty().toString());
            m.put("text", body);
            send(m);
        }

        @Override
        public void onKeyFrameRequested(Call c) {
            send(obj("keyframeRequest"));
        }

        @Override
        public void onEnded(Call c, TerminationReason reason) {
            sendCallState(c, CallState.ENDED, reason);
            if (call == c) {
                call = null;
            }
        }
    }

    private final class Trace implements SipMessageInterceptor {
        @Override
        public SipMessage onReceive(InboundMessage inbound) {
            trace("in", inbound.source().toString(), inbound.message());
            return inbound.message();
        }

        @Override
        public SipMessage onSend(SipMessage message, TransportAddress destination) {
            trace("out", destination.toString(), message);
            return message;
        }

        private void trace(String direction, String remote, SipMessage message) {
            ObjectNode m = obj("sip");
            m.put("direction", direction);
            m.put("remote", remote);
            m.put("text", message.toString());
            send(m);
        }
    }

    private void sendCallState(Call c, CallState state, TerminationReason reason) {
        ObjectNode m = obj("callState");
        m.put("callId", c.id());
        String name = state.name();
        if (state == CallState.CONNECTED && c.isOnHold()) {
            name = "HOLD";
        } else if (state == CallState.CONNECTED && c.isRemoteHold()) {
            name = "REMOTE_HOLD";
        }
        m.put("state", name);
        m.put("remote", c.remoteParty().toString());
        m.put("direction", c.direction() == CallDirection.OUTGOING ? "outgoing" : "incoming");
        if (reason != null) {
            m.put("reason", reason.cause() + (reason.statusCode() > 0 ? " " + reason.statusCode() : "")
                    + (reason.reason() != null ? " " + reason.reason() : ""));
        }
        send(m);
    }

    private void sendStats() {
        Call c = call;
        if (c == null || c.state() != CallState.CONNECTED) {
            return;
        }
        ObjectNode m = obj("stats");
        c.audioStream().ifPresent(a -> {
            ObjectNode audio = m.putObject("audio");
            audio.put("sent", a.packetsSent());
            audio.put("received", a.packetsReceived());
            audio.put("lost", a.packetsLost());
        });
        c.videoStream().ifPresent(v -> {
            ObjectNode video = m.putObject("video");
            video.put("framesSent", v.framesSent());
            video.put("keyFrameRequests", v.keyFrameRequestsSent());
        });
        send(m);
    }

    // ============================================================================================ helpers

    private Softphone requirePhone() {
        Softphone p = phone;
        if (p == null) {
            throw new IllegalStateException("phone not configured yet");
        }
        return p;
    }

    private Call requireCall() {
        Call c = call;
        if (c == null) {
            throw new IllegalStateException("no active call");
        }
        return c;
    }

    private ObjectNode obj(String type) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        return n;
    }

    private void send(ObjectNode message) {
        if (channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(message.toString()));
        }
    }

    private void error(String message) {
        log.debug("Browser session error: {}", message);
        ObjectNode m = obj("error");
        m.put("message", message);
        send(m);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? fallback : v.asText().trim();
    }
}
