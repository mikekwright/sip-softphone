package com.github.mikekwright.sip.softphone.sdp;

/**
 * Thrown by {@link SdpParser} when a session description is syntactically invalid. The message includes the
 * (1-based) line number of the offending line when known.
 */
public class SdpParseException extends RuntimeException {

    private final int lineNumber;

    /**
     * @param message    description of the problem
     * @param lineNumber 1-based line number, or 0 when the problem is not tied to a line
     */
    public SdpParseException(String message, int lineNumber) {
        super(lineNumber > 0 ? "line " + lineNumber + ": " + message : message);
        this.lineNumber = lineNumber;
    }

    /** The 1-based line number of the offending line, or 0 when not tied to a line. */
    public int lineNumber() {
        return lineNumber;
    }
}
