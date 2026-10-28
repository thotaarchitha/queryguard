# Design decisions

> Draft notes. Rewrite each section in your own words as you build. Interviewers will ask about these.

## Phase 1: pass-through proxy

### Sharing the event loop

The outbound PostgreSQL connection is created with `client.eventLoop()` as its group.
Both halves of a proxied connection are then always handled by the same thread:

- no thread hop when bytes go from one channel to the other,
- no locks needed for per-connection state,
- a connection's work stays on one core.

### Backpressure

Both channels run with `AUTO_READ` off. A side only reads more when the other side is writable:

1. After forwarding a batch (`channelReadComplete`), we flush and then call `read()` again,
   but only if the destination channel is still writable.
2. If it isn't writable, reading stops. When the destination drains below the low-water mark,
   `channelWritabilityChanged` fires on that channel's handler and it restarts reads on the source.

Without this, a big result set to a slow client would pile up in the proxy's memory.

### Closing

When either side goes inactive, the other side is flushed and then closed. If PostgreSQL dies
mid-query the client sees a disconnect right away instead of hanging. The backend connect has a
3 s timeout (`QG_CONNECT_TIMEOUT_MS`).

## Open questions for later phases

- Fail-open vs fail-closed when EXPLAIN times out or the parser fails (Phase 3)
- Column identity by table OID + column number instead of name (Phase 4)
- LISTEN/NOTIFY for live rule reloads (Phase 5)
- Async event logging so queries never wait on Kafka (Phase 6)
