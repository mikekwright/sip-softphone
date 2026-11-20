package com.github.mikekwright.sip.softphone.rtp;

/**
 * Picture Loss Indication (PLI), RFC 4585 §6.3.1: a payload-specific feedback message (PT=206, FMT=1) with no
 * feedback control information, telling the media sender that the receiver lost an undefined amount of coded
 * video data and needs a decoder refresh (typically answered with a key frame).
 *
 * @param senderSsrc SSRC of the packet sender (the receiver of the media) (uint32)
 * @param mediaSsrc  SSRC of the media source the indication refers to (uint32)
 */
public record PictureLossIndication(long senderSsrc, long mediaSsrc) implements RtcpPacket {

    /** Feedback message type of PLI within PSFB. */
    public static final int FMT = 1;

    public PictureLossIndication {
        Uint32.check(senderSsrc, "senderSsrc");
        Uint32.check(mediaSsrc, "mediaSsrc");
    }

    @Override
    public int packetType() {
        return PT_PSFB;
    }
}
