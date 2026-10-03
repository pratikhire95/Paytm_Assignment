# Seat Reservation Service

A JSON HTTP service that sells numbered seats for a show and decides, atomically and correctly, who gets each
one - even when thousands of buyers stampede the same seat at on-sale time.

Spring Boot 4 · Java 17+ (runs on 21 in Docker) · PostgreSQL · plain JDBC with hand-written SQL.
Design notes, trade-offs and the AI-usage disclosure are in **[WRITEUP.md](WRITEUP.md)**.

| | |
|---|---|
| **Live URL** | https://seat-reservation-zepg.onrender.com |
| Readiness / liveness | [/readyz](https://seat-reservation-zepg.onrender.com/readyz) · [/healthz](https://seat-reservation-zepg.onrender.com/healthz) |
| Metrics (Prometheus text) | [/metrics](https://seat-reservation-zepg.onrender.com/metrics) |
| Recent logs (JSON lines, public) | [/logs?limit=200](https://seat-reservation-zepg.onrender.com/logs?limit=200) · `/logs?request_id=<id>` |
| Burst test | `ADMIN_TOKEN=<token> ./burst.sh https://seat-reservation-zepg.onrender.com` (the admin token is handed over with the submission, see below) |
| Postman collection | [`postman/seat-reservation.postman_collection.json`](postman/seat-reservation.postman_collection.json): import it, set the `adminToken` variable, **Run collection** |

## What it guarantees

- **A seat is never sold twice.** For a hot seat with N contenders exactly one gets `201`; the rest get a clean `409 seat_taken`.
- **No 5xx for domain outcomes.** Declines are `4xx`. Database trouble is `503 + Retry-After`, never a stack trace.
- **`available + held + confirmed == total_seats`** in every response of `GET /shows/{id}` (one snapshot).
- **Idempotent retries.** Same `Idempotency-Key` + same seats returns the original reservation; same key + different seats is `409`.
- **Per-user limit** (default 4 seats per show) holds under concurrency.
- **Identity comes from the bearer token only.** A `user_id` in a request body is ignored; only the owner can cancel.
- **Multi-seat requests are all-or-nothing.** If any requested seat is taken nothing is booked.
- Money is integer paise.

## Testing it (for reviewers)

All you need is the live URL above and the admin token that came with the submission. The token only guards `POST /shows`; users need no secret.

| Way | What it shows |
|---|---|
| `ADMIN_TOKEN=<token> ./burst.sh https://seat-reservation-zepg.onrender.com` | The stampede: 20,000 reservations from 20,000 users with a hot-seat storm, racing retries and cancel/re-book churn, then the reconciliation against `GET /shows/{id}` and `/metrics`. Needs a JDK 11+ (or Docker). `--quick` is a 2,000-request smoke run. Details in "The burst test" below. |
| [`postman/seat-reservation.postman_collection.json`](postman/seat-reservation.postman_collection.json) | Import it into Postman, open the collection > **Variables**, paste the admin token into `adminToken` (Current value), press **Run collection**. Every request carries assertions: auth, create, reserve, idempotent replay, key conflict, seat taken, all-or-nothing, per-user limit, a spoofed `user_id`, cancel and re-book, the `available + held + confirmed == total_seats` invariant, metrics and logs. The requests run one after another, so this shows the contract; concurrency is what the burst is for. Each run uses fresh ids, so it can be repeated. |
| The curl walkthrough under "API" | The same steps by hand. |
| `make up`, then `./burst.sh http://localhost:8080` | A private copy on your own machine (Docker). |

To drive it with your own load tool: mint a token per user with `POST /auth/token {"user_id":"u123"}` (public, no secret), create the show once with the admin token,
then send `POST /shows/{id}/reserve` with `Authorization: Bearer <user token>` and an `Idempotency-Key` header. Status codes and error codes are in the "API" tables.

## Quick start

Needs Docker (with compose), `make`, `openssl`, and a JDK 11+ for the burst script.

```bash
make up            # creates .env with random secrets on first run, builds, starts service + PostgreSQL, waits for /readyz
make burst-quick   # 2,000-request stampede + reconciliation
make burst         # the full 20,000-request run
make logs          # follow the structured JSON logs
make down
```

Without `make`: copy `.env.example` to `.env`, fill the three secrets (`openssl rand -hex 32`), run
`docker compose up -d --build`, then `./burst.sh http://localhost:8080`.

Without Docker (macOS with Homebrew; on Linux install PostgreSQL with your package manager):

```bash
brew install postgresql@16 && brew services start postgresql@16
export PATH="$(brew --prefix postgresql@16)/bin:$PATH"
createdb seats && createdb seats_test        # seats_test is a scratch database for the tests

cd seatManagement
export DATABASE_URL=jdbc:postgresql://localhost:5432/seats DB_USER=$(whoami)   # Homebrew's default role has no password
export ADMIN_TOKEN=$(openssl rand -hex 32) TOKEN_SECRET=$(openssl rand -hex 48)
./mvnw spring-boot:run                       # Flyway creates the tables on start-up; http://localhost:8080/readyz

# all tests, including the PostgreSQL concurrency tests (they write freely into the database they are pointed at):
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/seats_test TEST_DB_USER=$(whoami) ./mvnw test
```

With a remote or password-protected PostgreSQL use `DATABASE_URL=postgresql://user:password@host:5432/dbname` instead (see "The database" below).

The schema is created by Flyway at start-up. The service **refuses to start** without `ADMIN_TOKEN` (16+ chars),
`TOKEN_SECRET` (32+ chars) and a database - there are no default secrets.

## Authentication

| Who | How |
|---|---|
| Users | `POST /auth/token` with `{"user_id":"alice"}` returns `{"token": "..."}`. Send `Authorization: Bearer <token>`. Tokens are HMAC-signed and expire (24 h). |
| Admin (only `POST /shows`) | `Authorization: Bearer <ADMIN_TOKEN>`. Locally it is in `.env`; for the live deployment it is handed over **with the submission**, not in this repository. |

`POST /auth/token` stands in for an identity provider: anyone may ask for a token for any user id. What the
service guarantees is that a request is only ever executed as the user *named inside the verified token*.
Switch the endpoint off with `ALLOW_TOKEN_MINT=false` when real tokens come from elsewhere.

## API

All bodies are JSON. Every response carries `X-Request-Id`; errors look like
`{"error":{"code":"seat_taken","message":"...", ...},"request_id":"..."}`.

| Method & path | Auth | Purpose |
|---|---|---|
| `POST /shows` | admin | `{"name":"friday-night","seats":["A1","A2"],"price_paise":25000,"per_user_limit":4}` creates a show, every seat `available`. Returns `201`. |
| `GET /shows/{id}` | public | Per-seat status plus counts. `?seats=false` returns counts only (cheap, good for polling). |
| `POST /shows/{id}/reserve` | user | `{"seats":["A12"]}` plus an idempotency key (`Idempotency-Key` header, `X-Idempotency-Key` header or `"idempotency_key"` body field). |
| `POST /reservations/{id}/cancel` | owner | Releases the seats. Repeating it is harmless. |
| `POST /auth/token` | public | Mint a user token (see above). |
| `GET /healthz` · `GET /readyz` · `GET /metrics` · `GET /logs` | public | Operations. |

```bash
BASE=http://localhost:8080
ADMIN=$(grep ^ADMIN_TOKEN= .env | cut -d= -f2)

SHOW=$(curl -s -X POST $BASE/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A4"],"price_paise":25000}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
TOKEN=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

curl -s -X POST $BASE/shows/$SHOW/reserve -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-1001' -d '{"seats":["A1","A2"]}'
# 201 {"reservation_id":"...","show_id":"...","user_id":"alice","seats":["A1","A2"],"amount_paise":50000,"status":"confirmed",...}
curl -s $BASE/shows/$SHOW?seats=false
```

### Responses

| Status | When | `error.code` |
|---|---|---|
| `201` | reservation created | |
| `200` + `Idempotent-Replay: true` | same key and same seats seen before: the original reservation is returned | |
| `409` | seat already taken (names the seats) | `seat_taken` |
| `409` | would exceed the per-user limit (nothing booked) | `per_user_limit` |
| `409` | key reused with different seats | `idempotency_key_conflict` |
| `400` | malformed JSON, bad seat list, missing key, fractional price ... | `invalid_request`, `invalid_json`, `idempotency_key_required` |
| `401` / `403` | missing or invalid token / not allowed (non-admin creating a show, non-owner cancelling) | `unauthorized`, `forbidden` |
| `404` · `422` | unknown show or reservation · seat label that is not part of the show | `show_not_found`, `reservation_not_found`, `unknown_seat` |
| `503` + `Retry-After` | the database is unavailable or a lock wait timed out - retry | `service_unavailable` |

### Behaviour worth knowing

- **Partial requests are all-or-nothing.** Asking for `["A12","A13"]` when A13 is taken returns `409 seat_taken` (listing `A13`) and books nothing, also under concurrency.
- **A declined request leaves no trace**, so its idempotency key is not consumed: retrying it re-evaluates it. A key is bound to a request only once a reservation exists.
- **Release is an explicit cancel** (owner only). A released seat is immediately bookable again; a stale or repeated cancel can never free a seat that has since been sold to someone else. The per-user limit counts seats currently owned, so cancelling gives the quota back.
- **Replays reflect current state**: replaying the key of a since-cancelled reservation returns it with `"status":"cancelled"`.

## Observability

**Metrics** (`GET /metrics`, Prometheus text format):

| Metric | Meaning |
|---|---|
| `reservations_confirmed_total` | reservations created (`201`); incremented only after the transaction commits |
| `reservations_declined_total{reason="seat_taken\|per_user_limit\|idempotent_replay\|idempotency_key_conflict"}` | requests that created nothing, by reason |
| `reservations_cancelled_total` | cancellations |
| `seats_available{show_id}` · `seats_held` · `seats_confirmed` · `seats_total` | gauges read from the database at scrape time (one statement, one snapshot) for the 10 most recent shows, so they always equal `GET /shows/{id}` |
| `server_errors_total{type}` | 5xx by cause - should stay 0 |
| `http_requests_total{method,route,status}` · `http_request_duration_seconds` | traffic and latency histogram per route template |
| `db_pool_connections{state}` · `db_pool_threads_awaiting` · `jvm_*` · `process_*` | saturation signals |

Counters are per process (they reset on restart; use `rate()` / `increase()`); the seat gauges come from the database, so they are correct across several instances.

**Logs**: one JSON object per line on stdout (Spring Boot structured logging) with `request_id`, `user_id`, `route`, `status`, `duration_ms`, `outcome`.
The same events are readable without a platform login at `GET /logs?limit=200` (newest last) and `GET /logs?request_id=<value of the X-Request-Id response header>` (all lines of one request).
Tokens are never logged; the public endpoint carries only a safe field subset (no stack traces). Switch it off with `PUBLIC_LOGS_ENABLED=false`.

**Health**: `/healthz` only says the process answers (so a database outage does not cause restart loops). `/readyz` performs a real `SELECT 1` on a separate small pool and returns `503` when it fails or while shutting down - it fails closed.

## The burst test

```bash
./burst.sh http://localhost:8080                      # ADMIN_TOKEN is read from .env for local runs
ADMIN_TOKEN=<token> ./burst.sh https://<live-url>     # against a deployment
./burst.sh <url> --quick | --one-seat | --requests 50000 --concurrency 3000 | --help
```

It needs only a JDK 11+ (a single dependency-free Java file, `scripts/Burst.java`; falls back to Docker if there is no JDK). It:

1. waits for `/readyz` (a cold start is waiting, not failing);
2. runs ~20 functional probes on a small show (auth, contract, idempotency, per-user limit under concurrency, all-or-nothing, cancel, validation);
3. fires **20,000 reserve requests from 20,000 distinct users**, up to 1,500 in flight: 20 hot seats take 60% of the traffic, 12% ask for 2-3 seats, 10% are sent twice at once (a client retry racing the original);
4. has owners cancel (twice, concurrently) 300 reservations while fresh users race to re-book the very same seats;
5. prints the outcome distribution (201 / 200 replay / 409 by reason / 5xx / connection failures, latency percentiles) and **reconciles the clients' ledger against `GET /shows/{id}` and against `/metrics`**: no seat sold twice, every retry replays its original, every seat someone asked for alone ended up sold, `available + held + confirmed == total_seats`, final seat map == bookings - cancellations + re-bookings, metric deltas == what the clients saw, `seats_available` gauge == API.

Exit code `0` = all checks passed, `1` = a correctness check failed, `2` = it could not run. Use `--lenient-metrics` when other traffic hits the same
service (metric deltas then become warnings). Connection-level failures (stale keep-alive connections) are retried with the same idempotency key and reported as a warning.
Minting the 20,000 tokens is setup and has no side effects, so a transient answer there (a proxy's `520`/`502`/`504`, a `503`, a `429`, a dropped connection) is repeated up to six times and reported as a warning;
only a request that still fails aborts the run (exit `2`). During the storm itself every 5xx stays a failure, and the report says how many of them carried no `X-Request-Id` header: the service stamps one on every response it writes,
so a 5xx without it was produced by a proxy in front of the service.

## Tests

```bash
make test       # unit tests; the PostgreSQL-backed ones are skipped without a database
make test-db    # everything, including the concurrency tests, against the compose PostgreSQL (database seats_test; needs Docker)
```

`ReservationConcurrencyTest` (real PostgreSQL, many threads): 300 users on one seat -> exactly one winner; 20 hot seats x 20 contenders while a watcher thread checks the invariant on every snapshot;
overlapping multi-seat requests in opposite orders (no deadlock, all-or-nothing); one user firing 10 parallel requests at limit 4; 30 parallel retries of one key; concurrent duplicate cancels; stale cancel vs re-sale;
an 800-operation random mix of reserves and cancels, then database-level checks that seats, reservations and per-user counters agree.
`ApiSmokeTest` exercises the HTTP contract end to end. The rest are plain unit tests (token forging/expiry, route table, validation, JSON/metrics rendering, configuration parsing).
CI (`.github/workflows/ci.yml`) runs all of it against PostgreSQL, then builds the Docker image and runs the burst test against the composed stack.

## The database

The service needs one **empty PostgreSQL database** (14+; CI and Docker use 16) and nothing else. **It creates its own tables:** Flyway runs `V1__init.sql` when the service starts, under a database lock,
so you never run SQL by hand and several instances may start at once. Hand it the connection through `DATABASE_URL`, in any of these forms (credentials may be URL-encoded):

- `postgresql://user:password@host:5432/dbname` - what Render and most hosts give you (`postgres://` works too; the port is optional);
- `jdbc:postgresql://host:5432/dbname` together with `DB_USER` and `DB_PASSWORD`;
- or no `DATABASE_URL` at all, but `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.

Add `?sslmode=require` when the database is reached over the public internet (not needed on a provider's private network). Connect **directly**, not through a transaction-mode pooler (PgBouncer, Supabase's port-6543 pooler):
the service sets session timeouts on each connection and runs its own pool (`DB_POOL_SIZE`, default 20 - keep it below the database's connection limit; the Render free database allows 100).

## Deploying

### On Render (what `render.yaml` automates)

1. Push this repository to GitHub.
2. In the Render dashboard choose **New > Blueprint**, connect your GitHub account, click **Connect** next to this repository, keep the defaults and click **Deploy Blueprint**. Render reads `render.yaml` and creates
   - the PostgreSQL 16 database `seats-db`, and
   - the Docker web service `seat-reservation` (built from `seatManagement/Dockerfile`) with `DATABASE_URL` set to the database's internal connection string, `PORT=10000`, and `ADMIN_TOKEN` / `TOKEN_SECRET` generated for you.
3. Wait for the first build (a few minutes: Maven downloads its dependencies). The service is up when `https://<name>.onrender.com/readyz` answers `200`; the platform health check is `/healthz`.
4. Read the admin token: service `seat-reservation` > **Environment** > `ADMIN_TOKEN` (hand it over with the submission, never in the repository).
5. Check the deployment: `ADMIN_TOKEN=<token> ./burst.sh https://<name>.onrender.com --quick`, then the full run without `--quick`.
6. Paste the URL at the top of this file. Optionally add the repository variable `SERVICE_URL` (GitHub: Settings > Secrets and variables > Actions > Variables) so the keep-warm workflow can ping it.

Any other host that runs a Docker image and gives you a PostgreSQL URL works the same way: build `seatManagement/`, set `DATABASE_URL`, `ADMIN_TOKEN` (16+ chars), `TOKEN_SECRET` (32+ chars) and, if the platform does not inject it, `PORT`.

### Free tier facts (Render docs, checked 2 Oct 2026)

- **Web service, free plan:** 0.1 CPU, 512 MB. It spins down after 15 minutes without traffic and takes about a minute to spin back up, plus the JVM start. 750 free instance hours per workspace and month.
- **Database, free plan:** 256 MB RAM, 1 GB storage, no backups, at most 100 connections. It **expires 30 days after creation**, with 14 more days to upgrade it before it is deleted, and a workspace may have **only one** free database
  (if you already have one, delete it or give `seats-db` a paid plan in `render.yaml`).
- Paid plans: change `plan:` in `render.yaml` (web `0.5c-512mb` = 0.5 CPU / 512 MB, `1c-2g` = 1 CPU / 2 GB; database `0.1c-256mb`, `0.5c-1g`, ...) and push; Render redeploys. Upgrading the workspace plan alone does not lift free-instance limits.

**Cold starts.** A sleeping free service pays a full JVM start on the next request (a minute or more on 0.1 CPU).
`.github/workflows/keepwarm.yml` pings `/readyz` every 10 minutes if you set the repository variable `SERVICE_URL`; any uptime monitor works too.
The burst script and the Docker `HEALTHCHECK` are written to wait for this instead of failing.

**Capacity.** The free tier (0.1 CPU, 512 MB, free database) is enough to prove correctness but not to carry load: it serves roughly 40 requests per second, so a stampede queues up. Overload is answered with a `503 + Retry-After`, never with a wrong answer:
a request waits for one of the `DB_POOL_SIZE` database connections for up to `DB_CONNECTION_TIMEOUT_MS` (30 s by default, 60 s in `render.yaml`) and only then gets the 503. The server log line `database call failed, answering 503: cause=...`
says which limit was hit (`pool_timeout`, `lock_timeout`, `statement_timeout`, `connection_lost`, ...). A platform proxy may also answer very slow requests with `502/504`, or with a Cloudflare-style `520` when it loses its connection to the service; the service keeps idle connections open for 130 s (`TOMCAT_KEEP_ALIVE_TIMEOUT`),
longer than the 120 s that Render's troubleshooting guide recommends, so that it is never the side that closes a connection the proxy is about to reuse. The burst test counts every 5xx as a failure.
For the grading window use paid plans (see above): 20,000 open connections need memory as well as CPU, so give the web service more than 512 MB, and keep `DB_POOL_SIZE` below the database's connection limit.
A lower `--concurrency` (for example `./burst.sh <url> --quick --concurrency 60`) stays under the timeout on the free plan.

## Configuration (environment variables)

| Variable | Default | |
|---|---|---|
| `ADMIN_TOKEN` | **required**, 16+ chars | admin credential |
| `TOKEN_SECRET` | **required**, 32+ chars | HMAC key for user tokens |
| `DATABASE_URL` | **required** | `jdbc:postgresql://...` (then also `DB_USER`, `DB_PASSWORD`) or `postgres://user:pass@host:5432/db`; alternatively `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` |
| `PORT` | 8080 | injected by most platforms |
| `DB_POOL_SIZE` | 20 | JDBC connections per instance (the real concurrency limit; extra requests queue) |
| `DB_CONNECTION_TIMEOUT_MS` | 30000 (60000 in `render.yaml`) | max wait for a pooled connection before `503` |
| `DEFAULT_PER_USER_LIMIT` | 4 | used when a show does not specify `per_user_limit` |
| `TOKEN_TTL_SECONDS` | 86400 | |
| `ALLOW_TOKEN_MINT` | true | `POST /auth/token` |
| `PUBLIC_LOGS_ENABLED` | true | `GET /logs` |
| `MAX_SEATS_PER_SHOW` · `MAX_SEATS_PER_REQUEST` | 100000 · 100 | input bounds |
| `METRICS_MAX_SHOWS` | 10 | shows exported as seat gauges |
| `ACCESS_LOG_LEVEL` | INFO | `WARN` silences the per-request access line on very small instances |
| `TOMCAT_MAX_THREADS` · `TOMCAT_MAX_CONNECTIONS` · `TOMCAT_ACCEPT_COUNT` · `VIRTUAL_THREADS` | 400 · 30000 · 4096 · false | HTTP server sizing |
| `TOMCAT_KEEP_ALIVE_TIMEOUT` | 130s | how long an idle keep-alive connection stays open; keep it above the proxy's idle time (Render recommends 120 s) |
| `JAVA_OPTS` | see `Dockerfile` | JVM flags in the container |

## Repository layout

```
seatManagement/                       the service (Maven project)
  src/main/resources/db/migration/    V1__init.sql - schema (Flyway)
  src/main/java/.../domain/           ReservationService (the transaction) + ReservationRepository (every decisive SQL statement)
  src/main/java/.../api/              controllers, validation, response formats
  src/main/java/.../auth/             token service + deny-by-default AuthFilter
  src/main/java/.../observability/    metrics, request-id/access-log filter, /logs buffer, readiness
  src/test/java/...                   unit, concurrency (PostgreSQL) and HTTP tests
  Dockerfile
scripts/Burst.java · burst.sh         the one-command burst test
postman/                              Postman collection with assertions (import, set adminToken, Run collection)
docker-compose.yml · Makefile         local stack and shortcuts
render.yaml · .github/workflows/      deployment blueprint, CI, keep-warm
WRITEUP.md                            design notes
```
