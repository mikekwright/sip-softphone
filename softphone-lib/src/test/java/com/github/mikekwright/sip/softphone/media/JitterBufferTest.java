package com.github.mikekwright.sip.softphone.media;

import com.github.mikekwright.sip.softphone.rtp.RtpPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JitterBufferTest {

    private static RtpPacket packet(int seq) {
        return packet(seq, 1);
    }

    private static RtpPacket packet(int seq, long ssrc) {
        return RtpPacket.builder().sequenceNumber(seq & 0xFFFF).ssrc(ssrc).payload(new byte[]{(byte) seq}).build();
    }

    private static List<Integer> seqs(List<RtpPacket> packets) {
        return packets.stream().map(RtpPacket::sequenceNumber).toList();
    }

    @Test
    void passesInOrderPacketsThroughImmediately() {
        JitterBuffer buffer = new JitterBuffer(3);
        for (int i = 100; i < 110; i++) {
            assertThat(seqs(buffer.push(packet(i)))).containsExactly(i);
        }
        assertThat(buffer.size()).isZero();
    }

    @Test
    void reordersWithinCapacity() {
        JitterBuffer buffer = new JitterBuffer(3);
        assertThat(seqs(buffer.push(packet(1)))).containsExactly(1);
        assertThat(seqs(buffer.push(packet(3)))).isEmpty();
        assertThat(seqs(buffer.push(packet(4)))).isEmpty();
        assertThat(buffer.size()).isEqualTo(2);
        assertThat(seqs(buffer.push(packet(2)))).containsExactly(2, 3, 4);
        assertThat(buffer.skippedPackets()).isZero();
    }

    @Test
    void dropsDuplicatesAndLatePackets() {
        JitterBuffer buffer = new JitterBuffer(3);
        buffer.push(packet(1));
        buffer.push(packet(3));
        assertThat(buffer.push(packet(3))).isEmpty();
        assertThat(buffer.duplicatePackets()).isEqualTo(1);
        assertThat(buffer.push(packet(1))).isEmpty();
        assertThat(buffer.latePackets()).isEqualTo(1);
        assertThat(seqs(buffer.push(packet(2)))).containsExactly(2, 3);
        assertThat(buffer.push(packet(2))).isEmpty();
        assertThat(buffer.latePackets()).isEqualTo(2);
    }

    @Test
    void givesUpOnMissingPacketWhenFull() {
        JitterBuffer buffer = new JitterBuffer(3);
        buffer.push(packet(1));
        assertThat(buffer.push(packet(3))).isEmpty();
        assertThat(buffer.push(packet(4))).isEmpty();
        assertThat(buffer.push(packet(5))).isEmpty();
        assertThat(seqs(buffer.push(packet(6)))).containsExactly(3, 4, 5, 6);
        assertThat(buffer.skippedPackets()).isEqualTo(1);
        // the missing packet arriving now is late
        assertThat(buffer.push(packet(2))).isEmpty();
        assertThat(buffer.latePackets()).isEqualTo(1);
    }

    @Test
    void handlesSequenceWrapAround() {
        JitterBuffer buffer = new JitterBuffer(3);
        List<Integer> out = new ArrayList<>();
        out.addAll(seqs(buffer.push(packet(65534))));
        out.addAll(seqs(buffer.push(packet(0))));
        out.addAll(seqs(buffer.push(packet(65535))));
        out.addAll(seqs(buffer.push(packet(2))));
        out.addAll(seqs(buffer.push(packet(1))));
        assertThat(out).containsExactly(65534, 65535, 0, 1, 2);
    }

    @Test
    void restartsOnNewSourceOrLargeJump() {
        JitterBuffer buffer = new JitterBuffer(3);
        buffer.push(packet(10));
        buffer.push(packet(12));
        // new SSRC: held packet is flushed, then the new source starts
        assertThat(seqs(buffer.push(packet(500, 2)))).containsExactly(12, 500);
        // jump far ahead (sender restart without SSRC change)
        assertThat(seqs(buffer.push(packet(30_000, 2)))).containsExactly(30_000);
        // jump far back
        assertThat(seqs(buffer.push(packet(5, 2)))).containsExactly(5);
    }

    @Test
    void flushReleasesHeldPacketsInOrder() {
        JitterBuffer buffer = new JitterBuffer(5);
        buffer.push(packet(1));
        buffer.push(packet(5));
        buffer.push(packet(3));
        assertThat(seqs(buffer.flush())).containsExactly(3, 5);
        assertThat(buffer.size()).isZero();
        assertThat(seqs(buffer.push(packet(6)))).containsExactly(6);
        buffer.reset();
        assertThat(seqs(buffer.push(packet(100)))).containsExactly(100);
    }

    @Test
    void zeroCapacityDisablesReordering() {
        JitterBuffer buffer = new JitterBuffer(0);
        assertThat(seqs(buffer.push(packet(1)))).containsExactly(1);
        assertThat(seqs(buffer.push(packet(3)))).containsExactly(3);
        assertThat(buffer.push(packet(2))).isEmpty();
    }
}
