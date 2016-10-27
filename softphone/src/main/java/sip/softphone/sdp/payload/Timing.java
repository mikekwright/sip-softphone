package sip.softphone.sdp.payload;

/*
   Timing ("t=")

      t=<start-time> <stop-time>

   The "t=" lines specify the start and stop times for a session.
   Multiple "t=" lines MAY be used if a session is active at multiple
   irregularly spaced times; each additional "t=" line specifies an
   additional period of time for which the session will be active.  If
   the session is active at regular times, an "r=" line (see below)
   should be used in addition to, and following, a "t=" line -- in which
   case the "t=" line specifies the start and stop times of the repeat
   sequence.

   The first and second sub-fields give the start and stop times,
   respectively, for the session.  These values are the decimal
   representation of Network Time Protocol (NTP) time values in seconds
   since 1900 [13].  To convert these values to UNIX time, subtract
   decimal 2208988800.

   NTP timestamps are elsewhere represented by 64-bit values, which wrap
   sometime in the year 2036.  Since SDP uses an arbitrary length
   decimal representation, this should not cause an issue (SDP
   timestamps MUST continue counting seconds since 1900, NTP will use
   the value modulo the 64-bit limit).

   If the <stop-time> is set to zero, then the session is not bounded,
   though it will not become active until after the <start-time>.  If
   the <start-time> is also zero, the session is regarded as permanent.

   User interfaces SHOULD strongly discourage the creation of unbounded
   and permanent sessions as they give no information about when the
   session is actually going to terminate, and so make scheduling
   difficult.

   The general assumption may be made, when displaying unbounded
   sessions that have not timed out to the user, that an unbounded
   session will only be active until half an hour from the current time
 */
public class Timing {

    private static final Long DEFAULT_START_TIME = 0L;
    private static final Long DEFAULT_STOP_TIME = 0L;

    private Long startTime;
    private Long stopTime;

    public Timing() {
        startTime = DEFAULT_START_TIME;
        stopTime = DEFAULT_STOP_TIME;
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getStopTime() {
        return stopTime;
    }

    public void setStopTime(Long stopTime) {
        this.stopTime = stopTime;
    }

    public String encode() {
        return String.format("t=%d %d", startTime, stopTime);
    }

    @Override
    public String toString() {
        return encode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Timing timing = (Timing) o;

        if (startTime != null ? !startTime.equals(timing.startTime) : timing.startTime != null) return false;
        return stopTime != null ? stopTime.equals(timing.stopTime) : timing.stopTime == null;

    }

    @Override
    public int hashCode() {
        int result = startTime != null ? startTime.hashCode() : 0;
        result = 31 * result + (stopTime != null ? stopTime.hashCode() : 0);
        return result;
    }
}

