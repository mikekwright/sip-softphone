package com.github.mikekwright.sip.softphone.nat;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StunClientTest {

    private EventLoopGroup group;
    private StunServer server;
    private Channel client;

    @BeforeEach
    void setUp() throws Exception {
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        server = StunServer.start(group, new InetSocketAddress("127.0.0.1", 0));
        client = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter()).bind("127.0.0.1", 0).sync().channel();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    @Test
    void discoversObservedSourceAddress() throws Exception {
        InetSocketAddress mapped = StunClient.discover(client, "127.0.0.1:" + server.localAddress().getPort())
                .get(5, TimeUnit.SECONDS);
        assertThat(mapped).isEqualTo(client.localAddress());
    }

    @Test
    void reportsNatMappingAndRetransmits() throws Exception {
        server.setMapping(a -> new InetSocketAddress("203.0.113.50", 61000));
        server.dropNext(2);
        InetSocketAddress mapped = StunClient.discover(client, server.localAddress(), Duration.ofMillis(50),
                Duration.ofSeconds(3)).get(5, TimeUnit.SECONDS);
        assertThat(mapped).isEqualTo(new InetSocketAddress("203.0.113.50", 61000));
    }

    @Test
    void timesOutWithoutServer() {
        InetSocketAddress gone = server.localAddress();
        server.close();
        assertThat(StunClient.discover(client, gone, Duration.ofMillis(20), Duration.ofMillis(300)))
                .failsWithin(5, TimeUnit.SECONDS);
    }

    @Test
    void socketsAnswerStunProbesToo() throws Exception {
        // any channel with the StunHandler answers Binding requests (useful for peers probing our ports)
        Channel other = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter()).bind("127.0.0.1", 0).sync().channel();
        StunHandler.install(other);
        InetSocketAddress mapped = StunClient.discover(client, (InetSocketAddress) other.localAddress(),
                Duration.ofMillis(50), Duration.ofSeconds(2)).get(5, TimeUnit.SECONDS);
        assertThat(mapped).isEqualTo(client.localAddress());
        other.close();
    }
}
