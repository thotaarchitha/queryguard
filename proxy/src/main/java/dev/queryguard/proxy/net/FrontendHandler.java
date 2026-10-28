package dev.queryguard.proxy.net;

import dev.queryguard.common.rules.Decision;
import dev.queryguard.proxy.config.ActivePolicy;
import dev.queryguard.proxy.events.QueryEvent;
import dev.queryguard.proxy.protocol.FrontendMessage;
import dev.queryguard.proxy.protocol.FrontendMessage.Bind;
import dev.queryguard.proxy.protocol.FrontendMessage.CancelRequest;
import dev.queryguard.proxy.protocol.FrontendMessage.Close;
import dev.queryguard.proxy.protocol.FrontendMessage.Describe;
import dev.queryguard.proxy.protocol.FrontendMessage.Execute;
import dev.queryguard.proxy.protocol.FrontendMessage.GssEncRequest;
import dev.queryguard.proxy.protocol.FrontendMessage.Other;
import dev.queryguard.proxy.protocol.FrontendMessage.Parse;
import dev.queryguard.proxy.protocol.FrontendMessage.Query;
import dev.queryguard.proxy.protocol.FrontendMessage.SslRequest;
import dev.queryguard.proxy.protocol.FrontendMessage.StartupMessage;
import dev.queryguard.proxy.protocol.FrontendMessage.Sync;
import dev.queryguard.proxy.protocol.FrontendMessage.Terminate;
import dev.queryguard.proxy.protocol.PgBackendDecoder;
import dev.queryguard.proxy.protocol.PgMessages;
import dev.queryguard.proxy.rules.Decider.Verdict;
import dev.queryguard.proxy.session.Session;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client side of one connection.
 *
 * <p>Messages are handled strictly in arrival order. When a Q or Parse needs a decision, the
 * decision runs off-loop and everything behind it waits in {@code inbox}; we also stop reading
 * from the socket until it's back. That keeps pipelined clients (JDBC sends Parse/Bind/Execute/Sync
 * in one packet) correct: nothing overtakes a query that's still being judged.
 *
 * <p>Blocking a query: we don't answer for Postgres. We replace the SQL with something that fails
 * to parse, let Postgres reject it, and swap Postgres' ErrorResponse for ours on the way back.
 * That sounds roundabout, but it means Postgres does all the protocol bookkeeping: it aborts an
 * open transaction, skips the rest of the extended-protocol batch until Sync, and sends the
 * right ReadyForQuery status. Faking those states by hand is where proxies usually get it wrong.
 */
public final class FrontendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(FrontendHandler.class);

    /** Guaranteed syntax error, cheap for Postgres to reject. Shows up in the server log, which is a nice audit trail. */
    static final String BLOCKED_SQL = "QUERYGUARD_BLOCKED /* rejected by QueryGuard */";

    private record Rejection(String sqlstate, String message) {
    }

    private final ProxyServices services;
    private final ArrayDeque<FrontendMessage> inbox = new ArrayDeque<>();
    private Connection conn;
    private Rejection rejection;
    private boolean deciding;
    private boolean counted;

    public FrontendHandler(ProxyServices services) {
        this.services = services;
    }

    // ---- lifecycle -----------------------------------------------------------------------

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel client = ctx.channel();
        services.clients().add(client);
        String ip = client.remoteAddress() instanceof InetSocketAddress a ? a.getAddress().getHostAddress() : "?";
        conn = new Connection(new Session(ip), client);

        counted = true;
        int active = services.metrics().activeConnections().incrementAndGet();
        if (active > services.config().maxConnections()) {
            reject(ctx, "53300", "sorry, too many clients already (QueryGuard limit is " + services.config().maxConnections() + ")");
            return;
        }
        // Fail fast while Postgres is known to be down, instead of piling up connects
        // that each wait the full timeout.
        if (!services.backendBreaker().tryAcquirePermission()) {
            reject(ctx, "08006", "QueryGuard: the database is unreachable right now (circuit open). Try again in a few seconds.");
            return;
        }

        long t0 = System.nanoTime();
        Bootstrap b = new Bootstrap()
                .group(client.eventLoop())
                .channel(client.getClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, services.config().connectTimeoutMillis())
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new PgBackendDecoder(), new BackendHandler(conn, services));
                    }
                });
        ChannelFuture connect = b.connect(services.config().pgHost(), services.config().pgPort());
        conn.backend = connect.channel();
        connect.addListener((ChannelFutureListener) f -> {
            long took = System.nanoTime() - t0;
            if (f.isSuccess()) {
                services.backendBreaker().onSuccess(took, TimeUnit.NANOSECONDS);
                client.read();
                conn.backend.read();
            } else {
                services.backendBreaker().onError(took, TimeUnit.NANOSECONDS, f.cause());
                services.metrics().backendError();
                log.warn("Backend connect to {}:{} failed: {}", services.config().pgHost(), services.config().pgPort(), f.cause().toString());
                conn.backend = null;
                reject(ctx, "08006", "QueryGuard: couldn't reach the database (" + f.cause().getMessage() + ")");
            }
        });
    }

    /** Remember why, keep reading so we can answer the startup message with a proper FATAL. */
    private void reject(ChannelHandlerContext ctx, String sqlstate, String message) {
        rejection = new Rejection(sqlstate, message);
        services.metrics().rejected();
        ctx.read();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (counted) {
            services.metrics().activeConnections().decrementAndGet();
            counted = false;
        }
        inbox.forEach(m -> ReferenceCountUtil.release(m.raw()));
        inbox.clear();
        if (conn != null) {
            closeOnFlush(conn.backend);
            conn.releasePending();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            log.info("Closing idle connection {}", conn.session);
            ctx.writeAndFlush(PgMessages.errorResponse(ctx.alloc(), "FATAL", "57P05",
                            "terminating connection due to idle timeout (QueryGuard)", null, null))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Client connection error ({}): {}", conn == null ? "?" : conn.session, cause.toString());
        closeOnFlush(ctx.channel());
    }

    // ---- reading -------------------------------------------------------------------------

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        inbox.add((FrontendMessage) msg);
        drain(ctx);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        if (conn.backend != null) {
            conn.backend.flush();
        }
        maybeRead(ctx);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable() && conn.backend != null && conn.backend.isActive()) {
            conn.backend.read();
        }
        ctx.fireChannelWritabilityChanged();
    }

    /** Ask for more client bytes only when nothing is queued and Postgres can take more. */
    private void maybeRead(ChannelHandlerContext ctx) {
        if (!deciding && inbox.isEmpty() && (conn.backend == null || conn.backend.isWritable())) {
            ctx.read();
        }
    }

    private void drain(ChannelHandlerContext ctx) {
        FrontendMessage m;
        while (!deciding && ctx.channel().isActive() && (m = inbox.poll()) != null) {
            handle(ctx, m);
        }
    }

    private void handle(ChannelHandlerContext ctx, FrontendMessage m) {
        switch (m) {
            case SslRequest s -> refuseEncryption(ctx, s.raw());
            case GssEncRequest g -> refuseEncryption(ctx, g.raw());
            case CancelRequest c -> forwardCancel(ctx, c.raw());
            case StartupMessage s -> onStartup(ctx, s);
            case Query q -> onQuery(ctx, q);
            case Parse p -> onParse(ctx, p);
            case Bind b -> {
                conn.session.bind(b.portal(), b.statement());
                send(Pending.Kind.BIND, null, null, null, b.raw());
            }
            case Describe d -> {
                String stmt = d.kind() == 'S' ? d.name() : conn.session.statementForPortal(d.name());
                send(Pending.Kind.DESCRIBE, stmt, null, null, d.raw());
            }
            case Execute e -> onExecute(e);
            case Close c -> {
                if (c.kind() == 'S') {
                    conn.session.closeStatement(c.name());
                } else {
                    conn.session.closePortal(c.name());
                }
                send(Pending.Kind.CLOSE, null, null, null, c.raw());
            }
            case Sync s -> send(Pending.Kind.SYNC, null, null, null, s.raw());
            case Terminate t -> {
                forward(t.raw());
                closeOnFlush(conn.backend);
                ctx.close();
            }
            case Other o -> {
                if (o.type() == 'F') {
                    send(Pending.Kind.FUNCTION, null, null, null, o.raw());
                } else {
                    forward(o.raw()); // password/SASL, Flush, CopyData/Done/Fail
                }
            }
        }
    }

    // ---- startup -------------------------------------------------------------------------

    private void refuseEncryption(ChannelHandlerContext ctx, ByteBuf raw) {
        // No TLS termination yet: say 'N' and the client continues in plain text
        // (or gives up, if it was configured with sslmode=require).
        raw.release();
        ctx.writeAndFlush(PgMessages.refuseEncryption(ctx.alloc()));
    }

    /** Cancel goes to Postgres on a fresh connection, exactly as the client sent it. The key Postgres gave the client is already the right one. */
    private void forwardCancel(ChannelHandlerContext ctx, ByteBuf raw) {
        if (conn.backend != null) {
            conn.backend.close();
        }
        new Bootstrap()
                .group(ctx.channel().eventLoop())
                .channel(ctx.channel().getClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, services.config().connectTimeoutMillis())
                .handler(new ChannelInboundHandlerAdapter())
                .connect(services.config().pgHost(), services.config().pgPort())
                .addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        f.channel().writeAndFlush(raw).addListener(ChannelFutureListener.CLOSE);
                    } else {
                        raw.release();
                    }
                });
        ctx.close();
    }

    private void onStartup(ChannelHandlerContext ctx, StartupMessage s) {
        if (rejection != null) {
            s.raw().release();
            ctx.writeAndFlush(PgMessages.errorResponse(ctx.alloc(), "FATAL", rejection.sqlstate(), rejection.message(), null, null))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ActivePolicy policy = services.policies().current();
        String user = s.params().get("user");
        conn.session.start(s.params(), policy.config().roleFor(user));
        log.info("connect {} app={}", conn.session, conn.session.application());
        services.decider().prefetch(conn.session.database(), conn.session.role());
        send(Pending.Kind.STARTUP, null, null, null, s.raw());
    }

    // ---- queries -------------------------------------------------------------------------

    private void onQuery(ChannelHandlerContext ctx, Query q) {
        if (q.sql().isBlank()) {
            send(Pending.Kind.QUERY, null, null, null, q.raw()); // empty query, Postgres answers EmptyQueryResponse
            return;
        }
        decideThen(ctx, q.sql(), q.raw(), v -> {
            Decision d = v.decision();
            QueryEvent ev = services.eventFactory().create(conn.session, v.query(), d, v.nanos());
            switch (d.verdict()) {
                case ALLOW -> {
                    ev.started(System.nanoTime());
                    send(Pending.Kind.QUERY, null, ev, null, q.raw());
                }
                case REWRITE -> {
                    q.raw().release();
                    log.info("rewrite {} {}: {}", d.appliedRules(), conn.session, v.query().fingerprint());
                    ev.started(System.nanoTime());
                    send(Pending.Kind.QUERY, null, ev, null, PgMessages.query(ctx.alloc(), d.rewrittenSql()));
                }
                case BLOCK -> {
                    q.raw().release();
                    logBlock(d, v);
                    services.events().publish(ev);
                    send(Pending.Kind.QUERY, null, null, errorFor(ctx, d), PgMessages.query(ctx.alloc(), BLOCKED_SQL));
                }
            }
        });
    }

    private void onParse(ChannelHandlerContext ctx, Parse p) {
        if (p.sql().isBlank()) {
            send(Pending.Kind.PARSE, null, null, null, p.raw());
            return;
        }
        decideThen(ctx, p.sql(), p.raw(), v -> {
            Decision d = v.decision();
            QueryEvent ev = services.eventFactory().create(conn.session, v.query(), d, v.nanos());
            switch (d.verdict()) {
                case ALLOW -> {
                    conn.session.prepared(p.name(), new Session.Prepared(v.query(), d, ev));
                    send(Pending.Kind.PARSE, p.name(), null, null, p.raw());
                }
                case REWRITE -> {
                    p.raw().release();
                    conn.session.prepared(p.name(), new Session.Prepared(v.query(), d, ev));
                    send(Pending.Kind.PARSE, p.name(), null, null,
                            PgMessages.parse(ctx.alloc(), p.name(), d.rewrittenSql(), p.paramTypes()));
                }
                case BLOCK -> {
                    p.raw().release();
                    logBlock(d, v);
                    services.events().publish(ev);
                    conn.session.closeStatement(p.name());
                    // Postgres fails this Parse, then ignores everything until Sync. That's
                    // the same thing it does for any bad statement, so clients already handle it.
                    send(Pending.Kind.PARSE, p.name(), null, errorFor(ctx, d),
                            PgMessages.parse(ctx.alloc(), p.name(), BLOCKED_SQL, new int[0]));
                }
            }
        });
    }

    private void onExecute(Execute e) {
        String stmt = conn.session.statementForPortal(e.portal());
        Session.Prepared prepared = stmt == null ? null : conn.session.prepared(stmt);
        QueryEvent ev = null;
        if (prepared != null) {
            ev = prepared.eventForExecute(services.eventFactory().newId(), services.eventFactory().now());
            ev.started(System.nanoTime());
        }
        send(Pending.Kind.EXECUTE, stmt, ev, null, e.raw());
    }

    /**
     * Pause this connection, decide off-loop, resume on the loop. If the client disconnects
     * while we're deciding, the message bytes are released and the verdict is dropped.
     */
    private void decideThen(ChannelHandlerContext ctx, String sql, ByteBuf raw, java.util.function.Consumer<Verdict> then) {
        deciding = true;
        Session s = conn.session;
        ActivePolicy policy = services.policies().current();
        s.role(policy.config().roleFor(s.user())); // role mapping can change on reload
        services.decider().decide(s.user(), s.role(), s.database(), sql).whenComplete((verdict, err) ->
                ctx.channel().eventLoop().execute(() -> {
                    deciding = false;
                    if (!ctx.channel().isActive()) {
                        ReferenceCountUtil.release(raw);
                        return;
                    }
                    if (verdict == null) {
                        // Decider handles its own failures; this is a bug if it happens.
                        log.error("No verdict for query, closing connection", err);
                        ReferenceCountUtil.release(raw);
                        ctx.close();
                        return;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("{} {} sql={}", verdict.decision().verdict(), s, sql);
                    }
                    then.accept(verdict);
                    drain(ctx);
                    if (conn.backend != null) {
                        conn.backend.flush();
                    }
                    maybeRead(ctx);
                }));
    }

    private ByteBuf errorFor(ChannelHandlerContext ctx, Decision d) {
        String detail = "rule " + d.ruleId() + (d.estimatedCost() == null ? "" : String.format(java.util.Locale.ROOT,
                ", estimated cost %.0f, estimated rows %.0f", d.estimatedCost(), d.estimatedRows()));
        return PgMessages.errorResponse(ctx.alloc(), "ERROR", d.sqlstate(), d.message(), detail, null);
    }

    private void logBlock(Decision d, Verdict v) {
        log.info("BLOCK {} {} fp={} ({} us)", d.ruleId(), conn.session, v.query().fingerprint(), v.nanos() / 1000);
    }

    // ---- writing to Postgres -------------------------------------------------------------

    private void send(Pending.Kind kind, String statement, QueryEvent event, ByteBuf replacementError, ByteBuf bytes) {
        if (conn.backend == null || !conn.backend.isActive()) {
            ReferenceCountUtil.release(bytes);
            ReferenceCountUtil.release(replacementError);
            return;
        }
        conn.pending.add(new Pending(kind, statement, event, replacementError));
        forward(bytes);
    }

    private void forward(ByteBuf bytes) {
        if (conn.backend == null || !conn.backend.isActive()) {
            ReferenceCountUtil.release(bytes);
            return;
        }
        conn.backend.write(bytes).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                conn.client.close();
            }
        });
    }

    static void closeOnFlush(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(io.netty.buffer.Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
