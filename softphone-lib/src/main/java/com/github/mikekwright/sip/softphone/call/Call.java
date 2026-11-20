package com.github.mikekwright.sip.softphone.call;

import com.github.mikekwright.sip.softphone.media.AudioStream;
import com.github.mikekwright.sip.softphone.media.AudioStreamListener;
import com.github.mikekwright.sip.softphone.media.MediaFormat;
import com.github.mikekwright.sip.softphone.media.MediaStreamSpec;
import com.github.mikekwright.sip.softphone.media.VideoStream;
import com.github.mikekwright.sip.softphone.media.audio.AudioSink;
import com.github.mikekwright.sip.softphone.media.audio.AudioSource;
import com.github.mikekwright.sip.softphone.media.video.EncodedVideoFrame;
import com.github.mikekwright.sip.softphone.media.video.VideoSink;
import com.github.mikekwright.sip.softphone.sdp.MediaDescription;
import com.github.mikekwright.sip.softphone.sdp.MediaDirection;
import com.github.mikekwright.sip.softphone.sdp.NegotiatedMedia;
import com.github.mikekwright.sip.softphone.sdp.NegotiationResult;
import com.github.mikekwright.sip.softphone.sdp.SdpMediaConfig;
import com.github.mikekwright.sip.softphone.sdp.SdpNegotiator;
import com.github.mikekwright.sip.softphone.sdp.SdpParser;
import com.github.mikekwright.sip.softphone.sdp.SessionDescription;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipStatus;
import com.github.mikekwright.sip.softphone.sip.session.InviteSession;
import com.github.mikekwright.sip.softphone.sip.session.InviteSessionListener;
import com.github.mikekwright.sip.softphone.sip.session.TerminationReason;
import com.github.mikekwright.sip.softphone.sip.session.TerminationReason.Cause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One phone call: the SIP {@link InviteSession} combined with SDP offer/answer and the RTP {@link AudioStream} /
 * {@link VideoStream}. Created by {@link com.github.mikekwright.sip.softphone.Softphone#call} (outgoing) or
 * delivered to {@link com.github.mikekwright.sip.softphone.SoftphoneListener#onIncomingCall} (incoming).
 *
 * <p>Designed for automation: every operation returns or exposes a {@link CompletableFuture}, and
 * {@link #awaitState(CallState, Duration)} blocks until a state is reached. Audio is pulled from an
 * {@link AudioSource} and pushed to an {@link AudioSink} (tones, WAV files, recorders, devices), video is exchanged
 * as encoded frames.</p>
 *
 * <p>Thread-safe. Listener callbacks are delivered on the softphone event thread.</p>
 */
public final class Call {

    private static final Logger log = LoggerFactory.getLogger(Call.class);
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final CallContext context;
    private final CallDirection direction;
    private final CallOptions options;
    private final NameAddress remoteParty;
    private final long number = SEQUENCE.incrementAndGet();
    private final SdpNegotiator negotiator = new SdpNegotiator();
    private final long sdpSessionId = System.currentTimeMillis() / 1000 + SEQUENCE.get();
    private final List<CallListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<CallState, CompletableFuture<Void>> stateFutures = new EnumMap<>(CallState.class);
    private final CompletableFuture<TerminationReason> ended = new CompletableFuture<>();

    // all fields below are accessed on the SIP core thread unless volatile
    private volatile CallState state;
    private volatile boolean localHold;
    private volatile boolean remoteHold;
    private volatile TerminationReason endReason;
    private volatile InviteSession session;
    private volatile AudioStream audio;
    private volatile VideoStream video;
    private volatile MediaStreamSpec audioSpec;
    private volatile MediaStreamSpec videoSpec;
    private volatile AudioSource pendingSource;
    private volatile AudioSink pendingSink;
    private volatile VideoSink pendingVideoSink;
    private SessionDescription remoteOffer;
    private SessionDescription localOffer;
    private long sdpVersion = 1;
    private boolean hangupRequested;

    Call(CallContext context, CallDirection direction, CallOptions options, NameAddress remoteParty) {
        this.context = context;
        this.direction = direction;
        this.options = options;
        this.remoteParty = remoteParty;
        for (CallState s : CallState.values()) {
            stateFutures.put(s, new CompletableFuture<>());
        }
        this.state = direction == CallDirection.OUTGOING ? CallState.CALLING : CallState.INCOMING;
        stateFutures.get(state).complete(null);
    }

    // ============================================================================================ public accessors

    /** The SIP Call-ID (available once the INVITE exists). */
    public String id() {
        InviteSession s = session;
        return s == null ? null : s.callId();
    }

    /** A small, process-wide unique number, convenient for UIs ("call #3"). */
    public long number() {
        return number;
    }

    public CallDirection direction() {
        return direction;
    }

    public CallOptions options() {
        return options;
    }

    public CallState state() {
        return state;
    }

    /** The other party (To for outgoing, From for incoming calls). */
    public NameAddress remoteParty() {
        return remoteParty;
    }

    /** We put the call on hold. */
    public boolean isOnHold() {
        return localHold;
    }

    /** The remote party put us on hold (they stopped receiving our media). */
    public boolean isRemoteHold() {
        return remoteHold;
    }

    /** The underlying SIP session for low level access (dialog, raw INVITE). */
    public InviteSession session() {
        return session;
    }

    /** Whether the remote offer of an incoming call contains video. */
    public boolean remoteOffersVideo() {
        SessionDescription offer = remoteOffer;
        return offer != null && offer.media().stream()
                .anyMatch(m -> m.media().equals(MediaDescription.VIDEO) && !m.isRejected());
    }

    /** Negotiated send codec for audio, if media has been negotiated. */
    public Optional<MediaFormat> audioFormat() {
        MediaStreamSpec s = audioSpec;
        return s == null ? Optional.empty() : Optional.of(s.sendFormat());
    }

    /** Negotiated send codec for video, if video was negotiated. */
    public Optional<MediaFormat> videoFormat() {
        MediaStreamSpec s = videoSpec;
        return s == null ? Optional.empty() : Optional.of(s.sendFormat());
    }

    public Optional<MediaStreamSpec> audioSpec() {
        return Optional.ofNullable(audioSpec);
    }

    public Optional<MediaStreamSpec> videoSpec() {
        return Optional.ofNullable(videoSpec);
    }

    public boolean hasVideo() {
        return videoSpec != null;
    }

    /** The audio stream once media has been set up (statistics, low level access). */
    public Optional<AudioStream> audioStream() {
        return Optional.ofNullable(audio);
    }

    public Optional<VideoStream> videoStream() {
        return Optional.ofNullable(video);
    }

    /** Why the call ended, or {@code null} while it is active. */
    public TerminationReason endReason() {
        return endReason;
    }

    public void addListener(CallListener listener) {
        listeners.add(listener);
    }

    public void removeListener(CallListener listener) {
        listeners.remove(listener);
    }

    // ============================================================================================ futures

    /** Completes when the call is connected; fails with {@link CallEndedException} if it ends first. */
    public CompletableFuture<Void> connected() {
        return stateFuture(CallState.CONNECTED);
    }

    /** Completes with the termination reason when the call ends. */
    public CompletableFuture<TerminationReason> ended() {
        return ended;
    }

    /**
     * Completes when the call has reached the state (now or at any earlier point); fails with
     * {@link CallEndedException} if the call ends without reaching it.
     */
    public CompletableFuture<Void> stateFuture(CallState s) {
        synchronized (stateFutures) {
            return stateFutures.get(s).copy();
        }
    }

    /** Blocks until the state is reached. */
    public void awaitState(CallState s, Duration timeout) throws TimeoutException, InterruptedException {
        try {
            stateFuture(s).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("call ended before reaching " + s + ": " + endReason, e.getCause());
        }
    }

    // ============================================================================================ media I/O

    /** Sets where outgoing audio comes from (default: silence). Can be changed at any time. */
    public void setAudioSource(AudioSource source) {
        pendingSource = source;
        AudioStream a = audio;
        if (a != null) {
            a.setSource(source);
        }
    }

    /** Sets where received audio goes (default: discarded). Can be changed at any time. */
    public void setAudioSink(AudioSink sink) {
        pendingSink = sink;
        AudioStream a = audio;
        if (a != null) {
            a.setSink(sink);
        }
    }

    /** Sets where received (encoded) video frames go. */
    public void setVideoSink(VideoSink sink) {
        pendingVideoSink = sink;
        VideoStream v = video;
        if (v != null) {
            v.setSink(sink);
        }
    }

    /** Sends one encoded video frame (codec as given by {@link #videoFormat()}). Ignored without video. */
    public void sendVideoFrame(EncodedVideoFrame frame) {
        VideoStream v = video;
        if (v != null && videoSpec != null) {
            v.sendFrame(frame);
        }
    }

    /** Asks the remote party for a video key frame (RTCP PLI). */
    public void requestKeyFrame() {
        VideoStream v = video;
        if (v != null) {
            v.requestKeyFrame();
        }
    }

    // ============================================================================================ actions

    /** Answers an incoming call with the options it was created with (video if offered and configured). */
    public CompletableFuture<Void> answer() {
        return answer(options.withVideo(remoteOffersVideo() && options.video()));
    }

    /** Answers an incoming call. Completes when the 200 OK has been sent. */
    public CompletableFuture<Void> answer(CallOptions answerOptions) {
        if (direction != CallDirection.INCOMING) {
            return CompletableFuture.failedFuture(new IllegalStateException("not an incoming call"));
        }
        boolean wantVideo = answerOptions.video() && remoteOffersVideo() && !context.config().videoCodecs().isEmpty();
        boolean wantAudio = remoteOffer == null || remoteOffer.media().stream()
                .anyMatch(m -> m.media().equals(MediaDescription.AUDIO));
        return bindMedia(wantAudio, wantVideo || (remoteOffer == null && answerOptions.video()))
                .thenCompose(v -> context.stack().submit(() -> {
                    if (state != CallState.INCOMING) {
                        throw new IllegalStateException("cannot answer in state " + state);
                    }
                    String sdp;
                    if (remoteOffer == null) {
                        localOffer = buildOffer();
                        sdp = localOffer.toString();
                    } else {
                        NegotiationResult result = negotiator.createAnswer(remoteOffer, context.config().user(),
                                mediaHost(), sdpSessionId, sdpVersion++, localConfigs());
                        if (result.media().stream().allMatch(NegotiatedMedia::rejected)) {
                            // RFC 3261 §13.3.1.3 / RFC 3264 §6: no acceptable media -> 488
                            session.reject(SipStatus.NOT_ACCEPTABLE_HERE);
                            throw new IllegalStateException("no common codec with the offer (488 sent)");
                        }
                        applyNegotiation(result.media());
                        sdp = withNatRtcp(result.answer()).toString();
                    }
                    session.accept(sdp);
                    return null;
                }));
    }

    /** Rejects an incoming call with 486 Busy Here. */
    public void reject() {
        reject(SipStatus.BUSY_HERE);
    }

    /** Rejects an incoming call with the given status (e.g. 603 Decline, 480 Temporarily Unavailable). */
    public void reject(int status) {
        InviteSession s = session;
        if (s != null) {
            s.reject(status);
        }
    }

    /** Ends the call in whatever state it is: CANCEL, reject (603) or BYE. */
    public void hangup() {
        context.stack().execute(() -> {
            hangupRequested = true;
            if (session == null) {
                end(TerminationReason.of(Cause.LOCAL_CANCEL, 0, "hung up before the INVITE was sent"));
            } else {
                session.hangup();
            }
        });
    }

    /** Puts the call on hold with a re-INVITE offering {@code a=sendonly} (RFC 3264 §8.4). */
    public CompletableFuture<Void> hold() {
        return changeHold(true);
    }

    /** Resumes a held call ({@code a=sendrecv}). */
    public CompletableFuture<Void> resume() {
        return changeHold(false);
    }

    private CompletableFuture<Void> changeHold(boolean hold) {
        return context.stack().submit(() -> {
            if (state != CallState.CONNECTED) {
                throw new IllegalStateException("call is not connected");
            }
            boolean previous = localHold;
            localHold = hold;
            localOffer = buildOffer();
            return session.reinvite(localOffer.toString()).handle((answer, err) -> {
                if (err != null) {
                    localHold = previous;
                    throw new IllegalStateException("re-INVITE failed: " + err.getMessage(), err);
                }
                if (previous != hold) {
                    fireHold();
                }
                return (Void) null;
            });
        }).thenCompose(f -> f);
    }

    /**
     * Sends DTMF digits as RFC 4733 telephone events (or in-band tones when telephone-event was not negotiated).
     * Completes when all digits have been sent.
     */
    public CompletableFuture<Void> sendDtmf(String digits) {
        AudioStream a = audio;
        if (a == null || audioSpec == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no audio stream"));
        }
        return a.sendDtmf(digits);
    }

    /** Sends a DTMF digit with SIP INFO {@code application/dtmf-relay} (RFC 6086 usage common in PBXs). */
    public CompletableFuture<SipResponse> sendDtmfInfo(char digit, int durationMillis) {
        return sendInfo("application/dtmf-relay", "Signal=" + digit + "\r\nDuration=" + durationMillis + "\r\n");
    }

    /** Sends an INFO request within the call. */
    public CompletableFuture<SipResponse> sendInfo(String contentType, String body) {
        InviteSession s = session;
        return s == null ? CompletableFuture.failedFuture(new IllegalStateException("no session"))
                : s.sendRequest(SipMethod.INFO, contentType, body);
    }

    /** Sends a text MESSAGE within the call's dialog (RFC 3428). */
    public CompletableFuture<SipResponse> sendMessage(String text) {
        InviteSession s = session;
        return s == null ? CompletableFuture.failedFuture(new IllegalStateException("no session"))
                : s.sendRequest(SipMethod.MESSAGE, "text/plain;charset=UTF-8", text);
    }

    // ============================================================================================ internals

    /** Starts an outgoing call: binds RTP ports, builds the offer and sends the INVITE. Called by Softphone. */
    void startOutgoing(NameAddress from, NameAddress to) {
        boolean wantVideo = options.video() && !context.config().videoCodecs().isEmpty();
        bindMedia(options.audio(), wantVideo).thenRunAsync(() -> {
            if (hangupRequested || state == CallState.ENDED) {
                return;
            }
            localOffer = buildOffer();
            InviteSession s = InviteSession.outgoing(context.stack(), from, to, context.config().user(),
                    localOffer.toString(), sessionListener);
            options.headers().forEach((k, v) -> s.invite().addHeader(k, v));
            session = s;
            s.start();
        }, context.stack().core()).exceptionally(err -> {
            context.stack().execute(() -> end(TerminationReason.of(Cause.TRANSPORT_ERROR, 0,
                    "media setup failed: " + err.getMessage())));
            return null;
        });
    }

    /** Attaches an incoming INVITE. Called by Softphone on the SIP core thread. */
    void attachIncoming(com.github.mikekwright.sip.softphone.sip.core.IncomingRequest request,
                        SessionDescription offer) {
        remoteOffer = offer;
        session = InviteSession.incoming(request, context.config().user(), sessionListener);
        session.ring();
    }

    private CompletableFuture<Void> bindMedia(boolean withAudio, boolean withVideo) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        if (withAudio && audio == null) {
            futures.add(context.bindRtp().thenAccept(rtp -> {
                AudioStream a = new AudioStream(rtp, context.audioCodecs());
                a.setListener(new AudioStreamListener() {
                    @Override
                    public void onDtmf(char digit, int durationMillis) {
                        fire(l -> l.onDtmf(Call.this, digit, durationMillis));
                    }
                });
                if (pendingSource != null) {
                    a.setSource(pendingSource);
                }
                if (pendingSink != null) {
                    a.setSink(pendingSink);
                }
                audio = a;
            }));
        }
        if (withVideo && video == null) {
            futures.add(context.bindRtp().thenAccept(rtp -> {
                VideoStream v = new VideoStream(rtp);
                v.setKeyFrameRequestListener(() -> fire(l -> l.onKeyFrameRequested(Call.this)));
                if (pendingVideoSink != null) {
                    v.setSink(pendingVideoSink);
                }
                video = v;
            }));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private String mediaHost() {
        return context.mediaHost();
    }

    private List<SdpMediaConfig> localConfigs() {
        MediaDirection dir = MediaNegotiation.holdDirection(localHold, remoteHold);
        List<SdpMediaConfig> configs = new ArrayList<>();
        if (audio != null) {
            configs.add(MediaNegotiation.audioConfig(mediaHost(), advertisedPort(audio.session()),
                    MediaNegotiation.audioFormats(context.config().audioCodecs(),
                            context.config().telephoneEventPayloadType()), dir));
        }
        if (video != null) {
            configs.add(MediaNegotiation.videoConfig(mediaHost(), advertisedPort(video.session()),
                    MediaNegotiation.videoFormats(context.config().videoCodecs()), dir));
        }
        return configs;
    }

    private SessionDescription buildOffer() {
        return withNatRtcp(negotiator.createOffer(context.config().user(), mediaHost(), sdpSessionId, sdpVersion++,
                localConfigs()));
    }

    /** The RTP port to advertise: the STUN discovered public port when behind NAT, else the local port. */
    private static int advertisedPort(com.github.mikekwright.sip.softphone.rtp.RtpSession session) {
        java.net.InetSocketAddress pub = session.publicRtpAddress();
        return pub != null ? pub.getPort() : session.localRtpPort();
    }

    /**
     * Behind a NAT the public RTCP port is not necessarily RTP + 1, so announce it explicitly with
     * {@code a=rtcp:<port> IN IP4 <addr>} (RFC 3605) whenever STUN found a different mapping.
     */
    private SessionDescription withNatRtcp(SessionDescription sdp) {
        for (com.github.mikekwright.sip.softphone.rtp.RtpSession s : new com.github.mikekwright.sip.softphone.rtp
                .RtpSession[]{audio == null ? null : audio.session(), video == null ? null : video.session()}) {
            if (s == null || s.publicRtpAddress() == null || s.publicRtcpAddress() == null) {
                continue;
            }
            java.net.InetSocketAddress rtp = s.publicRtpAddress();
            java.net.InetSocketAddress rtcp = s.publicRtcpAddress();
            boolean standard = rtcp.getPort() == rtp.getPort() + 1 && rtcp.getAddress().equals(rtp.getAddress());
            if (standard) {
                continue;
            }
            for (MediaDescription m : sdp.media()) {
                if (m.port() == rtp.getPort() && m.attributes("rtcp").isEmpty()) {
                    String ip = rtcp.getAddress().getHostAddress();
                    m.addAttribute("rtcp", rtcp.getPort() + " IN " + (ip.contains(":") ? "IP6 " : "IP4 ") + ip);
                }
            }
        }
        return sdp;
    }

    /** Starts or updates the RTP streams from the negotiation result and updates hold flags. */
    private void applyNegotiation(List<NegotiatedMedia> media) {
        MediaStreamSpec newAudio = null;
        MediaStreamSpec newVideo = null;
        NegotiatedMedia audioMedia = null;
        for (NegotiatedMedia m : media) {
            MediaStreamSpec spec = MediaNegotiation.toSpec(m);
            if (spec == null) {
                continue;
            }
            switch (spec.type()) {
                case AUDIO -> {
                    if (newAudio == null) {
                        newAudio = spec;
                        audioMedia = m;
                    }
                }
                case VIDEO -> {
                    if (newVideo == null) {
                        newVideo = spec;
                    }
                }
            }
        }
        try {
            if (newAudio != null && audio != null) {
                if (audioSpec == null) {
                    audio.start(newAudio);
                } else {
                    audio.update(newAudio);
                }
                audioSpec = newAudio;
            }
            if (newVideo != null && video != null) {
                if (videoSpec == null) {
                    video.start(newVideo);
                } else {
                    video.update(newVideo);
                }
                videoSpec = newVideo;
            } else if (newVideo == null && video != null && videoSpec == null) {
                // video was offered/requested but rejected: release the ports
                VideoStream v = video;
                video = null;
                v.stop();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to apply negotiated media for call {}", id(), e);
        }
        boolean newRemoteHold = audioMedia != null && !audioMedia.canSend() && !localHold
                || audioMedia != null && audioMedia.direction() == MediaDirection.INACTIVE;
        boolean holdChanged = newRemoteHold != remoteHold;
        remoteHold = newRemoteHold;
        fire(l -> l.onMediaChanged(this));
        if (holdChanged) {
            fireHold();
        }
    }

    private void fireHold() {
        boolean lh = localHold;
        boolean rh = remoteHold;
        fire(l -> l.onHoldChanged(this, lh, rh));
    }

    private final InviteSessionListener sessionListener = new InviteSessionListener() {
        @Override
        public void onProvisional(InviteSession s, SipResponse response) {
            if (response.statusCode() == SipStatus.RINGING && state == CallState.CALLING) {
                setState(CallState.RINGING);
            }
        }

        @Override
        public void onAnswer(InviteSession s, String sdp) {
            if (localOffer == null) {
                return;
            }
            try {
                SessionDescription answer = SdpParser.parse(sdp);
                List<NegotiatedMedia> negotiated = negotiator.processAnswer(localOffer, answer);
                if (negotiated.stream().allMatch(NegotiatedMedia::rejected)) {
                    throw new IllegalArgumentException("answer rejected all media streams");
                }
                applyNegotiation(negotiated);
                if (state == CallState.CALLING || state == CallState.RINGING) {
                    if (s.state() != com.github.mikekwright.sip.softphone.sip.session.SessionState.CONFIRMED) {
                        setState(CallState.EARLY_MEDIA);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Cannot process SDP answer for call {}: {}", s.callId(), e.getMessage());
                if (s.state() == com.github.mikekwright.sip.softphone.sip.session.SessionState.CONFIRMED) {
                    s.bye(); // RFC 3261 §13.2.2.4: unacceptable answer in 2xx -> BYE
                }
            }
        }

        @Override
        public void onEstablished(InviteSession s) {
            setState(CallState.CONNECTED);
        }

        @Override
        public String onRemoteOffer(InviteSession s, String offer) {
            try {
                SessionDescription parsed = SdpParser.parse(offer);
                NegotiationResult result = negotiator.createAnswer(parsed, context.config().user(), mediaHost(),
                        sdpSessionId, sdpVersion++, localConfigs());
                applyNegotiation(result.media());
                return withNatRtcp(result.answer()).toString();
            } catch (RuntimeException e) {
                log.warn("Rejecting re-INVITE offer for call {}: {}", s.callId(), e.getMessage());
                return null;
            }
        }

        @Override
        public String onOfferRequested(InviteSession s) {
            localOffer = buildOffer();
            return localOffer.toString();
        }

        @Override
        public void onInfo(InviteSession s, SipRequest info) {
            String body = info.bodyAsString();
            if ("application/dtmf-relay".equals(info.contentType())) {
                char digit = 0;
                int duration = 250;
                for (String line : body.split("\r?\n")) {
                    String[] kv = line.split("=", 2);
                    if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("Signal") && !kv[1].isBlank()) {
                        digit = kv[1].trim().charAt(0);
                    } else if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("Duration")) {
                        try {
                            duration = Integer.parseInt(kv[1].trim());
                        } catch (NumberFormatException ignored) {
                            // keep default
                        }
                    }
                }
                if (digit != 0) {
                    char d = digit;
                    int dur = duration;
                    fire(l -> l.onDtmf(Call.this, d, dur));
                }
                return;
            }
            fire(l -> l.onMessage(Call.this, info.contentType(), body));
        }

        @Override
        public void onMessage(InviteSession s, SipRequest message) {
            fire(l -> l.onMessage(Call.this, message.contentType(), message.bodyAsString()));
        }

        @Override
        public void onTerminated(InviteSession s, TerminationReason reason) {
            end(reason);
        }
    };

    private void setState(CallState next) {
        if (state == next || state == CallState.ENDED) {
            return;
        }
        state = next;
        synchronized (stateFutures) {
            stateFutures.get(next).complete(null);
        }
        fire(l -> l.onStateChanged(this, next));
        context.onCallStateChanged(this, next);
    }

    private void end(TerminationReason reason) {
        if (state == CallState.ENDED) {
            return;
        }
        endReason = reason;
        AudioStream a = audio;
        VideoStream v = video;
        if (a != null) {
            a.stop();
        }
        if (v != null) {
            v.stop();
        }
        state = CallState.ENDED;
        synchronized (stateFutures) {
            stateFutures.get(CallState.ENDED).complete(null);
            stateFutures.forEach((s, f) -> f.completeExceptionally(new CallEndedException(reason)));
        }
        ended.complete(reason);
        fire(l -> l.onStateChanged(this, CallState.ENDED));
        fire(l -> l.onEnded(this, reason));
        context.onCallStateChanged(this, CallState.ENDED);
        context.onCallEnded(this);
    }

    private void fire(java.util.function.Consumer<CallListener> event) {
        context.dispatch(() -> {
            for (CallListener l : listeners) {
                try {
                    event.accept(l);
                } catch (RuntimeException e) {
                    log.warn("Call listener failed", e);
                }
            }
        });
    }

    @Override
    public String toString() {
        return "Call#" + number + "[" + direction + " " + remoteParty + " " + state
                + (localHold ? " HOLD" : "") + (remoteHold ? " REMOTE-HOLD" : "") + "]";
    }
}
