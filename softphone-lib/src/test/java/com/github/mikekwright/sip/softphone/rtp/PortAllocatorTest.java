package com.github.mikekwright.sip.softphone.rtp;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortAllocatorTest {

    @Test
    void handsOutEvenPortsRoundRobinWithinRange() {
        PortAllocator ports = new PortAllocator(10_001, 10_010);
        assertThat(ports.pairCount()).isEqualTo(4);
        assertThat(ports.firstPort()).isEqualTo(10_002);
        assertThat(ports.lastPort()).isEqualTo(10_008);
        assertThat(IntStream.range(0, 6).map(i -> ports.nextRtpPort()).toArray())
                .containsExactly(10_002, 10_004, 10_006, 10_008, 10_002, 10_004);
    }

    @Test
    void rejectsInvalidRanges() {
        assertThatThrownBy(() -> new PortAllocator(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortAllocator(20, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortAllocator(10_001, 10_002)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortAllocator(1, 70_000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PortAllocator(10_000, 10_001).pairCount()).isEqualTo(1);
    }

    @Test
    void isSafeForConcurrentUse() {
        PortAllocator ports = new PortAllocator(20_000, 21_999);
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 1000).parallel().forEach(i -> seen.add(ports.nextRtpPort()));
        assertThat(seen).hasSize(1000).allMatch(p -> p % 2 == 0 && p >= 20_000 && p < 21_999);
    }
}
