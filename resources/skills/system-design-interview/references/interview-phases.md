# Interview Phases: Detailed Playbook

This file expands each phase of the system design interview with: purpose, core questions, what to listen for, common pitfalls, how to know when to move on, and what to capture for the output spec.

Use it as a working reference throughout the interview. You don't need to ask every question in every phase — pick the ones that matter for the system at hand.

## Pacing: the user drives

Every "When to move on" criterion in this file has an invisible prerequisite: **the user has confirmed they're done with the current topic**. Even when you have all the information you think you need, do not advance on your own. Users often remember something important a minute after their first answer, or want to revise something they just said.

Two concrete rules:

1. **After each answer within a phase**, acknowledge briefly and check: "Got it — [short recap]. Anything to add, or shall we move to [next question]?" If they add more, fold it in and re-check. Only ask the next question when they've clearly released the current one.

2. **Between phases**, always summarize and confirm: "Here's what we've captured for [phase]: [bullet recap]. Ready to move into [next phase], or anything you want to revisit first?" Wait for an affirmative before proceeding.

The only exception: if the user has explicitly signaled they want to move fast ("skip the check-ins", "just go", "I'll tell you if I want to add something"), respect that and compress the confirmations. Otherwise, err on the side of giving the user space.

## Table of Contents
1. [Context & Goals](#1-context--goals)
2. [Functional Requirements](#2-functional-requirements)
3. [Non-Functional Requirements](#3-non-functional-requirements)
4. [Capacity & Scale](#4-capacity--scale)
5. [Core Entities](#5-core-entities)
6. [API / Interface](#6-api--interface)
7. [Data Model](#7-data-model)
8. [Architecture](#8-architecture)
9. [Deep Dives](#9-deep-dives)
10. [Build Plan](#10-build-plan)
11. [Decisions & Open Questions](#11-decisions--open-questions)

---

## 1. Context & Goals

### Purpose
Before designing anything, understand **why this is being built**. This phase doesn't exist in interview frameworks because interviewers give you the problem. In real life, the "problem" is fuzzy and the best architecture depends entirely on the goal.

### Core questions
- "What are you trying to build, in one sentence?"
- "Who is this for? (Yourself, a team, paying customers, internal tool?)"
- "What problem does it solve that isn't already solved?"
- "What does success look like in 3 months? In a year?"
- "Is this a prototype, an MVP for real users, or a production system from day one?"
- "Are there constraints I should know about upfront — budget, hosting, tech stack preferences, deadlines?"

### What to listen for
- **Scope signals.** "Just for me" → optimize for simplicity. "For my startup's launch" → MVP-grade, needs to actually work. "Internal tool at a 10K-person company" → probably needs SSO, audit logging, compliance.
- **Stage signals.** "Prototype" means cut every corner; "production" means don't.
- **Existing tech.** If they mention "we use AWS" or "it has to integrate with our Postgres DB", that's architectural gravity you can't ignore.
- **Skill level.** If they talk like an engineer, match that register. If they're a non-technical founder, you'll need to do more of the deciding and explain choices more carefully.

### Pitfalls
- Diving into features before understanding the goal. A spec-grade "to-do app" looks very different if it's a personal side project vs. enterprise task management.
- Assuming production grade when it's a prototype (wasted complexity) or prototype grade when it's production (critical gaps).
- Missing a hard constraint that will invalidate your whole design later.

### When to move on
You can state the goal in one sentence, you know who the users are, and you know whether this is throwaway, MVP, or production. Three bullet points captured.

### Capture for the spec
- One-sentence description of what's being built
- Target users and use case
- Stage (prototype / MVP / production)
- Hard constraints (tech, budget, timeline, integrations)

---

## 2. Functional Requirements

### Purpose
Nail down the top 3–7 things users can actually do. Not a feature wishlist — the core capabilities without which the product doesn't exist.

### Core questions
- "What are the main things a user should be able to do? Let's aim for 3–5 core capabilities."
- After each one: "Is this essential for v1, or could it come later?"
- "Are there different types of users (e.g., regular users, admins, creators)?"
- "What's the core flow from the user's perspective — walk me through it?"

### What to listen for
- **Core verb per user.** "Post tweets", "follow users", "see a feed" — each one a "users should be able to X" statement.
- **Creep.** If they start listing 15 features, push back: "which 3 are the absolute must-haves for v1? Everything else goes in a later list."
- **Implicit requirements.** If they say "post videos", you need storage + CDN + probably transcoding. Note the implicit pieces but don't expand scope.

### Pitfalls
- **Requirement inflation.** Every feature sounds essential in a brainstorm. Force prioritization now or you'll regret it.
- **Designing for the 1% case.** If 99% of users do X, design for X. Handle the 1% as an edge case.
- **Missing the creator side.** If it's a two-sided system (creators + consumers), requirements for both.

### When to move on
You have a prioritized list of 3–7 "users should be able to..." statements. Everything else is captured in a "later" list.

### Capture for the spec
- Prioritized v1 functional requirements
- Later/nice-to-have list (explicitly deferred)
- User roles / personas if relevant
- Primary user flow (1 paragraph or a bulleted sequence)

---

## 3. Non-Functional Requirements

### Purpose
Capture the qualities the system needs beyond features: scale, latency, availability, consistency, durability, security, compliance, observability. The right NFRs drive every architectural decision.

Load `references/non-functional-checklist.md` during this phase to make sure you cover the full set.

### Core questions
Walk through each relevant dimension. Don't ask about dimensions that clearly don't apply (no need to ask about HIPAA for a personal blog).

- **Availability**: "If the system goes down for 5 minutes, what happens? What about 1 hour? A day?"
- **Consistency**: "If two users update the same thing at once, does it matter who wins, or do we need strict ordering?" or "Is it OK for a user to see slightly stale data (a few seconds old)?"
- **Latency**: "How fast does this need to feel? Sub-second? A few seconds? Overnight?"
- **Durability**: "If we lost the last hour of data, would that be catastrophic, annoying, or fine?"
- **Security**: "Is there sensitive data — PII, payments, health, financials? Auth model?"
- **Compliance**: "Any regulations in play — GDPR, HIPAA, SOC 2, PCI?"
- **Observability**: "Do you need to see metrics, logs, traces in production?"

### What to listen for
- **Unquantified NFRs.** "Low latency" means nothing. Push for a number: "< 200ms for the feed, < 1s for search."
- **Availability vs consistency tradeoffs.** Money, inventory, booking → usually strong consistency. Social, feeds, analytics → usually eventual consistency and higher availability.
- **Durability = backups + replication.** If they say "losing data is catastrophic," you're signing up for replication and backup strategy.

### Pitfalls
- Over-specifying NFRs. Not every system needs five-nines availability. Most MVP apps can tolerate a few minutes of downtime for a deploy.
- Under-specifying. If you skip NFRs, you end up choosing Postgres when you needed DynamoDB, or vice versa.
- Taking "it should be fast" at face value. Always quantify.

### When to move on
You have concrete numbers (or explicit "not a concern") for the 4–6 NFRs that actually matter for this system.

### Capture for the spec
- Prioritized list of NFRs with **quantified targets** wherever possible
- Explicit CAP-like tradeoff statement (e.g., "availability > consistency for X, but strong consistency for Y")
- Security / compliance requirements if any

---

## 4. Capacity & Scale

### Purpose
Do the actual math. How many users? How much data? How many requests? This is skipped in interviews but matters in real life because it determines whether you need a single box or a distributed system.

### Core questions
- "Roughly how many users do you expect at launch? In 6 months? In 2 years?"
- "What portion of them are active daily? (DAU / MAU ratio)"
- "Per active user, roughly how many actions per day? (Posts, messages, page views — whatever the core action is.)"
- "How much data per action / per user? (Text = bytes, images = MB, video = GB)"
- "Is usage steady or bursty? (Spikes at specific times, seasonal, event-driven)"
- "Read-heavy or write-heavy?"

### What to listen for
- **Actual scale.** A 100-user internal tool does not need a distributed system. A 10M-user consumer app does.
- **Read/write ratio.** Dramatic skews (99% reads) change what you cache and how you replicate.
- **Burstiness.** Flat 1K QPS and spiky 100 QPS → 10K QPS bursts are very different systems.

### Doing the math
Do this inline with the user, so they see the reasoning:

> "So: 10K DAU × 5 posts/day = 50K posts/day = ~0.6 writes/sec average. Even 10× burst is 6 writes/sec. That's laughably easy for any database. We don't need sharding or Kafka or anything fancy on the write path — Postgres or even SQLite would handle this."

Keep the numbers rough — orders of magnitude matter, decimals don't.

### Pitfalls
- Pretending you need "web-scale" infrastructure for a 100-user system. Don't.
- Ignoring storage growth over time. A 10KB/user/day app × 1M users × 2 years = ~7 TB. That matters.
- Forgetting peak / burst. A global app has time-zone-concentrated peaks.

### When to move on
You have order-of-magnitude estimates for: users (DAU), writes/sec, reads/sec, storage, and any peak vs. average gap. You've verbalized whether the system is "small" (single box), "medium" (single DB + some scaling), or "large" (distributed).

### Capture for the spec
- DAU estimate (launch, 6 months, 2 years)
- Writes/sec and reads/sec (average and peak)
- Storage estimate (initial and growth rate)
- Read/write ratio
- Your verdict on scale tier (small / medium / large)

---

## 5. Core Entities

### Purpose
Identify the nouns of the system. These are what the API exchanges and what the database stores. Keep the list small at first — you'll add to it as you go.

### Core questions
- "What are the main 'things' in the system? Let's list the nouns."
- "Who are the actors? Are any of them overlapping (e.g., a user who's also a creator)?"
- "What entities persist across sessions vs. just exist in memory?"

### What to listen for
- The functional requirements already imply most entities. "Users post tweets" → User, Tweet. "Users follow users" → Follow (a relationship entity, not obvious).
- **Join / relationship entities** are easy to miss. Follows, likes, memberships, permissions — each often deserves its own entity.

### Pitfalls
- Going straight to fields. Just names for now; fields come in the data model phase.
- Over-generic names. "Item" is usually too vague. "Product" or "Post" or "Message" is better.
- Missing the polymorphic case. If "messages" can be text, image, or video, do you model that as one table with a type column or three tables?

### When to move on
5–15 core entities named. Obvious relationships noted informally ("a User has many Posts").

### Capture for the spec
- List of entities with one-line descriptions
- Primary relationships between them (informal; the data model phase formalizes)

---

## 6. API / Interface

### Purpose
Define the contract between the system and its clients. For internal systems this might be function signatures; for most projects it's REST or GraphQL endpoints.

### Core questions
- "Is this talking to a web frontend, a mobile app, other services, CLI users, or all of the above?"
- "Do you have a preference — REST, GraphQL, or something else? (If not, I'll default to REST unless there's a reason to deviate.)"
- "For real-time or streaming, do we need WebSockets / SSE?"
- "What's the auth story — sessions, JWT, API keys, OAuth?"

### Designing the endpoints
Walk through the functional requirements and derive endpoints:

- "Users should be able to post" → `POST /posts`
- "See a feed" → `GET /feed`
- "Follow a user" → `POST /follows { followee_id }`

Rules of thumb:
- **Plural resource names** (`/posts`, not `/post`)
- **Current user from auth token**, never from request body — never trust a user ID passed in the body for authz decisions
- **HTTP verbs semantically**: GET read, POST create, PUT replace, PATCH partial update, DELETE delete
- **Path params for required identifiers** (`/events/{id}/tickets`), **query params for optional filters** (`/events?city=NYC&from=2026-01-01`), **request body for payload**
- Reach for **WebSockets** only when you truly need bidirectional push; **SSE** or long-polling is simpler for server → client
- Reach for **GraphQL** when you have diverse clients with very different data needs (rare in early-stage projects)

### HTTP method idempotency (matters for retries)
- **GET, PUT, DELETE** are idempotent — safe to retry
- **POST, PATCH** are NOT inherently idempotent — duplicate retries can create duplicate resources or apply updates twice
- For write endpoints that clients might retry (payments, order creation): use an **idempotency key** header — see `tech-decisions.md` → Reliability patterns

### Pagination (anything returning a list needs it)
- **Offset-based** (`?offset=40&limit=20`): simple, allows jump-to-page, breaks when new records are inserted mid-paginate (shifted rows, duplicates)
- **Cursor-based** (`?cursor=<opaque>&limit=20`): stable under concurrent writes, the default for feeds and real-time lists; downside is no jump-to-page

Default: cursor-based for feeds / chat / anything time-ordered and active; offset-based for admin tables and stable datasets.

### Versioning
- **URL versioning** (`/v1/posts`, `/v2/posts`): explicit, obvious in logs, easy to route. Default choice.
- **Header versioning** (`Accept-Version: 2`): cleaner URLs, follows HTTP conventions, harder to debug in a browser.

Pick one and stick with it. Versioning is cheap to add upfront and expensive to retrofit when you realize you've shipped v1 without it.

### Auth mechanisms
- **Session cookies** (signed or server-stored): default for web apps with a frontend. Revocable, HTTP-only, battle-tested.
- **JWT**: stateless (no DB lookup per request), carries user info, good for distributed systems. Harder to revoke — use short expiries + refresh tokens.
- **API keys**: for service-to-service and external developers. Long opaque strings, stored server-side. Not for end users.
- **OAuth**: when users log in with Google/GitHub/etc.

Default recommendation: use a managed provider (Clerk, Auth0, Supabase Auth, WorkOS). DIY auth is rarely worth the risk.

### Error format
Pick a consistent shape upfront. Example:
```json
{ "error": { "code": "validation_error", "message": "...", "details": {...} } }
```
Standard status codes: 200, 201, 400, 401, 403, 404, 409, 429, 500. Don't invent new ones.

### Rate limiting
Mention it explicitly, especially for public APIs. Per-user for authenticated, per-IP for unauthenticated, stricter limits on write/auth endpoints. Implementation usually at the API gateway, LB, or middleware (Redis token bucket is the classic).

### Pitfalls
- **Over-specifying.** 5–10 endpoints with rough request/response shapes is enough. Don't spec every header.
- **Forgetting pagination.** Any endpoint returning a list needs it from day one.
- **Forgetting error shapes.** Pick one format upfront.
- **Trusting request bodies for authz.** User ID for who-am-I comes from the auth token, period.
- **Missing idempotency on write endpoints that clients retry.** Payments, especially.

### When to move on
You have a rough list of endpoints covering every functional requirement, with method, path, and a one-line description. Auth model picked. Pagination, versioning, and error format conventions chosen. **The user has confirmed they're done with this phase.**

### Capture for the spec
- Protocol choice with rationale (REST / GraphQL / RPC / WebSocket)
- List of endpoints: method, path, description, rough request/response shape
- Auth mechanism
- Pagination strategy (cursor vs offset)
- Versioning approach
- Error format convention
- Rate limiting approach (even if just "per-user token bucket in Redis, details TBD")
- Idempotency strategy for write endpoints

---

## 7. Data Model

### Purpose
Formalize how entities are stored. Table/collection structure, key fields, indexes, relationships, and (if needed) shard strategy.

### Core questions
- "For each entity, what fields are actually needed for v1? (Name, ID, timestamps, and the fields the API needs — that's usually it.)"
- "What's the primary access pattern for each entity? (Lookup by ID, query by user, search by text, etc.)"
- "Which relationships are 1-to-many vs. many-to-many?"
- "Is anything multi-tenant? (Row-level `tenant_id` on every table if so.)"

### The 6-step data modeling process
For each major entity, work through these in order:

1. **Pick the database type** (usually the same for all entities in v1 — Postgres by default)
2. **List the fields** needed to fulfill functional requirements (don't spec every UI field — just what the API needs)
3. **Identify primary and foreign keys** (system-generated IDs like UUIDs, not business keys like emails)
4. **Decide indexes** based on query patterns from the API
5. **Consider denormalization** — only if there's a specific read-performance reason
6. **Consider sharding** — almost never in v1; see thresholds in `tech-decisions.md`

Write this directly next to the DB box on the architecture diagram so it stays visually anchored.

### SQL vs. NoSQL
Default: **SQL (Postgres)** unless there's a reason to deviate. Reasons to deviate:
- **Known access patterns + need for horizontal scale from day one** → DynamoDB / Cassandra
- **Schemaless documents where structure varies a lot** → MongoDB / document DB (but Postgres `jsonb` covers most of this)
- **Full-text search is a core feature** → Postgres + Elasticsearch, or just Elasticsearch if search-first
- **Graph traversals are the dominant access pattern** → Neo4j or similar (rare; even Facebook models their social graph in MySQL)

See `references/tech-decisions.md` for detailed thresholds.

**Don't make SQL vs NoSQL comparisons in the abstract.** State the specific database you're picking and the specific features of it that help. "Postgres for its ACID guarantees and `jsonb` flexibility" beats "SQL because the data is relational."

### Keys and constraints
- **Primary keys**: use system-generated UUIDs or ULIDs. Not emails, usernames, or other mutable business data.
- **Foreign keys**: enforce referential integrity in SQL. Write cost is small; orphaned-record bugs are expensive.
- **Unique constraints**: enforce at the DB level (email, username). App-level uniqueness checks have race conditions.
- **NOT NULL and CHECK constraints**: free correctness. Add them.

At massive scale some companies drop FKs for write performance. Not a v1 concern.

### Indexing (propose indexes as you design)
For SQL: propose indexes on fields you'll query frequently. Foreign keys, email-for-login, timestamps-for-ordering. Don't over-index — every index slows writes.

For NoSQL: design partition key + sort key around access patterns. Name the primary query for each table.

**Tie indexes to API endpoints explicitly**: "the `GET /users/{id}/posts` endpoint needs an index on `posts(user_id, created_at desc)`". This forces the design to be driven by actual queries, not guesses.

### Normalization vs denormalization
**Default: normalize.** Store each fact in exactly one place. Foreign keys and joins are cheap for most workloads.

Denormalize only when:
- **Analytics/reporting** where data is append-heavy and changes infrequently
- **Event logs and audit trails** that snapshot state at a point in time
- **Specific identified read hotspots** where joins measurably fail the latency SLA

Even then, consider a **cache of denormalized data** over denormalizing the source of truth. Keep the DB clean; precompute derived views in Redis or a materialized view.

### Sharding (almost never in v1)
See `tech-decisions.md` → When to shard. The one rule to remember: **shard by your primary access pattern**. User-centric app → shard by `user_id`. This keeps a user's data on one shard so single-user queries are fast.

**Anti-pattern: sharding by time range.** Sounds appealing ("recent data on one shard") but all current writes hit the newest shard → hot shard. Time-range partitioning only works for archival/analytics, not write-heavy workloads.

### Storage choices (not just SQL)
Most systems use multiple stores, each for what it's best at:
- **Postgres**: primary transactional data
- **S3 / R2**: blobs (images, videos, documents) — never in the DB
- **Redis**: sessions, cache, ephemeral state, leaderboards, pub/sub
- **Elasticsearch**: full-text search (if needed)

Capture which data lives where.

### Pitfalls
- **Over-modeling for v1.** If you don't need it on day one, leave it out.
- **Under-indexing.** Missing an index on a query path will bite in production.
- **Premature denormalization.** Start normalized; denormalize specific hot paths only when measured.
- **Storing blobs in the DB.** Images, videos, large files → object storage with a URL in the DB.
- **Using business keys as primary keys.** Emails change. Use UUIDs.
- **Forgetting tenant_id** in multi-tenant apps — retrofitting tenant isolation is painful.

### When to move on
Every entity has a table/collection name, fields, primary key, and indexes. Relationships are mapped. Storage choices per data category are explicit. **The user has confirmed they're done with this phase.**

### Capture for the spec
- For each entity: table/collection name, fields (name + type), primary key, indexes
- Relationships (FKs, join tables)
- Which storage system for each data category (e.g., "Postgres for core data, S3 for media, Redis for sessions, Elasticsearch for search")
- Any denormalization decisions with rationale
- Sharding strategy if applicable (usually "none in v1")
- Migration tooling choice (Alembic / Prisma / etc.)
- Data retention and backup strategy

---

## 8. Architecture

### Purpose
Draw the system as components and their connections. What runs where, what talks to what, what state lives in what store.

Load `references/tech-decisions.md` if you haven't already.

### Core questions
Often you'll be proposing and confirming rather than asking:

- "For the API layer, I'm thinking [FastAPI / Express / Next.js API routes] — any preference?"
- "For the database, given the requirements, I'd go with [Postgres / DynamoDB / ...]. Sound right?"
- "Do we need a cache? At our scale, probably not yet — we can add Redis later if we see hot read paths."
- "Do we need a background job system? (Yes if: sending emails, processing uploads, running anything async.)"
- "Hosting — where does this live? (Vercel / AWS / Fly / self-hosted?)"

### The diagram
Describe it textually (the output will include a mermaid diagram). Typical small-system shape:

```
Client (web/mobile) 
    → API (stateless app servers, N instances behind LB) 
    → Postgres (primary)
    → S3 (media)
    → Redis (cache, sessions) [optional at small scale]
    → Background worker (optional, reads queue, does async work)
```

Go component by component:
1. **Clients** — what's talking to the API
2. **Edge / CDN** — for static assets and/or caching
3. **Load balancer** — L4 for WebSockets, L7 for everything else
4. **API gateway** (if multiple services or complex cross-cutting needs)
5. **API layer** — stateless, horizontally scalable
6. **Data stores** — primary DB, object storage, cache, search
7. **Async layer** — queues + workers for anything slow
8. **External services** — payment, email, SMS, auth, AI

### Implementation considerations (bridge to LLD)
Once the high-level architecture is stable, spend a meaningful chunk of time — usually longer than people think — on implementation-level choices that will drive code structure. **This is the bridge from "which components" to "how the code is organized," and getting it right at design time saves a lot of refactoring later.** The generated skill's `implementation.md` only works if this section was thought through.

Cover:

- **Language and framework per component** (be specific: "Python 3.12 + FastAPI," not "Python web framework")
- **Module / package structure at a high level** — how code is organized within the API layer. For a monolith: domain modules vs API modules vs infra modules. For microservices: which service owns which domain.
- **The 3–5 most important domain classes / services** — name them, sketch their responsibilities (state + behavior), and identify which is the orchestrator. This is the LLD seed; future Claude expands it during implementation.
- **Any design patterns that are clearly called for** — Strategy if there are multiple payment providers, State Machine if there's a complex status lifecycle, Observer if multiple components react to events. Name them now; the generated skill's `implementation.md` will formalize.
- **Patterns explicitly avoided** — no Singletons, no deep inheritance, no premature abstractions. Capture these as guardrails.
- **Cross-cutting concerns** — where auth, rate limiting, logging, and error tracking live
- **Testing approach and the correctness-tests-first rule** — see below; this is non-negotiable.

#### Correctness tests come before implementation

A repeated failure mode is jumping from architecture to code without a precise definition of "correct." Lock in this rule for the project, and capture it in `implementation.md`:

> Before implementing any non-trivial class, method, or service, write down — in prose or as concrete test cases — the specific behaviors that must hold for it to be correct: happy path, edge cases, error / illegal-operation modes, and invariants. Vague designs hide behind vague tests.

In the interview, do a short pass on the 3–5 most important domain classes/services and seed their correctness expectations: list 2–4 invariants and 2–4 nasty edge cases per class. These don't need to be exhaustive — they're the seed list that future Claude expands when it starts step 4 of the LLD delivery framework (`implementation-guidance.md` § 1).

Examples of invariants and edge cases to surface during the interview:

- *Order service*: invariants — "an order has exactly one terminal state," "total never goes negative." Edge cases — partial refunds, concurrent status updates, payment-confirmed-but-shipping-failed.
- *Rate limiter*: invariants — "no user exceeds N req/window," "limits are correct under clock skew." Edge cases — burst at window boundary, distributed instances disagree.
- *Feed builder*: invariants — "no duplicate posts," "ordering is stable across paginated reads." Edge cases — celebrity follower with millions of fans, deleted post mid-fetch.

Capture these directly in the spec; they belong to design, not to "we'll figure it out when we code."

Load `references/implementation-guidance.md` if you're making pattern-level recommendations or sketching class responsibilities. Don't over-invest in code design here — this is orientation for the code-time decisions, not a full LLD. The goal is that future-Claude, reading `implementation.md` in the generated skill, can start coding *with the test list already in hand*, not re-derive it.

### Pitfalls
- **Adding components you don't need.** Kafka for 100 users. Microservices for a 5-person team. A separate auth service for a CRUD app. Each extra component is a thing that can break. The anti-FAANG principle applies hardest here.
- **Forgetting the async path.** Anything over ~1s should not be in the request-response cycle. Queue it.
- **Not choosing specific tech.** "A database" is not a choice. "Postgres 16" is.
- **Over-specifying LLD here.** Language + framework + high-level module boundaries + clearly-needed patterns is enough. Don't design every class.

### When to move on
Every component has a specific tech, a reason it's there, and you can describe the flow of a typical request/response end to end. Language/framework picked, rough module boundaries sketched, any clearly-needed patterns named, the 3–5 most important domain classes named with seed invariants and edge cases, and the correctness-tests-first rule explicitly recorded. **The user has confirmed they're done with this phase.**

### Capture for the spec
- Component diagram (mermaid in the output)
- Tech choice per component with 1-line rationale
- Request flow for 1–2 representative endpoints
- Hosting / deployment target
- Language + framework per component
- High-level module structure
- The 3–5 most important domain classes/services with one-line responsibilities and the orchestrator marked
- Per-class seed correctness expectations: invariants + edge cases (2–4 of each, not exhaustive)
- Design patterns deliberately chosen (and which ones to avoid)
- Cross-cutting concerns — where they live
- Testing approach + the **correctness-tests-first rule** (write tests before implementing each non-trivial slice)

---

## 9. Deep Dives

### Purpose
For any system, 2–3 things will be the hardest part. The design isn't real until you've addressed them. Identify them and sketch solutions.

### How to find them
Look back at the NFRs and the architecture. The deep dives are usually:
- The highest-traffic path (feed generation, search, etc.) — how do we make it fast?
- The consistency-sensitive path (payments, inventory, seat booking) — how do we avoid correctness bugs?
- The hardest scaling problem (celebrity users, hot partitions, bursty spikes)
- The hardest correctness problem (distributed transactions, idempotency, exactly-once delivery)
- Real-time / live features (who's online, live collab, live feed)
- AI/LLM integration if present — rate limits, streaming, costs, fallbacks

Ask the user: "What's the part of this you're most worried about?" They often know.

### How to solve them (briefly)
For each deep dive:
1. State the problem in one sentence
2. Walk through 2–3 options
3. Pick one and explain the tradeoff

Examples of common deep-dive patterns:

- **Feed generation**: fan-out-on-write vs. fan-out-on-read vs. hybrid. For most systems, fan-out-on-read with caching is fine until you have celebrity users.
- **Idempotency**: client generates a key, server dedupes by key within a window.
- **Rate limiting**: token bucket in Redis, keyed by user/IP.
- **Real-time updates**: SSE for server → client push; WebSocket only if you need bidirectional.
- **Hot shard / hot partition**: isolate hot keys, add a local cache layer, or shard differently.

See `references/tech-decisions.md` for more patterns.

### Pitfalls
- Deep-diving too early. If the architecture isn't stable, you'll deep-dive the wrong thing.
- Deep-diving things that aren't hard. If the system is small, most "deep dives" are just "use the obvious thing."
- Proposing a solution without stating what tradeoff it makes.

### When to move on
Each of the 2–3 hardest problems has an identified solution with a one-line rationale.

### Capture for the spec
- List of hard problems with chosen solutions and tradeoffs
- Explicit acknowledgment of problems we're *punting* on (e.g., "no caching in v1, revisit if read latency > 200ms")

---

## 10. Build Plan

### Purpose
Translate the design into a phased implementation order. This is what makes the skill actionable — future Claude can pick up from "milestone 2" and know exactly what's done and what's next.

### Core questions
- "Any preference on build order — backend first, frontend first, vertical slice through one feature, or something else?"
- "What's a natural first milestone? Something that would feel like real progress?"
- "Any parts you want to do yourself vs. have Claude help with?"

### How to structure the plan
Recommend **vertical slicing** by default: build one end-to-end feature before starting the next. This means each milestone is demoable.

Typical phased structure:

- **Phase 0 — Scaffolding**: repo setup, CI, auth stub, DB migrations infrastructure, deploy pipeline
- **Phase 1 — Core vertical slice**: the most important single user flow, end to end, backend + frontend
- **Phase 2 — Second and third flows**: remaining core functional requirements
- **Phase 3 — Hardening**: the deep dives, observability, rate limits, error handling
- **Phase 4 — Nice-to-haves**: the deferred feature list

For each phase, list:
- What gets built (specific user-facing capabilities)
- What's "done" looks like (**acceptance criteria expressed as concrete, testable conditions** — see below)
- What's explicitly NOT in this phase

#### Acceptance criteria as testable conditions

Vague acceptance criteria are how phases drift. "Users can sign up" is not testable. Express each criterion as a concrete condition that a test (or a manual run) could verify. Use a *given / when / then* shape where possible:

- *Given* a new visitor, *when* they submit valid signup with a unique email, *then* an account is created, a session is set, and they land on the onboarding page.
- *Given* an existing email, *when* signup is attempted, *then* a 409 is returned with `error.code = "email_exists"` and no account is created.
- *Given* a malformed email, *when* signup is attempted, *then* a 400 is returned and no DB write occurs.

This serves the same purpose at the phase level that step 4 of the LLD framework serves at the class level: it forces precision before implementation, and produces an executable definition of "done." When future Claude starts a phase, the acceptance criteria *are* the test list — implementation isn't done until each one passes.

For the deep-dive / hardening phase, also include criteria for the invariants surfaced during the Architecture phase (e.g., "no order can reach two terminal states," "rate limiter holds under N concurrent requests").

### Pitfalls
- Horizontal slicing ("build all the data models, then all the APIs, then all the UIs"). This means no demoable progress until the end. Avoid unless there's a specific reason.
- Over-planning. Phase 1 and 2 can be detailed; Phase 4 can be a bullet list.
- Forgetting Phase 0. Projects stall without infrastructure. A day on scaffolding saves a week later.

### When to move on
3–5 phases named, each with a rough scope and a clear "done" criterion.

### Capture for the spec
- Phased plan with per-phase scope and acceptance criteria
- Dependencies between phases (if any)
- An explicit "not yet / later" list

---

## 11. Decisions & Open Questions

### Purpose
Capture *why* you made the architectural choices you made, and what's still unresolved. Future-you (or future-Claude) will thank past-you for this.

### What to capture

**Decisions log** — for each significant choice:
- What was decided
- Why (1–2 sentences)
- What we considered instead
- When we'd revisit

Example:
> **Decision: Postgres for primary DB.**
> Why: Fits our relational model, handles our scale (low thousands of QPS) comfortably, everyone on the team knows SQL.
> Considered: DynamoDB (rejected — access patterns are diverse and we'd be fighting the model), MongoDB (rejected — no reason to lose relational integrity).
> Revisit if: writes exceed 10K TPS, or storage grows past 10 TB.

**Open questions** — things we didn't resolve:
- "Do we need SSO for the enterprise tier? (Decide before GA.)"
- "Email provider — Postmark vs. SES vs. Resend — postpone until Phase 2."

**Explicit assumptions** — things we assumed without confirming:
- "Assumed users will tolerate ~1s feed generation latency."
- "Assumed ≤ 10K DAU for the foreseeable future."

### Pitfalls
- Skipping this section because the interview is long. It's the most valuable output.
- Listing decisions without rationale. "We chose Postgres." Why? Say why.
- Leaving critical assumptions unstated.

### When to move on
You've captured the 5–10 most important decisions with rationale, the 2–5 open questions, and the explicit assumptions.

### Capture for the spec
- Decisions log (structured as above)
- Open questions list
- Assumptions list
