# Write-up

How the service decides who gets a seat, why that decision is race-free, and what I would do with more time. Section 6 separates what I decided from what the AI proposed and wrote.
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

The service uses the **explicit-cancel** model (the AI's recommendation, accepted; see 6.3): `POST /reservations/{id}/cancel` (owner only). Reserve confirms immediately because the contract says `status: "confirmed"` and there is no payment step to hold a seat for.

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

**Tool.** Claude (Anthropic), used through the Claude desktop app's agent mode ("Cowork") in one long working session (1-3 Oct 2026). It had a sandboxed shell and read/write access to this repository folder; it could not reach my laptop, my Render account or GitHub.
Deploying, running the burst against the live URL and pushing were done by me, and I pasted the results back into the session.

**In one sentence.** I chose the stack, the datastore, the hosting and how authorship is recorded, and I ran everything on real infrastructure; the AI proposed the concurrency mechanism and the rest of the design together with its trade-offs,
wrote the code, tests, tooling and docs, and diagnosed what my runs showed; I accepted its design as proposed.

### 6.1 Who did what

| Area | What I did or decided | What the AI did |
|---|---|---|
| The brief | Supplied the assignment and asked for a plan, with questions before any code | Planned the work and asked four questions, each with options and trade-offs (6.2) |
| Stack | Chose Java and Spring Boot on Maven; generated the project with Spring Initializr | Built on that scaffold; pinned its `SNAPSHOT` parent to a released Spring Boot (4.1.1) so a clean clone builds |
| Datastore | Chose PostgreSQL only: no second backend and no SQLite test backend | Schema, hand-written SQL, the PostgreSQL concurrency tests |
| Concurrency design | Accepted the AI's proposal (6.3); I did not specify a different mechanism | Proposed and documented it: row lock plus guarded update, one global lock order, a counter row for the quota, the idempotency key inside the booking transaction |
| Authentication | Confirmed that the assignment names no scheme and told the AI to use its own design | HMAC-signed bearer tokens, a separate admin credential, a deny-by-default filter |
| Code, tests, tooling | - | The service, the schema, unit and PostgreSQL tests, the burst tool, Docker and compose, CI, `render.yaml`, the Postman collection |
| Hosting | Picked Render and deployed the Blueprint from my own account | `render.yaml`, the step-by-step deployment and database instructions, CI and a keep-warm job |
| Running it | Ran `./burst.sh` against the live URL from my laptop and pasted the output back | Read the output, diagnosed it, changed the code, the burst tool and the docs |
| Scaling questions | Asked whether read replicas, sharding, horizontal or vertical scaling, partitioning, a bigger connection pool or a BookMyShow-style design would help | Compared the options and their costs (6.4); no design change followed |
| Authorship | Required that no commit carry an AI co-author trailer | Rebuilt the history without it (6.7) |
| Hand-over | Asked what to submit and how a reviewer can test it, and asked for this section to be restructured | The reviewer guide in the README; drafts of the README and of this write-up |

### 6.2 Decisions I made

I opened by asking for a plan and for questions before any code. The AI asked four. These were its options with its trade-off notes (condensed), and my answers:

| Question | Options and the AI's trade-off note | My answer |
|---|---|---|
| Language and runtime | **Node.js 22** (the AI's recommendation: one dependency, and it could run and test almost everything in its sandbox); Python with FastAPI (most readable, slowest per request on a 0.1-CPU free tier, not runnable in its sandbox); Go (best throughput, no toolchain in its sandbox); **Java with Spring Boot** (familiar in fintech, but no Maven or Gradle in its sandbox, so nothing could be built or run, and a slow cold start on a free tier) | **Spring Boot.** I wanted to run it on my own laptop, where Maven is already installed, and it is the code I have to explain and extend in the interview. I overrode the recommendation and accepted the downside: the AI could only compile-check the Java against stubs (6.5). |
| How to verify the PostgreSQL code path | **Add a SQLite dev/test backend** (the AI's recommendation: the same API, tests and burst run end to end in its sandbox, at the price of a second store to maintain); **PostgreSQL only** (simplest repo and one backend to explain, but the AI would write the database layer without being able to run it, so expect fix iterations) | **PostgreSQL only.** I overrode the recommendation. The first real execution of the SQL therefore happened outside the AI's sandbox. |
| Where to deploy | Render (the AI's recommendation: one Blueprint creates the web service and the database; the free web service sleeps after 15 minutes, which matches "survive a cold start"; the free database expires after 30 days); Railway (small trial credit, better CPU, credit-limited); Fly.io (no free tier for new accounts); undecided (ship generic config and pick later) | **Undecided** at the time. After the code was done I chose Render and deployed it myself. |
| Authentication | The assignment names no scheme. The AI's design (recommended): `POST /auth/token` issues an HMAC-signed token, the admin is a bearer `ADMIN_TOKEN` from the environment, identity comes only from the verified token. Alternatives: unsigned dev tokens (easiest for a load harness to generate, weaker integrity story) or a scheme the recruiter specified | The recruiter specified none, so **use the AI's design.** |

Two more things were mine: no AI co-author trailer on any commit (I am the author of record; this section is the disclosure), and running every burst myself against the real deployment and pasting the results back for the AI to diagnose.

### 6.3 Design decisions the AI proposed, and their trade-offs

I accepted these as proposed. The reasoning is in sections 1-5; this is the comparison in one place. Where I disagreed with the AI's recommendation (the stack and the test backend, 6.2) I said so; I did not disagree with any of the following.

| Decision | Alternatives | Chosen, and why | What it costs |
|---|---|---|---|
| Who arbitrates a seat | In-process locks or a Redis lock; optimistic versioning or `SERIALIZABLE`; a unique "hold" table; row lock plus guarded `UPDATE` in PostgreSQL | The row lock: one source of truth that survives restarts and works for any number of instances; each loser waits once and exits with a clean `409`, instead of a retry storm | Throughput is bounded by one primary; consistency over availability under a partition (section 4) |
| Per-user limit | Count then insert (racy); advisory locks; a counter row with a guarded upsert | The counter row: its lock also serialises one user's concurrent requests, so the limit is race-free in a single statement | The counter must stay in step with cancels (same transaction) and costs one extra row lock per request |
| Idempotency | A key cache in Redis; a separate keys table; a unique constraint on the reservation itself | `UNIQUE (user_id, idempotency_key)`, inserted by the same transaction that books the seats, so "key exists" and "seats booked" are one atomic fact | Declines are not remembered; keys never expire (section 2) |
| Partial requests | Best effort (book what is free) or all-or-nothing | All-or-nothing: no partial charges, one clear contract, and ascending lock order keeps it deadlock-free | A group request fails if one of its seats is contested |
| Release model | Time-boxed holds with an expiry sweeper, or an explicit cancel | Explicit cancel: there is no payment step, so a hold would have nothing to wait for, and it is the smaller surface to get right | No automatic release; time-boxed holds are a next step (section 3) |
| Cheap declines | Open a transaction for every request, or peek lock-free first | A lock-free peek: it turns most of a stampede into plain reads | Several extra queries per decline; correctness never depends on it |
| Data access | JPA/Hibernate, or plain JDBC with hand-written SQL | Plain SQL: lock order, guards and `RETURNING` clauses stay explicit and reviewable | More boilerplate |
| Auth plumbing | Spring Security, or one servlet filter | One deny-by-default filter: the scheme is "verify an HMAC, read two fields", the API is stateless and has no cookies | No OIDC or roles for free; a real identity provider is a next step |
| Schema on the hot path | Foreign keys, secondary indexes, default fillfactor | None of them: foreign-key checks on one parent row churn MultiXact state, every index is write amplification, `fillfactor 70` makes status flips HOT updates | Integrity on those paths rests on the transaction logic and the reconciliation checks, not on the database |
| Seat gauges | In-process gauges, or read from the database at scrape time | Read from the database: they always equal `GET /shows/{id}` and stay right with several instances | One query per scrape (limited to the 10 newest shows); counters remain per process |
| Logs | Platform logs only, or also a public in-memory buffer | `GET /logs`, a ring buffer: meets "public log access" without a platform login | Per instance, lost on restart, 5,000 lines by default |
| Database unreachable | Serve from a cache or replica (available), or refuse (consistent) | Refuse: `503` with `Retry-After`, `/readyz` fails closed, `/healthz` stays up so the platform does not restart-loop | Unavailable while the primary is; asynchronous failover could lose recent bookings (section 4) |

### 6.4 Scaling and capacity: the options the AI compared for me

After the first real runs I asked why the service could not absorb 20,000 requests on the free plan, and whether read replicas, sharding, horizontal or vertical scaling, partitioning, a bigger connection pool or a BookMyShow-style design would help.
The AI's reading of the live metrics: the connection pool was idle when checked mid-storm (`db_pool_threads_awaiting` read 0), and `POST /auth/token`, which never touches the database, was just as slow - so the limit is CPU on a 0.1-CPU instance, not the pool and not the design.
It put the ceiling at roughly 40-65 reserve requests per second (the first run measured about 42); that figure is an inference from the runs, not a profile.

| Option | What it buys | What it costs or limits | Where it stands |
|---|---|---|---|
| More CPU on the web service (vertical) | Attacks the actual bottleneck. Render `1c-2g` is about $25 a month, billed per second, so it can be on for the grading window only | Paid | The first lever to pull; not applied yet |
| More instances (horizontal) | Safe by construction, since no seat state lives in memory | Paid plans only; every instance shares one PostgreSQL primary, so the database becomes the next limit; counters and `/logs` are per instance | Not done; the service has never been run with more than one instance |
| A bigger connection pool | Nothing here: the pool was idle | More connections only add backends on a 0.1-CPU database (the usual guidance is about two per core) | Not adopted; revisit if `db_pool_threads_awaiting` stays high with the pool full |
| Read replicas | Relief for read-only traffic | The reserve decision must run on the primary, replicas lag, and Render's replicas need a bigger paid database | Not adopted for this workload |
| Sharding by show | Removes the single-primary ceiling | Several databases to run and a routing layer | Only if one show outgrows a primary (section 7) |
| Table partitioning | Retention and maintenance (`reservations` by time, `seats` by show) | Adds no CPU and does not relieve a hot seat's row lock | A next step, not a load fix |
| A BookMyShow-style design | Seat holds with a TTL, the booking table as source of truth with a cached availability view, a virtual waiting room, sharding by show or city | Much more than this assignment asks for | Waiting room and holds are in section 7 |
| A cheaper decline path | One statement instead of the three lock-free reads a declined request makes today | A change to the hot path | Proposed by the AI; not implemented |

I also asked whether I would have to pay. The AI's answer: no - the free plan already passed every correctness check, and paying only buys the capacity for the 20,000-request volume.
As of this write-up the deployment still runs on the free plans.

### 6.5 How it was checked, and what was not

**Sandbox limits.** The AI's sandbox had no Maven, Docker or PostgreSQL, so it could not run the service. It compiled all main and test sources with `javac` against hand-written stubs of the Spring, Hikari and servlet APIs and ran the pure-logic unit tests.
It validated the burst tool against a small in-memory fake of the API, including deliberately broken variants (a check-then-act race, ignored idempotency keys, an unenforced limit, random 500s, wrong metrics), each of which the tool caught.
The Postman collection got the same treatment with a small Postman-style runner, because Newman could not be installed there; it was not run in Postman itself. The SQL, the Spring Boot wiring and the PostgreSQL concurrency tests were **not executed by the AI**;
they are meant to run on my machine (`make test-db`) and in CI (`.github/workflows/ci.yml`).

**First real run (3 Oct 2026, Render free plan: 0.1 CPU / 512 MB web service, free PostgreSQL).** `./burst.sh <url> --quick` (2,199 HTTP requests from 2,000 users, up to 300 in flight): 39 of 41 checks passed, including every correctness check -
no seat sold twice, every retry replayed its original, the per-user limit held under 10 parallel requests, `available + held + confirmed == total_seats`, the final seat map equalled bookings - cancellations + re-bookings,
and the `/metrics` counters equalled what the clients saw. The two failed checks were one event: 6 requests were answered `503 service_unavailable` (the server's latency histogram shows exactly 6 requests slower than 30 s, which is
consistent with the 30 s database-pool wait limit). The instance managed about 42 requests/s with a median latency near 5 s, so the free plan was saturated; the design was not wrong, but the run did not meet "zero 5xx".
Follow-ups: a 503 now logs its cause, and the pool wait on Render is 60 s.

**Second run (the full 20,000-request run, same free plan).** It stopped during setup, before any reservation was sent: one `POST /auth/token` came back `HTTP 520`, a status the proxy in front of a Render service writes when it gets no valid answer from the origin.
The service never writes it (it stamps `X-Request-Id` on every response it produces, and its `/metrics` showed no 5xx), and I could not establish the cause from the outside. Follow-ups: minting a token is stateless, so the burst tool now repeats a transient setup answer
(5xx, 429, a dropped connection) up to six times and reports it as a warning; during the storm every 5xx still fails the run, and the report says how many carried no `X-Request-Id` (made by a proxy) and how many came from the service;
and Tomcat's idle keep-alive went from 60 s to 130 s, because Render's guidance for intermittent `Connection reset by peer` errors says to keep it above 120 s - a precaution, not a proven cause.

**Since then.** I have no complete 20,000-request result from the free plan: at roughly 40 requests per second the ~42,000 calls of a full run take well over ten minutes. Running it to completion on a paid plan is the outstanding step.

### 6.6 What the AI's own review caught

The idempotency-retry race described in section 2 was found by reasoning about what the duplicate-request mix in the burst test would hit; the final read-through found that cancel's `UPDATE` locked seats in index order rather than `ord`,
which broke the "one global lock order" claim in section 1 (fixed with an explicit ordered `SELECT ... FOR UPDATE` before the release); the Spring Initializr default parent was a `SNAPSHOT` build and was pinned to a released version so a clean clone keeps building;
an unused public route and an unneeded test assertion were removed.

### 6.7 Git history

I asked that no commit carry an AI co-author trailer: I am the author of record, and this section is where the AI's role is disclosed. The AI wrote the code in one long session and assembled the history *afterwards*: it replayed the finished working tree as 15 milestone commits
(schema, platform, engine, API, tests, burst tool, Docker/CI, docs), each of which compiles on its own against the sandbox stubs. The commits therefore all carry timestamps from within a few minutes of each other and are a readable reconstruction of the build order,
not a log of when the work happened. Every commit after those 15 (fixes from the real runs) is genuinely incremental.

### 6.8 What I would not trust without checking

Library version specifics (Spring Boot 4 / Jackson 3 API details), PostgreSQL behaviour that the AI described from documentation rather than observed (this is why the concurrency tests run against a real database), and any number in a doc that was not produced by a run.

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
