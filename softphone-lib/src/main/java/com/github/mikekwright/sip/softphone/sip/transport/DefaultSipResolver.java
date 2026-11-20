package com.github.mikekwright.sip.softphone.sip.transport;

import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * A pragmatic RFC 3263 resolver:
 * <ol>
 *   <li>Transport: the URI {@code transport} parameter, else TLS for {@code sips:}, else the default transport
 *       (RFC 3263 §4.1).</li>
 *   <li>If the host is a numeric IP address or the URI has an explicit port, use host:port directly (§4.2).</li>
 *   <li>Otherwise look up DNS SRV records ({@code _sip._udp}, {@code _sip._tcp}, {@code _sips._tcp}) ordered by
 *       priority and weight, falling back to A/AAAA records on the default port.</li>
 * </ol>
 * NAPTR records are not consulted. DNS lookups are blocking and therefore run on virtual threads.
 */
public final class DefaultSipResolver implements SipResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultSipResolver.class);
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final Executor executor;
    private final boolean useSrv;

    public DefaultSipResolver() {
        this(Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    public DefaultSipResolver(Executor executor, boolean useSrv) {
        this.executor = executor;
        this.useSrv = useSrv;
    }

    @Override
    public CompletableFuture<List<TransportAddress>> resolve(SipUri uri, TransportType defaultTransport) {
        TransportType transport = selectTransport(uri, defaultTransport);
        String host = uri.hostAddress();
        if (isNumeric(host)) {
            // No DNS involved: resolve synchronously to avoid a thread hop.
            return CompletableFuture.completedFuture(List.of(new TransportAddress(transport,
                    new InetSocketAddress(host, uri.hasPort() ? uri.port() : transport.defaultPort()))));
        }
        return CompletableFuture.supplyAsync(() -> resolveBlocking(uri, host, transport), executor);
    }

    /** RFC 3263 §4.1 transport selection. */
    public static TransportType selectTransport(SipUri uri, TransportType defaultTransport) {
        TransportType fromParam = TransportType.fromToken(uri.transport());
        if (fromParam != null) {
            return fromParam;
        }
        if (uri.isSecure()) {
            return TransportType.TLS;
        }
        return defaultTransport;
    }

    private List<TransportAddress> resolveBlocking(SipUri uri, String host, TransportType transport) {
        List<TransportAddress> result = new ArrayList<>();
        if (!uri.hasPort() && useSrv) {
            String service = (transport == TransportType.TLS ? "_sips._tcp." : transport == TransportType.TCP
                    ? "_sip._tcp." : "_sip._udp.") + host;
            for (SrvRecord srv : lookupSrv(service)) {
                try {
                    for (InetAddress addr : InetAddress.getAllByName(srv.target)) {
                        result.add(new TransportAddress(transport, new InetSocketAddress(addr, srv.port)));
                    }
                } catch (UnknownHostException e) {
                    log.debug("SRV target {} did not resolve", srv.target);
                }
            }
        }
        if (result.isEmpty()) {
            int port = uri.hasPort() ? uri.port() : transport.defaultPort();
            try {
                for (InetAddress addr : InetAddress.getAllByName(host)) {
                    result.add(new TransportAddress(transport, new InetSocketAddress(addr, port)));
                }
            } catch (UnknownHostException e) {
                throw new IllegalStateException("cannot resolve SIP host " + host, e);
            }
        }
        return result;
    }

    private record SrvRecord(int priority, int weight, int port, String target) {
    }

    private static List<SrvRecord> lookupSrv(String name) {
        List<SrvRecord> records = new ArrayList<>();
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "1000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            InitialDirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(name, new String[]{"SRV"});
            Attribute srv = attrs.get("SRV");
            if (srv != null) {
                for (int i = 0; i < srv.size(); i++) {
                    String[] parts = srv.get(i).toString().trim().split("\\s+");
                    if (parts.length == 4) {
                        String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1)
                                : parts[3];
                        records.add(new SrvRecord(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]), target));
                    }
                }
            }
            ctx.close();
        } catch (NamingException | RuntimeException e) {
            log.debug("SRV lookup for {} failed: {}", name, e.getMessage());
        }
        // RFC 2782: lower priority first; within a priority prefer higher weight (deterministic simplification)
        records.sort(Comparator.comparingInt(SrvRecord::priority).thenComparing(Comparator.comparingInt(
                SrvRecord::weight).reversed()));
        return records;
    }

    static boolean isNumeric(String host) {
        return IPV4.matcher(host).matches() || host.indexOf(':') >= 0;
    }
}
