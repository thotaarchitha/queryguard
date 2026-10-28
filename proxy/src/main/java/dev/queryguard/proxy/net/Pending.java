package dev.queryguard.proxy.net;

import dev.queryguard.proxy.events.QueryEvent;
import io.netty.buffer.ByteBuf;

/**
 * One response we're waiting for from Postgres, in the order the requests were sent.
 *
 * <p>Postgres answers strictly in order, so a FIFO of these tells the backend handler what
 * each incoming message belongs to: which Execute these DataRows are for (masks, row count),
 * which Parse failed, when a Q is finished.
 */
final class Pending {

    enum Kind {
        STARTUP,   // ends at the first ReadyForQuery
        QUERY,     // 'Q', ends at ReadyForQuery
        PARSE,     // '1' ParseComplete
        BIND,      // '2' BindComplete
        DESCRIBE,  // 'T' or 'n' (after 't' for a statement describe)
        EXECUTE,   // 'C', 's', 'I' or 'E'
        CLOSE,     // '3' CloseComplete
        SYNC,      // 'Z'
        FUNCTION   // 'F' fast-path call, ends at 'Z'
    }

    final Kind kind;
    final String statement;
    final QueryEvent event;
    /** When set, the first ErrorResponse for this request is swapped for these bytes. */
    ByteBuf replacementError;
    long rows;
    String errorCode;

    Pending(Kind kind, String statement, QueryEvent event, ByteBuf replacementError) {
        this.kind = kind;
        this.statement = statement;
        this.event = event;
        this.replacementError = replacementError;
    }

    boolean endsAtReadyForQuery() {
        return kind == Kind.SYNC || kind == Kind.QUERY || kind == Kind.STARTUP || kind == Kind.FUNCTION;
    }

    void release() {
        if (replacementError != null) {
            replacementError.release();
            replacementError = null;
        }
    }
}
