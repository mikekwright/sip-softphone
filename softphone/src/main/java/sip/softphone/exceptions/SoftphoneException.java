package sip.softphone.exceptions;

public class SoftphoneException extends Exception {
    public SoftphoneException(String message, Exception baseException) {
        super(message, baseException);
    }
}
