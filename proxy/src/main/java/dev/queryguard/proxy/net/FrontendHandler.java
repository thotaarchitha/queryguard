package dev.queryguard.proxy.net;

import dev.queryguard.proxy.ProxyConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client side of one proxied connection. Opens the matching PostgreSQL connection and
 * forwards client bytes to it.
 *
 * <p>Both channels run with auto-read off. We only ask for more data when the other side
 * can take it, which is what keeps a fast sender from filling up memory on a slow receiver.
 */
public final class FrontendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(FrontendHandler.class);

    private final ProxyConfig config;
    private Channel backend;

    public FrontendHandler(ProxyConfig config) {
        this.config = config;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel client = ctx.channel();

        // Same event loop as the client: both channels are handled by one thread,
        // so there are no thread hops and no locking between them.
        Bootstrap bootstrap = new Bootstrap()
                .group(client.eventLoop())
                .channel(client.getClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new BackendHandler(client));

        ChannelFuture connect = bootstrap.connect(config.pgHost(), config.pgPort());
        backend = connect.channel();
        connect.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.debug("{} connected to backend {}", client.remoteAddress(), backend.remoteAddress());
                client.read();
                backend.read();
            } else {
                log.warn("Backend connect to {}:{} failed for {}: {}",
                        config.pgHost(), config.pgPort(), client.remoteAddress(), future.cause().toString());
                client.close();
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (backend == null || !backend.isActive()) {
            ReferenceCountUtil.release(msg);
            return;
        }
        backend.write(msg).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                ctx.channel().close();
            }
        });
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        if (backend == null || !backend.isActive()) {
            return;
        }
        backend.flush();
        // If the backend's outbound buffer is full, stop here. BackendHandler
        // resumes our reads once it drains (channelWritabilityChanged).
        if (backend.isWritable()) {
            ctx.read();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        // The client caught up, so the backend may send more.
        if (ctx.channel().isWritable() && backend != null && backend.isActive()) {
            backend.read();
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Channels.closeOnFlush(backend);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Client connection error from {}: {}", ctx.channel().remoteAddress(), cause.toString());
        Channels.closeOnFlush(ctx.channel());
    }
}
