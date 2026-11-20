package com.github.mikekwright.sip.softphone.sip.transport;

import io.netty.channel.ChannelPipeline;

/**
 * Hook to add custom Netty handlers to SIP transport channels, e.g. a {@code LoggingHandler}, traffic shaping or a
 * packet-loss simulator in tests. Invoked for every new channel after the SIP codec handlers have been installed;
 * the SIP handlers are named {@code sip-decoder}, {@code sip-encoder} and {@code sip-handler}.
 */
@FunctionalInterface
public interface PipelineCustomizer {

    PipelineCustomizer NONE = (type, pipeline) -> {
    };

    void customize(TransportType type, ChannelPipeline pipeline);
}
