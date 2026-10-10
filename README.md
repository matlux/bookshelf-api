# bookshelf-api

A small, local-only Clojure service for learning how a REST API fits together.
It manages books and three seeded authors, and includes a Redis subscriber that
maintains a PostgreSQL books-per-author rollup. Everything is built from public
libraries; there are no cloud SDKs, cloud credentials, or deployment pipelines.

## Run everything

Install Docker with Compose, start its daemon, then run:

```sh
docker compose up
```

Compose builds the application on first run and starts PostgreSQL, Redis, the API,
and the worker. The initial image and dependency downloads need internet access;
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
| GET | `/api/v1/health` | 200 when PostgreSQL is reachable; otherwise 503 |
| GET | `/api/v1/authors` | Seeded authors, ordered by ID |
| GET | `/api/v1/books` | All books, ordered by ID |
| POST | `/api/v1/books` | Create; 201 with a `Location` header |
| GET | `/api/v1/books/:id` | Read; 404 if absent |
| PUT | `/api/v1/books/:id` | Replace all writable fields; 404 if absent |
| DELETE | `/api/v1/books/:id` | Delete; 204 with no body, or 404 |
| GET | `/api/v1/authors/book-counts` | Read the worker's stored rollup |

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

JSON uses kebab-case keys. All three writable fields are required for POST and
PUT. Titles must contain non-whitespace and have at most 300 characters; years
are integers from 1 through 9999; IDs are positive signed 64-bit integers. Unknown
body fields, invalid IDs, malformed JSON, and invalid bodies return 400. A missing
author returns 422. Responses are also schema-checked; unexpected internal errors
return a generic 500 and are logged through Logback. There is no author write API;
the migration seeds Ursula K. Le Guin, Octavia E. Butler, and Jane Austen.

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
  lib/system.clj              acquire pool, migrate, start API or worker; stop
  lib/http.clj                Reitit, middleware, Swagger, error handling
  lib/events.clj              Jedis publisher and reconnecting subscriber
  db/pool.clj                 HikariCP connection pool
  db/migrations.clj           Ragtime startup migrations
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
hidden in a feature namespace. `system/start!` creates the pool and runs Ragtime
before starting Jetty or the worker. A PostgreSQL transaction-scoped advisory lock
serializes migrations when both roles start together. Migration SQL and Ragtime's
history commit together. Shutdown stops the server/subscriber and then closes
the pool. The same lifecycle functions are usable from a REPL.

## Worker and delivery semantics

`APP_ROLE=worker` starts a dedicated Jedis pub/sub subscriber instead of Jetty.
Successful API writes publish a small `refresh` signal on `bookshelf:changed`
after the database statement commits. Each signal recomputes every author's
count, including zero counts, in one PostgreSQL transaction. Readers see a
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
| `DATABASE_URL` | `jdbc:postgresql://127.0.0.1:5432/bookshelf` |
| `DATABASE_USER` | `bookshelf` |
| `DATABASE_PASSWORD` | `bookshelf-local-only` |
| `DATABASE_POOL_SIZE` | `5` |
| `REDIS_ENABLED` | `false` |
| `REDIS_URL` | `redis://:redis-local-only@127.0.0.1:6379/0` |
| `REDIS_CHANNEL` | `bookshelf:changed` |

Stop the Compose API and worker first if already running, then:

```sh
docker compose stop api worker
docker compose up -d postgres redis
REDIS_ENABLED=true lein run
# In another terminal:
APP_ROLE=worker REDIS_ENABLED=true lein run
# Or run just the migrations and exit:
APP_ROLE=migrate lein run
```

If ports 3000, 5432, or 6379 are already in use, change the host side of the Compose
port mappings and the corresponding host development environment values. Compose
services still use the internal service names and ports.

## Tests

Handler tests exercise the real router, JSON middleware, coercion, and Swagger
with database functions stubbed. Integration tests use real PostgreSQL and Redis;
they cover migrations twice, CRUD, constraints, author reassignment, atomic
rollups, and subscriber startup reconciliation and event delivery.

```sh
lein test                       # handler tests; no services required
docker compose up -d postgres redis
lein test :all                  # handler + real database/Redis tests
# Or use only Docker, including the exact pinned Leiningen version:
docker compose --profile test run --build --rm test
```

Each integration test creates a random PostgreSQL schema, uses `currentSchema`
to isolate it, and drops only that schema in `finally`. Redis test channels also
have random names. `TEST_DATABASE_URL` optionally overrides the integration DB
URL; credentials come from `DATABASE_USER` and `DATABASE_PASSWORD`. Tests require
permission to create schemas. Missing services make integration tests fail;
they are never silently skipped. No application rows are deleted by tests.

## Public references and versions

Direct dependency versions were checked against Clojars/Maven Central on
2026-10-09 and pinned in `project.clj`: Clojure 1.12.6, Ring 1.15.5, Reitit 0.11.0,
Muuntaja 0.6.12, Malli 0.20.3, next.jdbc 1.3.1118, HoneySQL 2.7.1479, HikariCP 7.1.0,
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

This example intentionally has no authentication, pagination, or cloud/deployment
configuration. Keep the running service local.

## License

Copyright (c) 2026 Mathieu Gauthron. Released under the [MIT License](LICENSE).
Third-party dependencies retain their own licenses.
