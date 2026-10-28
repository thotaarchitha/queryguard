package dev.queryguard.proxy.protocol;

import io.netty.buffer.ByteBuf;
import java.util.Map;

/**
 * Messages from the client. Every record keeps {@code raw}: the exact bytes, header included,
 * as a retained slice of the read buffer. Forwarding untouched means writing raw; nothing gets
 * re-encoded unless we changed it.
 *
 * <p>Whoever ends up holding a message owns raw and must forward or release it.
 */
public sealed interface FrontendMessage {

    ByteBuf raw();

    record SslRequest(ByteBuf raw) implements FrontendMessage {
    }

    record GssEncRequest(ByteBuf raw) implements FrontendMessage {
    }

    record CancelRequest(ByteBuf raw) implements FrontendMessage {
    }

    record StartupMessage(int protocol, Map<String, String> params, ByteBuf raw) implements FrontendMessage {
    }

    /** 'Q': simple query, possibly several statements. */
    record Query(String sql, ByteBuf raw) implements FrontendMessage {
    }

    /** 'P': prepare a named (or unnamed, "") statement. */
    record Parse(String name, String sql, int[] paramTypes, ByteBuf raw) implements FrontendMessage {
    }

    /** 'B': bind a portal to a statement. */
    record Bind(String portal, String statement, ByteBuf raw) implements FrontendMessage {
    }

    /** 'E' from the client is Execute (from the server it's ErrorResponse). */
    record Execute(String portal, int maxRows, ByteBuf raw) implements FrontendMessage {
    }

    /** 'D' from the client is Describe; kind is 'S' (statement) or 'P' (portal). */
    record Describe(char kind, String name, ByteBuf raw) implements FrontendMessage {
    }

    /** 'C' from the client is Close; kind is 'S' or 'P'. */
    record Close(char kind, String name, ByteBuf raw) implements FrontendMessage {
    }

    record Sync(ByteBuf raw) implements FrontendMessage {
    }

    record Terminate(ByteBuf raw) implements FrontendMessage {
    }

    /** Everything we pass through without looking: password/SASL, Flush, CopyData, FunctionCall... */
    record Other(char type, ByteBuf raw) implements FrontendMessage {
    }
}
