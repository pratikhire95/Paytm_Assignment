# Write-up

How the service decides who gets a seat, why that decision is race-free, and what I would do with more time.
The short version of the whole design: **PostgreSQL is the only arbiter**. The application holds no seat state in memory,
so any number of instances behave identically, and every correctness property is either a constraint, a row lock or a guarded
statement inside one transaction - not something the Java code has to get right by being careful.

## 1. The atomic decision

One reserve request is **one database transaction at READ COMMITTED** whose statements always run in the same order
(`ReservationService.reserveInTransaction`, SQL in `ReservationRepository`):

| # | Statement | What it settles |
|---|---|---|
| 1 | `INSERT INTO reservations (...) ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING created_at` | idempotency: 0 rows = the key already exists |
| 2 | `INSERT INTO user_show_holds (show_id, user_id, seat_count) VALUES (?,?,n) ON CONFLICT (show_id, user_id) DO UPDATE SET seat_count = seat_count + n WHERE seat_count + n <= limit` | per-user limit: 0 rows affected = over the limit |
| 3 | `SELECT label FROM seats WHERE show_id = ? AND label = ANY (?) AND status = 'available' ORDER BY ord FOR UPDATE` | takes the row locks, in ascending `ord` |
| 4 | `UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ? WHERE ... AND status = 'available'` | claims the seats |
| 5 | `COMMIT` | |

If statement 2 affects no row, or statement 3 returns fewer rows than were asked for, the transaction **rolls back** and the
request is answered `409`. Because the idempotency row and the counter bump live in the same transaction, a decline leaves *nothing* behind.

**Why a double-sell is impossible.** A seat is one row, and `status` only changes inside a transaction that holds that row's lock
and has just checked `status = 'available'`:

- Contenders for the same seat queue on the row lock (`FOR UPDATE`). Under READ COMMITTED a waiter that gets the lock re-reads the
  *latest committed version* of the row and re-evaluates the `WHERE` clause (PostgreSQL's re-check of updated rows). The winner has
  committed `confirmed`, so the row no longer matches, drops out of the result, the waiter sees fewer rows than it asked for and declines.
  500 requests for A12 therefore produce one `201` and 499 `409`s - no error, no retry loop.
- Statement 4 repeats the `status = 'available'` guard (defence in depth), and a `CHECK` constraint (`seats_owner_consistent`) makes an
  "owned but ownerless" or "available but owned" row unrepresentable.
- **The lock-free peek is only an optimisation.** Before opening the transaction the service reads the requested seats without locks and
  declines at once if they are visibly taken. That turns most of a stampede into two cheap reads, but correctness never depends on it:
  everything it says is re-decided under the lock.

**Why not the alternatives.** A read-then-write ("is A12 free? ok take it") double-sells. `SERIALIZABLE`/optimistic versioning would make
499 of 500 hot-seat transactions fail with `40001` and then retry into the same wall; with a row lock each loser waits once and exits cleanly.
A unique `(show_id, seat)` hold table would also work as the arbiter, but seats here are a small state machine
(`available -> confirmed -> available`) and rows that are updated in place make cancel/re-sale a guarded update instead of delete-and-insert races.

**Multi-seat requests and deadlock avoidance.** The risk with multi-seat requests is A taking `S1` then waiting for `S2` while B holds `S2` and waits for `S1`.
Every transaction acquires locks in one global order, so a cycle cannot form:

1. its own new reservation row (nobody else can hold it; only an idempotency twin ever waits on it, and a waiting twin holds no locks),
2. the user's counter row,
3. seat rows in **ascending `ord`** - the database sorts, so the order does not depend on the order the client listed the seats
   (`["S3","S1"]` and `["S1","S3"]` lock `S1` first both times).

Cancel takes counter row, then the reservation, then its seats - the same hierarchy (no transaction asks for a counter row while holding a seat) - and it locks those seats
with an explicit `SELECT ... ORDER BY ord FOR UPDATE` before the `UPDATE`, because a bare `UPDATE` would lock in index order, not `ord`. That matters for one subtle case: PostgreSQL locks the *newest*
version of a row before it re-checks the `WHERE` clause, so a reserve working from a slightly stale snapshot can briefly hold a lock on a seat that has just become `confirmed`
(it then drops the row). Because reserve and cancel both take seat locks in ascending `ord`, even that cannot form a cycle, so the wait-for graph is acyclic.
As a safety net the service retries a transaction that PostgreSQL still aborts with `40001`/`40P01` (up to 4 times with jitter) - that path is a seatbelt, not the mechanism.
Session guards (`lock_timeout` 20 s, `statement_timeout` 30 s, `idle_in_transaction_session_timeout` 60 s) mean a stuck transaction can never pin the pool forever; they surface as `503`, never as a hang.

**Details that matter under load.** No foreign keys on the per-request paths (reservations, the per-user counters, `seats.reservation_id`): thousands of concurrent inserts that FK-check one parent row churn MultiXact state for no benefit
(the only FK, `seats -> shows`, is checked once, when a show is created); `fillfactor 70` so status flips are HOT updates; no secondary indexes beyond what correctness needs (primary keys and the idempotency unique constraint -
every extra index is write amplification on the stampede path); show configuration (immutable) is cached in memory, seat state never is; a fixed-size connection pool so a stampede queues in the pool instead of growing it.

## 2. Idempotency

- **Where the key lives:** `reservations (user_id, idempotency_key)` with a `UNIQUE` constraint. It is scoped per user, so one user cannot collide with or probe another user's keys.
  Next to it: `request_hash` = SHA-256 of `(show_id, sorted seat labels)`.
- **How exactly-once is enforced:** the key row is inserted by the *same transaction* that books the seats (statement 1 above), so "key exists" and "seats booked" are one atomic fact.
  Two concurrent requests with the same key serialise on the unique index: the second blocks until the first finishes, then gets `DO NOTHING` and is answered from the stored row
  (`200` + `Idempotent-Replay: true`). If the first one rolled back (it was declined) the second simply proceeds as a fresh request.
- **Same key, different body:** the stored hash differs from the new request's -> `409 idempotency_key_conflict`, nothing booked. The hash ignores seat order, so `["A1","A2"]` and `["A2","A1"]` are the same request.
- **Retries that race the original.** A retry can arrive between the original's commit and the retry's own key lookup. The cheap pre-check would then see its own seats as "taken" and answer a bogus `409 seat_taken`.
  The service therefore looks the key up *again* before declining, and the in-transaction path resolves via the unique index. This was a real bug, found while designing the burst test's duplicate-request mix
  (which exists to catch exactly this); it has a regression test (`sameIdempotencyKeyInParallelCreatesExactlyOneReservation`).
- **Declines are not remembered**, so a key is bound only once a reservation exists. That keeps "a retry of a declined request re-evaluates it" true (the seat may have been freed) at the price of not caching 409s.
- **Not done:** keys never expire (they live as long as the reservation row). In production I would add a retention window and partition the table by time.

## 3. Holds and expiry

I chose the **explicit-cancel** model: `POST /reservations/{id}/cancel` (owner only). Reserve confirms immediately because the contract says `status: "confirmed"` and there is no payment step to hold a seat for.

- Cancel is `UPDATE reservations SET status='cancelled' ... WHERE id = ? AND user_id = ? AND status = 'confirmed'`, then
  `UPDATE seats SET status='available', reservation_id=NULL, user_id=NULL WHERE ... AND reservation_id = ?`, then the counter decrement - one transaction.
- **A release can never resurrect or steal a seat.** The seat release is guarded by `reservation_id`: if the seat has meanwhile been sold to someone else its `reservation_id` is different and the stale cancel changes nothing.
  The reservation transition is guarded by `status = 'confirmed'`, so the quota is given back exactly once; a repeated or concurrent duplicate cancel returns `200` with `Idempotent-Replay: true`.
- Only the owner can cancel: ownership is part of the guarded statement, and the user id comes from the token (a different user gets `403`, an unknown id `404`).

How I would add time-boxed holds: `held` already exists as a seat status and is part of the reconciliation invariant. Reserve would write `held` + `hold_expires_at`, a confirm step flips it to `confirmed`,
and expiry would be lazy plus swept: any reader/reserver treats `held AND hold_expires_at < now()` as available via the same guarded update, and a background job releases expired holds in small batches with
`FOR UPDATE SKIP LOCKED` (counter decremented in the same transaction). Time comes from the database clock, never from the instances, so skew between app servers cannot matter.

## 4. Consistency vs availability under a partition

**This service is CP.** All decisions are made by one PostgreSQL primary; if an instance cannot reach it (partition, failover, overload) it cannot make a safe decision, so it makes none:

- reserve/cancel answer `503` with `Retry-After` (never a guess from a cache, never a half-applied booking - the transaction either committed or it did not);
- `/readyz` returns `503`, so a load balancer stops routing to the instance, while `/healthz` stays `200`, so the platform does not restart-loop a healthy JVM because the *database* is down;
- a client whose response was lost retries with the same idempotency key and gets the original reservation, so "unknown outcome" never turns into a double booking.

There is no in-memory seat state, so several instances need no coordination and no sticky routing. Reads (`GET /shows`) also go to the primary: serving them from an asynchronous replica would trade
freshness for availability, which is acceptable for display but not for the decision.
One honest caveat: with *asynchronous* replication a failover can lose the last few committed bookings and the promoted replica could sell those seats again. If "never double-sell" must survive a primary failure,
the seat tables need synchronous replication (or a quorum-commit managed database) - I would pay the commit latency for that.

## 5. Observability: what I would be paged for at 2am

The service exposes (`/metrics`): `reservations_confirmed_total`, `reservations_declined_total{reason}`, `reservations_cancelled_total`, `server_errors_total{type}`, `http_requests_total` / `http_request_duration_seconds`
by route template, `seats_available|held|confirmed|total{show_id}`, `db_pool_connections{state}`, `db_pool_threads_awaiting`, JVM memory/GC.
Seat gauges are read from the database at scrape time (one statement = one snapshot), so they always equal `GET /shows/{id}`; counters are per process.

| Alert | Expression (sketch) | Why |
|---|---|---|
| **Page**: any 5xx | `increase(server_errors_total[5m]) > 0`, or 5xx ratio > 1% | declines are 4xx by design, so a 5xx is an outage or a bug |
| **Page**: not ready | `/readyz` failing for 2 min (blackbox probe) | the database is unreachable or the instance is wedged |
| **Page**: data integrity | `seats_available + seats_held + seats_confirmed != seats_total` for any show, or `seat_gauges_up == 0` | the invariant cannot be violated through the API; if the numbers disagree something bypassed it, or the exporter cannot read the database |
| **Page**: saturation | `db_pool_threads_awaiting > 0` for 5 min, or p99 of `POST /shows/{id}/reserve` > 1 s | requests are queueing for connections faster than they drain |
| Ticket | jump in `reservations_declined_total{reason="per_user_limit"}` or `http_requests_total{status="401"}` | abuse / a misbehaving client, not an outage |
| Ticket | heap > 85% for 15 min, rising GC time, restarts | capacity |

First moves on a page: `GET /logs?limit=500` (or the platform log stream) filtered by `status>=500`, take a `request_id` from a failing response and pull its full trail with `GET /logs?request_id=...`,
check `db_pool_*` and the database's connection count / `pg_stat_activity` for long lock waits. Every log line is JSON and carries `request_id`, `user_id`, `route`, `status`, `duration_ms`, `outcome`; tokens are never logged.
Every 503 also logs its cause (`database call failed, answering 503: cause=pool_timeout|lock_timeout|statement_timeout|connection_lost|...`), which separates "saturated" from "database slow" from "database gone" without reading code;
for `pool_timeout` the line includes the pool's own counters (`total`, `active`, `idle`, `waiting`).

## 6. AI usage: directed vs decided

> **TODO before submitting - make this section yours.** The interviewers will ask you to extend the service live and to explain any line. The facts below are what actually happened in the
> session; the bracketed parts can only be filled in by you. Delete this note afterwards.

**Tool.** Claude (Anthropic), used through the Claude desktop app's agent mode ("Cowork") in a single long session. It had a sandboxed shell and read/write access to this repository folder.

**What I directed** (my instructions and choices):
- I supplied the assignment and asked for a plan first, with questions before any code.
- I chose the stack: Spring Boot with Maven (I generated the project with Spring Initializr) and PostgreSQL as the only datastore (no second backend, no cache).
- I told it to go with its own authentication design instead of specifying one, and I left the hosting platform open until the service worked.
- [TODO: other instructions you gave, e.g. "make the burst test fail on any mismatch", requests for changes after reviewing output.]

**What the AI decided (and I accepted or reviewed)**:
- The concurrency design in sections 1-3: row-lock-plus-guarded-update, the counter-row upsert for the per-user limit, the deterministic lock order, the schema choices (no FKs on hot paths, `fillfactor`, `ord` as lock order).
- The authentication scheme: HMAC-signed bearer tokens minted by `POST /auth/token`, with a separate static admin credential compared in constant time.
- The HTTP contract details: status codes and error codes, all-or-nothing for partial requests, that declines do not consume idempotency keys, replay semantics.
- The test suites, the burst tool (including the choice to model client retries and cancel/re-book races), Docker/compose/CI, the metrics set, and the first drafts of README and this file.
- [TODO: which of those you challenged or changed, and why.]

**How it was checked.** The AI's sandbox had no Maven, Docker or PostgreSQL, so it could not run the service. What it did instead: compiled all main and test sources with `javac` against hand-written stubs of the Spring/Hikari/servlet APIs
and ran the pure-logic unit tests; and validated the burst tool against a small in-memory fake of the API, including deliberately broken variants (check-then-act race, ignored idempotency keys, unenforced limit, random 500s,
wrong metrics), each of which the tool caught. The SQL, the Spring Boot wiring and the PostgreSQL concurrency tests had **not been executed by the AI**.
**First real run (3 Oct 2026, Render free plan: 0.1 CPU / 512 MB web service, free PostgreSQL).** `./burst.sh <url> --quick` (2,199 HTTP requests from 2,000 users, up to 300 in flight): 39 of 41 checks passed, including every correctness check -
no seat sold twice, every retry replayed its original, the per-user limit held under 10 parallel requests, `available + held + confirmed == total_seats`, the final seat map equalled bookings - cancellations + re-bookings,
and the `/metrics` counters equalled what the clients saw. The two failed checks were one event: 6 requests were answered `503 service_unavailable` (the server's latency histogram shows exactly 6 requests slower than 30 s, which is
consistent with the 30 s database-pool wait limit). The instance managed about 42 requests/s with a median latency near 5 s, so the free plan was saturated; the design was not wrong, but the run did not meet "zero 5xx".
Follow-ups from it: a 503 now logs its cause, the pool wait on Render is 60 s, and the graded run uses a paid plan.

**Second run (the full 20,000-request run on the same free plan).** It stopped during setup, before a single reservation was sent: 1 of the 20,000 `POST /auth/token` calls came back `HTTP 520`. 520 is a status the CDN/proxy in front of a Render service
writes when it gets no valid answer from the origin; the service never writes it (it stamps `X-Request-Id` on every response it produces, and its `/metrics` show no 5xx at all), so this was not a wrong answer from the application. I could not establish the cause from the outside.
Follow-ups: minting a token is stateless, so the burst tool now repeats a transient setup answer (5xx, 429, a dropped connection) up to six times and reports it as a warning instead of aborting; during the storm every 5xx still fails the run, but the
report now says how many carried no `X-Request-Id` (made by a proxy) and how many came from the service; and Tomcat's idle keep-alive went from 60 s to 130 s, because Render's troubleshooting guide for intermittent `Connection reset by peer` errors says to keep
the server's keep-alive timeout above 120 s - a precaution, not a proven cause.

[TODO: your own runs - `make test-db` / the CI result, the full 20,000-request run on the paid plan, and anything else that failed and how it was fixed.]

**Git history.** The AI wrote the code in one long session and assembled the history *afterwards*: it replayed the finished working tree as 15 milestone commits (schema, platform, engine, API, tests, burst tool, Docker/CI, docs),
each of which compiles on its own against the sandbox stubs. The commits therefore all carry timestamps from within a few minutes of each other and are a readable reconstruction of the build order, not a log of when the work happened.
Every commit after those 15 (fixes from the first real run) is genuinely incremental.

**Where review paid off** (real examples from this build): the idempotency-retry race described in section 2 was found by reasoning about what the duplicate-request mix in the burst test would hit; the final read-through found that
cancel's `UPDATE` locked seats in index order rather than `ord`, which broke the "one global lock order" claim in section 1 (fixed with an explicit ordered `SELECT ... FOR UPDATE` before the release); the Spring Initializr default parent was a
`SNAPSHOT` build and was pinned to a released version so a clean clone keeps building; an unused public route and an unneeded test assertion were removed during review.

**What I would not trust without checking:** library version specifics (Spring Boot 4 / Jackson 3 API details), PostgreSQL behaviour that the AI described from documentation rather than observed (this is why the concurrency tests run against a real database), and any number in a doc that was not produced by a run.

## 7. What I would do next

- **Time-boxed holds** with a payment/confirm step and the sweeper described in section 3.
- **Real identity:** replace `POST /auth/token` with OIDC/JWT validation (JWKS), keep the "identity only from the verified token" rule, add per-user and per-IP rate limits.
- **Admission control:** a bounded request queue with `429 + Retry-After` (and a virtual waiting room for real on-sales) instead of unbounded waiting on a saturated connection pool.
- **Observability:** OpenTelemetry traces across HTTP -> transaction -> SQL, alert rules and a Grafana dashboard checked into the repo, log shipping instead of the in-memory `/logs` ring buffer.
- **Data layer:** synchronous replication / a managed HA database for the seat tables, PgBouncer for connection scaling, idempotency-key retention and table partitioning, archiving of finished shows.
- **Verification:** run the burst test in CI against every deploy, add a kill-the-database-mid-burst chaos test, and a Jepsen-style history checker over the request log.
- **Seat assignment:** "best available N seats" with `FOR UPDATE SKIP LOCKED` so buyers who do not care which seat they get never contend on the same row.

## Appendix: code tour (in the order I would read it)

1. `db/migration/V1__init.sql` - the schema and its constraints.
2. `domain/ReservationRepository.java` - every decisive SQL statement, each with a comment on why it is shaped that way.
3. `domain/ReservationService.java` - the transaction, the order of the statements, retries.
4. `api/ReservationController.java`, `auth/AuthFilter.java`, `auth/TokenService.java` - where identity comes from and who may do what.
5. `observability/` - request id, metrics, readiness, the `/logs` buffer.
6. `ReservationConcurrencyTest.java` and `scripts/Burst.java` - how the claims above are checked.
