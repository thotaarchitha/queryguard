package dev.queryguard.proxy;

import dev.queryguard.proxy.net.FrontendHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Accepts client connections on the listen port and hands each one to a FrontendHandler. */
public final class ProxyServer {

    private static final Logger log = LoggerFactory.getLogger(ProxyServer.class);

    private final ProxyConfig config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public ProxyServer(ProxyConfig config) {
        this.config = config;
    }

    public void start() throws InterruptedException {
        // One thread is plenty for accepting; the workers do the real I/O.
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // Don't read from a client until its backend connection exists,
                // otherwise the first bytes have nowhere to go.
                .childOption(ChannelOption.AUTO_READ, false)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new FrontendHandler(config));
                    }
                });

        serverChannel = bootstrap.bind(config.listenPort()).sync().channel();
        log.info("QueryGuard starting on {} -> {}:{}", port(), config.pgHost(), config.pgPort());
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void stop() {
        log.info("QueryGuard shutting down");
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    public void awaitClose() throws InterruptedException {
        serverChannel.closeFuture().sync();
    }

    public static void main(String[] args) throws InterruptedException {
        ProxyServer server = new ProxyServer(ProxyConfig.fromEnv());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "qg-shutdown"));
        server.awaitClose();
    }
}
