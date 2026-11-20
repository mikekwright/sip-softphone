package com.github.mikekwright.sip.softphone.media.dtmf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DtmfSenderReceiverTest {

    private record Sent(long tick, DtmfSender.Packet packet) {
    }

    /** Drives the sender like an audio stream: one call per 20 ms tick, timestamp advancing by 160. */
    private static List<Sent> drive(DtmfSender sender, long startTimestamp, int ticks) {
        List<Sent> sent = new ArrayList<>();
        long timestamp = startTimestamp;
        for (int tick = 0; tick < ticks; tick++) {
            DtmfSender.Packet packet = sender.next(timestamp);
            if (packet != null) {
                sent.add(new Sent(tick, packet));
            }
            timestamp = (timestamp + 160) & 0xFFFFFFFFL;
        }
        return sent;
    }

    private record Digit(char digit, int durationMillis) {
    }

    @Test
    void producesRfc4733SequenceForOneDigit() {
        DtmfSender sender = new DtmfSender(8000, 20);
        assertThat(sender.isIdle()).isTrue();
        assertThat(sender.next(0)).isNull();

        CompletableFuture<Void> done = sender.enqueue("1");
        List<Sent> sent = drive(sender, 1000, 7);
        assertThat(sent).hasSize(7);
        assertThat(sent).extracting(s -> s.packet().timestamp()).containsOnly(1000L);
        assertThat(sent).extracting(s -> s.packet().marker()).containsExactly(true, false, false, false, false,
                false, false);
        assertThat(sent).extracting(s -> s.packet().event().duration())
                .containsExactly(160, 320, 480, 640, 800, 800, 800);
        assertThat(sent).extracting(s -> s.packet().event().end())
                .containsExactly(false, false, false, false, true, true, true);
        assertThat(sent).extracting(s -> s.packet().event().event()).containsOnly(1);
        assertThat(sent).extracting(s -> s.packet().event().volume()).containsOnly(DtmfSender.DEFAULT_VOLUME);
        assertThat(sent.get(0).packet().payload()).containsExactly(1, 10, 0, (byte) 160);
        assertThat(done).isCompleted();
        assertThat(sender.isEventInProgress()).isFalse();
        // 50 ms gap = 3 ticks without packets, then idle
        assertThat(drive(sender, 0, 3)).isEmpty();
        assertThat(sender.isIdle()).isTrue();
    }

    @Test
    void separatesDigitsByGapAndUsesNewEventTimestamps() {
        DtmfSender sender = new DtmfSender(8000, 20);
        CompletableFuture<Void> done = sender.enqueue("12");
        List<Sent> sent = drive(sender, 0, 30);
        assertThat(sent).hasSize(14);
        assertThat(sent.subList(0, 7)).extracting(Sent::tick).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L);
        // ticks 7, 8, 9 are the gap; the second event starts at tick 10 with that tick's timestamp
        assertThat(sent.get(7).tick()).isEqualTo(10);
        assertThat(sent.get(7).packet().timestamp()).isEqualTo(1600);
        assertThat(sent.get(7).packet().marker()).isTrue();
        assertThat(sent.get(7).packet().event().event()).isEqualTo(2);
        assertThat(done).isCompleted();
    }

    @Test
    void honoursCustomDurationsAndCancels() {
        DtmfSender sender = new DtmfSender(16_000, 30, 200, 0, 5);
        CompletableFuture<Void> done = sender.enqueue("#");
        List<Sent> sent = drive(sender, 0, 20);
        // 200 ms at 30 ms ticks -> 7 tone ticks (210 ms), the last of them the first end packet
        assertThat(sent).hasSize(9);
        assertThat(sent.get(6).packet().event().duration()).isEqualTo(7 * 480);
        assertThat(sent.get(6).packet().event().end()).isTrue();
        assertThat(done).isCompleted();

        CompletableFuture<Void> cancelled = sender.enqueue("123");
        sender.next(0);
        sender.cancel();
        assertThat(cancelled).isCompletedExceptionally();
        assertThatThrownBy(cancelled::join).isInstanceOf(CancellationException.class);
        assertThat(sender.isIdle()).isTrue();
        assertThatThrownBy(() -> sender.enqueue("1x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DtmfSender(8000, 20, 10_000, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.enqueue("")).isCompleted();
    }

    @Test
    void receiverReportsEachDigitOnceDespiteRedundantEndPackets() {
        List<Digit> digits = new ArrayList<>();
        DtmfReceiver receiver = new DtmfReceiver(8000, (d, ms) -> digits.add(new Digit(d, ms)));
        DtmfSender sender = new DtmfSender(8000, 20);
        sender.enqueue("123#");
        for (Sent s : drive(sender, 0xFFFFFF00L, 60)) {
            receiver.receive(s.packet().payload(), s.packet().timestamp(), s.packet().marker());
            // a duplicate of every packet, as a lossy network with retransmission might produce
            receiver.receive(s.packet().payload(), s.packet().timestamp(), s.packet().marker());
        }
        assertThat(digits).containsExactly(new Digit('1', 100), new Digit('2', 100), new Digit('3', 100),
                new Digit('#', 100));
        receiver.flush();
        assertThat(digits).hasSize(4);
    }

    @Test
    void receiverReportsEventWhoseEndWasLost() {
        List<Digit> digits = new ArrayList<>();
        DtmfReceiver receiver = new DtmfReceiver(8000, (d, ms) -> digits.add(new Digit(d, ms)));
        receiver.receive(new TelephoneEvent(4, false, 10, 160).encode(), 1000, true);
        receiver.receive(new TelephoneEvent(4, false, 10, 480).encode(), 1000, false);
        assertThat(digits).isEmpty();
        // all end packets of '4' lost; the next event starts
        receiver.receive(new TelephoneEvent(5, false, 10, 160).encode(), 5000, true);
        assertThat(digits).containsExactly(new Digit('4', 60));
        // a late end packet of '4' must not resurrect it
        receiver.receive(new TelephoneEvent(4, true, 10, 800).encode(), 1000, false);
        assertThat(digits).hasSize(1);
        receiver.receive(new TelephoneEvent(5, true, 10, 800).encode(), 5000, false);
        receiver.receive(new TelephoneEvent(5, true, 10, 800).encode(), 5000, false);
        assertThat(digits).containsExactly(new Digit('4', 60), new Digit('5', 100));
    }

    @Test
    void receiverHandlesLostStartAndFlush() {
        List<Digit> digits = new ArrayList<>();
        DtmfReceiver receiver = new DtmfReceiver(8000, (d, ms) -> digits.add(new Digit(d, ms)));
        // only end packets arrive
        receiver.receive(new TelephoneEvent(9, true, 10, 800).encode(), 1000, false);
        receiver.receive(new TelephoneEvent(9, true, 10, 800).encode(), 1000, false);
        assertThat(digits).containsExactly(new Digit('9', 100));
        // an event that is still going when the stream stops
        receiver.receive(new TelephoneEvent(0, false, 10, 320).encode(), 9000, true);
        receiver.flush();
        receiver.flush();
        assertThat(digits).containsExactly(new Digit('9', 100), new Digit('0', 40));
        // malformed payloads and non-DTMF events are ignored
        receiver.receive(new byte[2], 12_000, true);
        receiver.receive(new TelephoneEvent(16, true, 10, 800).encode(), 13_000, true);
        assertThat(digits).hasSize(2);
    }

    @Test
    void dtmfTonesMatrixAndDetection() {
        assertThat(DtmfTones.frequencies('1')).containsExactly(697, 1209);
        assertThat(DtmfTones.frequencies('#')).containsExactly(941, 1477);
        assertThat(DtmfTones.frequencies('d')).containsExactly(941, 1633);
        assertThatThrownBy(() -> DtmfTones.frequencies('x')).isInstanceOf(IllegalArgumentException.class);
        for (char digit : "0123456789*#ABCD".toCharArray()) {
            double[] f = DtmfTones.frequencies(digit);
            short[] samples = new short[400];
            com.github.mikekwright.sip.softphone.media.audio.ToneSource.mixed(0.5, f[0], f[1]).read(samples, 8000);
            assertThat(DtmfTones.detect(samples, 8000)).as("digit %s", digit).contains(digit);
        }
        short[] single = new short[400];
        new com.github.mikekwright.sip.softphone.media.audio.ToneSource(1000, 0.5).read(single, 8000);
        assertThat(DtmfTones.detect(single, 8000)).isEmpty();
        assertThat(DtmfTones.detect(new short[400], 8000)).isEmpty();
    }

    @Test
    void toneSourcePlaysDigitsInBand() {
        DtmfToneSource source = new DtmfToneSource(100, 60, 0.5);
        CompletableFuture<Void> done = source.enqueue("5#");
        assertThat(source.isActive()).isTrue();
        short[] first = new short[800];   // 100 ms tone of '5'
        source.read(first, 8000);
        assertThat(DtmfTones.detect(first, 8000)).contains('5');
        short[] gap = new short[480];     // 60 ms silence
        source.read(gap, 8000);
        assertThat(gap).containsOnly((short) 0);
        short[] second = new short[800];
        source.read(second, 8000);
        assertThat(DtmfTones.detect(second, 8000)).contains('#');
        assertThat(done).isNotDone();
        source.read(new short[480], 8000);
        assertThat(done).isCompleted();
        assertThat(source.isActive()).isFalse();
        short[] idle = {1, 1};
        source.read(idle, 8000);
        assertThat(idle).containsOnly((short) 0);

        DtmfToneSource other = DtmfToneSource.of("1");
        other.cancel();
        assertThat(other.isActive()).isFalse();
    }
}
