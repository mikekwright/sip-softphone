package com.github.mikekwright.sip.softphone.rtp;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out RTP port numbers from a configured range. Following RFC 3550 §11, RTP uses an even port and RTCP the
 * next higher (odd) port, so only even ports {@code p} with {@code p + 1 <= max} are returned.
 *
 * <p>Ports are returned round robin, starting at the lowest even port of the range; the allocator does not track
 * which ports are in use — {@link RtpSession#bind} simply tries successive ports until a bind succeeds, which
 * also copes with ports taken by other processes.</p>
 *
 * <p>Thread-safety: safe for concurrent use (lock-free).</p>
 */
public final class PortAllocator {

    /** Default range used by {@link #defaultRange()}. */
    public static final int DEFAULT_MIN_PORT = 10_000;
    /** Default range used by {@link #defaultRange()}. */
    public static final int DEFAULT_MAX_PORT = 20_000;

    private final int firstPort;
    private final int pairCount;
    private final AtomicInteger cursor = new AtomicInteger();

    /**
     * @param minPort lowest usable port (inclusive); rounded up to an even number
     * @param maxPort highest usable port (inclusive); the last RTCP port must not exceed it
     * @throws IllegalArgumentException if the range is invalid or holds no RTP/RTCP pair
     */
    public PortAllocator(int minPort, int maxPort) {
        if (minPort < 1 || maxPort > 65_535 || minPort > maxPort) {
            throw new IllegalArgumentException("invalid port range " + minPort + "-" + maxPort);
        }
        this.firstPort = (minPort + 1) & ~1;
        this.pairCount = (maxPort - firstPort + 1) / 2;
        if (pairCount < 1) {
            throw new IllegalArgumentException("port range " + minPort + "-" + maxPort + " holds no RTP/RTCP pair");
        }
    }

    /** An allocator for {@value #DEFAULT_MIN_PORT}-{@value #DEFAULT_MAX_PORT}. */
    public static PortAllocator defaultRange() {
        return new PortAllocator(DEFAULT_MIN_PORT, DEFAULT_MAX_PORT);
    }

    /** The next even RTP port (the RTCP port is this value plus one). */
    public int nextRtpPort() {
        int index = Math.floorMod(cursor.getAndIncrement(), pairCount);
        return firstPort + 2 * index;
    }

    /** Number of RTP/RTCP pairs in the range. */
    public int pairCount() {
        return pairCount;
    }

    /** The lowest RTP port handed out. */
    public int firstPort() {
        return firstPort;
    }

    /** The highest RTP port handed out. */
    public int lastPort() {
        return firstPort + 2 * (pairCount - 1);
    }
}
