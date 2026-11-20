package com.github.mikekwright.sip.softphone.sdp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Implements the SDP offer/answer model of RFC 3264 for RTP audio and video streams.
 *
 * <p>The negotiator is stateless: it builds offers ({@link #createOffer}), answers offers ({@link #createAnswer})
 * and evaluates answers to our offers ({@link #processAnswer}). Re-offers within a session (RFC 3264 §8) use the
 * same methods; the caller keeps the session id, increments the {@code o=} session version for every new
 * description and keeps the {@code m=} lines of the previous offer in place (a removed stream is offered with
 * port 0, see {@link SdpMediaConfig#withPort(int)}). Holding a call is a re-offer with
 * {@link MediaDirection#SENDONLY} (or {@link MediaDirection#INACTIVE}), RFC 3264 §8.4; {@link #isHold} detects
 * it on received descriptions.</p>
 *
 * <h2>Answer rules</h2>
 * <ul>
 *   <li>The answer has the same number and order of {@code m=} lines as the offer (RFC 3264 §6). Each offered
 *       line is served by the first unused local {@link SdpMediaConfig} of the same media type that has a non-zero
 *       port, a compatible protocol ({@link #supportsProtocol}) and at least one common format other than
 *       {@code telephone-event}. Otherwise, or if the offered port is 0, the line is rejected with port 0 and the
 *       first offered format (RFC 3264 §6).</li>
 *   <li>Formats match when {@link #formatsMatch} holds: same encoding name (case-insensitive), clock rate and
 *       channel count, plus equal H.264 {@code packetization-mode} (RFC 6184 §8.1; absent means 0). The answer
 *       lists the common formats in <em>local</em> preference order but reuses the <em>offerer's</em> payload type
 *       numbers, as RFC 3264 §6.1 recommends, and carries the local {@code fmtp}.</li>
 *   <li>{@code telephone-event} (RFC 4733) is answered when both sides support it at the same clock rate,
 *       preferring the clock rate of the primary codec.</li>
 *   <li>RTCP feedback ({@code a=rtcp-fb}, RFC 4585 §4.2) is answered with the values both sides listed for the
 *       format, written explicitly per payload type; the intersection is computed by
 *       {@link #commonRtcpFeedback}.</li>
 *   <li>Direction (RFC 3264 §6.1): {@code reverse(offered).intersect(local wish)}, e.g. offered {@code sendonly}
 *       is answered {@code recvonly}. An offered connection address of {@code 0.0.0.0} (RFC 2543 hold, RFC 3264
 *       §8.4) means the offerer does not want to receive, so the offered direction is first reduced to
 *       {@code sendonly}/{@code inactive}; a {@code sendrecv} offer with {@code c=0.0.0.0} is thus answered
 *       {@code recvonly}.</li>
 *   <li>{@code a=rtcp-mux} (RFC 5761 §5.1.1) is answered when offered and enabled locally.</li>
 *   <li>The answer copies the offer's {@code t=} lines (RFC 3264 §6).</li>
 * </ul>
 *
 * <p>The class is designed for extension: override the protected matching hooks to customise codec matching,
 * protocol compatibility or RTCP feedback negotiation.</p>
 */
public class SdpNegotiator {

    /** Creates a negotiator with the default matching rules. */
    public SdpNegotiator() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Offer
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Builds an offer (RFC 3264 §5): one {@code m=} line per configuration, each listing every configured format
     * with {@code a=rtpmap} (static ones included), {@code a=fmtp} and {@code a=rtcp-fb}, followed by
     * {@code a=ptime}, {@code a=rtcp-mux} when configured and an explicit direction attribute.
     *
     * @param username       {@code o=} username, e.g. {@code -}
     * @param address        local address for {@code o=} and the session-level {@code c=}
     * @param sessionId      {@code o=} session id, constant for the session
     * @param sessionVersion {@code o=} session version, incremented on every re-offer (RFC 3264 §8)
     * @param media          the streams to offer, in {@code m=} line order
     * @return the offer
     */
    public SessionDescription createOffer(String username, String address, long sessionId, long sessionVersion,
                                          List<SdpMediaConfig> media) {
        SessionDescription offer = newDescription(username, address, sessionId, sessionVersion);
        for (SdpMediaConfig config : media) {
            MediaDescription m = newMedia(config.mediaType(), config, address, config.protocol());
            config.formats().forEach(m::addFormat);
            addStreamAttributes(m, config, config.rtcpMux(), config.direction());
            offer.addMedia(m);
        }
        return offer;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Answer
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Answers an offer according to the rules in the class documentation (RFC 3264 §6).
     *
     * @param offer          the received offer
     * @param username       {@code o=} username of the answer
     * @param address        local address for {@code o=} and the session-level {@code c=}
     * @param sessionId      {@code o=} session id of the answer
     * @param sessionVersion {@code o=} session version of the answer
     * @param local          the local stream configurations available to serve the offered streams
     * @return the answer and the negotiated streams, one per offered {@code m=} line
     */
    public NegotiationResult createAnswer(SessionDescription offer, String username, String address,
                                          long sessionId, long sessionVersion, List<SdpMediaConfig> local) {
        SessionDescription answer = newDescription(username, address, sessionId, sessionVersion);
        answer.setTimings(offer.timings());
        List<NegotiatedMedia> negotiated = new ArrayList<>();
        boolean[] used = new boolean[local.size()];

        List<MediaDescription> offeredMedia = offer.media();
        for (int index = 0; index < offeredMedia.size(); index++) {
            MediaDescription offered = offeredMedia.get(index);
            List<RtpMap> offeredMaps = offered.rtpMaps();

            SdpMediaConfig config = null;
            List<Match> matches = List.of();
            if (!offered.isRejected()) {
                for (int i = 0; i < local.size(); i++) {
                    SdpMediaConfig candidate = local.get(i);
                    if (used[i] || candidate.port() == 0
                            || !candidate.mediaType().equalsIgnoreCase(offered.media())
                            || !supportsProtocol(offered.protocol(), candidate)) {
                        continue;
                    }
                    matches = match(candidate.formats(), offeredMaps, true);
                    if (!matches.isEmpty()) {
                        config = candidate;
                        used[i] = true;
                        break;
                    }
                }
            }

            if (config == null) {
                answer.addMedia(rejectedMedia(offered));
                negotiated.add(rejected(index, offer, offered));
                continue;
            }
            List<NegotiatedFormat> formats = new ArrayList<>();
            MediaDescription m = newMedia(offered.media(), config, address, offered.protocol());
            for (Match match : matches) {
                NegotiatedFormat format = answeredFormat(match);
                formats.add(format);
                m.addFormat(format.localRtpMap());
            }
            int primaryClockRate = formats.getFirst().clockRate();
            NegotiatedFormat telephoneEvent = matchTelephoneEvent(config.formats(), offeredMaps, true,
                    primaryClockRate).map(SdpNegotiator::answeredFormat).orElse(null);
            if (telephoneEvent != null) {
                m.addFormat(telephoneEvent.localRtpMap());
            }

            MediaDirection offeredDirection = remoteDirection(offer, offered);
            MediaDirection direction = offeredDirection.reverse().intersect(config.direction());
            boolean rtcpMux = offered.rtcpMux() && config.rtcpMux();
            addStreamAttributes(m, config, rtcpMux, direction);
            answer.addMedia(m);

            negotiated.add(accepted(index, offer, offered, rtcpMux, formats, telephoneEvent, direction));
        }
        return new NegotiationResult(answer, negotiated);
    }

    /** For the answerer, the offered payload type is used on both sides; fmtp is ours vs. theirs. */
    private static NegotiatedFormat answeredFormat(Match match) {
        RtpMap offered = match.remote();
        return new NegotiatedFormat(offered.encodingName(), offered.clockRate(), offered.channels(),
                offered.payloadType(), offered.payloadType(), match.local().fmtp(), offered.fmtp(),
                match.rtcpFeedback());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Processing an answer
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Evaluates the answer to one of our offers (RFC 3264 §7) and returns the negotiated streams from our point
     * of view. Formats follow the answerer's order, which expresses its preference (RFC 3264 §6.1); they are
     * matched against our offered formats by {@link #formatsMatch}, so an answerer that renumbered a payload
     * type is handled ({@link NegotiatedFormat#localPayloadType()} is ours, {@link
     * NegotiatedFormat#remotePayloadType()} theirs). An accepted stream without any common format is reported as
     * rejected.
     *
     * @param offer  the offer we sent
     * @param answer the answer we received
     * @return one entry per {@code m=} line
     * @throws SdpNegotiationException if the number of {@code m=} lines or their media types differ (RFC 3264 §6)
     */
    public List<NegotiatedMedia> processAnswer(SessionDescription offer, SessionDescription answer) {
        List<MediaDescription> offeredMedia = offer.media();
        List<MediaDescription> answeredMedia = answer.media();
        if (offeredMedia.size() != answeredMedia.size()) {
            throw new SdpNegotiationException("answer has " + answeredMedia.size() + " m= lines but the offer has "
                    + offeredMedia.size() + " (RFC 3264 §6)");
        }
        List<NegotiatedMedia> result = new ArrayList<>();
        for (int index = 0; index < offeredMedia.size(); index++) {
            MediaDescription offered = offeredMedia.get(index);
            MediaDescription answered = answeredMedia.get(index);
            if (!offered.media().equalsIgnoreCase(answered.media())) {
                throw new SdpNegotiationException("m= line " + index + " is '" + answered.media()
                        + "' in the answer but '" + offered.media() + "' in the offer (RFC 3264 §6)");
            }
            List<Match> matches = offered.isRejected() || answered.isRejected()
                    ? List.of()
                    : match(answered.rtpMaps(), offered.rtpMaps(), false);
            if (matches.isEmpty()) {
                result.add(rejected(index, answer, answered));
                continue;
            }
            List<NegotiatedFormat> formats = matches.stream().map(SdpNegotiator::offererFormat).toList();
            NegotiatedFormat telephoneEvent = matchTelephoneEvent(answered.rtpMaps(), offered.rtpMaps(), false,
                    formats.getFirst().clockRate()).map(SdpNegotiator::offererFormat).orElse(null);

            MediaDirection ours = offered.effectiveDirection(offer);
            MediaDirection theirs = remoteDirection(answer, answered);
            MediaDirection direction = ours.intersect(theirs.reverse());
            boolean rtcpMux = offered.rtcpMux() && answered.rtcpMux();
            result.add(accepted(index, answer, answered, rtcpMux, formats, telephoneEvent, direction));
        }
        return result;
    }

    /** For the offerer, our offered payload type is the local one and the answered one the remote. */
    private static NegotiatedFormat offererFormat(Match match) {
        RtpMap ours = match.local();
        RtpMap answered = match.remote();
        return new NegotiatedFormat(ours.encodingName(), ours.clockRate(), ours.channels(), ours.payloadType(),
                answered.payloadType(), ours.fmtp(), answered.fmtp(), match.rtcpFeedback());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hold detection
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Whether a received description puts the call on hold: it has at least one active stream and every active
     * (non-zero port) stream is on hold, see {@link #isHold(SessionDescription, MediaDescription)}.
     */
    public static boolean isHold(SessionDescription sdp) {
        List<MediaDescription> active = sdp.media().stream().filter(m -> !m.isRejected()).toList();
        return !active.isEmpty() && active.stream().allMatch(m -> isHold(sdp, m));
    }

    /**
     * Whether a stream of a received description is on hold, i.e. its author does not want to receive media:
     * direction {@code sendonly} or {@code inactive} (RFC 3264 §8.4, RFC 6337 §5) or the RFC 2543 style
     * connection address {@code 0.0.0.0}.
     */
    public static boolean isHold(SessionDescription sdp, MediaDescription media) {
        return !media.isRejected() && !remoteDirection(sdp, media).canReceive();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Extension hooks
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Whether a local and a remote format describe the same codec configuration. The default compares encoding
     * name (case-insensitive), clock rate and channels, and additionally requires an equal H.264
     * {@code packetization-mode} (RFC 6184 §8.2.2; absent means 0).
     */
    protected boolean formatsMatch(RtpMap local, RtpMap remote) {
        if (!local.sameEncoding(remote)) {
            return false;
        }
        if (local.is("H264")) {
            return packetizationMode(local) == packetizationMode(remote);
        }
        return true;
    }

    /**
     * Whether an offered transport protocol can be served by a local configuration. The default accepts an
     * identical protocol and treats {@code RTP/AVP} and {@code RTP/AVPF} as compatible (RFC 4585 §5 feedback
     * is then negotiated per format); the answer always uses the offered protocol.
     */
    protected boolean supportsProtocol(String offeredProtocol, SdpMediaConfig local) {
        if (offeredProtocol.equalsIgnoreCase(local.protocol())) {
            return true;
        }
        return isPlainRtp(offeredProtocol) && isPlainRtp(local.protocol());
    }

    /**
     * The RTCP feedback values to use for a matched format: by default those of {@code local} that also appear
     * in {@code remote} (compared case-insensitively with normalized whitespace), in local order.
     */
    protected List<String> commonRtcpFeedback(List<String> local, List<String> remote) {
        Set<String> remoteValues = new HashSet<>();
        remote.forEach(value -> remoteValues.add(normalize(value)));
        return local.stream().filter(value -> remoteValues.contains(normalize(value))).toList();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------------------

    /** A pair of matching formats (ours and the peer's) with the agreed RTCP feedback. */
    private record Match(RtpMap local, RtpMap remote, List<String> rtcpFeedback) {
    }

    private Match newMatch(RtpMap preferred, RtpMap candidate, boolean preferredIsLocal) {
        RtpMap local = preferredIsLocal ? preferred : candidate;
        RtpMap remote = preferredIsLocal ? candidate : preferred;
        return new Match(local, remote, commonRtcpFeedback(local.rtcpFeedback(), remote.rtcpFeedback()));
    }

    /**
     * Matches {@code preferred} (in its order) against {@code candidates}, excluding {@code telephone-event}.
     * Each candidate is used at most once; a candidate with the same payload type number is preferred.
     *
     * @param preferredIsLocal whether {@code preferred} are our formats (answerer) or the peer's (offerer)
     */
    private List<Match> match(List<RtpMap> preferred, List<RtpMap> candidates, boolean preferredIsLocal) {
        List<Match> matches = new ArrayList<>();
        Set<RtpMap> used = new HashSet<>();
        for (RtpMap wanted : preferred) {
            if (wanted.isTelephoneEvent()) {
                continue;
            }
            findMatch(wanted, candidates, used, preferredIsLocal).ifPresent(found -> {
                used.add(found);
                matches.add(newMatch(wanted, found, preferredIsLocal));
            });
        }
        return matches;
    }

    private Optional<RtpMap> findMatch(RtpMap wanted, Collection<RtpMap> candidates, Set<RtpMap> used,
                                       boolean wantedIsLocal) {
        RtpMap fallback = null;
        for (RtpMap candidate : candidates) {
            boolean matches = wantedIsLocal ? formatsMatch(wanted, candidate) : formatsMatch(candidate, wanted);
            if (used.contains(candidate) || candidate.isTelephoneEvent() || !matches) {
                continue;
            }
            if (candidate.payloadType() == wanted.payloadType()) {
                return Optional.of(candidate);
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return Optional.ofNullable(fallback);
    }

    /**
     * Matches a {@code telephone-event} format of {@code preferred} with one of {@code candidates} at the same
     * clock rate, preferring the clock rate of the primary codec (RFC 4733 §2.1).
     */
    private Optional<Match> matchTelephoneEvent(List<RtpMap> preferred, List<RtpMap> candidates,
                                                boolean preferredIsLocal, int primaryClockRate) {
        Match best = null;
        for (RtpMap wanted : preferred) {
            if (!wanted.isTelephoneEvent()) {
                continue;
            }
            for (RtpMap candidate : candidates) {
                if (candidate.isTelephoneEvent() && wanted.clockRate() == candidate.clockRate()) {
                    Match match = newMatch(wanted, candidate, preferredIsLocal);
                    if (wanted.clockRate() == primaryClockRate) {
                        return Optional.of(match);
                    }
                    if (best == null) {
                        best = match;
                    }
                    break;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The direction declared by the author of {@code sdp} for {@code media}, reduced to "not receiving" when the
     * connection address is unspecified or missing (RFC 3264 §8.4).
     */
    private static MediaDirection remoteDirection(SessionDescription sdp, MediaDescription media) {
        MediaDirection direction = media.effectiveDirection(sdp);
        Connection connection = sdp.connectionFor(media);
        if (connection == null || connection.isUnspecified()) {
            direction = direction.intersect(MediaDirection.SENDONLY);
        }
        return direction;
    }

    private static SessionDescription newDescription(String username, String address, long sessionId,
                                                     long sessionVersion) {
        Objects.requireNonNull(address, "address");
        return new SessionDescription()
                .setOrigin(Origin.of(username == null ? "-" : username, sessionId, sessionVersion, address))
                .setSessionName("-")
                .setConnection(Connection.ip(address));
    }

    private static MediaDescription newMedia(String mediaType, SdpMediaConfig config, String sessionAddress,
                                             String protocol) {
        MediaDescription m = new MediaDescription(mediaType, config.port(), protocol);
        if (config.address() != null && !config.address().equals(sessionAddress)) {
            m.setConnection(Connection.ip(config.address()));
        }
        return m;
    }

    private static void addStreamAttributes(MediaDescription m, SdpMediaConfig config, boolean rtcpMux,
                                            MediaDirection direction) {
        if (config.ptime() != null) {
            m.addAttribute("ptime", config.ptime().toString());
        }
        if (rtcpMux) {
            m.addAttribute(Attribute.property("rtcp-mux"));
        }
        m.setDirection(direction);
    }

    /** A rejected answer line: port 0, the offered protocol and the first offered format (RFC 3264 §6). */
    private static MediaDescription rejectedMedia(MediaDescription offered) {
        MediaDescription m = new MediaDescription(offered.media(), 0, offered.protocol());
        m.formats().add(offered.formats().isEmpty() ? "0" : offered.formats().getFirst());
        return m;
    }

    private static NegotiatedMedia rejected(int index, SessionDescription remoteSdp, MediaDescription remote) {
        Connection connection = remoteSdp.connectionFor(remote);
        String address = connection == null ? null : connection.host();
        return new NegotiatedMedia(index, remote.media(), true, address, remote.port(), address,
                remote.port() == 0 ? 0 : remote.port() + 1, false, List.of(), null, MediaDirection.INACTIVE);
    }

    /** Builds an accepted stream; transport details come from the remote party's description. */
    private static NegotiatedMedia accepted(int index, SessionDescription remoteSdp, MediaDescription remote,
                                            boolean rtcpMux, List<NegotiatedFormat> formats,
                                            NegotiatedFormat telephoneEvent, MediaDirection direction) {
        Connection connection = remoteSdp.connectionFor(remote);
        String address = connection == null ? null : connection.host();
        int port = remote.port();
        int rtcpPort = rtcpMux ? port : remote.rtcpPort().orElse(port + 1);
        String rtcpAddress = rtcpMux ? address : remote.rtcpConnection().map(Connection::host).orElse(address);
        return new NegotiatedMedia(index, remote.media(), false, address, port, rtcpAddress, rtcpPort, rtcpMux,
                formats, telephoneEvent, direction);
    }

    private static int packetizationMode(RtpMap map) {
        try {
            return map.fmtpParameter("packetization-mode").map(Integer::parseInt).orElse(0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isPlainRtp(String protocol) {
        return protocol.equalsIgnoreCase(MediaDescription.RTP_AVP)
                || protocol.equalsIgnoreCase(MediaDescription.RTP_AVPF);
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
