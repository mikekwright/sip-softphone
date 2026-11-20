package com.github.mikekwright.sip.softphone.call;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-call options for placing or answering a call.
 *
 * @param audio   offer / accept audio
 * @param video   offer / accept video (requires video codecs in the {@code SoftphoneConfig})
 * @param headers extra SIP headers to add to the INVITE (outgoing calls), e.g. for test correlation ids
 */
public record CallOptions(boolean audio, boolean video, Map<String, String> headers) {

    public CallOptions {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static CallOptions audioOnly() {
        return new CallOptions(true, false, Map.of());
    }

    public static CallOptions audioVideo() {
        return new CallOptions(true, true, Map.of());
    }

    public CallOptions withVideo(boolean enabled) {
        return new CallOptions(audio, enabled, headers);
    }

    public CallOptions withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new CallOptions(audio, video, h);
    }
}
