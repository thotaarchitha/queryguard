# Bug journal

Things that broke, how I noticed, and what I learned. Mostly for me, partly for interviews.

---

### 2026-10-28: seed script dies at row ~271K

**Symptom.** `docker compose up` failed with the postgres container exiting with code 3. The logs showed
`ERROR: integer out of range` on the customers insert.

**Cause.** `generate_series(1, 1000000)` produces `int4`, so `g * 7919` (a cheap way to scatter
`card_last4` values) overflows once `g` passes 2³¹ / 7919 ≈ 271,181. Products only goes to 10K,
so that insert ran fine and hid the problem.

**Fix.** `generate_series(1::bigint, ...)` everywhere. Cheaper than casting inside each expression.

**Lesson.** Synthetic-data math needs the same overflow care as real code. Test seeds at full scale,
not at 1K rows.

---

### 2026-10-28: "healthy" before the database existed

**Symptom.** After fixing the seed, compose reported postgres healthy and started the proxy, but the
orders insert hadn't run yet. Connecting would have hit a half-loaded database.

**Cause.** The postgres image runs init scripts on a temporary server that only listens on the Unix
socket. `pg_isready` with no `-h` checks that socket, so it says "ready" during the seed.

**Fix.** `pg_isready -h 127.0.0.1`. TCP isn't open until the real server starts after init.

**Lesson.** A healthcheck should test the same path the clients use, not just "is the process up."
