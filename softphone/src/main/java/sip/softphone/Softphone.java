package sip.softphone;

import java.io.Closeable;

public interface Softphone extends Closeable {
    Dialog callNumber(String telephoneNumber);
}
