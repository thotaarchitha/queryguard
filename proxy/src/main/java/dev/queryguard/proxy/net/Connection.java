package dev.queryguard.proxy.net;

import dev.queryguard.common.masking.MaskStrategy;
import dev.queryguard.proxy.session.Session;
import io.netty.channel.Channel;
import java.util.ArrayDeque;
import java.util.List;

/**
 * State shared by the two halves of one proxied connection. Both handlers run on the same
 * event loop (the backend channel is registered on the client's loop), so plain fields are safe.
 */
final class Connection {

    final Session session;
    final Channel client;
    Channel backend;
    final ArrayDeque<Pending> pending = new ArrayDeque<>();

    /** Masks for the current simple-query result set (set by 'T', cleared by 'C'). */
    MaskStrategy[] queryMasks;
    List<String> queryMaskedColumns = List.of();

    Connection(Session session, Channel client) {
        this.session = session;
        this.client = client;
    }

    void releasePending() {
        pending.forEach(Pending::release);
        pending.clear();
    }
}
