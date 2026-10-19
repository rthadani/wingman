# Question Banks by System Archetype

Most software projects fit into a handful of archetypes. Each archetype has predictable hard problems and predictable questions that unlock the right architecture. When you recognize the archetype early in the interview, load the matching question set to avoid missing things.

Archetypes below are not mutually exclusive — many real systems blend several (e.g., a social app with search is Social Feed + Search).

## Table of Contents
1. [CRUD SaaS / Internal Tool](#1-crud-saas--internal-tool)
2. [Social / Feed App](#2-social--feed-app)
3. [Chat / Messaging](#3-chat--messaging)
4. [Marketplace / Two-Sided Platform](#4-marketplace--two-sided-platform)
5. [Real-Time Collaboration](#5-real-time-collaboration)
6. [E-Commerce / Payments](#6-e-commerce--payments)
7. [Search / Discovery](#7-search--discovery)
8. [Data Pipeline / ETL](#8-data-pipeline--etl)
9. [AI / LLM App](#9-ai--llm-app)
10. [Mobile-First Consumer App](#10-mobile-first-consumer-app)
11. [Scheduled / Cron-Driven System](#11-scheduled--cron-driven-system)
12. [Developer Tool / CLI / SDK](#12-developer-tool--cli--sdk)

---

## 1. CRUD SaaS / Internal Tool

**Examples**: project tracker, HR tool, admin dashboard, internal CRM, form-builder.

**Signals**: "internal tool", "dashboard", "track X", "manage Y", small-to-medium user count.

**Default architecture**: single web app (server-rendered or SPA + API), one Postgres DB, one app server, auth via session cookies or a managed auth provider.

### Key questions
- "Single tenant (one company / one user) or multi-tenant (many customers)?"
- "Do users belong to organizations/teams? What's the permission model?"
- "Is this replacing a spreadsheet or an existing system? What's currently painful?"
- "Do you need an audit log of who changed what?"
- "Export to CSV / Excel / PDF — needed?"
- "Email/slack notifications on events?"
- "Integration with other tools — Slack, SSO, Zapier, their existing data sources?"

### Watch for
- Multi-tenancy is often missed. If there are multiple customers, row-level tenant_id on every table is the default.
- Audit logs are often a late-added requirement. Ask about them up front.
- "Admin view" is often a separate feature with separate auth.

---

## 2. Social / Feed App

**Examples**: Twitter clone, Instagram clone, forum, community site, news feed.

**Signals**: "users post", "follow", "feed", "timeline", "discover".

**Default architecture**: Postgres for posts + graph, Redis for hot feed caching, object storage for media, background worker for fanout, CDN for media delivery.

### Key questions
- "One feed for everyone, or personalized per user?"
- "How is the feed ordered — chronological, algorithmic, engagement-ranked?"
- "Average followers per user? Any celebrity users (millions of followers)?"
- "Media types — text only, images, video? If video, short-form or long-form?"
- "Can users edit or delete posts? What happens to edits in others' feeds?"
- "Likes, comments, reposts, quote-posts — which are v1?"
- "Public by default, or privacy controls?"
- "Notifications — push, email, in-app?"
- "Content moderation — automated, human, hybrid, none for v1?"

### Watch for
- **Feed generation is the deep dive.** Fan-out-on-write (push) is fast for reads but breaks for celebrity users. Fan-out-on-read (pull) is simpler but slow for users who follow many. Hybrid is the usual answer at scale.
- Celebrity users break the simple model. Handle them as a special case (pull rather than push, or hybrid).
- Moderation needs a plan by v2 even if deferred in v1.

---

## 3. Chat / Messaging

**Examples**: Slack clone, WhatsApp clone, in-app chat, customer support chat.

**Signals**: "real-time", "chat", "messages", "conversations", "typing indicator", "online status".

**Default architecture**: WebSocket server for live delivery, Postgres for message history, Redis for presence / online status, message queue for delivery retries.

### Key questions
- "1-on-1 chat only, or group chat? Max group size?"
- "Message history — forever, or expire after N days?"
- "Do recipients need to see 'read' receipts, typing indicators, online status?"
- "Attachments — images, files, video? Size limits?"
- "Search across chat history?"
- "Multiple devices per user? Need cross-device sync?"
- "E2E encryption?"
- "Message edits and deletions — allowed? Preserve history for edits?"
- "Offline delivery — if recipient is offline, how long do we retry?"

### Watch for
- **Connection management is the deep dive.** WebSocket connections are stateful. You can't just put them behind a standard load balancer. Need sticky routing or a pubsub layer (Redis pubsub, NATS) between stateless API and stateful WS servers.
- Presence (online/offline/typing) is a separate subsystem and usually uses Redis with short TTLs.
- Delivery guarantees matter. "At-least-once" with client-side dedup is usually right.

---

## 4. Marketplace / Two-Sided Platform

**Examples**: Airbnb clone, Uber clone, Etsy clone, freelancer platform, local delivery.

**Signals**: "buyers and sellers", "providers and consumers", "connect X with Y", "matching".

**Default architecture**: Postgres for listings + bookings, Redis for availability checks, search service (Elasticsearch or Postgres full-text), geospatial indexing if location-based, payment integration, notification system.

### Key questions
- "Who are the two sides? What does each need to do?"
- "How do the two sides find each other — search, browse, recommendation, matching algorithm?"
- "Is the transaction happening in-platform or off-platform (just discovery)?"
- "If in-platform: payment flow — do you hold funds in escrow? Disburse when?"
- "Location-based? Global, national, city-specific?"
- "Reviews/ratings — bidirectional or one-way?"
- "How do you handle disputes? Support queue?"
- "Trust and safety — ID verification, fraud detection?"
- "Commissions / take rate — how is pricing structured?"

### Watch for
- **Double-booking / inventory contention is the deep dive** for bookings (rides, rentals, tickets). Need explicit locking or reservation pattern.
- **Geospatial queries** need PostGIS or a dedicated geo-index. Don't bolt on an ORM and hope.
- Payment integration (Stripe Connect, Adyen) is non-trivial and usually drives data model decisions.

---

## 5. Real-Time Collaboration

**Examples**: Google Docs, Figma, Miro, live whiteboard, collaborative code editor.

**Signals**: "multiple users edit at once", "live cursors", "see what others are doing", "collaborative".

**Default architecture**: CRDT or OT library (Yjs, Automerge), WebSocket server, Postgres for persistence, periodic snapshotting, presence tracking.

### Key questions
- "How many concurrent editors per document, typical and max?"
- "What's the document model — text, structured data, free-form shapes, code?"
- "Conflict resolution — CRDTs (peer-to-peer friendly) or OT (server-mediated)?"
- "Offline editing — does it need to work offline and sync later?"
- "Presence — showing cursors, selections, who's online?"
- "Permissioning — view, comment, edit? Per-document or per-workspace?"
- "Version history — granularity?"
- "Undo across users — user-local or global?"

### Watch for
- **CRDT vs OT choice drives everything.** CRDTs (Yjs, Automerge) are easier to distribute and work offline; OT (ShareJS, Google Docs) is battle-tested and more memory-efficient.
- Presence is a separate subsystem with different requirements (ephemeral, high-frequency, small messages).
- Persistence strategy: snapshot + operation log, replay on load.

---

## 6. E-Commerce / Payments

**Examples**: online store, subscription service, donation platform, booking with payment.

**Signals**: "buy", "checkout", "subscription", "recurring", "inventory", "payment".

**Default architecture**: Postgres for orders + inventory (strong consistency), Stripe/Braintree for payments (don't build your own), background job for fulfillment pipeline, email for receipts, object storage for product images.

### Key questions
- "One-time purchases, subscriptions, or both?"
- "Physical goods (inventory to manage, shipping) or digital (immediate delivery)?"
- "Payment processor — Stripe almost certainly, unless there's a reason?"
- "Tax calculation — handled by processor or a service like TaxJar/Stripe Tax?"
- "Multi-currency?"
- "Refunds and chargebacks — automated or manual review?"
- "Inventory accuracy requirements — can you oversell or must every sale be backed by stock?"
- "Abandoned cart recovery — needed?"
- "Shipping / fulfillment — in-house, third-party, drop-ship?"

### Watch for
- **Consistency is critical.** Inventory and payment reconciliation need strong consistency. No eventual consistency handwaves here.
- **Idempotency on payment endpoints is non-optional.** Duplicate charges are very bad.
- Use Stripe / an established processor. Do not build payment handling yourself. Regulations alone make this a hard no.
- Webhooks from payment processors need to be reliable. Idempotent handler, retry on failure.

---

## 7. Search / Discovery

**Examples**: product search, job search, document search, code search, recipe finder.

**Signals**: "search", "find", "discover", "filter", "browse with facets".

**Default architecture**: Elasticsearch (or Postgres full-text for small scale), relevance tuning, CDC pipeline from primary DB, user query analytics.

### Key questions
- "What's being searched — structured data (filterable), unstructured text (relevance-ranked), or both?"
- "Search corpus size — thousands, millions, billions of items?"
- "How fresh does the index need to be? Real-time, minutes, hours?"
- "What ranking signals matter — relevance, recency, popularity, personalization?"
- "Filters and facets — what's available? (Category, price, date range, tags.)"
- "Autocomplete / typeahead?"
- "Typo tolerance / fuzzy matching?"
- "Personalization — does different users see different results?"
- "Geospatial search?"

### Watch for
- **Index freshness is the deep dive.** Search indexes always lag the primary DB. CDC (change data capture) from Postgres → Elasticsearch is the usual pattern.
- **Relevance tuning is an ongoing concern**, not a v1 solve. Start with defaults, add signals iteratively.
- Postgres full-text is surprisingly good for <1M items. Don't jump to Elasticsearch for small corpora.

---

## 8. Data Pipeline / ETL

**Examples**: analytics pipeline, log aggregation, data warehouse loader, crawler, scraper.

**Signals**: "ingest", "process", "transform", "load", "aggregate", "batch", "stream".

**Default architecture**: Kafka or SQS for ingestion, stream processor (Flink, Spark Streaming, or simpler workers) or scheduled batch jobs, data warehouse (Snowflake, BigQuery, or Postgres for small), dead-letter queue for failures.

### Key questions
- "Streaming (continuous) or batch (periodic)?"
- "Source systems — how many, what formats, how reliable?"
- "Expected volume — records/sec at peak, bytes/day?"
- "Freshness requirement — real-time, minutes, daily?"
- "Transformations — simple per-record, aggregations (sums, counts), joins across streams, ML scoring?"
- "Idempotency — can we reprocess without double-counting?"
- "Schema evolution — do source schemas change?"
- "Failure handling — drop, retry, DLQ?"
- "Data retention — raw, transformed, aggregated — how long?"

### Watch for
- **Exactly-once semantics** is harder than it looks. Most systems settle for at-least-once + idempotency.
- Schema evolution kills pipelines. Use a schema registry (Avro / Protobuf) or accept the churn.
- Small-scale ETL doesn't need Kafka + Flink. A cron job reading from S3 and writing to Postgres is fine for MB/day volumes.

---

## 9. AI / LLM App

**Examples**: chatbot, AI writing assistant, RAG Q&A over docs, AI coding helper, agent.

**Signals**: "LLM", "GPT", "Claude", "AI assistant", "chatbot", "RAG", "embeddings", "semantic search".

**Default architecture**: frontend + API + LLM provider (Anthropic/OpenAI/etc.), vector DB for RAG (Pinecone, Weaviate, Postgres+pgvector), streaming response handling, cost monitoring, rate limiting.

### Key questions
- "Which model / provider? (Anthropic Claude, OpenAI GPT, local model?) Any reason for one over others?"
- "Streaming responses or wait-for-complete?"
- "Conversation history — multi-turn? How much context carried forward?"
- "RAG — yes/no? If yes, what's the knowledge corpus and how is it indexed?"
- "Tool use / function calling / agentic behavior?"
- "Cost expectations — $/user/month budget?"
- "Rate limits — per-user? Fair-share across users?"
- "Eval / quality measurement — how will you know if outputs are good?"
- "Failure modes — what happens if the LLM is slow, errors, hallucinates, goes down?"
- "PII / sensitive data in prompts — do you need redaction or on-prem inference?"

### Watch for
- **Streaming is near-table-stakes for UX.** SSE is the usual pattern for LLM → client.
- **Cost can explode.** Every call is $0.001–$1+. Rate-limit per-user. Cache where possible. Short-circuit obvious queries.
- **RAG retrieval quality matters more than the LLM choice.** Bad chunks → bad answers.
- **Evals are a real engineering concern**, not an afterthought. Build the eval harness early.
- Don't forget fallback behavior when the provider is down (retry, degrade to cached response, apology).

---

## 10. Mobile-First Consumer App

**Examples**: fitness tracker, journaling app, habit tracker, meditation, social photo app.

**Signals**: "iOS app", "Android app", "mobile-first", "push notifications", "offline".

**Default architecture**: mobile clients (native or React Native/Flutter), stateless API, Postgres, push notification service (APNs + FCM), object storage for user-generated media, auth via managed provider.

### Key questions
- "Native iOS/Android, cross-platform (React Native/Flutter), or mobile web?"
- "Offline support — does the app need to work without connectivity?"
- "Sync model — last-write-wins, per-field merging, CRDT?"
- "Push notifications — transactional, marketing, or both?"
- "Media — photos, video, audio? Stored where, viewed how?"
- "Platform-specific features — HealthKit, Apple Pay, Siri, widgets?"
- "App store requirements — subscription, in-app purchase model?"
- "Analytics / crash reporting?"

### Watch for
- **Offline sync is a deep dive** if required. Don't underestimate it.
- Push notification infrastructure (APNs cert management, FCM tokens) has real operational complexity.
- App store review adds a deployment step you don't control — design for it.
- In-app purchase compliance (Apple 30%) changes monetization design.

---

## 11. Scheduled / Cron-Driven System

**Examples**: daily report generator, scheduled email sender, backup runner, price scraper, alert poller.

**Signals**: "every day at X", "scheduled", "cron", "periodic", "batch run".

**Default architecture**: scheduler (cron, Airflow, Temporal, or managed service), worker pool, persistent state in Postgres, dead-letter handling, retry logic.

### Key questions
- "Frequency — every minute, hour, day? Aligned to clock (top of hour) or relative?"
- "Idempotent? If the job runs twice, is that a problem?"
- "What happens if a run fails — retry immediately, retry next cycle, alert a human?"
- "Long-running or fast? (Minutes changes architecture vs. hours.)"
- "Parallel-safe — can two instances run at once, or need distributed lock?"
- "State between runs — needed? Stored where?"
- "Backfill / replay capability?"
- "Observability — how will you know if a run is stuck or failing silently?"

### Watch for
- **Silent failures are the default mode** for cron jobs. Alerting on missed runs is essential.
- Overlapping runs when a job takes longer than its interval. Lock or skip.
- If jobs get complex (DAG of dependencies, retries, branching), use Temporal / Airflow / Prefect — don't build your own.

---

## 12. Developer Tool / CLI / SDK

**Examples**: command-line tool, language SDK, build tool, dev server, linter.

**Signals**: "CLI", "SDK", "library", "npm package", "pip package", "developer-facing".

**Default architecture**: minimal runtime dependencies, clear versioning, robust error messages, installable via standard package manager.

### Key questions
- "Target audience — which language/ecosystem, what experience level?"
- "Installation path — npm, pip, homebrew, curl-pipe-sh, binary download?"
- "OS support — macOS, Linux, Windows?"
- "Online or offline tool? (Does it call home / require network?)"
- "Configuration — flags, config file, env vars, interactive prompts?"
- "Output format — human-readable, JSON for piping, both?"
- "Telemetry — opt-in or none?"
- "How will users discover the tool and learn it? (README, docs site, inline help?)"
- "Plugin / extension system?"
- "Versioning — semver, backwards compatibility commitments?"

### Watch for
- DX is the product. Bad error messages, slow startup, weird flags kill adoption.
- Versioning and backwards compat are commitments. Think about it early.
- Cross-platform (esp. Windows) is often harder than it looks.

---

## How to use these banks

When you recognize the archetype during the interview:

1. **Read the archetype's "Key questions"** and pick 5–8 that feel relevant.
2. **Note the "Watch for" items** — these are the common pitfalls specific to this archetype.
3. **Use the "Default architecture"** as a starting point; adjust based on the user's actual scale and constraints.

If the project spans multiple archetypes (e.g., a marketplace with chat), read both relevant sections and cherry-pick.
