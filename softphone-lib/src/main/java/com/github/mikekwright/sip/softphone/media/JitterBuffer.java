package com.github.mikekwright.sip.softphone.media;

import com.github.mikekwright.sip.softphone.rtp.RtpPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small reordering buffer for RTP packets of one source: releases packets strictly in sequence number order,
 * drops duplicates and packets arriving after their slot was released, and gives up on a missing packet once
 * {@code capacity} later packets are waiting.
 *
 * <p>Packets are released as soon as they are in order, so an in-order stream passes through with no added
 * delay; only a gap holds packets back, for at most {@code capacity} packets (with 20 ms audio frames and the
 * default capacity of 3, about 60 ms). There is no timer: when packets stop arriving while a gap is pending, the
 * held packets are released by the next arrival or {@link #flush()}. This keeps the buffer deterministic and
 * easy to test; it is not an adaptive playout buffer.</p>
 *
 * <p>Sequence numbers are compared modulo 2<sup>16</sup>. A change of SSRC, or a jump of more than
 * {@value #MAX_JUMP} packets in either direction (a sender restart), flushes the buffer and restarts from the
 * new packet.</p>
 *
 * <p>Thread-safety: not thread-safe; confine to one thread (the streams use their session's event loop).</p>
 */
public final class JitterBuffer {

    /** Default capacity in packets. */
    public static final int DEFAULT_CAPACITY = 3;
    /** Sequence jumps larger than this restart the buffer. */
    public static final int MAX_JUMP = 1000;

    private final int capacity;
    private final TreeMap<Long, RtpPacket> pending = new TreeMap<>();
    private boolean started;
    private long ssrc;
    /** Extended (unwrapped) sequence number of the next packet to release. */
    private long nextSequence;

    private long late;
    private long duplicates;
    private long skipped;

    /** A buffer with {@link #DEFAULT_CAPACITY}. */
    public JitterBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /** @param capacity maximum number of packets held while waiting for a missing one (0 disables reordering) */
    public JitterBuffer(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        this.capacity = capacity;
    }

    /**
     * Adds a packet and returns the packets that are now ready, in sequence order (possibly empty).
     */
    public List<RtpPacket> push(RtpPacket packet) {
        List<RtpPacket> ready = new ArrayList<>(2);
        if (started && packet.ssrc() != ssrc) {
            drainAll(ready);
            started = false;
        }
        if (!started) {
            start(packet);
        }
        int delta = (short) (packet.sequenceNumber() - (int) (nextSequence & 0xFFFF));
        if (delta > MAX_JUMP || delta < -MAX_JUMP) {
            drainAll(ready);
            start(packet);
            delta = 0;
        }
        if (delta < 0) {
            late++;
            return ready;
        }
        long extended = nextSequence + delta;
        if (pending.putIfAbsent(extended, packet) != null) {
            duplicates++;
            return ready;
        }
        releaseInOrder(ready);
        while (pending.size() > capacity) {
            long first = pending.firstKey();
            skipped += first - nextSequence;
            nextSequence = first;
            releaseInOrder(ready);
        }
        return ready;
    }

    /** Releases all held packets in order, skipping gaps. */
    public List<RtpPacket> flush() {
        List<RtpPacket> ready = new ArrayList<>(pending.size());
        drainAll(ready);
        return ready;
    }

    /** Discards held packets and all sequence state. */
    public void reset() {
        pending.clear();
        started = false;
    }

    /** Number of packets currently held. */
    public int size() {
        return pending.size();
    }

    /** Packets dropped because they arrived after their position was released. */
    public long latePackets() {
        return late;
    }

    /** Packets dropped as duplicates of a held packet. */
    public long duplicatePackets() {
        return duplicates;
    }

    /** Sequence numbers given up on (never received in time). */
    public long skippedPackets() {
        return skipped;
    }

    private void start(RtpPacket packet) {
        started = true;
        ssrc = packet.ssrc();
        nextSequence = packet.sequenceNumber();
    }

    private void releaseInOrder(List<RtpPacket> ready) {
        RtpPacket next;
        while ((next = pending.remove(nextSequence)) != null) {
            ready.add(next);
            nextSequence++;
        }
    }

    private void drainAll(List<RtpPacket> ready) {
        for (Map.Entry<Long, RtpPacket> entry : pending.entrySet()) {
            skipped += entry.getKey() - nextSequence;
            ready.add(entry.getValue());
            nextSequence = entry.getKey() + 1;
        }
        pending.clear();
    }
}
