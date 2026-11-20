package com.github.mikekwright.sip.softphone.web;

import java.nio.file.Path;

/**
 * Command line options of the web softphone server.
 *
 * @param httpBind   address the HTTP/WebSocket server binds to
 * @param httpPort   HTTP port
 * @param sipBind    bind address for SIP and RTP sockets created for browser sessions
 * @param sipHost    address advertised in SIP/SDP (null = detect)
 * @param rtpMin     RTP port range start
 * @param rtpMax     RTP port range end
 * @param webRoot    serve UI files from this directory instead of the classpath (development)
 * @param tlsCert    PEM certificate for HTTPS (null = plain HTTP)
 * @param tlsKey     PEM private key for HTTPS
 * @param stunServer default STUN server for browser sessions behind NAT (null = none)
 */
record WebSoftphoneOptions(String httpBind, int httpPort, String sipBind, String sipHost, int rtpMin, int rtpMax,
                           Path webRoot, Path tlsCert, Path tlsKey, String stunServer) {
}
