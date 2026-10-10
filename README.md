# bookshelf-api

A small, local-only Clojure service for learning how a REST API fits together.
It manages books and authors (with three starter authors), and includes a Redis subscriber that
maintains a PostgreSQL books-per-author rollup. Everything is built from public
libraries; there are no cloud SDKs, cloud credentials, or deployment pipelines.

## Run everything

Install Docker with Compose, start its daemon, then run:

```sh
docker compose up
```

Compose builds the application on first run and starts PostgreSQL, Redis, the API,
and the worker. A one-shot `migrate` service must finish successfully before the
API and worker start. The initial image and dependency downloads need internet access;
the running service uses only local containers. Wait for the API to become healthy:

```sh
curl http://localhost:3000/api/v1/health
```

Open [Swagger UI](http://localhost:3000/swagger-ui) to explore the API. The schema
is at [swagger.json](http://localhost:3000/swagger.json). Swagger assets are bundled
in the application, and the external Swagger validator is disabled.

All published ports bind to `127.0.0.1`. The API binds to `0.0.0.0` **inside** its
container so Docker can reach it; direct host runs default to `127.0.0.1`.
Passwords in this repository are deliberately throwaway local passwords.

```sh
docker compose logs -f api worker
docker compose down       # stop; retain books
docker compose down -v    # stop and DELETE this project's database volume
docker compose up --build # rebuild after source changes
```

## Try the endpoints

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/api/v1/health` | Readiness: PostgreSQL and enabled Redis; 503 on failure |
| GET | `/api/v1/health/live` | Process liveness; no dependency checks or request-executor admission |
| GET, POST | `/api/v1/authors` | List or create authors (201 + Location) |
| GET, PUT, DELETE | `/api/v1/authors/:id` | Read, rename, or delete an author; 409 if books reference it |
| GET | `/api/v1/books` | Paginated books with optional filters and sorting |
| POST | `/api/v1/books` | Create; 201 with a `Location` header |
| GET | `/api/v1/books/:id` | Read; 404 if absent |
| PUT | `/api/v1/books/:id` | Replace all writable fields; 404 if absent |
| DELETE | `/api/v1/books/:id` | Delete; 204 with no body, or 404 |
| GET | `/api/v1/authors/book-counts` | Read counts and `refreshed-at` (UTC timestamp, or null before first refresh) |

```sh
curl http://localhost:3000/api/v1/authors

curl -i -X POST http://localhost:3000/api/v1/books \
  -H 'Content-Type: application/json' \
  -d '{"title":"A Wizard of Earthsea","author-id":1,"published-year":1968}'

# Replace 1 below with the ID returned by POST.
curl http://localhost:3000/api/v1/books/1
curl -X PUT http://localhost:3000/api/v1/books/1 \
  -H 'Content-Type: application/json' \
  -d '{"title":"The Left Hand of Darkness","author-id":1,"published-year":1969}'

curl http://localhost:3000/api/v1/authors/book-counts
curl -i -X DELETE http://localhost:3000/api/v1/books/1
```

JSON uses kebab-case keys. Books require all three writable fields for POST and
PUT. Titles must contain non-whitespace and have at most 300 characters; years
are integers from 1 through 9999; IDs are positive signed 64-bit integers. Unknown
body fields, invalid IDs, malformed JSON, and invalid bodies return 400. A missing
author returns 422. Deleting an author that still has books returns 409; deleting
an author without books also removes its rollup row. Unique violations return
409; not-null, check, length, and numeric-range violations return 422. Other SQL
errors return a generic 500. SQL details are not exposed in responses. Author
names must be nonblank and at most 300 characters; names need not be unique.
Responses are schema-checked too. The first migration seeds Ursula K. Le Guin,
Octavia E. Butler, and Jane Austen.

```sh
curl -X POST http://localhost:3000/api/v1/authors \
  -H 'Content-Type: application/json' -d '{"name":"New author"}'
curl 'http://localhost:3000/api/v1/books?limit=10&offset=0&author-id=1&title=earth&sort=published-year&order=desc'
```

Book list query parameters: `limit` (1–100, default 20), `offset` (nonnegative,
default 0), `author-id` (exact match), `title` (case-insensitive literal substring),
`sort` (`id`, `title`, or `published-year`, default `id`), and `order` (`asc` or
`desc`, default `asc`). Results remain a JSON array. Non-ID sorts use ascending
ID as a stable tie-breaker. Unknown parameters and invalid values return 400.
Offset pagination can shift when concurrent writes occur; it is intended for
this small local dataset.

## HTTP middleware

Reitit middleware wraps the complete handler, including Swagger, errors, and
unmatched routes. Each response has `X-Request-ID`: an incoming ID containing
1–128 letters, digits, dots, underscores, or hyphens is retained; otherwise a UUID
is generated. An access log records that ID, method, path, status, and elapsed
milliseconds. Bodies and query strings are not logged.

CORS is an explicit origin allowlist, empty by default. Compose allows
`http://localhost:5173` as a local development frontend example. Set
`CORS_ALLOWED_ORIGINS='["http://localhost:5173"]'` to configure it on the host.
Allowed preflights receive 204; denied origins, methods, or headers receive 403.
Responses to allowed origins expose `X-Request-ID` and `Location`; credentials
are not enabled. CORS governs browser access and is not authentication.

`REQUEST_TIMEOUT_MS` defaults to 5000. Matched API routes run on a bounded pool of
`REQUEST_THREADS` (default 8), with no waiting queue. A timed-out request returns
504 and its task is interrupted; a saturated pool returns 503. These responses
also include request IDs, access logs, and CORS headers. The GET liveness route
opts out through trusted Reitit route data: it runs on the Jetty thread, retains
JSON encoding and the outer middleware, and stays 200 when the request executor
is full. Readiness still uses the executor and may return 503 under load. Static
Swagger UI assets and unmatched-route responses also run outside that executor.

Every new API database connection gets PostgreSQL `statement_timeout` set to
90% of `REQUEST_TIMEOUT_MS`, rounded down (4500 ms by default). Hikari's connection
initialization SQL also applies it to replacement connections. PostgreSQL cancels
overlong statements server-side, and SQLSTATE `57014` maps to a JSON 504 without
exposing SQL details. Worker and migration connections retain the database server's
timeout setting because they have no HTTP deadline. See the public
[PostgreSQL timeout documentation](https://www.postgresql.org/docs/18/runtime-config-client.html#GUC-STATEMENT-TIMEOUT)
and [HikariCP connection initialization options](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby).

The database pool and request executor both default to 8. Each current request
uses at most one database connection at a time; this avoids a smaller pool forcing
otherwise admitted requests to wait for a connection. Each process has its own
pool, so API and worker connection budgets add up. Tune `DATABASE_POOL_SIZE` and
`REQUEST_THREADS` together: a smaller pool is allowed but introduces contention
and a possible pool acquisition failure after 2 seconds. This is a simple local
default, not a database throughput tuning recommendation.

The statement limit applies per SQL statement, not to the whole request. Connection
acquisition, multiple statements, or other blocking work can still outlive the HTTP
deadline; thread interruption remains best effort. A timed-out write may already
have committed, so check the resource before retrying. The HTTP timeout bounds
synchronous handler work, not subsequent network transmission of the response.

## Follow one request through the code

```text
HTTP request
  -> Jetty converts HTTP into a Ring request map
  -> Reitit matches /api/v1/books/:id and its HTTP method
  -> Muuntaja negotiates JSON and decodes the request body
  -> Malli coerces path IDs and validates the body
  -> feature handler chooses the operation
  -> HoneySQL turns a Clojure query map into parameterized SQL
  -> next.jdbc executes it using a connection from HikariCP
  -> PostgreSQL enforces constraints and returns rows
  -> handler returns a Ring {:status ... :body ...} map
  -> Malli validates the response; Muuntaja encodes JSON
  -> Jetty writes the HTTP response
```

The middleware order matters: response encoding surrounds exception handling,
which surrounds body decoding and coercion. Consequently even decoding and
validation failures can be encoded as JSON. Data values become SQL parameters,
never SQL string concatenation. Database `author_id` becomes Clojure/JSON
`:author-id` through next.jdbc's result-set builder.

```text
src/bookshelf_api/
  core.clj                    main entry point and shutdown hook
  lib/config.clj              cprop environment overrides + config validation
  lib/system.clj              acquire resources, run selected role, stop
  lib/http.clj                Reitit, Swagger, error handling
  lib/middleware.clj          request IDs, access logs, CORS, bounded timeouts
  lib/events.clj              Jedis publisher and reconnecting subscriber
  db/pool.clj                 HikariCP connection pool
  db/migrations.clj           locked Ragtime migration command
  db/queries.clj              HoneySQL + next.jdbc CRUD and rollup queries
  features/health/            handlers.clj + schemas.clj
  features/books/             handlers.clj + schemas.clj
  features/authors/           handlers.clj + schemas.clj
resources/
  config.edn                  local defaults
  logback.xml                 console logging
  db/migrations/              paired up/down SQL files
test/bookshelf_api/           handler and real PostgreSQL/Redis tests
```

Dependencies are passed into handlers explicitly; no global connection pool is
hidden in a feature namespace. `system/start!` runs Ragtime only for
`APP_ROLE=migrate`; API and worker roles never apply migrations. Compose gates
both services on the migration service's successful exit. A transaction-scoped
PostgreSQL advisory lock also serializes accidentally concurrent migration
commands; SQL and Ragtime history commit together. Shutdown stops the
server/subscriber and request executor, then closes the pool. These lifecycle
functions are usable from a REPL.

Migration `002` upgrades an existing database without replacing books or authors.
It adds a nullable `refreshed_at` column (exposed as `refreshed-at`) and cascades
author deletion to its derived rollup row; book foreign keys still restrict
author deletion. The timestamp remains null for old rows until a worker refresh.
PostgreSQL 18 stores data beneath `/var/lib/postgresql/18/docker`; the existing
volume mount at `/var/lib/postgresql` is intentional. See the
[official image documentation](https://github.com/docker-library/docs/blob/master/postgres/README.md#pgdata).

## Worker and delivery semantics

`APP_ROLE=worker` starts a dedicated Jedis pub/sub subscriber instead of Jetty.
Successful API writes publish a small `refresh` signal on `bookshelf:changed`
after the database statement commits. Each signal recomputes every author's
count, including zero counts, and stores the refresh statement time in one
PostgreSQL transaction. `refreshed-at` indicates the age of the rollup snapshot;
it is not an exact measurement of event-delivery lag. Readers see a
complete old or new rollup. A separate advisory lock serializes rollup writers.

On subscription and reconnection, the worker recomputes the rollup to repair
events missed while it was offline. Connection or refresh failures trigger a
retry after one second. Duplicate signals are harmless. This is deliberately a
small **eventually consistent, best-effort** example: Redis pub/sub is not a durable
queue, and the DB write and publication are not atomic. Failed publication does
not fail an already committed CRUD operation. Counts may remain stale until the
next successful event or worker reconnect. To force repair:

```sh
docker compose restart worker
```

With Redis disabled, CRUD still works but rollups do not update. A production
service needing guaranteed delivery would use a transactional outbox and a
durable consumer; that is outside this learning example.

## Configuration and host development

The container build pins Leiningen 2.13.0 and uses Java 21. For host development,
install Java 21+ and Leiningen 2.13.0. cprop loads `resources/config.edn`, then
applies matching system properties and environment variables (environment wins).
Single underscores in environment names map to hyphens in Clojure keys.

| Environment variable | Host default |
| --- | --- |
| `APP_ROLE` | `api` (`api`, `worker`, or `migrate`) |
| `HTTP_HOST` | `127.0.0.1` |
| `HTTP_PORT` | `3000` |
| `REQUEST_TIMEOUT_MS` | `5000` (2–120000; allows a positive, shorter SQL timeout) |
| `REQUEST_THREADS` | `8` (1–64) |
| `CORS_ALLOWED_ORIGINS` | `[]` (EDN vector of exact HTTP(S) origins) |
| `DATABASE_URL` | `jdbc:postgresql://127.0.0.1:5432/bookshelf` |
| `DATABASE_USER` | `bookshelf` |
| `DATABASE_PASSWORD` | `bookshelf-local-only` |
| `DATABASE_POOL_SIZE` | `8` (matches the default request executor) |
| `REDIS_ENABLED` | `false` |
| `REDIS_URL` | `redis://:redis-local-only@127.0.0.1:6379/0` |
| `REDIS_CHANNEL` | `bookshelf:changed` |

Stop the Compose API and worker first if already running, then:

```sh
docker compose stop api worker
docker compose up -d postgres redis
APP_ROLE=migrate lein run
REDIS_ENABLED=true lein run
# In another terminal:
APP_ROLE=worker REDIS_ENABLED=true lein run
# Or run just the migrations and exit:
APP_ROLE=migrate lein run
```

If the default host ports are already in use, override them in your shell or a
local `.env` file (ignored by Git). The loopback bind and container ports stay
unchanged; services still connect to `postgres:5432`, `redis:6379`, and API port
3000 internally.

| Compose variable | Default host port |
| --- | --- |
| `POSTGRES_HOST_PORT` | `5432` |
| `REDIS_HOST_PORT` | `6379` |
| `API_HOST_PORT` | `3000` |

```sh
export POSTGRES_HOST_PORT=15432 REDIS_HOST_PORT=16379 API_HOST_PORT=13000
docker compose up -d --build
curl "http://127.0.0.1:${API_HOST_PORT}/api/v1/health"

# Host tests must use the published database ports:
DATABASE_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_HOST_PORT}/bookshelf" \
REDIS_URL="redis://:redis-local-only@127.0.0.1:${REDIS_HOST_PORT}/0" \
lein test :all
```

If `TEST_DATABASE_URL` is set, update it too: it takes precedence for integration
tests. A Compose `.env` file does not export these variables into your host shell;
export them as above or put the actual port numbers in the host URLs. Container
tests need no URL changes. Swagger UI uses the published API port, for example
`http://localhost:13000/swagger-ui/`. The smoke script discovers that port from
`docker compose port api 3000`, including overrides from `.env`.

## Tests

Handler tests exercise the real router, JSON middleware, coercion, and Swagger
with database functions stubbed. Integration tests use real PostgreSQL and Redis;
they cover migration upgrades/rollback and concurrent migration commands, author
and book CRUD, constraints, pagination/filtering/sorting, rollup timestamps, and
subscriber event delivery. A test-only TCP proxy drops Redis connections and
refuses reconnects, then restores connectivity; the subscriber must repair a
write made during the outage without receiving another event. This avoids
interrupting anyone else's Redis connections. Unit tests cover config validation,
migration-role selection, readiness/liveness, SQL errors, CORS, request IDs,
timeouts, and liveness during pool saturation. Real PostgreSQL tests verify
server-side query cancellation, HTTP 504 mapping, capacity recovery, timeout
initialization on multiple/replacement connections, and separate background-role
settings.

```sh
lein test                       # unit/handler tests; no services required
docker compose up -d postgres redis
lein test :all                  # handler + real database/Redis tests
# Or use only Docker, including the exact pinned Leiningen version:
docker compose --profile test run --build --rm test
# Repeat the full suite on Java 27 (the runtime image remains Java 21):
TEST_JDK_IMAGE=eclipse-temurin:27-jdk docker compose --profile test run --build --rm test
# Full HTTP checks, including actual Redis/PostgreSQL stop/start (Python 3):
python3 scripts/smoke.py
```

The smoke script briefly stops the local Compose Redis and PostgreSQL services,
checks readiness/liveness, recovery, and persistence, then restarts them and
removes its own test books/authors. Run it when interrupting local requests is OK.

The middleware regression test checks that executor submission preserves the
complete handler response and dynamic bindings. Run it without the integration
tests using `lein test :only bookshelf-api.middleware-test/timeout-preserves-handler-response-and-bindings`.
The Java 21 and Java 27 Docker commands above exercise it on both JDKs. To check
compiler reflection warnings, run `lein check`; the timeout's `submit` call must
resolve to the `Callable` overload without reflection.

CRUD and migration integration tests create a random PostgreSQL schema, use
`currentSchema` to isolate it, and drop only that schema in `finally`. Pool tests
use read-only queries and do not create application data. Redis test channels also
have random names. `TEST_DATABASE_URL` optionally overrides the integration DB
URL; credentials come from `DATABASE_USER` and `DATABASE_PASSWORD`. Tests require
permission to create schemas. Missing services make integration tests fail;
they are never silently skipped. No application rows are deleted by tests.

## Public references and versions

Direct dependency versions were checked against Clojars/Maven Central on
2026-10-09 and pinned in `project.clj`: Clojure 1.12.6, Ring 1.15.5, Reitit 0.11.0,
Muuntaja 0.6.12, Jsonista 1.0.1, Malli 0.20.3, next.jdbc 1.3.1118, HoneySQL 2.7.1479, HikariCP 7.1.0,
PostgreSQL JDBC 42.7.14, Ragtime 0.12.1, cprop 0.1.21, Logback 1.6.5, Jedis 8.0.2,
tools.logging 1.3.1, and ring-mock 0.6.2. Compose follows stable PostgreSQL 18 and
Redis 8 image tags; those tags can receive patch updates.

- [Clojure](https://clojure.org/reference/documentation) and [Leiningen](https://leiningen.org/)
- [Ring](https://github.com/ring-clojure/ring)
- [Reitit Malli/Swagger example](https://github.com/metosin/reitit/tree/0.11.0/examples/ring-malli-swagger)
- [Muuntaja](https://github.com/metosin/muuntaja) and [Malli](https://github.com/metosin/malli)
- [next.jdbc](https://cljdoc.org/d/com.github.seancorfield/next.jdbc/1.3.1118/doc/getting-started)
- [HoneySQL](https://cljdoc.org/d/com.github.seancorfield/honeysql/2.7.1479/doc/getting-started)
- [HikariCP](https://github.com/brettwooldridge/HikariCP) and [PostgreSQL JDBC](https://jdbc.postgresql.org/documentation/)
- [Ragtime](https://github.com/weavejester/ragtime) and [cprop](https://github.com/tolitius/cprop)
- [Logback](https://logback.qos.ch/manual/) and [Jedis](https://redis.github.io/jedis/)

This example intentionally has no authentication or cloud/deployment
configuration. Keep the running service local.

## License

Copyright (c) 2026 Mathieu Gauthron. Released under the [MIT License](LICENSE).
Third-party dependencies retain their own licenses.
