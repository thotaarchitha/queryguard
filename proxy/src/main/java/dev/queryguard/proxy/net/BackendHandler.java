package dev.queryguard.proxy.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** PostgreSQL side of one proxied connection. Sends everything the database says back to the client. */
public final class BackendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(BackendHandler.class);

    private final Channel client;

    public BackendHandler(Channel client) {
        this.client = client;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        client.write(msg).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                ctx.channel().close();
            }
        });
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        client.flush();
        // Same rule as the frontend: only read more rows when the client can keep up.
        if (client.isWritable()) {
            ctx.read();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        // PostgreSQL drained our queue, so the client may send more.
        if (ctx.channel().isWritable() && client.isActive()) {
            client.read();
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Database went away (crash, restart, terminate). Close the client
        // rather than leaving it waiting on a reply that will never come.
        Channels.closeOnFlush(client);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Backend connection error for {}: {}", client.remoteAddress(), cause.toString());
        Channels.closeOnFlush(ctx.channel());
    }
}
