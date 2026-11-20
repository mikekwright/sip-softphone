package com.github.mikekwright.sip.softphone.media;

import com.github.mikekwright.sip.softphone.media.audio.AudioCodec;
import com.github.mikekwright.sip.softphone.media.audio.AudioCodecRegistry;
import com.github.mikekwright.sip.softphone.media.audio.AudioSink;
import com.github.mikekwright.sip.softphone.media.audio.AudioSource;
import com.github.mikekwright.sip.softphone.media.audio.NullSink;
import com.github.mikekwright.sip.softphone.media.audio.SilenceSource;
import com.github.mikekwright.sip.softphone.media.dtmf.DtmfReceiver;
import com.github.mikekwright.sip.softphone.media.dtmf.DtmfSender;
import com.github.mikekwright.sip.softphone.media.dtmf.DtmfToneSource;
import com.github.mikekwright.sip.softphone.media.dtmf.TelephoneEvent;
import com.github.mikekwright.sip.softphone.rtp.ReceptionStatistics;
import com.github.mikekwright.sip.softphone.rtp.RtcpPacket;
import com.github.mikekwright.sip.softphone.rtp.RtpPacket;
import com.github.mikekwright.sip.softphone.rtp.RtpSession;
import com.github.mikekwright.sip.softphone.rtp.RtpSessionListener;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A bidirectional audio RTP stream on top of an {@link RtpSession}: a media clock that pulls PCM from an
 * {@link AudioSource}, encodes and sends it every ptime, and a receive path that reorders inbound packets with
 * a {@link JitterBuffer}, decodes them and writes PCM to an {@link AudioSink}. DTMF is sent and received as
 * RFC 4733 telephone events, with an in-band tone fallback for sending.
 *
 * <h2>Sending (RFC 3550 §5.1, RFC 3551 §4)</h2>
 * <ul>
 *   <li>A fixed-rate timer on the session's event loop ticks every {@code spec.ptimeMillis()}. On every tick
 *       the RTP timestamp advances by {@code clockRate * ptime / 1000}, also while not sending (hold,
 *       no remote target), so timestamps stay continuous with wall-clock time.</li>
 *   <li>Media is sent only when {@code spec.send() && spec.hasRemoteTarget()}; the source is not read while
 *       not sending, so file playback pauses during hold.</li>
 *   <li>Initial sequence number and timestamp are random (RFC 3550 §5.1). The sequence number increments by
 *       one per packet; the first packet after (re)starting to send carries the marker bit (start of
 *       talkspurt, RFC 3551 §4.1).</li>
 *   <li>{@link #sendDtmf} uses RFC 4733 events when {@code spec.sendTelephoneEventPt()} is set: events replace
 *       audio packets while in flight, share the stream's sequence numbers and carry the timestamp at which the
 *       event started (see {@link DtmfSender}). Without a negotiated telephone-event payload type, digits are
 *       played in-band as dual tones through the audio codec ({@link DtmfToneSource}).</li>
 * </ul>
 *
 * <h2>Receiving</h2>
 * <p>Packets are delivered only when {@code spec.receive()}. Packets with a payload type from
 * {@code spec.receiveFormats()} are decoded and passed to the sink at the codec's sample rate; packets with
 * {@code spec.receiveTelephoneEventPt()} go to a {@link DtmfReceiver} that reports each digit once to
 * {@link AudioStreamListener#onDtmf}. Other payload types (e.g. comfort noise) are ignored.</p>
 *
 * <h2>Codecs</h2>
 * <p>{@link #start(MediaStreamSpec)} and {@link #update(MediaStreamSpec)} create codecs from the
 * {@link AudioCodecRegistry} given at construction, matching encoding name and clock rate of the spec's formats;
 * receive formats without a registered codec are ignored, an unsupported send format is an error. The
 * overloads taking codec instances bypass the registry. Codec instances are reused across updates when the
 * format does not change.</p>
 *
 * <h2>Lifecycle and threading</h2>
 * <p>{@code new} &rarr; {@link #start} &rarr; any number of {@link #update} &rarr; {@link #stop}. The stream
 * owns the session: {@code stop()} closes it (sending RTCP BYE) and also closes the current source and sink.
 * All public methods are thread-safe; internal state is confined to the session's event loop, and source reads,
 * sink writes and listener callbacks happen there. Concurrent {@code update} calls should be serialised by the
 * caller (the call layer naturally does so).</p>
 */
public final class AudioStream {

    private static final Logger log = LoggerFactory.getLogger(AudioStream.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AudioStreamListener NO_LISTENER = new AudioStreamListener() {
    };

    private enum State { NEW, RUNNING, STOPPED }

    private final RtpSession session;
    private final AudioCodecRegistry registry;
    private final EventLoop loop;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final DtmfToneSource inbandDtmf = new DtmfToneSource();
    private final JitterBuffer jitterBuffer;

    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong packetsReceived = new AtomicLong();

    private volatile AudioSource source = SilenceSource.INSTANCE;
    private volatile AudioSink sink = NullSink.INSTANCE;
    private volatile AudioStreamListener listener = NO_LISTENER;
    // Written on the caller thread of start/update, read on the event loop.
    private volatile MediaStreamSpec spec;
    private volatile AudioCodec sendCodec;
    private volatile Map<Integer, AudioCodec> receiveCodecs = Map.of();

    // ---- event loop confined state: the installed configuration as applied by applySpec()
    private MediaStreamSpec appliedSpec;
    private AudioCodec appliedSendCodec;
    private Map<Integer, AudioCodec> appliedReceiveCodecs = Map.of();
    private ScheduledFuture<?> clock;
    private int clockPtime;
    private DtmfSender dtmfSender;
    private int dtmfSenderPtime;
    private DtmfReceiver dtmfReceiver;
    private int dtmfReceiverClockRate;
    private int sequenceNumber = RANDOM.nextInt(0x10000);
    private long timestamp = RANDOM.nextInt() & 0xFFFFFFFFL;
    private boolean talkspurt = true;

    /** A stream using {@link AudioCodecRegistry#defaults()} and a default-size jitter buffer. */
    public AudioStream(RtpSession session) {
        this(session, AudioCodecRegistry.defaults());
    }

    /** A stream creating codecs from {@code registry}, with a default-size jitter buffer. */
    public AudioStream(RtpSession session, AudioCodecRegistry registry) {
        this(session, registry, JitterBuffer.DEFAULT_CAPACITY);
    }

    /**
     * @param session              the RTP session to use; owned (and eventually closed) by this stream
     * @param registry             codec factory for the spec based methods
     * @param jitterBufferCapacity packets held while waiting for a missing one (see {@link JitterBuffer})
     */
    public AudioStream(RtpSession session, AudioCodecRegistry registry, int jitterBufferCapacity) {
        this.session = Objects.requireNonNull(session, "session");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.loop = session.eventLoop();
        this.jitterBuffer = new JitterBuffer(jitterBufferCapacity);
    }

    // ===================================================================================== lifecycle

    /**
     * Starts the stream with codecs created from the registry.
     *
     * @throws IllegalArgumentException if the spec is not audio or its send format has no registered codec
     * @throws IllegalStateException    if already started or stopped
     */
    public void start(MediaStreamSpec spec) {
        validate(spec);
        start(spec, resolveSendCodec(spec), resolveReceiveCodecs(spec));
    }

    /**
     * Starts the stream with the given codec instances.
     *
     * @param sendCodec     codec for {@code spec.sendFormat()}
     * @param receiveCodecs codecs by receive payload type
     */
    public void start(MediaStreamSpec spec, AudioCodec sendCodec, Map<Integer, AudioCodec> receiveCodecs) {
        validate(spec);
        Objects.requireNonNull(sendCodec, "sendCodec");
        Map<Integer, AudioCodec> receive = Map.copyOf(receiveCodecs);
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            throw new IllegalStateException("audio stream already " + state.get().name().toLowerCase());
        }
        install(spec, sendCodec, receive);
        session.setListener(new SessionListener());
        onLoop(this::applySpec);
    }

    /**
     * Applies a renegotiated spec (re-INVITE / UPDATE): direction changes such as hold, new remote address,
     * codec or ptime changes. Codecs are created from the registry unless the format is unchanged.
     *
     * @throws IllegalArgumentException if the new send format has no registered codec
     * @throws IllegalStateException    if the stream is not running
     */
    public void update(MediaStreamSpec spec) {
        validate(spec);
        update(spec, resolveSendCodec(spec), resolveReceiveCodecs(spec));
    }

    /** Applies a renegotiated spec with the given codec instances. */
    public void update(MediaStreamSpec spec, AudioCodec sendCodec, Map<Integer, AudioCodec> receiveCodecs) {
        validate(spec);
        Objects.requireNonNull(sendCodec, "sendCodec");
        if (state.get() != State.RUNNING) {
            throw new IllegalStateException("audio stream not running");
        }
        install(spec, sendCodec, Map.copyOf(receiveCodecs));
        onLoop(this::applySpec);
    }

    /**
     * Stops the media clock, cancels pending DTMF, reports a pending received DTMF event, closes the source,
     * sink and RTP session (which sends an RTCP BYE). Idempotent.
     *
     * @return a future completed once the session is closed
     */
    public CompletableFuture<Void> stop() {
        if (state.getAndSet(State.STOPPED) == State.STOPPED) {
            return stopped;
        }
        session.setListener(null);
        try {
            loop.execute(() -> {
                if (clock != null) {
                    clock.cancel(false);
                }
                if (dtmfSender != null) {
                    dtmfSender.cancel();
                }
                inbandDtmf.cancel();
                if (dtmfReceiver != null) {
                    dtmfReceiver.flush();
                }
                closeResources();
            });
        } catch (RejectedExecutionException e) {
            closeResources();
        }
        return stopped;
    }

    private void closeResources() {
        try {
            source.close();
        } catch (RuntimeException e) {
            log.warn("closing audio source failed", e);
        }
        try {
            sink.close();
        } catch (RuntimeException e) {
            log.warn("closing audio sink failed", e);
        }
        session.close().whenComplete((v, e) -> stopped.complete(null));
    }

    // ===================================================================================== configuration

    /** The underlying RTP session (for statistics and addresses). */
    public RtpSession session() {
        return session;
    }

    /** The spec most recently passed to {@link #start} or {@link #update}, or {@code null}. */
    public MediaStreamSpec spec() {
        return spec;
    }

    /** Whether the stream is started and not stopped. */
    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /**
     * Replaces the audio source (takes effect on the next tick). The previous source is not closed;
     * {@code null} selects silence.
     */
    public void setSource(AudioSource source) {
        this.source = source == null ? SilenceSource.INSTANCE : source;
    }

    public AudioSource source() {
        return source;
    }

    /** Replaces the audio sink. The previous sink is not closed; {@code null} discards received audio. */
    public void setSink(AudioSink sink) {
        this.sink = sink == null ? NullSink.INSTANCE : sink;
    }

    public AudioSink sink() {
        return sink;
    }

    /** Sets the listener for stream events ({@code null} removes it). */
    public void setListener(AudioStreamListener listener) {
        this.listener = listener == null ? NO_LISTENER : listener;
    }

    // ===================================================================================== DTMF

    /**
     * Sends DTMF digits, as RFC 4733 events if a telephone-event payload type is negotiated, else in-band.
     * Digits queue behind any digits already being sent. While the stream is not sending (e.g. on hold) digits
     * stay queued.
     *
     * @param digits characters from {@code 0-9 * # A-D}
     * @return a future completed when the last digit has been sent; failed with {@link IllegalArgumentException}
     *         for invalid digits, {@link IllegalStateException} if the stream is not running, or cancelled when
     *         the stream stops first
     */
    public CompletableFuture<Void> sendDtmf(String digits) {
        try {
            TelephoneEvent.validateDigits(digits);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (state.get() != State.RUNNING) {
            return CompletableFuture.failedFuture(new IllegalStateException("audio stream not running"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            onLoop(() -> {
                if (state.get() != State.RUNNING) {
                    result.completeExceptionally(new IllegalStateException("audio stream not running"));
                    return;
                }
                CompletableFuture<Void> sending = dtmfSender != null
                        ? dtmfSender.enqueue(digits) : inbandDtmf.enqueue(digits);
                sending.whenComplete((v, e) -> {
                    if (e != null) {
                        result.completeExceptionally(e);
                    } else {
                        result.complete(null);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("event loop shut down", e));
        }
        return result;
    }

    // ===================================================================================== statistics

    /** RTP packets sent (audio and telephone events). */
    public long packetsSent() {
        return packetsSent.get();
    }

    /** RTP packets received while receiving was enabled (before reordering and duplicate removal). */
    public long packetsReceived() {
        return packetsReceived.get();
    }

    /** Packets lost according to RFC 3550 reception statistics, summed over remote sources (never negative). */
    public long packetsLost() {
        long lost = 0;
        for (ReceptionStatistics stats : session.receptionStatistics()) {
            lost += Math.max(0, stats.packetsLost());
        }
        return lost;
    }

    // ===================================================================================== internals

    private static void validate(MediaStreamSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.type() != MediaType.AUDIO) {
            throw new IllegalArgumentException("not an audio stream spec: " + spec.type());
        }
    }

    private static boolean matches(AudioCodec codec, MediaFormat format) {
        return codec != null && codec.encodingName().equalsIgnoreCase(format.encodingName())
                && codec.clockRate() == format.clockRate();
    }

    private AudioCodec resolveSendCodec(MediaStreamSpec spec) {
        MediaFormat format = spec.sendFormat();
        AudioCodec current = sendCodec;
        if (matches(current, format)) {
            return current;
        }
        return registry.create(format.encodingName(), format.clockRate())
                .orElseThrow(() -> new IllegalArgumentException("unsupported audio send format "
                        + format.encodingName() + "/" + format.clockRate()));
    }

    private Map<Integer, AudioCodec> resolveReceiveCodecs(MediaStreamSpec spec) {
        Map<Integer, AudioCodec> current = receiveCodecs;
        Map<Integer, AudioCodec> codecs = new LinkedHashMap<>();
        for (MediaFormat format : spec.receiveFormats()) {
            if (format.is(TelephoneEvent.ENCODING_NAME)) {
                continue;
            }
            AudioCodec existing = current.get(format.payloadType());
            if (matches(existing, format)) {
                codecs.put(format.payloadType(), existing);
            } else {
                registry.create(format.encodingName(), format.clockRate())
                        .ifPresentOrElse(codec -> codecs.put(format.payloadType(), codec),
                                () -> log.debug("no codec for receive format {}", format));
            }
        }
        return codecs;
    }

    private void install(MediaStreamSpec spec, AudioCodec send, Map<Integer, AudioCodec> receive) {
        this.receiveCodecs = receive;
        this.sendCodec = send;
        this.spec = spec;
    }

    /** Applies the latest installed spec on the event loop. */
    private void applySpec() {
        if (state.get() != State.RUNNING) {
            return;
        }
        MediaStreamSpec newSpec = spec;
        AudioCodec codec = sendCodec;
        MediaStreamSpec old = appliedSpec;
        appliedSpec = newSpec;
        appliedSendCodec = codec;
        appliedReceiveCodecs = receiveCodecs;

        if (old == null || !Objects.equals(old.remoteRtp(), newSpec.remoteRtp())
                || !Objects.equals(old.remoteRtcp(), newSpec.remoteRtcp()) || old.rtcpMux() != newSpec.rtcpMux()) {
            session.connect(newSpec.hasRemoteTarget() ? newSpec.remoteRtp() : null, newSpec.remoteRtcp(),
                    newSpec.rtcpMux());
        }

        Map<Integer, Integer> clockRates = new HashMap<>();
        newSpec.receiveFormats().forEach(f -> clockRates.put(f.payloadType(), f.clockRate()));
        clockRates.putIfAbsent(newSpec.sendFormat().payloadType(), codec.clockRate());
        if (newSpec.sendTelephoneEventPt() != null) {
            clockRates.putIfAbsent(newSpec.sendTelephoneEventPt(), codec.clockRate());
        }
        session.setPayloadClockRates(clockRates);

        int ptime = newSpec.ptimeMillis();
        if (newSpec.sendTelephoneEventPt() != null) {
            if (dtmfSender == null || dtmfSender.clockRate() != codec.clockRate() || dtmfSenderPtime != ptime) {
                if (dtmfSender != null) {
                    dtmfSender.cancel();
                }
                dtmfSender = new DtmfSender(codec.clockRate(), ptime);
                dtmfSenderPtime = ptime;
            }
        } else if (dtmfSender != null) {
            dtmfSender.cancel();
            dtmfSender = null;
        }

        Integer receiveTe = newSpec.receiveTelephoneEventPt();
        if (receiveTe != null) {
            int rate = newSpec.receiveFormats().stream().filter(f -> f.payloadType() == receiveTe)
                    .mapToInt(MediaFormat::clockRate).findFirst().orElse(codec.clockRate());
            if (dtmfReceiver == null || dtmfReceiverClockRate != rate) {
                dtmfReceiver = new DtmfReceiver(rate, this::fireDtmf);
                dtmfReceiverClockRate = rate;
            }
        } else {
            dtmfReceiver = null;
        }

        if (!newSpec.send()) {
            talkspurt = true;
        }
        if (clock == null || clockPtime != ptime) {
            if (clock != null) {
                clock.cancel(false);
            }
            clockPtime = ptime;
            clock = loop.scheduleAtFixedRate(this::tick, ptime, ptime, TimeUnit.MILLISECONDS);
        }
    }

    /** One media clock tick, on the event loop. */
    private void tick() {
        MediaStreamSpec s = appliedSpec;
        AudioCodec codec = appliedSendCodec;
        if (state.get() != State.RUNNING || s == null || codec == null) {
            return;
        }
        int ptime = s.ptimeMillis();
        try {
            if (s.send() && s.hasRemoteTarget()) {
                sendTick(s, codec, ptime);
            } else {
                talkspurt = true;
            }
        } catch (RuntimeException e) {
            log.warn("audio send tick failed", e);
        }
        timestamp = (timestamp + codec.timestampIncrement(ptime)) & 0xFFFFFFFFL;
    }

    private void sendTick(MediaStreamSpec s, AudioCodec codec, int ptime) {
        Integer eventPt = s.sendTelephoneEventPt();
        if (eventPt != null && dtmfSender != null) {
            DtmfSender.Packet event = dtmfSender.next(timestamp);
            if (event != null) {
                sendRtp(eventPt, event.marker(), event.timestamp(), event.payload());
                return;
            }
        }
        short[] pcm = new short[codec.samplesPerFrame(ptime)];
        AudioSource src = inbandDtmf.isActive() ? inbandDtmf : source;
        try {
            src.read(pcm, codec.sampleRate());
        } catch (RuntimeException e) {
            log.warn("audio source failed; sending silence", e);
            Arrays.fill(pcm, (short) 0);
        }
        sendRtp(s.sendFormat().payloadType(), talkspurt, timestamp, codec.encode(pcm));
        talkspurt = false;
    }

    private void sendRtp(int payloadType, boolean marker, long rtpTimestamp, byte[] payload) {
        RtpPacket packet = RtpPacket.builder()
                .payloadType(payloadType)
                .marker(marker)
                .sequenceNumber(sequenceNumber)
                .timestamp(rtpTimestamp)
                .ssrc(session.ssrc())
                .payload(payload)
                .build();
        sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
        if (session.send(packet)) {
            packetsSent.incrementAndGet();
        }
    }

    private void deliver(MediaStreamSpec s, RtpPacket packet) {
        Integer eventPt = s.receiveTelephoneEventPt();
        if (eventPt != null && packet.payloadType() == eventPt) {
            if (dtmfReceiver != null) {
                dtmfReceiver.receive(packet);
            }
            return;
        }
        AudioCodec codec = appliedReceiveCodecs.get(packet.payloadType());
        if (codec == null) {
            return;
        }
        short[] pcm;
        try {
            pcm = codec.decode(packet.payload());
        } catch (RuntimeException e) {
            log.debug("failed to decode {} payload: {}", codec.encodingName(), e.toString());
            return;
        }
        try {
            sink.write(pcm, codec.sampleRate());
        } catch (RuntimeException e) {
            log.warn("audio sink failed", e);
        }
    }

    private void fireDtmf(char digit, int durationMillis) {
        try {
            listener.onDtmf(digit, durationMillis);
        } catch (RuntimeException e) {
            log.warn("DTMF listener failed", e);
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
            packetsReceived.incrementAndGet();
            for (RtpPacket ready : jitterBuffer.push(packet)) {
                deliver(s, ready);
            }
        }

        @Override
        public void onRtcp(List<RtcpPacket> packets, InetSocketAddress sender) {
            // Reception statistics are maintained by the session; nothing stream specific to do.
        }
    }

    @Override
    public String toString() {
        return "AudioStream[" + state.get() + ", " + session + "]";
    }
}
