package dev.queryguard.proxy.net;

import dev.queryguard.common.masking.MaskStrategy;
import dev.queryguard.proxy.events.QueryEvent;
import dev.queryguard.proxy.masking.DataRowMasker;
import dev.queryguard.proxy.masking.MaskTargets;
import dev.queryguard.proxy.masking.RowDescription;
import dev.queryguard.proxy.protocol.BackendMessage;
import dev.queryguard.proxy.protocol.PgFrontendDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Postgres side of one connection. Forwards everything to the client, and on the way:
 * masks DataRows, swaps in our ErrorResponse for blocked queries, counts rows and finishes
 * query events.
 *
 * <p>The only messages decoded beyond their type byte are T (to plan masks), D (only when
 * something is masked), E (to read the SQLSTATE) and Z (transaction status).
 */
final class BackendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(BackendHandler.class);

    private final Connection conn;
    private final ProxyServices services;

    BackendHandler(Connection conn, ProxyServices services) {
        this.conn = conn;
        this.services = services;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        BackendMessage m = (BackendMessage) msg;
        ByteBuf out = m.raw();
        Pending head = conn.pending.peek();
        try {
            switch (m.type()) {
                case 'T' -> onRowDescription(m.raw(), head);
                case 'n' -> {
                    if (head != null && head.kind == Pending.Kind.DESCRIBE) {
                        conn.session.masks(head.statement, null, List.of());
                        conn.pending.poll();
                    }
                }
                case 'D' -> out = onDataRow(ctx, m.raw(), head);
                case '1', '2', '3' -> conn.pending.poll();
                case 'C', 's', 'I' -> onCommandDone(head);
                case 'E' -> out = onError(m.raw(), head);
                case 'Z' -> onReady(m.raw());
                default -> {
                    // ParameterStatus, BackendKeyData, auth, notices, notifications, COPY: just forward
                }
            }
        } catch (RuntimeException e) {
            // A decoding problem here must not leak unmasked data, so the connection goes.
            log.error("Couldn't process backend message '{}' for {}: {}", m.type(), conn.session, e.toString());
            m.raw().release();
            conn.client.close();
            return;
        }
        conn.client.write(out).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                ctx.channel().close();
            }
        });
    }

    private void onRowDescription(ByteBuf raw, Pending head) {
        List<RowDescription.Field> fields = RowDescription.parse(raw);
        MaskTargets.Resolved resolved = services.maskTargets().peek(conn.session.database());
        MaskStrategy[] masks = resolved.isEmpty() ? null : MaskTargets.plan(fields, resolved, conn.session.role());
        List<String> masked = masks == null ? List.of() : maskedNames(fields, masks, resolved);

        if (head == null) {
            return;
        }
        if (head.kind == Pending.Kind.DESCRIBE) {
            // Remember per statement: later Executes of it often skip Describe entirely.
            conn.session.masks(head.statement, masks, masked);
            conn.pending.poll();
        } else if (head.kind == Pending.Kind.QUERY) {
            conn.queryMasks = masks;
            conn.queryMaskedColumns = masked;
            if (head.event != null) {
                head.event.addMaskedColumns(masked);
            }
        }
    }

    private ByteBuf onDataRow(ChannelHandlerContext ctx, ByteBuf raw, Pending head) {
        MaskStrategy[] masks = null;
        if (head != null) {
            head.rows++;
            if (head.kind == Pending.Kind.QUERY) {
                masks = conn.queryMasks;
            } else if (head.kind == Pending.Kind.EXECUTE && head.statement != null) {
                masks = conn.session.masks(head.statement);
                if (masks != null && head.event != null && head.rows == 1) {
                    head.event.addMaskedColumns(conn.session.maskedColumns(head.statement));
                }
            }
        }
        if (masks == null) {
            return raw;
        }
        ByteBuf rewritten = DataRowMasker.mask(raw, masks, ctx.alloc());
        raw.release();
        services.metrics().masked(countMasked(masks));
        return rewritten;
    }

    private void onCommandDone(Pending head) {
        if (head == null) {
            return;
        }
        if (head.kind == Pending.Kind.EXECUTE) {
            conn.pending.poll();
            publish(head, null);
        } else if (head.kind == Pending.Kind.QUERY) {
            conn.queryMasks = null; // next statement in a multi-statement Q sends its own T
            conn.queryMaskedColumns = List.of();
        }
    }

    private ByteBuf onError(ByteBuf raw, Pending head) {
        ByteBuf out = raw;
        String code = sqlstate(raw);
        if (head != null && head.replacementError != null) {
            out = head.replacementError;
            head.replacementError = null;
            raw.release();
        }
        if (head == null) {
            return out;
        }
        switch (head.kind) {
            case QUERY -> head.errorCode = code; // the Q goes on until ReadyForQuery
            case STARTUP, SYNC, FUNCTION -> {
            }
            default -> {
                // Extended protocol: after an error Postgres skips everything up to Sync,
                // so none of those requests will get their own answer.
                while (!conn.pending.isEmpty() && conn.pending.peek().kind != Pending.Kind.SYNC) {
                    Pending p = conn.pending.poll();
                    if (p == head && p.event != null) {
                        p.errorCode = code;
                        publish(p, code);
                    }
                    p.release();
                }
            }
        }
        return out;
    }

    private void onReady(ByteBuf raw) {
        conn.session.txStatus((char) raw.getByte(5));
        conn.queryMasks = null;
        while (!conn.pending.isEmpty()) {
            Pending p = conn.pending.poll();
            if (p.kind == Pending.Kind.QUERY && p.event != null) {
                publish(p, p.errorCode);
            }
            p.release();
            if (p.endsAtReadyForQuery()) {
                return;
            }
        }
    }

    private void publish(Pending p, String errorCode) {
        QueryEvent ev = p.event;
        if (ev == null) {
            return;
        }
        ev.finish(System.nanoTime(), p.rows, errorCode);
        services.events().publish(ev);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        conn.client.flush();
        // Only pull more rows from Postgres when the client is keeping up.
        if (conn.client.isWritable()) {
            ctx.read();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable() && conn.client.isActive()) {
            conn.client.read();
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Postgres went away (crash, restart, pg_terminate_backend). Don't leave the client hanging.
        FrontendHandler.closeOnFlush(conn.client);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        services.metrics().backendError();
        log.warn("Backend connection error for {}: {}", conn.session, cause.toString());
        FrontendHandler.closeOnFlush(ctx.channel());
    }

    private static String sqlstate(ByteBuf raw) {
        ByteBuf b = raw.duplicate().skipBytes(5);
        while (b.isReadable()) {
            byte field = b.readByte();
            if (field == 0) {
                break;
            }
            String value = PgFrontendDecoder.readCString(b);
            if (field == 'C') {
                return value;
            }
        }
        return null;
    }

    /** Real names (customers.email), not whatever the query aliased them to. */
    private static List<String> maskedNames(List<RowDescription.Field> fields, MaskStrategy[] masks, MaskTargets.Resolved resolved) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < masks.length; i++) {
            if (masks[i] != null) {
                var rule = resolved.ruleFor(fields.get(i).tableOid(), fields.get(i).attnum());
                names.add(rule == null ? fields.get(i).name() : rule.tableName() + "." + rule.columnName());
            }
        }
        return names;
    }

    private static int countMasked(MaskStrategy[] masks) {
        int n = 0;
        for (MaskStrategy s : masks) {
            if (s != null) {
                n++;
            }
        }
        return n;
    }
}
