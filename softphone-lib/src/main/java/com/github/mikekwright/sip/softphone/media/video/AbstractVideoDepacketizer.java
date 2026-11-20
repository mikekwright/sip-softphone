package com.github.mikekwright.sip.softphone.media.video;

import com.github.mikekwright.sip.softphone.rtp.RtpPacket;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Codec independent part of frame reassembly: frame boundaries (RTP timestamp change and marker bit, RFC 3551
 * §5), sequence gap detection, discarding of broken frames, waiting for a key frame after loss (and initially),
 * and conversion of RTP timestamps to microseconds relative to the first emitted frame (with 32-bit wrap-around
 * handling).
 *
 * <p>Subclasses implement the payload format: {@link #depacketize} appends the media data of one packet,
 * {@link #isKeyFrame} classifies a reassembled frame, and the optional {@link #startFrame()} /
 * {@link #endFrame()} hooks manage per-frame state such as fragmentation units.</p>
 *
 * <p>Thread-safety: not thread-safe, like all depacketizers.</p>
 */
public abstract class AbstractVideoDepacketizer implements VideoDepacketizer {

    private final String encodingName;
    private final int clockRate;
    private final ByteArrayOutputStream frame = new ByteArrayOutputStream(16 * 1024);

    private long ssrc = -1;
    private int expectedSequence = -1;
    private boolean inFrame;
    private boolean frameBroken;
    private long frameTimestamp;
    private boolean waitingForKeyFrame = true;
    private boolean keyFrameNeeded;

    private boolean haveTimestampBase;
    private long lastEmittedRtpTimestamp;
    private long extendedTimestamp;

    /**
     * @param encodingName RTP encoding name reported in emitted frames
     * @param clockRate    RTP clock rate (90000 for video, RFC 3551 §5)
     */
    protected AbstractVideoDepacketizer(String encodingName, int clockRate) {
        this.encodingName = Objects.requireNonNull(encodingName, "encodingName");
        if (clockRate <= 0) {
            throw new IllegalArgumentException("clock rate must be positive");
        }
        this.clockRate = clockRate;
    }

    @Override
    public final String encodingName() {
        return encodingName;
    }

    /**
     * Appends the media data carried by one RTP payload to {@code frame}.
     *
     * @param payload           the RTP payload
     * @param firstPacketOfFrame whether this is the first packet received for the current RTP timestamp
     * @param frame             the frame being reassembled
     * @return {@code false} if the payload is malformed or cannot continue the frame (e.g. a continuation
     *         fragment whose start was lost); the frame is then discarded
     */
    protected abstract boolean depacketize(byte[] payload, boolean firstPacketOfFrame, ByteArrayOutputStream frame);

    /** Whether a reassembled frame is a key frame (decoder refresh point). */
    protected abstract boolean isKeyFrame(byte[] frameData);

    /** Called before the first packet of a new frame; resets per-frame state. */
    protected void startFrame() {
    }

    /** Called when the marker packet was processed; return {@code false} if the frame is incomplete. */
    protected boolean endFrame() {
        return true;
    }

    @Override
    public final EncodedVideoFrame push(RtpPacket packet) {
        if (ssrc != -1 && packet.ssrc() != ssrc) {
            reset(); // new source: start over and wait for its key frame
        }
        ssrc = packet.ssrc();

        int sequence = packet.sequenceNumber();
        if (expectedSequence >= 0) {
            int delta = (short) (sequence - expectedSequence);
            if (delta < 0) {
                return null; // duplicate or too late
            }
            if (delta > 0) {
                lossDetected();
            }
        }
        expectedSequence = (sequence + 1) & 0xFFFF;

        if (inFrame && packet.timestamp() != frameTimestamp) {
            // The previous frame never saw its marker packet.
            lossDetected();
            inFrame = false;
        }
        boolean first = !inFrame;
        if (first) {
            inFrame = true;
            frameBroken = false;
            frameTimestamp = packet.timestamp();
            frame.reset();
            startFrame();
        }
        if (!frameBroken && !depacketize(packet.payload(), first, frame)) {
            frameBroken = true;
            requestKeyFrame();
        }
        if (!packet.marker()) {
            return null;
        }
        inFrame = false;
        if (frameBroken || !endFrame()) {
            requestKeyFrame();
            return null;
        }
        byte[] data = frame.toByteArray();
        if (data.length == 0) {
            return null;
        }
        boolean key = isKeyFrame(data);
        if (key) {
            waitingForKeyFrame = false;
        } else if (waitingForKeyFrame) {
            keyFrameNeeded = true; // undecodable delta frame
            return null;
        }
        return new EncodedVideoFrame(data, key, toMicros(frameTimestamp), encodingName);
    }

    private void lossDetected() {
        if (inFrame) {
            frameBroken = true;
        }
        requestKeyFrame();
    }

    private void requestKeyFrame() {
        waitingForKeyFrame = true;
        keyFrameNeeded = true;
    }

    private long toMicros(long rtpTimestamp) {
        if (!haveTimestampBase) {
            haveTimestampBase = true;
            extendedTimestamp = 0;
        } else {
            extendedTimestamp += (int) (rtpTimestamp - lastEmittedRtpTimestamp);
        }
        lastEmittedRtpTimestamp = rtpTimestamp;
        return extendedTimestamp * 1_000_000L / clockRate;
    }

    @Override
    public boolean consumeKeyFrameNeeded() {
        boolean needed = keyFrameNeeded;
        keyFrameNeeded = false;
        return needed;
    }

    @Override
    public void reset() {
        ssrc = -1;
        expectedSequence = -1;
        inFrame = false;
        frameBroken = false;
        waitingForKeyFrame = true;
        keyFrameNeeded = false;
        haveTimestampBase = false;
        frame.reset();
    }
}
