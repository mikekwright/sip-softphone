package sip.softphone;

import java.io.Closeable;

public interface Dialog extends Closeable {
    void hangup();
    void hold();
    void unhold();
    void transfer();
}
