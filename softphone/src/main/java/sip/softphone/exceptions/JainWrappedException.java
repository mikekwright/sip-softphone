package sip.softphone.exceptions;

public class JainWrappedException extends SoftphoneException {
    public JainWrappedException(String message, Exception baseException) {
        super(message, baseException);
    }
}
