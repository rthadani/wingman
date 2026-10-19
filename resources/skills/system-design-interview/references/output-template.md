# Output Template: The Generated Skill

Read this file when the interview is complete and you're ready to generate the output skill. It defines the exact file structure, frontmatter conventions, and content templates.

## The shape

```
<project-name>/
  SKILL.md
  references/
    spec.md            # Context, functional + non-functional requirements, capacity
    architecture.md    # Components, tech stack, diagrams, data flow
    api.md             # API contract (endpoints, schemas, auth)
    data-model.md      # Storage schemas, indexes, relationships
    implementation.md  # Language, conventions, patterns, OOP/LLD decisions
    build-plan.md      # Phased implementation plan
    decisions.md       # Decision log, assumptions, open questions
```

### Naming
- Project name: `kebab-case`, short, distinctive. If the user has a name, use it. If not, propose one based on the product.
- Avoid generic names like `my-app` or `project`.

### File creation
Write everything into `/mnt/user-data/outputs/<project-name>/` so the user can download the skill as a directory. Use `create_file` for each file. At the end, use `present_files` to surface the SKILL.md first, then the references in logical order.

---

## `SKILL.md` (required)

### Frontmatter

```yaml
---
name: <project-name>
description: <one to three sentences describing the project, its purpose, and when Claude should load this skill. Include the project name, the core thing being built, and trigger phrases. Make it "pushy" — Claude tends to undertrigger project skills, so explicitly list relevant user phrases.>
---
```

**Writing the description well** is critical — this determines whether Claude loads the skill in future sessions. Include:
- The project name (users will mention it by name)
- What kind of system it is (so feature-related phrases trigger)
- Explicit trigger phrases: "continue building X", "work on X", "add feature to X", and domain-specific terms

**Example good description**:
> `description: Billwise is a personal finance tracker that ingests bank transactions, auto-categorizes them, and shows monthly spending trends. Use this skill whenever the user mentions Billwise, transactions, categorization, budgets, spending reports, the ingestion pipeline, or wants to continue building, debugging, or extending the app. Load this skill for any work on the project, even if the user only mentions a feature name or file path associated with it.`

### Body

The SKILL.md body should be short (< 200 lines) and act as a table of contents. Structure:

```markdown
# <Project Name>

<One-paragraph project summary: what it is, who it's for, what stage it's at.>

## Current status

<Where the project is right now. On first generation, this is: "Spec complete, implementation not started. See `references/build-plan.md` for the phased plan." Future Claude will update this field as work progresses.>

## Stack at a glance

- **Backend**: <language / framework>
- **Database**: <e.g., Postgres on Neon / Supabase>
- **Frontend**: <if applicable>
- **Hosting**: <where it runs>
- **Key third parties**: <Stripe, Clerk, OpenAI, etc.>

## How to use this skill

Load the references as needed:

- **`references/spec.md`** — What the system does and what it needs to satisfy. Start here to understand goals and requirements.
- **`references/architecture.md`** — Components, tech choices, request flow. Read when making architectural decisions or adding components.
- **`references/api.md`** — API contracts. Read when building or consuming endpoints.
- **`references/data-model.md`** — Data schemas and storage. Read when adding fields, queries, or migrations.
- **`references/implementation.md`** — Language, framework, conventions, and design-pattern decisions. Read before writing code, especially before introducing a new class, module, or pattern.
- **`references/build-plan.md`** — Phased implementation order. Read to know what's next.
- **`references/decisions.md`** — Why choices were made, what's assumed, what's unresolved. Read before making choices that might conflict with existing ones.

## Working on this project

<Key things future Claude should know — conventions, pitfalls, "don't do X" notes. Examples:>

- Always work from `references/build-plan.md` to know the current phase
- Don't add dependencies without checking `references/decisions.md` first
- Migrations go in `db/migrations/` using <tool>
- Run tests with `<command>`
- <Any other gotchas surfaced during the interview>

## Updating the skill

When a milestone completes or major architecture changes, update:
- `## Current status` in this file
- The relevant reference(s)
- `references/decisions.md` with any new decisions made
```

---

## `references/spec.md`

Captures the user-facing and quality requirements.

```markdown
# Specification

## Context & Goals

<One paragraph: why this exists, who it's for, what success looks like.>

**Stage**: <prototype / MVP / production>
**Target users**: <description>
**Success criteria**: 
- <measurable outcome 1>
- <measurable outcome 2>

## Hard constraints

<Any non-negotiables surfaced in the interview: tech, budget, timeline, integrations, compliance. If none, say "None surfaced during interview.">

## Functional Requirements (v1)

Core user-facing capabilities that must exist for v1:

1. **<Capability name>**. <Users should be able to... Brief description of what this means.>
2. **<Capability name>**. <...>
3. **<Capability name>**. <...>

<Keep to 3–7 items. Be concrete about what each includes.>

## User roles

<If relevant:>
- **<Role name>**: <what they can do, how they're different>
- **<Role name>**: <...>

## Primary user flow

<The main happy-path flow through the system, as a numbered list.>

1. User arrives at X
2. User does Y
3. System responds with Z
4. ...

## Non-Functional Requirements

- **Availability**: <target, e.g., "99.9% uptime; scheduled maintenance windows acceptable">
- **Consistency**:
  - Strong: <list the data that needs strong consistency, e.g., orders, payments>
  - Eventual: <the rest, with acceptable staleness, e.g., "feed (< 5 s)">
- **Latency** (p95 targets):
  - <Operation>: < X ms
  - ...
- **Scale**: <DAU at launch, projections; peak QPS; storage>
- **Durability**: <backup frequency, RPO, RTO>
- **Security**: <sensitive data types, auth model, key requirements>
- **Compliance**: <GDPR, HIPAA, PCI, etc., or "none">
- **Observability**: <error tracking, metrics, logging requirements>
- **Cost target**: <monthly budget, per-user cost if relevant>

## Capacity estimates

- **Users**: <X DAU at launch, Y in 12mo>
- **Write rate**: <avg writes/sec, peak writes/sec>
- **Read rate**: <avg reads/sec, peak reads/sec>
- **Storage**: <initial, growth per user per month, 12-mo projection>
- **Read:write ratio**: <e.g., 10:1>
- **Scale tier**: <small / medium / large, based on above>

## Deferred to later

Explicitly out of scope for v1 — do not build these unless the user revisits priorities:

- <Feature / capability deferred>
- <Feature / capability deferred>
- ...
```

---

## `references/architecture.md`

Captures components, tech choices, and how they interact.

```markdown
# Architecture

## High-level diagram

\`\`\`mermaid
graph LR
    Client[Client<br/>(Web + Mobile)] --> LB[Load Balancer]
    LB --> API[API Servers<br/>(stateless, N instances)]
    API --> DB[(Postgres<br/>primary)]
    DB --> Replica[(Postgres<br/>read replica)]
    API --> Cache[(Redis<br/>cache + sessions)]
    API --> S3[(S3<br/>media)]
    API --> Queue[(Job Queue)]
    Queue --> Worker[Background<br/>Worker]
    Worker --> DB
    Worker --> External[External APIs<br/>(email, AI, etc.)]
\`\`\`

<Adjust the diagram to match the actual system. Keep it simple — 5–10 boxes, not 30.>

## Components

### <Component name, e.g., API Layer>
- **Tech**: <specific choice, e.g., FastAPI on Python 3.12>
- **Role**: <what it does>
- **Scaling**: <horizontal / vertical / N/A>
- **Rationale**: <one sentence on why this choice>

### <Component name, e.g., Primary Database>
- **Tech**: <e.g., Postgres 16 on managed service>
- **Role**: <e.g., source of truth for all domain data>
- **Scaling**: <e.g., vertical + read replicas>
- **Rationale**: <one sentence>

<Repeat for each major component.>

## Request flow: <representative endpoint, e.g., "POST /posts">

1. Client sends `POST /posts` with body + auth token
2. Load balancer routes to an API server
3. API server validates auth via <mechanism>
4. API server validates request body
5. API server writes to Postgres (primary) 
6. If media attached: API server uploads to S3, stores URL in DB
7. API server enqueues fanout job for followers
8. API server returns 201 with the new post ID
9. Background worker picks up fanout job, updates feed caches

<Include 1–2 flows — the main write path and the main read path usually.>

## Hosting / deployment

- **Environment**: <e.g., Fly.io, with Postgres on Neon, Redis on Upstash, S3 on Cloudflare R2>
- **Deploy**: <mechanism, e.g., "git push to main auto-deploys">
- **Environments**: <dev, staging, prod, whatever applies>
- **Secrets**: <how they're managed>

## External services

- **<Service>**: <what it's for>
- **<Service>**: <what it's for>

## What's NOT here (and why)

Things a reader might expect to see but that we've consciously excluded:

- **No Redis in v1**: our scale doesn't need it; revisit if feed read latency creeps past 200ms
- **No sharding**: single Postgres handles projected 12-month load; revisit if write TPS exceeds 10K
- **No microservices**: monolith is right for a small team and modest scale

<This section prevents Claude in future sessions from cargo-culting components we deliberately left out.>
```

---

## `references/api.md`

Captures the API contract.

```markdown
# API

## Protocol

**<REST / GraphQL / RPC / ...>**. Rationale: <one sentence>.

Base URL: `<e.g., https://api.example.com/v1>`

## Auth

**<Mechanism, e.g., Session cookies via Clerk / JWT / API keys>**.

- <Detail 1, e.g., "Sessions set via `/auth/callback`, cookies are HttpOnly Secure SameSite=Lax">
- <Detail 2, e.g., "Current user derived from session; never from request body">

## Conventions

- All requests and responses are JSON (`Content-Type: application/json`)
- Timestamps in ISO 8601 (`2026-04-19T12:34:56Z`)
- IDs are <UUIDs / ULIDs / etc.>
- Pagination: <cursor-based / offset-based>; default limit <N>, max <M>

## Error format

\`\`\`json
{
  "error": {
    "code": "validation_error",
    "message": "Human-readable description",
    "details": { "field": "reason" }
  }
}
\`\`\`

Status codes: 400 (validation), 401 (unauthenticated), 403 (unauthorized), 404 (not found), 409 (conflict), 429 (rate limited), 500 (server error).

## Endpoints

### <Resource, e.g., Posts>

#### `POST /posts`
Create a new post.

Request:
\`\`\`json
{
  "text": "string (max 500 chars)",
  "media_urls": ["string", "..."]
}
\`\`\`

Response `201`:
\`\`\`json
{
  "id": "uuid",
  "text": "...",
  "author_id": "uuid",
  "created_at": "2026-04-19T..."
}
\`\`\`

#### `GET /posts/:id`
Fetch a single post.

Response `200`:
\`\`\`json
{ "id": "...", "text": "...", ... }
\`\`\`

<Repeat for each endpoint. 5–15 endpoints is a normal v1 spec.>

## Rate limits

- Authenticated: <N> req/min per user
- Unauthenticated: <N> req/min per IP
- Write endpoints (create / update / delete): <stricter limit>

## WebSocket / SSE endpoints (if applicable)

### `<GET or WS /events>`
- **Direction**: <server → client / bidirectional>
- **Protocol**: <SSE / WebSocket>
- **Auth**: <how>
- **Message format**: <shape>
- **Events**: <list of event types>

## Webhooks (if applicable)

### `POST <your-service>/webhooks/<provider>`
- **From**: <Stripe, etc.>
- **Verification**: <signature check>
- **Idempotency**: <how duplicates are handled>
- **Events handled**: <list>
```

---

## `references/data-model.md`

Captures storage schemas.

```markdown
# Data Model

## Storage choices

- **Primary DB**: <e.g., Postgres — for all transactional data>
- **Object storage**: <e.g., S3 — for user-uploaded media>
- **Cache**: <e.g., Redis — sessions and hot reads> (or "none in v1")
- **Search**: <e.g., Postgres FTS — or "none in v1">

## Entities

### `users`
| Field | Type | Notes |
|---|---|---|
| `id` | uuid | primary key |
| `email` | text | unique, indexed |
| `display_name` | text | |
| `created_at` | timestamptz | indexed |
| `...` | | |

Indexes:
- `users_email_idx` on `email` (unique)
- `users_created_at_idx` on `created_at`

Access patterns:
- Lookup by id (primary key)
- Lookup by email (login)

### `<next entity>`
<Same structure.>

<Repeat for each entity. 5–15 entities is typical for a v1.>

## Relationships

- `posts.author_id` → `users.id` (many-to-one)
- `follows(follower_id, followee_id)` → `users.id`, `users.id` (many-to-many via join table)
- ...

## Key queries and their indexes

| Query | Index used |
|---|---|
| Get user by email (login) | `users_email_idx` |
| Get posts by user (profile page) | `posts_author_id_created_at_idx` |
| Get recent global posts | `posts_created_at_idx` |
| ... | |

## Migrations

- Tool: <e.g., Prisma / Alembic / raw SQL via a runner>
- Location: <e.g., `db/migrations/`>
- Naming: `<convention, e.g., YYYYMMDDHHMM_description.sql>`

## Data retention

- **<Entity>**: <retained forever / N days / archived after M>
- **Soft delete**: <which entities use it; how>
- **Backups**: <frequency, retention window>
```

---

## `references/implementation.md`

Captures how code is structured: language, conventions, and design-pattern decisions. Read `implementation-guidance.md` in the meta-skill before populating this — it has the LLD principles and pattern catalog this builds on.

```markdown
# Implementation

How code is structured, which conventions apply, and which design patterns we've chosen (and deliberately avoided).

## Language and framework

- **Language**: <e.g., Python 3.12>
- **Framework**: <e.g., FastAPI>
- **Package manager**: <e.g., uv / poetry / npm / pnpm>
- **Type-checking**: <e.g., mypy strict / TypeScript strict / none>

Rationale: <one sentence>

## Code structure

```
<project-root>/
  src/
    <module>/       # <what lives here>
    <module>/       # <what lives here>
  tests/
  db/migrations/
  ...
```

Module boundaries: <one or two sentences on what's allowed to import what. E.g., "domain modules don't import from api or infrastructure; api and infrastructure can import from domain.">

## Conventions

- **Naming**: <snake_case for Python, camelCase for TS, etc.>
- **File layout**: <one class per file / one module per file / ...>
- **Error handling**: <exceptions for programmer errors, returned errors for expected failure cases / exceptions everywhere / ...>
- **Logging**: <library, format, levels>
- **Configuration**: <env vars via pydantic-settings / .env / ...>
- **Tests**: <pytest / jest; unit vs integration layout; what's mocked>

## Design principles to hold tightly

- **KISS + YAGNI**: start simple; add patterns only when simple code breaks down. Don't introduce a new class/interface/pattern preemptively.
- **SRP**: one class, one reason to change. Split when a class accumulates multiple responsibilities.
- **Composition over inheritance**: default to composition and interfaces. Inheritance only when sharing stable implementation AND the is-a relationship is genuine.
- **Tell, don't ask**: objects manage their own state; don't reach in, read fields, decide, and write back.
- <Any other principle especially load-bearing for this project>

## Patterns deliberately chosen

Each entry lists the pattern, where it's used, and why.

- **Strategy — `<domain>.<InterfaceName>`**: <why>. E.g., "`payments.PaymentProvider` — we have Stripe now, PayPal and crypto likely in the year."
- **State Machine — `<domain>.<Thing>Status`**: <why>. E.g., "`orders.OrderStatus` — order lifecycle has strict transitions (pending → paid → shipped → delivered); rules belong to each state."
- **Facade — `<orchestrator class>`**: <why, usually "coordinates N components behind a clean interface">.
- ...

## Patterns deliberately avoided

To prevent cargo-culting in later work:

- **No Singletons.** DI instead — pass shared resources through constructors.
- **No Builder** — <e.g., "our domain objects have 2–4 required fields; constructors work fine">.
- **No Factory** unless we have type-based dispatch — <e.g., "currently no polymorphic creation needs">.
- ...

## Class and module hints for the main entities

For the major domain entities, a rough sketch of how they should be modeled, **plus a seed list of correctness expectations** that future implementation work expands into actual tests. Not final code, just orientation.

### `<Entity>`
- **Responsibilities**: <bullets>
- **State**: <fields>
- **Public behavior**: <methods>
- **Rules belonging here**: <which validations / transitions live on this class>
- **Invariants** (must always hold): 
  - <e.g., "balance never goes negative">
  - <e.g., "every entity has exactly one terminal status">
- **Edge cases / error modes to cover before implementing**:
  - <e.g., "operation called in wrong state → raise IllegalStateError">
  - <e.g., "concurrent updates → last write wins, never partial state">

### `<Orchestrator>`
- **Coordinates**: <which other entities>
- **State**: <fields>
- **Public behavior**: <methods>
- **Invariants**: <bullets>
- **Edge cases / error modes to cover before implementing**: <bullets>

<Repeat for the 3–5 most important classes. Skip for simple projects.>

## Correctness-tests-first rule (non-negotiable)

Before implementing any non-trivial class, method, or service in this project:

1. Write down — in prose or as concrete test cases — the specific behaviors that must hold (happy path, edge cases, error / illegal-operation modes, invariants).
2. Pull the seed list above for the relevant entity, then expand it into a full test list for the slice you're building.
3. Trace one realistic scenario through the design *against* the test list before any code is written.
4. Implement to satisfy the tests — happy path first, then edge cases. If you discover a case that wasn't on the list, add it to the list before implementing it.

See the meta-skill's `references/implementation-guidance.md` § 1 (Implementation-time delivery framework) for the full framework. This is the single most important LLD rule for the project.

## Testing approach

- **Tests-first per slice**: see the rule above. No PR introduces a new non-trivial class without its test list documented (in code or in the PR description).
- **Unit tests**: <what's unit-tested — usually domain logic>
- **Integration tests**: <what's integration-tested — usually API + DB>
- **Property-based tests** (where they apply): <e.g., "encode/decode round-trip, sort produces a permutation, idempotent retries are safe">
- **Mocks**: <what's mocked, what uses real implementations>
- **Coverage target**: <e.g., "no hard number; domain logic should be ~90%, infra code ~50%">
- **How to run**: <command>

## Cross-cutting concerns

- **Auth**: <how the current user is attached to requests; where the check happens>
- **Rate limiting**: <middleware / API gateway / app-level>
- **Observability**: <structured logging, metrics, error tracking — where it's wired in>
- **Idempotency**: <for write endpoints: how keys are generated and checked>
- **Background jobs**: <queue + worker library; where jobs are defined>
```

---

## `references/build-plan.md`

Captures the phased implementation order.

```markdown
# Build Plan

The project is built in vertical slices — each phase ends with a demoable, deployable unit of progress.

## Phase 0 — Scaffolding

**Goal**: everything needed to build and deploy, but no features.

- Repo with CI
- Dev environment (docker-compose or equivalent)
- Deploy pipeline to staging
- DB migrations infrastructure
- Auth stub (managed provider wired up, no custom flows yet)
- Error tracking + basic metrics
- README with setup instructions

**Done when**: a new engineer can clone, run locally, and deploy a "hello world" to staging.

## Phase 1 — <Core slice name, e.g., "User can sign up and post">

**Goal**: <the single most important user flow, end to end>.

Scope:
- <Specific capability 1>
- <Specific capability 2>
- <UI for this flow>

**Done when** (each criterion below should be a concrete, testable condition — write the test before the code):

- *Given* <starting state>, *when* <action>, *then* <observable result> — <e.g., "Given a new visitor, when they submit valid signup with a unique email, then an account is created, a session is set, and they land on the onboarding page.">
- *Given* <error state>, *when* <action>, *then* <error result> — <e.g., "Given an existing email, when signup is attempted, then a 409 is returned with `error.code = 'email_exists'` and no account is created.">
- <Observable system invariant that must hold across the phase>

**Not in this phase**: <list things that might seem like they belong here but are deferred>.

## Phase 2 — <Next slice>

<Same structure.>

## Phase 3 — Hardening

**Goal**: make it production-worthy.

- Deep dives: <the hard problems identified>
- Rate limiting
- Better error messages
- Observability: dashboards, alerts, runbooks
- Backups verified
- Load test the critical paths
- Security review

**Done when**: we can confidently put this in front of real users.

## Phase 4 — Nice-to-haves

<The deferred list from the spec. Can be a bullet list, no need for detail yet.>

---

## Current phase

**→ <Phase N>**

<Updated as work progresses. Future Claude sessions update this field when a milestone is hit.>

## Dependencies between phases

<If any non-trivial dependencies, list them. Often you can skip this section.>
```

---

## `references/decisions.md`

Captures the decision log, assumptions, and open questions.

```markdown
# Decisions, Assumptions, and Open Questions

## Decisions log

Key choices and their rationale. Entries are append-only — don't delete, just add new entries that supersede.

### <date> — <short decision title>
**Decision**: <what was chosen>.
**Why**: <1–2 sentences of rationale>.
**Considered**: <alternatives and why rejected>.
**Revisit if**: <condition that would make us reconsider>.

### <date> — <...>
...

## Assumptions

Things we assumed without fully confirming. If any turn out wrong, parts of the design may need revisiting.

- <Assumption>. <Implication if wrong.>
- ...

## Open questions

Things we deliberately didn't resolve. Each should have a target for when it needs answering.

- **<Question>** — <who owns the answer> — <by when>
- ...

## Glossary

<Any domain terms or internal names that might not be obvious. Often a few terms saves hours of confusion later.>

- **<Term>**: <definition>
- ...
```

---

## Writing guidance for the generated skill

- **Be concrete.** "Stores user data" is not a specification. "Stores users in a `users` table with (id, email, name, created_at), indexed on email" is.
- **Write like you're briefing your replacement.** Future Claude — or a future human dev — will read this cold. No "as we discussed" references.
- **Mermaid over prose** for diagrams.
- **Tables over bullets** for structured data (schemas, endpoints, metrics).
- **Specific tech choices, not categories.** "A database" is not a choice. "Postgres 16 on Neon" is.
- **Capture rationale, not just decisions.** "Postgres" tells the next person what; "Postgres because we need relational joins and pgvector for RAG" tells them why.
- **Keep it under ~200 lines per reference file** where possible. If a reference is sprawling, split it.

## After generating

1. Write all files to `/mnt/user-data/outputs/<project-name>/`
2. Use `present_files` to surface them — SKILL.md first, then references in logical order (spec, architecture, api, data-model, build-plan, decisions)
3. Give the user a short summary: "Here's the skill. Install it by <brief instructions>, and in a future session just say '<trigger phrase>' to pick up where we left off."
4. Offer a quick check: "Anything you'd change before we call this done?"
