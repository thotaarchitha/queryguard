# Design decisions

Notes on the calls I made and what I gave up for each. Newest phase at the bottom. If a decision
gets reversed later, I leave the old reasoning here and add why it changed.

---

## Netty, not thread-per-connection

*Phase 1*

The obvious version is one thread per client, blocking reads, done in an afternoon. It also pins a
thread to every idle psql session someone forgot to close, and proxies mostly hold idle connections.

With Java 21 virtual threads, thread-per-connection is honestly viable again, and I thought about it.
I went with Netty anyway for two reasons:

1. Phase 2 needs a real framing layer: messages split across TCP reads, several messages in one read.
   `ByteToMessageDecoder` is that layer, already tested by a lot of people.
2. Backpressure is explicit in Netty (`isWritable`, auto-read). With blocking streams it's implicit,
   and implicit is harder to reason about when a 5M-row result is in flight.

Virtual threads still show up in Phase 3, for the parts that really do block (JDBC to run EXPLAIN).

## One event loop per connection pair

The backend `Bootstrap` uses `client.eventLoop()` instead of its own group. Both channels of a
proxied connection then live on one thread:

- no thread hop on every forwarded buffer
- per-connection state needs no synchronization
- the work stays on one core

The cost: one expensive connection can starve the others sharing its loop. That's acceptable only
because nothing slow is allowed to run *on* the loop. That rule becomes the constraint for every
later phase.

## Backpressure

`AUTO_READ` is off on both sides. The loop is:

```
read batch → write to other side → channelReadComplete: flush
    other side writable?  yes → read() again
                          no  → stop; wait for channelWritabilityChanged on the other side
```

Without this, a slow client reading a large result makes the proxy buffer whatever Postgres sends
as fast as Postgres sends it. The 8 MB test in `ProxyServerTest` exists to push past Netty's 64 KB
high-water mark and actually go down this path.

Not done yet: the high- and low-water marks are Netty defaults (32/64 KB). They may want tuning once
there are DataRows to rewrite.

## Failure behavior so far

- Backend connect timeout: 3 s (`QG_CONNECT_TIMEOUT_MS`). The client gets closed, not left hanging.
- Either side closes → flush what's queued, close the other side.
- No retries. Retrying a connect behind a client's back is how you get duplicate writes. The circuit
  breaker in Phase 4 is the right place for "Postgres is down" logic.

---

## Coming up (decide before writing the code)

- **Fail-open vs fail-closed** when EXPLAIN times out or the parser chokes. My current lean: fail-closed
  for writes, fail-open for reads, behind a flag. It needs a real argument written here.
- **Masking by table OID + attnum**, and what to do with expressions (`upper(email)`), where
  RowDescription reports OID 0.
- **LISTEN/NOTIFY for rule reload**: what happens to a query that's mid-evaluation during a swap.
- **Async Kafka producer**: what we lose when Kafka is down, and why that's fine.
