package com.github.mikekwright.sip.softphone.call;

import com.github.mikekwright.sip.softphone.media.MediaFormat;
import com.github.mikekwright.sip.softphone.media.MediaStreamSpec;
import com.github.mikekwright.sip.softphone.media.MediaType;
import com.github.mikekwright.sip.softphone.sdp.MediaDirection;
import com.github.mikekwright.sip.softphone.sdp.NegotiatedFormat;
import com.github.mikekwright.sip.softphone.sdp.NegotiatedMedia;
import com.github.mikekwright.sip.softphone.sdp.RtpMap;
import com.github.mikekwright.sip.softphone.sdp.SdpMediaConfig;
import com.github.mikekwright.sip.softphone.sdp.StaticPayloadTypes;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Glue between the SDP offer/answer model and the media layer: builds the local SDP media configuration from the
 * softphone's codec preferences, and turns negotiated SDP media into transport neutral {@link MediaStreamSpec}s.
 */
public final class MediaNegotiation {

    /** RTCP feedback we support for video (RFC 4585 NACK/PLI, RFC 5104 FIR). */
    public static final List<String> VIDEO_FEEDBACK = List.of("nack", "nack pli", "ccm fir");

    private static final Map<String, Integer> PREFERRED_DYNAMIC = Map.of(
            "VP8", 96,
            "H264", 97,
            "L16", 98,
            "OPUS", 111);

    private MediaNegotiation() {
    }

    /** SDP formats for the configured audio codecs plus telephone-event. */
    public static List<RtpMap> audioFormats(List<String> codecs, int telephoneEventPt) {
        List<RtpMap> formats = new ArrayList<>();
        int nextDynamic = 102;
        for (String name : codecs) {
            String upper = name.toUpperCase(Locale.ROOT);
            RtpMap rtpMap = StaticPayloadTypes.all().values().stream()
                    .filter(m -> m.encodingName().equalsIgnoreCase(upper) && m.channels() == 1)
                    .findFirst()
                    .orElse(null);
            if (rtpMap == null) {
                int pt = PREFERRED_DYNAMIC.getOrDefault(upper, nextDynamic++);
                rtpMap = new RtpMap(pt, upper, upper.equals("L16") ? 8000 : 8000);
            }
            formats.add(rtpMap);
        }
        if (telephoneEventPt >= 0) {
            formats.add(new RtpMap(telephoneEventPt, RtpMap.TELEPHONE_EVENT, 8000).withFmtp("0-16"));
        }
        return formats;
    }

    /** SDP formats for the configured video codecs, with RTCP feedback. */
    public static List<RtpMap> videoFormats(List<String> codecs) {
        List<RtpMap> formats = new ArrayList<>();
        for (String name : codecs) {
            String upper = name.toUpperCase(Locale.ROOT);
            switch (upper) {
                case "VP8" -> formats.add(new RtpMap(96, "VP8", 90000).withRtcpFeedback(VIDEO_FEEDBACK));
                case "H264" -> formats.add(new RtpMap(97, "H264", 90000)
                        .withFmtp("profile-level-id=42e01f;packetization-mode=1")
                        .withRtcpFeedback(VIDEO_FEEDBACK));
                default -> throw new IllegalArgumentException("unsupported video codec " + name);
            }
        }
        return formats;
    }

    public static SdpMediaConfig audioConfig(String address, int port, List<RtpMap> formats,
                                             MediaDirection direction) {
        return SdpMediaConfig.audio(address, port, formats).withDirection(direction).withPtime(20);
    }

    public static SdpMediaConfig videoConfig(String address, int port, List<RtpMap> formats,
                                             MediaDirection direction) {
        return SdpMediaConfig.video(address, port, formats).withDirection(direction);
    }

    /**
     * Converts a negotiated SDP media line into a media stream specification, or {@code null} if the line was
     * rejected or is of an unsupported media type.
     */
    public static MediaStreamSpec toSpec(NegotiatedMedia media) {
        MediaType type = MediaType.fromSdpName(media.mediaType());
        if (type == null || media.rejected() || media.formats().isEmpty()) {
            return null;
        }
        List<MediaFormat> receive = new ArrayList<>();
        for (NegotiatedFormat f : media.formats()) {
            receive.add(new MediaFormat(f.localPayloadType(), f.encodingName(), f.clockRate(), f.channels(),
                    f.localFmtp(), f.rtcpFeedback()));
        }
        NegotiatedFormat primary = media.primaryFormat();
        MediaFormat send = new MediaFormat(primary.remotePayloadType(), primary.encodingName(), primary.clockRate(),
                primary.channels(), primary.remoteFmtp(), primary.rtcpFeedback());
        InetSocketAddress rtp = address(media.remoteAddress(), media.remotePort());
        InetSocketAddress rtcp = address(media.remoteRtcpAddress() != null ? media.remoteRtcpAddress()
                : media.remoteAddress(), media.remoteRtcpPort());
        NegotiatedFormat te = media.telephoneEvent();
        return new MediaStreamSpec(type, rtp, rtcp, media.rtcpMux(), send, receive,
                te == null ? null : te.remotePayloadType(), te == null ? null : te.localPayloadType(),
                media.canSend(), media.canReceive(), 20);
    }

    private static InetSocketAddress address(String host, int port) {
        if (host == null || port <= 0) {
            return null;
        }
        return new InetSocketAddress(host, port);
    }

    /** Human readable codec description, e.g. {@code PCMU/8000}. */
    public static String describe(MediaFormat f) {
        return f == null ? null : f.encodingName() + "/" + f.clockRate();
    }

    /** The local direction to offer for a hold state (RFC 3264 §8.4). */
    public static MediaDirection holdDirection(boolean localHold, boolean remoteHold) {
        if (localHold) {
            return remoteHold ? MediaDirection.INACTIVE : MediaDirection.SENDONLY;
        }
        return MediaDirection.SENDRECV;
    }
}
