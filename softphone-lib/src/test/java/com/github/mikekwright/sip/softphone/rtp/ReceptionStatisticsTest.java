package com.github.mikekwright.sip.softphone.rtp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReceptionStatisticsTest {

    private static final long MS = 1_000_000L;

    /** Feeds packets with ideal 20 ms spacing at 8 kHz. */
    private static void feed(ReceptionStatistics stats, int seq, int index) {
        stats.onPacket(seq & 0xFFFF, 1000L + 160L * index, 160, index * 20 * MS, 8000);
    }

    @Test
    void countsInOrderPacketsWithoutLoss() {
        ReceptionStatistics stats = new ReceptionStatistics(7);
        for (int i = 0; i < 100; i++) {
            feed(stats, 500 + i, i);
        }
        assertThat(stats.packetsReceived()).isEqualTo(100);
        assertThat(stats.packetsExpected()).isEqualTo(100);
        assertThat(stats.packetsLost()).isZero();
        assertThat(stats.octetsReceived()).isEqualTo(16_000);
        assertThat(stats.extendedHighestSequence()).isEqualTo(599);
        ReportBlock block = stats.toReportBlock(0);
        assertThat(block.ssrc()).isEqualTo(7);
        assertThat(block.fractionLost()).isZero();
        assertThat(block.cumulativeLost()).isZero();
        assertThat(block.jitter()).isZero();
        assertThat(block.lastSenderReport()).isZero();
        assertThat(block.delaySinceLastSenderReport()).isZero();
    }

    @Test
    void extendsSequenceNumbersAcrossWrapAround() {
        ReceptionStatistics stats = new ReceptionStatistics(1);
        for (int i = 0; i < 20; i++) {
            feed(stats, 65_530 + i, i);
        }
        // 65530 + 19 = 65549 = one cycle (65536) + 13
        assertThat(stats.extendedHighestSequence()).isEqualTo(65_536 + 13);
        assertThat(stats.packetsExpected()).isEqualTo(20);
        assertThat(stats.packetsLost()).isZero();
        assertThat(stats.toReportBlock(0).extendedHighestSequence()).isEqualTo(0x0001_000DL);
    }

    @Test
    void computesCumulativeAndIntervalLossPerAppendixA3() {
        ReceptionStatistics stats = new ReceptionStatistics(1);
        int index = 0;
        for (int seq = 0; seq < 100; seq++) {
            if (seq % 10 == 5) {
                continue; // lose 10 of 100
            }
            feed(stats, seq, index++);
        }
        assertThat(stats.packetsExpected()).isEqualTo(100);
        assertThat(stats.packetsLost()).isEqualTo(10);
        ReportBlock first = stats.toReportBlock(0);
        assertThat(first.cumulativeLost()).isEqualTo(10);
        assertThat(first.fractionLost()).isEqualTo(10 * 256 / 100);

        for (int seq = 100; seq < 200; seq++) {
            feed(stats, seq, index++);
        }
        ReportBlock second = stats.toReportBlock(0);
        assertThat(second.fractionLost()).isZero();
        assertThat(second.cumulativeLost()).isEqualTo(10);
    }

    @Test
    void duplicatesMakeCumulativeLossNegativeButFractionZero() {
        ReceptionStatistics stats = new ReceptionStatistics(1);
        feed(stats, 10, 0);
        feed(stats, 11, 1);
        feed(stats, 11, 2);
        assertThat(stats.packetsLost()).isEqualTo(-1);
        ReportBlock block = stats.toReportBlock(0);
        assertThat(block.cumulativeLost()).isEqualTo(-1);
        assertThat(block.fractionLost()).isZero();
    }

    @Test
    void resynchronisesAfterTwoSequentialPacketsFollowingALargeJump() {
        ReceptionStatistics stats = new ReceptionStatistics(1);
        feed(stats, 100, 0);
        feed(stats, 101, 1);
        assertThat(stats.onPacket(20_000, 0, 0, 2 * 20 * MS, 8000)).isFalse();
        assertThat(stats.extendedHighestSequence()).isEqualTo(101);
        assertThat(stats.onPacket(20_001, 160, 0, 3 * 20 * MS, 8000)).isTrue();
        assertThat(stats.extendedHighestSequence()).isEqualTo(20_001);
        assertThat(stats.packetsExpected()).isEqualTo(1);
        assertThat(stats.packetsReceived()).isEqualTo(1);
    }

    @Test
    void jitterIsZeroForPerfectlyPacedPackets() {
        ReceptionStatistics stats = new ReceptionStatistics(1);
        for (int i = 0; i < 50; i++) {
            feed(stats, i, i);
        }
        assertThat(stats.jitter()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void jitterFollowsAppendixA8Estimator() {
        ReceptionStatistics stats = new ReceptionStatistics(1);
        // Every other packet arrives 10 ms (80 timestamp units at 8 kHz) late.
        double expected = 0;
        long previousTransit = 0;
        for (int i = 0; i < 200; i++) {
            long delayMs = (i % 2 == 1) ? 10 : 0;
            long arrivalNanos = (i * 20 + delayMs) * MS;
            long rtpTimestamp = 160L * i;
            stats.onPacket(i, rtpTimestamp, 160, arrivalNanos, 8000);
            long transit = (i * 20 + delayMs) * 8 - rtpTimestamp;
            if (i > 0) {
                expected += (Math.abs(transit - previousTransit) - expected) / 16.0;
            }
            previousTransit = transit;
        }
        assertThat(stats.jitter()).isCloseTo(expected, within(1e-6));
        assertThat(stats.jitter()).isCloseTo(80.0, within(1.0));
        assertThat(stats.toReportBlock(0).jitter()).isEqualTo((long) stats.jitter());
    }

    @Test
    void jitterSurvivesRtpTimestampWrapAround() {
        ReceptionStatistics stats = new ReceptionStatistics(1);
        long start = 0xFFFFFFFFL - 160 * 5;
        for (int i = 0; i < 20; i++) {
            stats.onPacket(i, (start + 160L * i) & 0xFFFFFFFFL, 160, i * 20 * MS, 8000);
        }
        assertThat(stats.jitter()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void reportsLastSenderReportAndDelay() {
        ReceptionStatistics stats = new ReceptionStatistics(1);
        feed(stats, 1, 0);
        long ntp = 0x0102030405060708L;
        stats.onSenderReport(ntp, 1_000 * MS);
        ReportBlock block = stats.toReportBlock(2_500 * MS);
        assertThat(block.lastSenderReport()).isEqualTo(0x03040506L);
        assertThat(block.delaySinceLastSenderReport()).isEqualTo(65_536L * 3 / 2);
    }

    @Test
    void senderStatisticsBuildSenderReports() {
        SenderStatistics stats = new SenderStatistics();
        assertThat(stats.consumeSentSinceLastReport()).isFalse();
        stats.onPacketSent(160, 1000, 0, 8000);
        stats.onPacketSent(160, 1160, 20 * MS, 8000);
        assertThat(stats.consumeSentSinceLastReport()).isTrue();
        assertThat(stats.consumeSentSinceLastReport()).isFalse();
        SenderReport sr = stats.toSenderReport(42, 99L, 120 * MS, List.of());
        assertThat(sr.packetCount()).isEqualTo(2);
        assertThat(sr.octetCount()).isEqualTo(320);
        // 100 ms after the last packet at 8 kHz -> +800
        assertThat(sr.rtpTimestamp()).isEqualTo(1960);
        assertThat(sr.ntpTimestamp()).isEqualTo(99L);
    }
}
