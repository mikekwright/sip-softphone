package com.github.mikekwright.sip.softphone.media;

/** The kind of media carried by an RTP stream (the SDP {@code m=} media token). */
public enum MediaType {
    AUDIO("audio"),
    VIDEO("video");

    private final String sdpName;

    MediaType(String sdpName) {
        this.sdpName = sdpName;
    }

    /** The token used for this media type on an SDP {@code m=} line. */
    public String sdpName() {
        return sdpName;
    }

    /** Resolves an SDP media token ({@code audio}, {@code video}) to a media type, or {@code null} if unsupported. */
    public static MediaType fromSdpName(String name) {
        for (MediaType type : values()) {
            if (type.sdpName.equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
