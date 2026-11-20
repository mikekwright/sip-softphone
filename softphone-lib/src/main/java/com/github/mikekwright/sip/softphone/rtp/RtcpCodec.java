package com.github.mikekwright.sip.softphone.rtp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encoder/decoder for compound RTCP packets (RFC 3550 §6.1) and the individual packet types modelled by
 * {@link RtcpPacket}.
 *
 * <p>Length fields are computed on encoding (in 32-bit words minus one, RFC 3550 §6.4.1) and SDES chunks and BYE
 * reasons are padded to 32-bit boundaries as RFC 3550 §6.5 and §6.6 require. On decoding, the P bit is honoured
 * on any packet of the compound (RFC 3550 only allows it on the last one, but being lenient costs nothing).</p>
 *
 * <p>This class is stateless and thread-safe. Methods never release the buffers passed to them.</p>
 */
public final class RtcpCodec {

    private static final int HEADER_SIZE = 4;

    private RtcpCodec() {
    }

    // ===================================================================================== decoding

    /**
     * Decodes a compound RTCP packet from all readable bytes of {@code buf} (which are consumed).
     *
     * @throws MalformedPacketException when a header has a version other than 2, a length exceeds the buffer,
     *                                  padding is inconsistent or a known packet type has a truncated body
     */
    public static List<RtcpPacket> decode(ByteBuf buf) {
        List<RtcpPacket> packets = new ArrayList<>(3);
        if (!buf.isReadable()) {
            throw new MalformedPacketException("empty RTCP packet");
        }
        while (buf.isReadable()) {
            if (buf.readableBytes() < HEADER_SIZE) {
                throw new MalformedPacketException("truncated RTCP header");
            }
            int b0 = buf.readUnsignedByte();
            if (b0 >>> 6 != 2) {
                throw new MalformedPacketException("unsupported RTCP version " + (b0 >>> 6));
            }
            boolean padding = (b0 & 0x20) != 0;
            int count = b0 & 0x1F;
            int packetType = buf.readUnsignedByte();
            int bodyLength = buf.readUnsignedShort() * 4;
            if (buf.readableBytes() < bodyLength) {
                throw new MalformedPacketException("RTCP length " + bodyLength + " exceeds remaining "
                        + buf.readableBytes() + " bytes");
            }
            int paddingLength = 0;
            if (padding) {
                if (bodyLength == 0) {
                    throw new MalformedPacketException("RTCP padding bit set on empty packet");
                }
                paddingLength = buf.getUnsignedByte(buf.readerIndex() + bodyLength - 1);
                if (paddingLength == 0 || paddingLength > bodyLength) {
                    throw new MalformedPacketException("invalid RTCP padding length " + paddingLength);
                }
            }
            ByteBuf body = buf.readSlice(bodyLength).slice(0, bodyLength - paddingLength);
            try {
                packets.add(decodePacket(packetType, count, body));
            } catch (MalformedPacketException e) {
                throw e;
            } catch (IndexOutOfBoundsException e) {
                throw new MalformedPacketException("truncated RTCP packet of type " + packetType);
            } catch (IllegalArgumentException e) {
                // e.g. SDES text that is no longer valid after UTF-8 replacement
                throw new MalformedPacketException("invalid RTCP packet of type " + packetType + ": "
                        + e.getMessage());
            }
        }
        return packets;
    }

    private static RtcpPacket decodePacket(int packetType, int count, ByteBuf body) {
        return switch (packetType) {
            case RtcpPacket.PT_SR -> decodeSenderReport(count, body);
            case RtcpPacket.PT_RR -> new ReceiverReport(body.readUnsignedInt(), decodeReportBlocks(count, body));
            case RtcpPacket.PT_SDES -> decodeSourceDescription(count, body);
            case RtcpPacket.PT_BYE -> decodeBye(count, body);
            case RtcpPacket.PT_RTPFB -> count == GenericNack.FMT
                    ? decodeNack(body) : unknown(packetType, count, body);
            case RtcpPacket.PT_PSFB -> switch (count) {
                case PictureLossIndication.FMT ->
                        new PictureLossIndication(body.readUnsignedInt(), body.readUnsignedInt());
                case FullIntraRequest.FMT -> decodeFir(body);
                default -> unknown(packetType, count, body);
            };
            default -> unknown(packetType, count, body);
        };
    }

    private static UnknownRtcpPacket unknown(int packetType, int count, ByteBuf body) {
        byte[] bytes = new byte[body.readableBytes()];
        body.readBytes(bytes);
        return new UnknownRtcpPacket(packetType, count, bytes);
    }

    private static SenderReport decodeSenderReport(int count, ByteBuf body) {
        long ssrc = body.readUnsignedInt();
        long ntp = body.readLong();
        long rtpTimestamp = body.readUnsignedInt();
        long packets = body.readUnsignedInt();
        long octets = body.readUnsignedInt();
        // Profile-specific extensions after the report blocks are ignored (RFC 3550 §6.4.1).
        return new SenderReport(ssrc, ntp, rtpTimestamp, packets, octets, decodeReportBlocks(count, body));
    }

    private static List<ReportBlock> decodeReportBlocks(int count, ByteBuf body) {
        if (body.readableBytes() < count * ReportBlock.SIZE) {
            throw new MalformedPacketException("RTCP report truncated: " + count + " blocks announced");
        }
        List<ReportBlock> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long ssrc = body.readUnsignedInt();
            int fraction = body.readUnsignedByte();
            int cumulative = body.readMedium(); // signed 24-bit
            blocks.add(new ReportBlock(ssrc, fraction, cumulative, body.readUnsignedInt(), body.readUnsignedInt(),
                    body.readUnsignedInt(), body.readUnsignedInt()));
        }
        return blocks;
    }

    private static SourceDescription decodeSourceDescription(int count, ByteBuf body) {
        int bodyStart = body.readerIndex();
        List<SourceDescription.Chunk> chunks = new ArrayList<>(count);
        for (int c = 0; c < count; c++) {
            long ssrc = body.readUnsignedInt();
            List<SourceDescription.Item> items = new ArrayList<>(2);
            while (body.isReadable()) {
                int type = body.readUnsignedByte();
                if (type == 0) {
                    // Null item terminates the list; skip padding up to the next 32-bit boundary.
                    int misalignment = (body.readerIndex() - bodyStart) % 4;
                    if (misalignment != 0) {
                        body.skipBytes(Math.min(4 - misalignment, body.readableBytes()));
                    }
                    break;
                }
                int length = body.readUnsignedByte();
                String value = body.readCharSequence(length, StandardCharsets.UTF_8).toString();
                items.add(new SourceDescription.Item(type, value));
            }
            chunks.add(new SourceDescription.Chunk(ssrc, items));
        }
        return new SourceDescription(chunks);
    }

    private static Bye decodeBye(int count, ByteBuf body) {
        List<Long> ssrcs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ssrcs.add(body.readUnsignedInt());
        }
        String reason = null;
        if (body.isReadable()) {
            int length = body.readUnsignedByte();
            if (length > 0) {
                reason = body.readCharSequence(length, StandardCharsets.UTF_8).toString();
            }
        }
        return new Bye(ssrcs, reason);
    }

    private static GenericNack decodeNack(ByteBuf body) {
        long sender = body.readUnsignedInt();
        long media = body.readUnsignedInt();
        List<Integer> lost = new ArrayList<>();
        while (body.readableBytes() >= 4) {
            int pid = body.readUnsignedShort();
            int blp = body.readUnsignedShort();
            lost.add(pid);
            for (int bit = 0; bit < 16; bit++) {
                if ((blp & (1 << bit)) != 0) {
                    lost.add((pid + bit + 1) & 0xFFFF);
                }
            }
        }
        return new GenericNack(sender, media, lost);
    }

    private static FullIntraRequest decodeFir(ByteBuf body) {
        long sender = body.readUnsignedInt();
        body.skipBytes(4); // media source SSRC, unused for FIR (RFC 5104 §4.3.1.2)
        List<FullIntraRequest.Entry> entries = new ArrayList<>();
        while (body.readableBytes() >= 8) {
            long ssrc = body.readUnsignedInt();
            int seq = body.readUnsignedByte();
            body.skipBytes(3);
            entries.add(new FullIntraRequest.Entry(ssrc, seq));
        }
        return new FullIntraRequest(sender, entries);
    }

    // ===================================================================================== encoding

    /** Encodes {@code packets} as one compound packet into a new buffer; the caller must release it. */
    public static ByteBuf encode(List<? extends RtcpPacket> packets, ByteBufAllocator allocator) {
        ByteBuf buf = allocator.buffer(128);
        try {
            encode(packets, buf);
            return buf;
        } catch (RuntimeException e) {
            buf.release();
            throw e;
        }
    }

    /** Appends {@code packets} as one compound packet to {@code out}. */
    public static void encode(List<? extends RtcpPacket> packets, ByteBuf out) {
        for (RtcpPacket packet : packets) {
            encode(packet, out);
        }
    }

    /** Appends a single RTCP packet (header plus body) to {@code out}. */
    public static void encode(RtcpPacket packet, ByteBuf out) {
        int start = out.writerIndex();
        out.writeInt(0); // header placeholder
        int count = switch (packet) {
            case SenderReport sr -> {
                out.writeInt((int) sr.senderSsrc());
                out.writeLong(sr.ntpTimestamp());
                out.writeInt((int) sr.rtpTimestamp());
                out.writeInt((int) sr.packetCount());
                out.writeInt((int) sr.octetCount());
                writeReportBlocks(sr.reportBlocks(), out);
                yield sr.reportBlocks().size();
            }
            case ReceiverReport rr -> {
                out.writeInt((int) rr.senderSsrc());
                writeReportBlocks(rr.reportBlocks(), out);
                yield rr.reportBlocks().size();
            }
            case SourceDescription sdes -> {
                for (SourceDescription.Chunk chunk : sdes.chunks()) {
                    out.writeInt((int) chunk.ssrc());
                    int itemsLength = 0;
                    for (SourceDescription.Item item : chunk.items()) {
                        byte[] text = item.value().getBytes(StandardCharsets.UTF_8);
                        out.writeByte(item.type());
                        out.writeByte(text.length);
                        out.writeBytes(text);
                        itemsLength += 2 + text.length;
                    }
                    // At least one null octet terminating the list, then pad to a 32-bit boundary.
                    out.writeZero(4 - (itemsLength % 4));
                }
                yield sdes.chunks().size();
            }
            case Bye bye -> {
                for (long ssrc : bye.ssrcs()) {
                    out.writeInt((int) ssrc);
                }
                if (bye.reason() != null) {
                    byte[] text = bye.reason().getBytes(StandardCharsets.UTF_8);
                    out.writeByte(text.length);
                    out.writeBytes(text);
                    out.writeZero((4 - (1 + text.length) % 4) % 4);
                }
                yield bye.ssrcs().size();
            }
            case PictureLossIndication pli -> {
                out.writeInt((int) pli.senderSsrc());
                out.writeInt((int) pli.mediaSsrc());
                yield PictureLossIndication.FMT;
            }
            case FullIntraRequest fir -> {
                out.writeInt((int) fir.senderSsrc());
                out.writeInt(0);
                for (FullIntraRequest.Entry entry : fir.entries()) {
                    out.writeInt((int) entry.ssrc());
                    out.writeByte(entry.sequenceNumber());
                    out.writeMedium(0);
                }
                yield FullIntraRequest.FMT;
            }
            case GenericNack nack -> {
                out.writeInt((int) nack.senderSsrc());
                out.writeInt((int) nack.mediaSsrc());
                writeNackEntries(nack.lostSequenceNumbers(), out);
                yield GenericNack.FMT;
            }
            case UnknownRtcpPacket unknown -> {
                out.writeBytes(unknown.body());
                yield unknown.count();
            }
        };
        int bodyLength = out.writerIndex() - start - HEADER_SIZE;
        int padding = (4 - bodyLength % 4) % 4;
        if (padding > 0) {
            out.writeZero(padding - 1);
            out.writeByte(padding);
        }
        int words = (out.writerIndex() - start) / 4 - 1;
        if (words > 0xFFFF) {
            throw new IllegalArgumentException("RTCP packet too large");
        }
        out.setByte(start, 0x80 | (padding > 0 ? 0x20 : 0) | count);
        out.setByte(start + 1, packet.packetType());
        out.setShort(start + 2, words);
    }

    private static void writeReportBlocks(List<ReportBlock> blocks, ByteBuf out) {
        for (ReportBlock block : blocks) {
            out.writeInt((int) block.ssrc());
            out.writeByte(block.fractionLost());
            out.writeMedium(block.cumulativeLost());
            out.writeInt((int) block.extendedHighestSequence());
            out.writeInt((int) block.jitter());
            out.writeInt((int) block.lastSenderReport());
            out.writeInt((int) block.delaySinceLastSenderReport());
        }
    }

    private static void writeNackEntries(List<Integer> lost, ByteBuf out) {
        int pid = -1;
        int blp = 0;
        for (int seq : lost) {
            if (pid >= 0) {
                int diff = (seq - pid) & 0xFFFF;
                if (diff >= 1 && diff <= 16) {
                    blp |= 1 << (diff - 1);
                    continue;
                }
                out.writeShort(pid);
                out.writeShort(blp);
            }
            pid = seq;
            blp = 0;
        }
        if (pid >= 0) {
            out.writeShort(pid);
            out.writeShort(blp);
        }
    }
}
