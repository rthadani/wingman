# Tech Decisions: Heuristics and Thresholds

This file gives concrete thresholds for "when to add component X" and "when to use Y vs Z." Use it during the Architecture phase and whenever you're making a recommendation.

**Core rule**: the burden of proof is on *adding* a component, not leaving it out. When in doubt, leave it out. You can add it later.

## Table of Contents
1. [The default stack](#the-default-stack)
2. [Database: SQL vs NoSQL vs Other](#database-sql-vs-nosql-vs-other)
3. [Blob storage](#blob-storage)
4. [When to add a cache](#when-to-add-a-cache)
5. [When to shard](#when-to-shard)
6. [When to add a message queue](#when-to-add-a-message-queue)
7. [When to add a background worker](#when-to-add-a-background-worker)
8. [When to add a search index](#when-to-add-a-search-index)
9. [When to add a distributed lock](#when-to-add-a-distributed-lock)
10. [When to go microservices](#when-to-go-microservices)
11. [Load balancing: L4 vs L7, client-side vs dedicated](#load-balancing-l4-vs-l7-client-side-vs-dedicated)
12. [API gateway](#api-gateway)
13. [Real-time: WebSocket vs SSE vs Polling](#real-time-websocket-vs-sse-vs-polling)
14. [Consistency: strong vs eventual](#consistency-strong-vs-eventual)
15. [Hosting: PaaS vs IaaS vs self-host](#hosting-paas-vs-iaas-vs-self-host)
16. [Auth: build vs buy](#auth-build-vs-buy)
17. [Reliability patterns](#reliability-patterns)
18. [Numbers to know (reference)](#numbers-to-know-reference)

---

## The default stack

For most new projects, start here and deviate only with reason:

- **Backend**: one language/framework the team knows well. Python (FastAPI/Django), Node (Next.js/Express), Go, Ruby (Rails), or whatever the team is fastest in.
- **Database**: Postgres. Almost always Postgres.
- **Object storage**: S3 or equivalent (R2, GCS).
- **Hosting**: a managed PaaS (Fly, Render, Railway, Vercel, or equivalent on AWS/GCP).
- **Auth**: a managed provider (Clerk, Auth0, Supabase Auth, WorkOS) unless there's a specific reason to DIY.
- **Frontend**: React (Next.js) or Svelte or whatever the team knows. Don't overthink.
- **Observability**: some managed combination of logs + metrics + errors (e.g., Datadog, Grafana Cloud, Honeycomb, Sentry). Free tiers are often enough.

This stack handles up to ~100K DAU and low-MB/sec traffic without any exotic additions. Most projects never outgrow it.

---

## Database: SQL vs NoSQL vs Other

### Default: **Postgres**

Use Postgres unless you have a specific reason not to. It supports:
- Relational data with joins and foreign keys
- JSON (`jsonb`) columns for semi-structured data
- Full-text search (good for <1M documents)
- Geospatial (PostGIS extension)
- pgvector for embeddings/RAG
- Time-series (with extensions like TimescaleDB)

A single Postgres instance handles low tens of thousands of TPS and multiple TB of data. That's more than most systems ever need.

### Reach for NoSQL (DynamoDB, Cassandra) when:
- You know access patterns upfront AND need horizontal scale from day one (>100K writes/sec)
- You need predictable single-digit-ms latency at massive scale
- You're willing to design everything around partition keys

Don't pick DynamoDB because "it scales" — pick it because your specific access patterns demand it.

### Reach for document DBs (MongoDB) when:
- Schema varies wildly per record (true schema-less)
- You explicitly don't need relational integrity

In practice, Postgres `jsonb` covers most "schema-less" needs with better tooling.

### Reach for specialized stores when:
- **Time-series at scale**: InfluxDB, TimescaleDB, QuestDB — when you have millions of points/sec
- **Graph traversals dominate**: Neo4j, JanusGraph — when the core query is "find all friends-of-friends within 3 hops"
- **Vectors / embeddings**: Pinecone, Weaviate, Qdrant, Postgres + pgvector — for RAG and semantic search
- **Analytics / OLAP**: ClickHouse, BigQuery, Snowflake — for aggregations over millions of rows interactively

### What to tell the user
State the choice in one sentence with the why:
> "Postgres. Your access patterns are relational (users → orders → items), scale is well within a single instance, and pgvector handles the embedding storage for RAG too."

---

## Blob storage

For images, videos, documents, and other large binary files. **Use object storage, not your primary database** — storing blobs in Postgres is expensive (per GB) and slow (fetches compete with real queries).

### Default: S3 (or equivalent)
- **AWS**: S3 ($0.023/GB/month)
- **Cloudflare R2**: S3-compatible, no egress fees
- **GCP**: Cloud Storage
- **Azure**: Blob Storage

Treat object storage as effectively infinite in capacity and durability. Store blobs there; store a URL or key reference in the DB.

### The presigned-URL pattern
For user uploads and downloads, don't proxy through your servers. Instead:

**Upload flow:**
1. Client asks your server for a presigned upload URL
2. Server generates the URL, records the intended object key in the DB with status "pending"
3. Client uploads directly to S3 using the presigned URL
4. S3 fires a webhook to your server on success → status "ready"

**Download flow:**
1. Client asks your server for the file
2. Server generates a presigned download URL (scoped, short-lived)
3. Client fetches from the CDN in front of S3

This keeps bandwidth off your app servers entirely. Your servers never touch the bytes.

### Chunked uploads for large files
For files > ~100 MB, use multipart upload. The client uploads chunks in parallel; partial failures can retry individual chunks rather than restarting. S3, R2, and GCS all support this natively via SDKs.

### When NOT to use blob storage as-is
- **Metadata and relationships** still live in your real DB with a URL pointing to the blob.
- **Sub-100-byte values** (settings, feature flags, tokens) belong in Redis or the DB, not object storage.

### What to tell the user
> "Media goes to R2 with presigned URLs — clients upload direct, we don't proxy bytes. Metadata stays in Postgres with the blob URL."

---

## When to add a cache

**Default: don't.** Add a cache only when you have a specific read-heavy hot path.

### Signals you need a cache
- **Measured DB read latency exceeds SLA.** Actual numbers, not speculation.
- **Same queries hit the DB repeatedly at high rate.** (Think: feed for a celebrity user, homepage for millions.)
- **CPU or connection saturation on the DB.**
- **You're doing expensive computation** (e.g., feed generation, rankings) that could be precomputed and cached.

### Tiers of caching, cheapest to most complex
1. **HTTP caching / CDN** — static assets, cacheable API responses. Basically free. Always on.
2. **In-process cache** — small values (config, feature flags) with LRU eviction. Zero infrastructure.
3. **Redis** — shared cache across app instances. The workhorse.
4. **Multi-layer cache** (in-process + Redis) — when you're really optimizing.

### Patterns
- **Cache-aside** (90% case): read DB, write cache on miss. Invalidate on write to DB. Simple, works.
- **Write-through**: write to cache + DB together on every write. Consistent but slower writes. Use when stale reads are unacceptable.
- **Write-around**: writes skip the cache; cache fills on read. Good when recent writes are rarely re-read (logs, one-time data).
- **Write-back** (write-behind): write to cache, flush to DB async. Fast but risks data loss if the cache dies. Rarely worth it.
- **Refresh-ahead**: refresh hot keys before they expire. Advanced; only for very hot keys with predictable expiry.

### Eviction policies (when cache fills up)
- **LRU** (Least Recently Used) — default for most workloads. Keeps hot data, evicts cold.
- **LFU** (Least Frequently Used) — better when some keys are consistently hot regardless of recency.
- **FIFO** — rarely the right choice; ignores access patterns.
- **TTL-only** — no eviction by size; everything expires by wall clock. Useful for session data.

### Gotchas
- **Invalidation is hard.** TTLs + write-invalidation is the usual combo.
- **Cache stampede**: if Redis goes down, traffic slams the DB. Have a plan (fallback, circuit breaker, small in-process L1 cache).
- **Stale data**: cached data is always slightly stale. Make sure that's OK per data type.
- **Thundering herd on expiry**: if a hot key expires, N concurrent requests all try to refresh it. Use locks/singleflight, or jittered TTLs.

### Numbers
- Redis handles 100K+ ops/sec per instance.
- Cache hit on Redis: ~1ms. DB query: ~5–50ms. 10–50× speedup when it works.

### What to tell the user
> "No cache in v1 — your scale doesn't need it and it'd add invalidation complexity. Revisit if we see feed-read latency creep over 200ms."

---

## When to shard

**Default: don't. Seriously, don't.** A single Postgres instance can handle an astonishing amount of load.

### Signals you actually need to shard
- **Writes exceed ~10K TPS** consistently (not peak — sustained).
- **Single-instance storage approaching the cap** (~10+ TB on Postgres).
- **Read replicas can't keep up** (and vertical scaling + replicas aren't enough).

### If you really need it
- Pick a **shard key** that matches your dominant access pattern. Most user-centric apps shard by `user_id`.
- Use **hash-based sharding** to avoid hot spots, unless you have a natural partition (multi-tenant where each tenant only queries their own data).
- Be aware: **cross-shard transactions become nearly impossible**. Design shard boundaries to avoid them.
- **Resharding is painful.** Consistent hashing makes it less bad.
- Hot shards happen (celebrity users). Plan for isolation or special handling.

### Alternatives to try first
- **Vertical scaling**: bigger instance. Cheap and fast. 64-core Postgres boxes exist.
- **Read replicas**: scale reads to 2–10× the primary.
- **Caching**: offload hot reads.
- **Archiving**: move old data out of the main table.
- **Partitioning** (in Postgres): native table partitioning splits a table without true sharding.

### What to tell the user
> "No sharding. Even at your 5-year projection of 5M users, a single Postgres with read replicas handles this comfortably. Adding sharding now would cost weeks and give us nothing."

---

## When to add a message queue

### Signals you need a queue
- **Async work** that doesn't fit in the request-response cycle: sending emails, processing uploads, generating reports, calling slow third-party APIs.
- **Decoupling services** that shouldn't know about each other.
- **Smoothing spiky load** — ingestion bursts that downstream can't keep up with.
- **Reliability** — retry semantics for work that must eventually happen.

### Options
- **Simple task queue (SQS, Cloud Tasks, BullMQ, Celery, Sidekiq)**: the default. Work items, workers, retries, DLQ. Easy to add.
- **Pub/sub (Redis pub/sub, NATS, Google Pub/Sub)**: fan-out to multiple consumers; usually for real-time notifications and live updates.
- **Event streaming (Kafka, Kinesis, RedPanda)**: when you need replay, high throughput (>100K msgs/sec), long retention, multiple consumer groups.

### Don't reach for Kafka
Kafka is powerful but heavy. Don't use it unless you have a clear need: high throughput, event sourcing, or a stream-processing architecture. Most "we need a queue" problems are solved by SQS + workers.

### What to tell the user
> "Add a simple job queue (SQS + worker, or BullMQ if you're in Node). Email sending and image resizing go there. We don't need Kafka — volume is too low and we don't need replay."

---

## When to add a background worker

Basically any time you have a queue, you have workers. Also standalone:

- **Scheduled / cron-like work** (cleanup, reminders, periodic refreshes)
- **Long-running tasks** (anything > 1–2 seconds shouldn't be in the request path)
- **External API calls with retries** (anything that hits a flaky third party)

Options: a process running on your app servers reading a queue, or separate worker dynos (Heroku-style), or serverless functions (Lambda triggered by queue messages).

---

## When to add a search index

### Signals you need Elasticsearch (or similar)
- Full-text search over >1M documents
- Complex faceted filtering that's slow in Postgres
- Relevance ranking with tuned signals (TF-IDF, BM25, learning-to-rank)
- Autocomplete / typeahead with low latency

### Before Elasticsearch
- **Postgres full-text search** handles up to ~1M documents well. `tsvector` columns + GIN indexes.
- **Like / ILIKE** is fine for small datasets (<100K) or admin tools.

### If you use Elasticsearch
- Data gets there via CDC from the primary DB (Debezium, Fivetran, or DIY).
- Index lags the primary by seconds. Accept this.
- Schema changes require reindexing.
- Operational complexity: JVM, cluster management, shard tuning. Consider managed (Elastic Cloud, OpenSearch on AWS).

### What to tell the user
> "Postgres full-text search for v1. It'll handle up to a million items cleanly. If relevance tuning becomes critical or we exceed that, we move to Elasticsearch."

---

## When to add a distributed lock

**Default: don't.** Most coordination problems can be handled with DB transactions (row-level locks, `SELECT FOR UPDATE`), optimistic concurrency (version columns), or simple idempotency keys.

### Signals you actually need one
- **Reserving a resource across a long window** (minutes, not milliseconds): holding a concert seat while the user checks out, a driver assignment in a ride-share.
- **Coordinating scheduled work across multiple instances**: "exactly one server should run this cron" when you have N replicas.
- **Mutually-exclusive bidding or auction finalization**.
- **Cross-service exclusion** where you can't use a single DB transaction.

### How it works
Use Redis (`SET key value NX EX 600`) or ZooKeeper. The atomicity of the key-value store is the guarantee: only one caller gets the lock; everyone else fails. The `EX` (expiry) is critical — if the holder crashes, the lock releases automatically so the system isn't stuck.

### Gotchas
- **Always set an expiry.** A lock without expiry + a crashed holder = permanent deadlock.
- **Lock expiry < work duration is a bug.** Holder's lock expires mid-operation, someone else grabs it, now two holders think they own the resource. Use renewal (heartbeat) for long-running holds.
- **Redlock** (multi-node Redis lock) is the standard hardened version. Overkill for most apps; a single Redis + short expiry is fine.
- **Deadlocks** happen when two processes need two locks in different orders. Acquire in a consistent global order, or don't hold more than one at a time.

### What to tell the user
> "Redis lock on `checkout:{ticket_id}` with a 10-minute TTL during checkout — other users get a clear 'currently reserved' error. We don't need Redlock; a single Redis instance with TTL is fine."

---

## When to go microservices

**Default: monolith.** Especially for small teams.

### Signals for splitting
- **Team size**: >50 engineers, with independently-owned product areas.
- **Independent scaling needs**: one component has 100× the load of others.
- **Independent deployment cadence**: one part deploys 10× a day, another monthly.
- **Language heterogeneity**: part must be in Python for ML, another in Go for perf.

### What you pay for microservices
- Network calls where there used to be function calls (latency, failure modes)
- Distributed tracing / observability overhead
- Deployment complexity (many repos, many pipelines, service mesh considerations)
- Data consistency nightmares (no cross-service transactions)
- Harder local development

For a team of <20 engineers, a well-structured monolith beats microservices in almost every metric.

### Middle ground: "modular monolith"
One deployable, but with clear internal module boundaries. Easy to split later if actually needed. This is usually the right answer for small teams.

### What to tell the user
> "Monolith. You're a team of 4. Microservices would waste weeks of your runway for zero benefit right now. Structure it as modules internally so we can split if we need to."

---

## Load balancing: L4 vs L7, client-side vs dedicated

Any time you have multiple instances capable of handling the same request, you need load balancing. Two axes of choice: **who does the routing** (client-side vs dedicated LB) and **which layer** (L4 vs L7).

### Client-side vs dedicated
- **Client-side**: the client itself picks which backend to talk to (often via a service registry). Saves a network hop. Examples: Redis Cluster clients, gRPC built-in LB, DNS round-robin. Good for internal service-to-service calls where you control both ends.
- **Dedicated**: a load balancer sits between client and backends. Client talks only to the LB. Default for public traffic — clients don't know your topology, updates are instant.

### L4 vs L7 (for dedicated LBs)

**Layer 4** operates at the TCP level. Fast, minimal processing, no visibility into request content. The LB just picks a backend and forwards the connection.
- Use for: **WebSockets** (persistent TCP connections must stick to one backend), raw throughput, protocols that aren't HTTP.

**Layer 7** operates at the application level. Can route based on URL, headers, cookies, method. Terminates client connections and opens new ones to backends.
- Use for: almost all HTTP traffic. Can do path-based routing (`/api/*` to one pool, `/static/*` to another), sticky sessions by cookie, header-based canary deploys.

**Quick rule**: WebSockets → L4. Everything else → L7.

### Algorithms
- **Round robin / random**: default for stateless services. New backends get traffic automatically.
- **Least connections**: best for persistent connections (SSE, WebSockets) so one backend doesn't accumulate all long-lived clients.
- **Least response time**: aware of real latency; requires probes.
- **IP hash / consistent hash**: same client always hits the same backend. Useful for coarse session persistence without cookies.

### Health checks
Any LB in production should health-check backends and remove unhealthy ones automatically. Use L7 checks (an HTTP endpoint returning 200) over L4 checks (TCP connect), because the app can be hung but still accept connections.

### Common implementations
- **Managed**: AWS ELB/ALB/NLB, GCP Load Balancing, Cloudflare Load Balancer
- **Self-hosted**: NGINX, HAProxy, Envoy, Traefik

### What to tell the user
> "Application load balancer (L7) in front of the API servers. If we add WebSockets for live chat later, we put those behind a separate L4 balancer so connections stick."

---

## API gateway

An API gateway sits in front of your service(s) and handles cross-cutting concerns: auth, rate limiting, routing, logging, request transformation. In a monolith, these concerns often live in middleware inside the app. In a microservice architecture, a dedicated gateway is common so each service doesn't re-implement them.

### When to add one
- **Multiple backend services** that should share auth and rate limiting
- **Different clients need different shapes** (mobile vs web vs partners) — gateway does the shaping
- **You need a stable public API surface** decoupled from internal service topology
- **Regulated environments** where you need a single audit + logging point

### When to skip
- **Monolith with one app**: just use middleware inside the app
- **Tiny project**: your LB/CDN already does much of what you'd use a gateway for (Cloudflare Workers, Vercel middleware)

### Options
- **Managed**: AWS API Gateway, GCP API Gateway, Azure API Management, Cloudflare API Gateway
- **Self-hosted**: Kong, Tyk, Envoy, NGINX
- **Framework-level**: Next.js middleware, Spring Cloud Gateway, etc.

### What to tell the user
> "No separate gateway for v1 — auth and rate limiting live as FastAPI middleware. If we split services later, we move those concerns into Kong or API Gateway."

---

## Real-time: WebSocket vs SSE vs Polling

### Short polling
- Client re-fetches every N seconds
- **Good for**: low-frequency updates, small teams, simple to implement
- **Bad for**: anything that feels "live"

### Long polling
- Client opens a request, server holds it until data is available or timeout
- Middle ground; easier than WS to scale (stateless HTTP)

### Server-Sent Events (SSE)
- Server → client one-way stream over HTTP
- **Good for**: notifications, live feeds, LLM token streaming
- Works through standard HTTP infra; simpler than WS
- Unidirectional — client can't push back on the same connection

### WebSockets
- Full-duplex persistent connection
- **Good for**: chat, live collaboration, games, anything where the client pushes data frequently
- **Cost**: stateful connections don't work with standard L7 load balancers; need sticky routing or a pubsub layer; connection count is a capacity variable

### Default choice
- "Live" updates where the server needs to push: **SSE**
- Bidirectional realtime (chat, collab): **WebSockets**
- "Good enough freshness" for dashboards: **polling every N seconds**
- LLM streaming: **SSE**

### What to tell the user
> "SSE for notifications — simpler than WebSockets and handles our use case. If we need typing indicators in chat later, we'll add a WebSocket layer for that specifically."

---

## Consistency: strong vs eventual

### Default: eventual consistency
Most user-facing features tolerate a few seconds of staleness. Feed, likes, counters, recommendations, notifications — all fine with eventual consistency.

### Strong consistency is required when:
- **Money** (payments, balances, transfers)
- **Inventory** (stock counts — overselling is bad)
- **Limited-resource booking** (seats, slots, unique usernames)
- **Authorization / permissions** (access revocation must be immediate)

### Within one system, mix consistency per data type
- E-commerce: eventually consistent reviews and product descriptions; strongly consistent inventory and order state.
- Social app: eventually consistent feed and like counts; strongly consistent account creation and payment.

### What to tell the user
> "Eventual consistency for the feed — a few seconds of lag on new posts is fine. Strong consistency for the order and payment tables — those can't ever disagree with Stripe."

---

## Hosting: PaaS vs IaaS vs self-host

### PaaS (Fly, Render, Railway, Vercel, Heroku, Supabase, ...)
- **Default choice** for any project that isn't huge-scale or special
- You trade a little flexibility and cost per GB for huge operational simplicity
- Deploy is `git push`
- Managed DB, managed Redis, managed workers usually included

### Cloud IaaS (AWS, GCP, Azure)
- When you need something PaaS can't give: specific regions, specific compliance, specific services (SageMaker, BigQuery)
- More ops burden — you're running Kubernetes or ECS or equivalent

### Self-hosted (bare metal, VPS, homelab)
- For personal projects, privacy-sensitive tools, or cost-optimization at very high scale
- You're now also the sysadmin

### What to tell the user
> "Start on [Fly/Render/Railway]. Zero ops overhead, deploy is one command, managed Postgres included. Move to AWS if we hit a reason — we probably won't."

---

## Auth: build vs buy

**Default: buy.** Rolling your own auth is a tarpit.

### Managed auth providers
- **Clerk** — opinionated, nice UI components, startup-friendly
- **Auth0 / Okta** — enterprise, SSO-heavy
- **Supabase Auth** — if you're already on Supabase
- **WorkOS** — enterprise SSO specifically
- **Firebase Auth** — if you're on GCP

### DIY auth is reasonable when:
- You have a specific requirement a provider doesn't meet
- You're really, really sure
- You have security expertise on the team

### What "buy" saves you from
- Password hashing / rotation
- MFA flows
- OAuth / SSO integration
- Session management and revocation
- Password reset flows
- Rate limiting on auth endpoints
- Compliance audits of auth code

---

## Reliability patterns

The network is unreliable. Servers crash. Third parties go down. The following patterns aren't optional for production systems — they're how real systems survive bad weather. Surface them during the deep-dives phase; bake them into the implementation plan.

### Idempotency (and idempotency keys)

**An operation is idempotent if running it N times has the same effect as running it once.** GET, PUT, and DELETE are naturally idempotent. POST is not — which means retrying a POST could double-charge a card, send duplicate emails, or create duplicate records.

**Idempotency keys** make POSTs safe to retry. Client generates a unique key per logical request and sends it in a header (`Idempotency-Key: <uuid>`). Server stores the key with the response; if the same key shows up again within a window, the server returns the cached response without re-executing.

```
POST /payments
Idempotency-Key: 9f2a7b1e-...
{ "amount": 1000, "source": "tok_..." }
```

**Where it matters**: payments, order creation, signups, anything with side effects. Stripe, Square, and friends require it on write endpoints. Make it a convention in your own APIs too.

**Server-side implementation**: a table `idempotency_keys(key, user_id, response_body, created_at)` with a unique index on `(user_id, key)`. Inserts fail fast on duplicate; you return the cached response.

### Retries with exponential backoff and jitter

Retries recover from transient failures (network blip, brief timeout, slow server). But naive retries cause **retry storms**: every client hits a failing service at the same millisecond, making recovery impossible.

The fix has three parts:
1. **Exponential backoff**: 1s, 2s, 4s, 8s, 16s — doubling each time
2. **Jitter**: randomize within a window so retries spread out (e.g., `sleep(base * 2^attempt * random(0.5, 1.5))`)
3. **Cap on attempts**: 3–5 retries, then give up and return an error

**"Retry with exponential backoff and jitter"** is the phrase. Know it.

**When NOT to retry**: 4xx errors (bad request, not found, unauthorized) will never succeed on retry. Retry only on 5xx, timeouts, and network errors.

### Timeouts (every network call has one)

No unbounded waits, ever. Every external call (DB, cache, third-party API, internal service) has a timeout. Without one, a slow downstream takes down your upstream — every request thread piles up waiting.

Rules of thumb:
- **DB queries**: 1–5 s (anything longer is probably a bug)
- **Redis**: 100–500 ms
- **Third-party APIs**: 5–30 s depending on the API
- **Internal RPC**: 1–5 s, tuned to the actual p99

The caller's timeout should be shorter than any upstream waits, so you don't have requests waiting for work already abandoned.

### Circuit breakers

When a downstream is hard-down, retrying with backoff still hammers it. A circuit breaker short-circuits after repeated failures: once N failures happen in window W, the breaker **opens** and calls fail immediately for a cool-down period. After the cool-down, it goes **half-open** and tests a single request; success closes it, failure re-opens it.

**Why**: gives the failing service room to recover (a "thundering herd" of retries keeps it pinned). Also: fail fast → better UX. User gets an error in 10 ms instead of waiting 30 s for a timeout.

**Where**: around every call to an unreliable dependency — third-party APIs, internal services, flaky infra. Libraries: Hystrix (legacy), Resilience4j, `opossum` (Node), `circuitbreaker` (Go), `tenacity` + custom state (Python).

### Dead letter queues (DLQ)

For async work: when a job fails all retries, don't lose it — route it to a dead letter queue for inspection. DLQ lets you debug failures, replay jobs after fixing the bug, and monitor failure rates (a growing DLQ is an alert).

### Thundering herd / cache stampede

When a hot cache key expires, N concurrent requests all try to refresh it simultaneously — each hitting the DB. Fixes:
- **Singleflight / request coalescing**: one request does the refresh; others wait for the result.
- **Jittered TTLs**: add randomness so hot keys don't all expire at once.
- **Refresh-ahead**: proactively re-cache before expiry.

### Graceful degradation

When something fails, return a reduced experience instead of an error. Examples:
- Recommendations service down → show popular items instead
- Image CDN slow → serve a placeholder
- Search down → fall back to simple keyword filter on cached data

Call out degradation paths explicitly in the design. "If X fails, we Y."

### What to tell the user
> "Idempotency keys on every POST, retries with exponential backoff + jitter on all outbound calls, timeouts on every network call, circuit breakers around Stripe and OpenAI. Jobs that exhaust retries land in a DLQ."

---

## Numbers to know (reference)

Rough orders of magnitude for reasoning about scale. These are modern hardware numbers — don't use 2010-era mental models.

### Latency
| What | Time |
|---|---|
| Memory access | ~100 ns |
| SSD random read | ~100 μs |
| Intra-datacenter network round trip | 0.5–1 ms |
| Redis operation | ~1 ms |
| Postgres cached read | 1–5 ms |
| Postgres uncached read | 10–50 ms |
| Coast-to-coast US network round trip | ~60 ms |
| Transcontinental (e.g., NY ↔ London) | ~80 ms |
| NY ↔ Sydney | ~200 ms |

### Component capacities (single instance, well-tuned)
| Component | Throughput | Notes |
|---|---|---|
| Postgres | 10K–50K TPS | Up to several TB storage; can do more with tuning |
| Redis | 100K+ ops/sec | Memory-bound (up to ~1 TB on big instances) |
| App server (web) | 1K–10K req/sec | Depends on work per request |
| Elasticsearch node | 1K–10K QPS | Scales with cluster size |
| S3 / object storage | Effectively unlimited | Per-key rate limits exist |
| Kafka broker | 100K+ msgs/sec | Scales linearly with brokers |
| WebSocket server | ~100K concurrent conns | Per server, with enough RAM |

### Storage sizes (rough, per record)
| Data type | Size |
|---|---|
| Text post / tweet | 0.5–2 KB |
| User profile (without media) | 1–5 KB |
| Chat message | 0.2–1 KB |
| Photo | 500 KB – 5 MB |
| Short video | 5–50 MB |
| Long-form video | 100 MB – 10 GB |

### When to start worrying
| Signal | What it suggests |
|---|---|
| Storage > 1 TB | Start thinking about archival |
| Writes > 5K TPS | Start thinking about caching writes, batching |
| Writes > 10K TPS sustained | Start considering sharding |
| DAU > 100K | Your single box probably still works but audit it |
| DAU > 10M | You need a real distributed architecture |
| Concurrent WebSocket conns > 100K | You need multiple WS servers + pubsub |

Remember: thresholds above are for "start considering." Most numbers below them mean "don't bother."
