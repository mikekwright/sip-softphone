package com.github.mikekwright.sip.softphone.sdp;

/**
 * Serializes a {@link SessionDescription} to SDP text.
 *
 * <p>Lines are written in the order mandated by RFC 8866 §5 and terminated with CRLF (including the last line).
 * Session level: {@code v o s i u e p c b t}, the preserved {@code r= z= k=} and unknown lines, then {@code a};
 * per media: {@code m i c b}, preserved lines ({@code k=}), then {@code a}. A description without timings gets
 * {@code t=0 0}, as the line is mandatory.</p>
 */
public final class SdpWriter {

    private static final String CRLF = "\r\n";

    private SdpWriter() {
    }

    /** Returns the SDP text of a session description. */
    public static String write(SessionDescription sd) {
        StringBuilder sb = new StringBuilder(512);
        line(sb, 'v', Integer.toString(sd.version()));
        line(sb, 'o', sd.origin().toSdpValue());
        line(sb, 's', sd.sessionName());
        optionalLine(sb, 'i', sd.sessionInformation());
        optionalLine(sb, 'u', sd.uri());
        sd.emails().forEach(email -> line(sb, 'e', email));
        sd.phones().forEach(phone -> line(sb, 'p', phone));
        if (sd.connection() != null) {
            line(sb, 'c', sd.connection().toSdpValue());
        }
        sd.bandwidths().forEach(bw -> line(sb, 'b', bw.toSdpValue()));
        if (sd.timings().isEmpty()) {
            line(sb, 't', Timing.PERMANENT.toSdpValue());
        }
        sd.timings().forEach(timing -> line(sb, 't', timing.toSdpValue()));
        sd.otherLines().forEach(raw -> sb.append(raw).append(CRLF));
        sd.attributes().forEach(attribute -> line(sb, 'a', attribute.toSdpValue()));
        sd.media().forEach(media -> writeMedia(sb, media));
        return sb.toString();
    }

    /** Appends a single {@code m=} section. */
    static void writeMedia(StringBuilder sb, MediaDescription media) {
        StringBuilder m = new StringBuilder().append(media.media()).append(' ').append(media.port());
        if (media.numberOfPorts() != null) {
            m.append('/').append(media.numberOfPorts());
        }
        m.append(' ').append(media.protocol());
        media.formats().forEach(format -> m.append(' ').append(format));
        line(sb, 'm', m.toString());
        optionalLine(sb, 'i', media.information());
        if (media.connection() != null) {
            line(sb, 'c', media.connection().toSdpValue());
        }
        media.bandwidths().forEach(bw -> line(sb, 'b', bw.toSdpValue()));
        media.otherLines().forEach(raw -> sb.append(raw).append(CRLF));
        media.attributes().forEach(attribute -> line(sb, 'a', attribute.toSdpValue()));
    }

    private static void optionalLine(StringBuilder sb, char type, String value) {
        if (value != null) {
            line(sb, type, value);
        }
    }

    private static void line(StringBuilder sb, char type, String value) {
        sb.append(type).append('=').append(value).append(CRLF);
    }
}
