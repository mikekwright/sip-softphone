package sip.softphone

import java.io.Closeable

interface Dialog : Closeable {
    fun hangup()
    fun hold()
    fun unhold()
    fun transfer()
}