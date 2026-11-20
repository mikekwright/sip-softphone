package com.github.mikekwright.sip.softphone.sip.message;

import java.util.Map;

/**
 * SIP response status codes and their default reason phrases (RFC 3261 §21 plus common extensions).
 */
public final class SipStatus {

    public static final int TRYING = 100;
    public static final int RINGING = 180;
    public static final int CALL_IS_BEING_FORWARDED = 181;
    public static final int QUEUED = 182;
    public static final int SESSION_PROGRESS = 183;
    public static final int OK = 200;
    public static final int ACCEPTED = 202;
    public static final int MULTIPLE_CHOICES = 300;
    public static final int MOVED_PERMANENTLY = 301;
    public static final int MOVED_TEMPORARILY = 302;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405;
    public static final int NOT_ACCEPTABLE = 406;
    public static final int PROXY_AUTHENTICATION_REQUIRED = 407;
    public static final int REQUEST_TIMEOUT = 408;
    public static final int UNSUPPORTED_MEDIA_TYPE = 415;
    public static final int UNSUPPORTED_URI_SCHEME = 416;
    public static final int BAD_EXTENSION = 420;
    public static final int INTERVAL_TOO_BRIEF = 423;
    public static final int TEMPORARILY_UNAVAILABLE = 480;
    public static final int CALL_TRANSACTION_DOES_NOT_EXIST = 481;
    public static final int LOOP_DETECTED = 482;
    public static final int TOO_MANY_HOPS = 483;
    public static final int ADDRESS_INCOMPLETE = 484;
    public static final int BUSY_HERE = 486;
    public static final int REQUEST_TERMINATED = 487;
    public static final int NOT_ACCEPTABLE_HERE = 488;
    public static final int REQUEST_PENDING = 491;
    public static final int SERVER_INTERNAL_ERROR = 500;
    public static final int NOT_IMPLEMENTED = 501;
    public static final int SERVICE_UNAVAILABLE = 503;
    public static final int SERVER_TIMEOUT = 504;
    public static final int VERSION_NOT_SUPPORTED = 505;
    public static final int BUSY_EVERYWHERE = 600;
    public static final int DECLINE = 603;
    public static final int DOES_NOT_EXIST_ANYWHERE = 604;

    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(100, "Trying"),
            Map.entry(180, "Ringing"),
            Map.entry(181, "Call Is Being Forwarded"),
            Map.entry(182, "Queued"),
            Map.entry(183, "Session Progress"),
            Map.entry(200, "OK"),
            Map.entry(202, "Accepted"),
            Map.entry(300, "Multiple Choices"),
            Map.entry(301, "Moved Permanently"),
            Map.entry(302, "Moved Temporarily"),
            Map.entry(305, "Use Proxy"),
            Map.entry(380, "Alternative Service"),
            Map.entry(400, "Bad Request"),
            Map.entry(401, "Unauthorized"),
            Map.entry(402, "Payment Required"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"),
            Map.entry(406, "Not Acceptable"),
            Map.entry(407, "Proxy Authentication Required"),
            Map.entry(408, "Request Timeout"),
            Map.entry(410, "Gone"),
            Map.entry(413, "Request Entity Too Large"),
            Map.entry(414, "Request-URI Too Long"),
            Map.entry(415, "Unsupported Media Type"),
            Map.entry(416, "Unsupported URI Scheme"),
            Map.entry(420, "Bad Extension"),
            Map.entry(421, "Extension Required"),
            Map.entry(423, "Interval Too Brief"),
            Map.entry(480, "Temporarily Unavailable"),
            Map.entry(481, "Call/Transaction Does Not Exist"),
            Map.entry(482, "Loop Detected"),
            Map.entry(483, "Too Many Hops"),
            Map.entry(484, "Address Incomplete"),
            Map.entry(485, "Ambiguous"),
            Map.entry(486, "Busy Here"),
            Map.entry(487, "Request Terminated"),
            Map.entry(488, "Not Acceptable Here"),
            Map.entry(491, "Request Pending"),
            Map.entry(493, "Undecipherable"),
            Map.entry(500, "Server Internal Error"),
            Map.entry(501, "Not Implemented"),
            Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Server Time-out"),
            Map.entry(505, "Version Not Supported"),
            Map.entry(513, "Message Too Large"),
            Map.entry(600, "Busy Everywhere"),
            Map.entry(603, "Decline"),
            Map.entry(604, "Does Not Exist Anywhere"),
            Map.entry(606, "Not Acceptable"));

    private SipStatus() {
    }

    /** Default reason phrase for a status code (falls back to the class name, e.g. "Client Error"). */
    public static String reasonPhrase(int code) {
        String r = REASONS.get(code);
        if (r != null) {
            return r;
        }
        return switch (code / 100) {
            case 1 -> "Provisional";
            case 2 -> "Success";
            case 3 -> "Redirection";
            case 4 -> "Client Error";
            case 5 -> "Server Error";
            case 6 -> "Global Failure";
            default -> "Unknown";
        };
    }

    public static boolean isProvisional(int code) {
        return code >= 100 && code < 200;
    }

    public static boolean isSuccess(int code) {
        return code >= 200 && code < 300;
    }

    public static boolean isFinal(int code) {
        return code >= 200;
    }
}
