package com.github.mikekwright.sip.softphone.media;

import com.github.mikekwright.sip.softphone.media.video.EncodedVideoFrame;
import com.github.mikekwright.sip.softphone.media.video.VideoDepacketizer;
import com.github.mikekwright.sip.softphone.media.video.VideoPacketizer;
import com.github.mikekwright.sip.softphone.media.video.VideoPayloadFormats;
import com.github.mikekwright.sip.softphone.media.video.VideoSink;
import com.github.mikekwright.sip.softphone.rtp.FullIntraRequest;
import com.github.mikekwright.sip.softphone.rtp.PictureLossIndication;
import com.github.mikekwright.sip.softphone.rtp.RtcpPacket;
import com.github.mikekwright.sip.softphone.rtp.RtpPacket;
import com.github.mikekwright.sip.softphone.rtp.RtpSession;
import com.github.mikekwright.sip.softphone.rtp.RtpSessionListener;
import com.github.mikekwright.sip.softphone.rtp.SenderReport;
import io.netty.channel.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A bidirectional video RTP stream on top of an {@link RtpSession}, transporting {@link EncodedVideoFrame}s
 * produced and consumed by an application supplied codec.
 *
 * <h2>Sending</h2>
 * <p>{@link #sendFrame} packetizes a frame with the {@link VideoPacketizer} registered for
 * {@code spec.sendFormat().encodingName()} into payloads of at most {@code maxPayloadSize} bytes (default
 * {@value #DEFAULT_MAX_PAYLOAD_SIZE}, safe for typical 1500 byte MTUs), sends them with consecutive sequence
 * numbers, one RTP timestamp derived from the frame's {@code timestampMicros} at the format's clock rate (90 kHz)
 * plus a random offset (RFC 3550 §5.1), and the marker bit on the last packet (RFC 3551 §5). Frames are sent only
 * when {@code spec.send() && spec.hasRemoteTarget()}.</p>
 *
 * <h2>Receiving</h2>
 * <p>Inbound packets pass a {@link JitterBuffer} and the {@link VideoDepacketizer} registered for their payload
 * type's encoding; complete frames go to the {@link VideoSink}. When a depacketizer reports loss or an
 * undecodable frame (including a first frame that is not a key frame), a Picture Loss Indication
 * (RFC 4585 §6.3.1) is sent, at most once per {@value #KEY_FRAME_REQUEST_INTERVAL_MILLIS} ms; it can also be
 * requested explicitly with {@link #requestKeyFrame()}. Inbound PLI and FIR (RFC 5104 §4.3.1) addressed to our SSRC
 * invoke the key frame request listener, which should make the encoder produce a key frame.</p>
 *
 * <h2>Lifecycle and threading</h2>
 * <p>Same as {@link AudioStream}: {@code start → update* → stop}; the stream owns and closes the session. All
 * public methods are thread-safe; frames are packetized and sent on the session's event loop, and the sink and
 * the key frame request listener are invoked there.</p>
 */
public final class VideoStream {

    /** Default maximum RTP payload size in bytes. */
    public static final int DEFAULT_MAX_PAYLOAD_SIZE = 1200;
    /** Minimum interval between automatic or explicit PLIs. */
    public static final long KEY_FRAME_REQUEST_INTERVAL_MILLIS = 500;
    /** Default jitter buffer capacity in packets (a video frame typically spans several packets). */
    public static final int DEFAULT_JITTER_BUFFER_CAPACITY = 16;

    private static final Logger log = LoggerFactory.getLogger(VideoStream.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final VideoSink NO_SINK = frame -> {
    };
    private static final Runnable NO_LISTENER = () -> {
    };

    private enum State { NEW, RUNNING, STOPPED }

    private final RtpSession session;
    private final VideoPayloadFormats formats;
    private final int maxPayloadSize;
    private final EventLoop loop;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final JitterBuffer jitterBuffer = new JitterBuffer(DEFAULT_JITTER_BUFFER_CAPACITY);
    private final long timestampOffset = RANDOM.nextInt() & 0xFFFFFFFFL;

    private final AtomicLong framesSent = new AtomicLong();
    private final AtomicLong framesReceived = new AtomicLong();
    private final AtomicLong keyFrameRequestsSent = new AtomicLong();
    private final AtomicLong keyFrameRequestsReceived = new AtomicLong();

    private volatile VideoSink sink = NO_SINK;
    private volatile Runnable keyFrameRequestListener = NO_LISTENER;
    private volatile MediaStreamSpec spec;

    // ---- event loop confined state
    private MediaStreamSpec appliedSpec;
    private VideoPacketizer packetizer;
    private final Map<Integer, VideoDepacketizer> depacketizers = new HashMap<>();
    private int sequenceNumber = RANDOM.nextInt(0x10000);
    private long remoteSsrc = -1;
    private long lastKeyFrameRequestNanos;
    private boolean keyFrameRequested;

    /** A stream with {@link VideoPayloadFormats#defaults()} and the default maximum payload size. */
    public VideoStream(RtpSession session) {
        this(session, VideoPayloadFormats.defaults(), DEFAULT_MAX_PAYLOAD_SIZE);
    }

    /**
     * @param session        the RTP session; owned (and eventually closed) by this stream
     * @param formats        payload format registry
     * @param maxPayloadSize maximum RTP payload size in bytes
     */
    public VideoStream(RtpSession session, VideoPayloadFormats formats, int maxPayloadSize) {
        this.session = Objects.requireNonNull(session, "session");
        this.formats = Objects.requireNonNull(formats, "formats");
        if (maxPayloadSize < 16) {
            throw new IllegalArgumentException("maxPayloadSize too small: " + maxPayloadSize);
        }
        this.maxPayloadSize = maxPayloadSize;
        this.loop = session.eventLoop();
    }

    // ===================================================================================== lifecycle

    /**
     * Starts the stream.
     *
     * @throws IllegalArgumentException if the spec is not video or its send format has no registered packetizer
     * @throws IllegalStateException    if already started or stopped
     */
    public void start(MediaStreamSpec spec) {
        validate(spec);
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            throw new IllegalStateException("video stream already " + state.get().name().toLowerCase());
        }
        this.spec = spec;
        session.setListener(new SessionListener());
        onLoop(this::applySpec);
    }

    /**
     * Applies a renegotiated spec (direction, remote address, formats).
     *
     * @throws IllegalArgumentException if the new send format has no registered packetizer
     * @throws IllegalStateException    if the stream is not running
     */
    public void update(MediaStreamSpec spec) {
        validate(spec);
        if (state.get() != State.RUNNING) {
            throw new IllegalStateException("video stream not running");
        }
        this.spec = spec;
        onLoop(this::applySpec);
    }

    /** Stops the stream and closes the session (sending RTCP BYE). Idempotent. */
    public CompletableFuture<Void> stop() {
        if (state.getAndSet(State.STOPPED) == State.STOPPED) {
            return stopped;
        }
        session.setListener(null);
        session.close().whenComplete((v, e) -> stopped.complete(null));
        return stopped;
    }

    // ===================================================================================== API

    /** The underlying RTP session. */
    public RtpSession session() {
        return session;
    }

    /** The spec most recently passed to {@link #start} or {@link #update}, or {@code null}. */
    public MediaStreamSpec spec() {
        return spec;
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /** Sets the consumer of received frames ({@code null} discards them). */
    public void setSink(VideoSink sink) {
        this.sink = sink == null ? NO_SINK : sink;
    }

    /** Sets the callback invoked when the remote side requests a key frame via PLI or FIR. */
    public void setKeyFrameRequestListener(Runnable listener) {
        this.keyFrameRequestListener = listener == null ? NO_LISTENER : listener;
    }

    /**
     * Sends one encoded frame, asynchronously on the event loop. Frames are dropped (with a debug/warn log) when
     * the stream is not running, not sending, or the frame's codec differs from the negotiated send format.
     */
    public void sendFrame(EncodedVideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        try {
            onLoop(() -> sendFrameOnLoop(frame));
        } catch (RejectedExecutionException e) {
            log.debug("dropping video frame: event loop shut down");
        }
    }

    /**
     * Asks the remote sender for a key frame with an RTCP PLI. Rate limited to one request per
     * {@value #KEY_FRAME_REQUEST_INTERVAL_MILLIS} ms; requests inside that window are dropped.
     */
    public void requestKeyFrame() {
        try {
            onLoop(this::requestKeyFrameOnLoop);
        } catch (RejectedExecutionException e) {
            log.debug("cannot request key frame: event loop shut down");
        }
    }

    public long framesSent() {
        return framesSent.get();
    }

    public long framesReceived() {
        return framesReceived.get();
    }

    /** PLIs sent. */
    public long keyFrameRequestsSent() {
        return keyFrameRequestsSent.get();
    }

    /** PLIs and FIRs received for our SSRC. */
    public long keyFrameRequestsReceived() {
        return keyFrameRequestsReceived.get();
    }

    // ===================================================================================== internals

    private void validate(MediaStreamSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.type() != MediaType.VIDEO) {
            throw new IllegalArgumentException("not a video stream spec: " + spec.type());
        }
        if (!formats.supports(spec.sendFormat().encodingName())) {
            throw new IllegalArgumentException("unsupported video send format " + spec.sendFormat().encodingName());
        }
    }

    private void applySpec() {
        if (state.get() != State.RUNNING) {
            return;
        }
        MediaStreamSpec newSpec = spec;
        MediaStreamSpec old = appliedSpec;
        appliedSpec = newSpec;

        if (old == null || !Objects.equals(old.remoteRtp(), newSpec.remoteRtp())
                || !Objects.equals(old.remoteRtcp(), newSpec.remoteRtcp()) || old.rtcpMux() != newSpec.rtcpMux()) {
            session.connect(newSpec.hasRemoteTarget() ? newSpec.remoteRtp() : null, newSpec.remoteRtcp(),
                    newSpec.rtcpMux());
        }

        String sendName = newSpec.sendFormat().encodingName();
        if (packetizer == null || !packetizer.encodingName().equalsIgnoreCase(sendName)) {
            packetizer = formats.createPacketizer(sendName).orElseThrow();
        }

        Map<Integer, Integer> clockRates = new HashMap<>();
        Map<Integer, VideoDepacketizer> previous = new HashMap<>(depacketizers);
        depacketizers.clear();
        for (MediaFormat format : newSpec.receiveFormats()) {
            clockRates.put(format.payloadType(), format.clockRate());
            VideoDepacketizer existing = previous.get(format.payloadType());
            if (existing != null && existing.encodingName().equalsIgnoreCase(format.encodingName())) {
                depacketizers.put(format.payloadType(), existing);
            } else {
                formats.createDepacketizer(format.encodingName())
                        .ifPresent(d -> depacketizers.put(format.payloadType(), d));
            }
        }
        clockRates.putIfAbsent(newSpec.sendFormat().payloadType(), newSpec.sendFormat().clockRate());
        session.setPayloadClockRates(clockRates);
    }

    private void sendFrameOnLoop(EncodedVideoFrame frame) {
        MediaStreamSpec s = appliedSpec;
        if (state.get() != State.RUNNING || s == null || !s.send() || !s.hasRemoteTarget()) {
            return;
        }
        MediaFormat format = s.sendFormat();
        if (!frame.codec().equalsIgnoreCase(format.encodingName())) {
            log.warn("dropping {} frame: negotiated send format is {}", frame.codec(), format.encodingName());
            return;
        }
        List<byte[]> payloads;
        try {
            payloads = packetizer.packetize(frame, maxPayloadSize);
        } catch (RuntimeException e) {
            log.warn("failed to packetize {}", frame, e);
            return;
        }
        long rtpTimestamp = (frame.timestampMicros() * format.clockRate() / 1_000_000L + timestampOffset)
                & 0xFFFFFFFFL;
        for (int i = 0; i < payloads.size(); i++) {
            RtpPacket packet = RtpPacket.builder()
                    .payloadType(format.payloadType())
                    .marker(i == payloads.size() - 1)
                    .sequenceNumber(sequenceNumber)
                    .timestamp(rtpTimestamp)
                    .ssrc(session.ssrc())
                    .payload(payloads.get(i))
                    .build();
            sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
            session.send(packet);
        }
        framesSent.incrementAndGet();
    }

    private void requestKeyFrameOnLoop() {
        if (state.get() != State.RUNNING) {
            return;
        }
        long now = System.nanoTime();
        if (keyFrameRequested
                && now - lastKeyFrameRequestNanos < TimeUnit.MILLISECONDS.toNanos(KEY_FRAME_REQUEST_INTERVAL_MILLIS)) {
            return;
        }
        keyFrameRequested = true;
        lastKeyFrameRequestNanos = now;
        // RFC 4585 §6.1: the media source SSRC identifies the stream; 0 if we have not learnt it yet.
        long mediaSsrc = remoteSsrc < 0 ? 0 : remoteSsrc;
        if (session.sendFeedback(new PictureLossIndication(session.ssrc(), mediaSsrc))) {
            keyFrameRequestsSent.incrementAndGet();
        }
    }

    private void onKeyFrameRequested() {
        keyFrameRequestsReceived.incrementAndGet();
        try {
            keyFrameRequestListener.run();
        } catch (RuntimeException e) {
            log.warn("key frame request listener failed", e);
        }
    }

    private void onLoop(Runnable task) {
        if (loop.inEventLoop()) {
            task.run();
        } else {
            loop.execute(task);
        }
    }

    /** Receives packets from the session, on the event loop. */
    private final class SessionListener implements RtpSessionListener {
        @Override
        public void onRtp(RtpPacket packet, InetSocketAddress sender) {
            MediaStreamSpec s = appliedSpec;
            if (state.get() != State.RUNNING || s == null || !s.receive()) {
                return;
            }
            for (RtpPacket ready : jitterBuffer.push(packet)) {
                remoteSsrc = ready.ssrc();
                VideoDepacketizer depacketizer = depacketizers.get(ready.payloadType());
                if (depacketizer == null) {
                    continue;
                }
                EncodedVideoFrame frame = depacketizer.push(ready);
                if (frame != null) {
                    framesReceived.incrementAndGet();
                    try {
                        sink.onFrame(frame);
                    } catch (RuntimeException e) {
                        log.warn("video sink failed", e);
                    }
                }
                if (depacketizer.consumeKeyFrameNeeded()) {
                    requestKeyFrameOnLoop();
                }
            }
        }

        @Override
        public void onRtcp(List<RtcpPacket> packets, InetSocketAddress sender) {
            if (state.get() != State.RUNNING) {
                return;
            }
            long ours = session.ssrc();
            for (RtcpPacket packet : packets) {
                switch (packet) {
                    case PictureLossIndication pli when pli.mediaSsrc() == ours || pli.mediaSsrc() == 0 ->
                            onKeyFrameRequested();
                    case FullIntraRequest fir when fir.targets(ours) -> onKeyFrameRequested();
                    case SenderReport sr when remoteSsrc < 0 -> remoteSsrc = sr.senderSsrc();
                    default -> {
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return "VideoStream[" + state.get() + ", " + session + "]";
    }
}
