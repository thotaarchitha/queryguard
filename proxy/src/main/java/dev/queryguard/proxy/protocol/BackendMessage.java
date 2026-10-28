package dev.queryguard.proxy.protocol;

import io.netty.buffer.ByteBuf;

/**
 * A server message, framed but not decoded. Most of them (ParameterStatus, BackendKeyData,
 * auth) we never look inside; the backend handler only opens up T, D, E and Z.
 */
public record BackendMessage(char type, ByteBuf raw) {
}
