# Postgres wire protocol: what I needed to know

Notes from building the proxy. The bytes below are real, captured through QueryGuard with
[`tools/wire_peek.py`](tools/wire_peek.py), a 60-line script that speaks just enough of the protocol
by hand. Reading these dumps did more for me than the spec did.

## The first two messages have no type byte

Everything after startup is `type (1 byte) | length (4, includes itself) | body`. The startup phase is the
exception: just `length | code | ...`. That's why the decoder has two states.

**SSLRequest**: always exactly these 8 bytes:

```
0000  00 00 00 08 04 d2 16 2f                           ......./
      └ length 8  └ 80877103 = 1234 << 16 | 5679
```

The magic number is `1234` in the high 16 bits and `5679` in the low 16 bits, chosen so it can never be
mistaken for a protocol version. The answer is a single byte, also with no type and no length:

```
0000  4e                                                N        ← "no TLS here, carry on in plaintext"
```

QueryGuard answers this itself and never forwards it. If it forwarded it and Postgres said `S`, the
client would start a TLS handshake *with Postgres*, through the proxy, and the proxy would be reading
ciphertext. Terminating TLS in the proxy is on the roadmap.

**StartupMessage**: length, protocol version 3.0 (`0x00030000` = 196608), then null-terminated
key/value pairs, ending with an extra null:

```
0000  00 00 00 3b 00 03 00 00 75 73 65 72 00 62 6f 62   ...;....user.bob
0010  00 64 61 74 61 62 61 73 65 00 73 68 6f 70 00 61   .database.shop.a
0020  70 70 6c 69 63 61 74 69 6f 6e 5f 6e 61 6d 65 00   pplication_name.
0030  77 69 72 65 5f 70 65 65 6b 00 00                  wire_peek..
                                   └ empty key = end of params
```

This is the only place the proxy learns who the user is. It's plaintext even when the password isn't.

## Auth: forwarded, never understood

Postgres' first typed message:

```
0000  52 00 00 00 17 00 00 00 0a 53 43 52 41 4d 2d 53   R........SCRAM-S
0010  48 41 2d 32 35 36 00 00                           HA-256..
      R = Authentication, length 23, code 10 = SASL, mechanisms: SCRAM-SHA-256
```

The SCRAM exchange that follows (`p` from the client, `R` code 11/12 from the server) goes through the
proxy byte for byte. SCRAM is a challenge-response with a server signature the client verifies, so
a man-in-the-middle can't fake it, but it doesn't need to: it just relays. That's the reason the
proxy works with any auth method for free, and the reason it can't pool connections (the
proxy never has anything it could log in with).

## The messages that matter after startup

| From | Type | What QueryGuard does |
|---|---|---|
| client | `Q` simple query | decides, then forwards / rewrites / replaces with a placeholder that fails |
| client | `P` Parse | same, once per prepared statement |
| client | `B` Bind, `E` Execute, `D` Describe, `C` Close, `S` Sync | tracks portal → statement, forwards |
| server | `T` RowDescription | reads table OID + column number per column, plans masks |
| server | `D` DataRow | rewrites masked values, fixes every length |
| server | `E` ErrorResponse | swapped for QueryGuard's own when the query was blocked |
| server | `Z` ReadyForQuery | records transaction status (`I`, `T`, `E`) |

Name collisions to watch: `E` is Execute from the client but ErrorResponse from the server, `D` is
Describe vs DataRow, `C` is Close vs CommandComplete. The direction decides.

## Extended protocol, the part that bit me

JDBC and psycopg send `Parse / Bind / Describe / Execute / Sync` in **one** TCP packet. Postgres answers
in order: `1 ParseComplete`, `2 BindComplete`, `T`, `D`..., `C`, `Z`. If anything fails, Postgres sends
`E` and then **ignores every message until Sync**. The proxy keeps a FIFO of "what we're waiting for"
so it knows which DataRows belong to which statement, and on an error it drops everything up to Sync
too, the same way Postgres does.

The masking catch: after 5 executions pgjdbc switches to a named server-side statement and stops
sending Describe, so no more `T`. The DataRows arrive with nothing describing them. QueryGuard
remembers the masks from the last `T` per statement name. There's an integration test that runs 12
executions to make sure execution 6 onward is still masked.
