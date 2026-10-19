# Non-Functional Requirements Checklist

Use this during Phase 3 (Non-Functional Requirements) to make sure you don't miss a dimension that matters. Go through the list, decide whether each dimension applies, and if it does, pin down a concrete target with the user.

**The cardinal rule**: quantify everything. "It should be fast" means nothing. "< 200ms p95 for the feed load" means something you can design around.

---

## Core dimensions

### 1. Availability
**Question**: What's the acceptable downtime?

| Target | Downtime allowed | What it implies |
|---|---|---|
| 99% | ~3.65 days/year | Any reasonable single-server setup |
| 99.9% ("three nines") | ~8.76 hours/year | Redundancy, automatic failover |
| 99.95% | ~4.38 hours/year | Multi-region failover for most workloads |
| 99.99% ("four nines") | ~52.56 minutes/year | Serious investment — active-active multi-region |
| 99.999% ("five nines") | ~5.26 minutes/year | Telecom-grade, very expensive |

**Questions to ask**:
- "If this goes down for 10 minutes, what happens? For an hour? For a day?"
- "Can you tolerate a planned maintenance window?"
- "Is it user-facing or internal?" (Internal tools can usually tolerate more.)

**Default for most startups / internal tools**: 99% to 99.9%. Don't over-engineer.

---

### 2. Consistency
**Question**: What kind of consistency does the data need?

- **Strong consistency**: every read returns the latest write. Needed for money, inventory, limited resource booking (tickets, seats, unique usernames).
- **Eventual consistency**: reads may be stale by seconds. Fine for feeds, counters, notifications, recommendations, analytics.
- **Read-your-writes**: a user sees their own writes immediately, but others may not. Good default for many user-facing systems.

**Questions to ask**:
- "If user A posts and user B refreshes, is it OK if B sees the post 1–2 seconds later?"
- "Are there any places where two users updating the same thing at once would be catastrophic?"

**Per-feature, not per-system**: the same app often needs strong consistency for some things (orders) and eventual for others (feeds). Record the mapping.

---

### 3. Latency
**Question**: What's the target response time?

Quantify for the **user-facing p95** (95th percentile — not average).

| User experience | Target p95 |
|---|---|
| Feels instant | < 100 ms |
| Feels fast | < 300 ms |
| Noticeable but fine | < 1 s |
| Feels slow | 1–3 s |
| "Is this broken?" | > 3 s |

**Questions to ask**:
- "Which operations need to feel instant?"
- "Which can take a second or two?"
- "What's the target p95 for the main feed / search / login?"
- For global users: "Is everyone in one region, or do we have users far from our servers?"

**Watch out for**: light-of-speed math. NY ↔ London is 80ms minimum. If you need < 200ms globally, you need regional deployments.

---

### 4. Throughput / Scale
**Question**: How much traffic, and what's the shape?

Already covered in Phase 4 (Capacity) but worth restating in NFR form:
- DAU / MAU target (launch, 6mo, 2y)
- Peak QPS, average QPS
- Read/write ratio
- Storage growth

**Scale tiers**:
- **Small**: < 10K DAU, single box handles it
- **Medium**: 10K–1M DAU, single DB + read replicas + caching
- **Large**: > 1M DAU, real distributed architecture

---

### 5. Durability
**Question**: What's the tolerance for data loss?

- "If we lost the last 5 minutes of data, is that...?" (Catastrophic / Annoying / Fine.)
- "If we lost everything and had to restore from last night's backup, how bad is that?"

**Levels**:
- **"Can't lose anything"**: synchronous replication, frequent backups, point-in-time recovery. Banking / payments / medical.
- **"Can lose minutes, not hours"**: async replication + hourly backups. Most apps.
- **"Can lose up to a day"**: daily backups. Analytics / logs / non-critical.

**Watch out for**: "durability" and "backups" are not the same thing. Replication protects against server failure; backups protect against bugs, deletes, and ransomware. You usually want both.

---

### 6. Security
**Question**: What's at risk if the system is compromised?

- "What's the most sensitive data in the system? (PII, payments, health, IP, credentials)"
- "Who's the threat model? (Random script kiddies, targeted attackers, nation-states?)"
- "What compliance regimes apply? (GDPR, HIPAA, PCI, SOC 2, FedRAMP)"
- "Do you need encryption at rest? In transit? (In transit is always yes.)"
- "Auth mechanism for users? For services?"
- "Multi-tenant isolation requirements?"

**Security baseline (non-negotiable for any production system)**:
- HTTPS everywhere
- Passwords hashed (bcrypt/argon2), never stored plain
- SQL parameterization (no string concatenation into queries)
- Session/token expiry and rotation
- Rate limiting on auth endpoints
- Secrets in a vault or env vars — never in code

**Extra for sensitive data**:
- Encryption at rest
- Audit logging
- PII access reviews

---

### 7. Compliance
**Question**: Are there legal / regulatory / contractual requirements?

- **GDPR** (EU users): right to deletion, data portability, consent tracking, data residency.
- **HIPAA** (US health): BAAs with every vendor, audit logs, specific infrastructure requirements.
- **PCI DSS** (payments): don't touch card data directly — delegate to Stripe/Braintree.
- **SOC 2**: formal controls, annual audits. Usually a B2B sales requirement.
- **COPPA** (US, under 13): special consent + limits.
- **Accessibility** (WCAG / ADA): often a legal requirement for government, healthcare, education.
- **Data residency**: some customers / countries require data to stay in specific regions.

**Questions**:
- "Who are your users — consumers, businesses, government, healthcare?"
- "Any requirements from customers or investors I should know about?"

---

### 8. Observability
**Question**: How will you know when something's wrong?

- **Metrics**: request rates, latencies, error rates (RED: Rate, Errors, Duration).
- **Logs**: structured, centralized, searchable, retained.
- **Traces**: for distributed systems, to follow a request across services.
- **Alerts**: on symptoms users feel (high error rate, high latency) — not just on infrastructure (CPU).

**Defaults**:
- Every production system needs error tracking (Sentry, Rollbar, or similar).
- Every production system needs basic metrics and uptime checks.
- Tracing is worth adding if you have >3 services or debugging cross-service issues.

**Questions**:
- "Who gets paged when this is down?"
- "What's an acceptable time to detect an issue? To resolve?"

---

### 9. Maintainability / Operability
**Question**: How painful is this to run day-to-day?

Often ignored but always important:
- Deploys: how fast, how safe, how reversible?
- Migrations: zero-downtime?
- Feature flags?
- On-call rotation and runbooks?
- Local dev environment: how fast does it spin up?

**Questions**:
- "Who'll be maintaining this — you alone, a team, a future unknown person?"
- "How often does it need to deploy — daily, weekly, monthly?"

---

### 10. Cost
**Question**: What's the budget?

- **Infrastructure cost**: DB, storage, compute, bandwidth, managed services.
- **Third-party APIs**: LLMs, payments, email, SMS can dominate the bill.
- **Cost per user**: useful for unit economics.

**Questions**:
- "What's a comfortable monthly infrastructure budget?"
- "If this has AI calls, what's the budget per user per month?"
- "What would make you want to optimize cost hard?"

**Watch out for**: LLM costs balloon fast. Anything else you can mostly throw hardware at.

---

### 11. Portability / Lock-in
**Question**: How tied to specific vendors is OK?

- Serverless on AWS means rewriting to move off AWS.
- DynamoDB is not trivially portable — access patterns are baked in.
- Postgres is portable. S3-compatible storage is portable.
- Managed Kafka can be moved; managed Kinesis cannot.

**Questions**:
- "Is multi-cloud or cloud-agnostic a requirement?"
- "Any specific vendor constraints (e.g., 'we're an AWS shop')?"

For most startups, lock-in is a fine trade for velocity. Note it as a known tradeoff.

---

### 12. Accessibility / Internationalization
**Question**: Who are the users and where are they?

- **Accessibility**: screen readers, keyboard navigation, color contrast, captions for video. Often a legal requirement. Always the right thing to do.
- **Localization**: multiple languages, date/number/currency formats, right-to-left scripts.
- **Geography**: global users vs. single-region, data residency, latency implications.

**Questions**:
- "Single language or multi-lingual?"
- "Any accessibility requirements from customers or regulation?"
- "Where are the users geographically?"

---

## How to work the checklist

1. Start with the top 6 (Availability, Consistency, Latency, Scale, Durability, Security). These apply to almost every system.
2. Add the rest as relevant — don't force it. A personal project doesn't need a compliance conversation.
3. **Quantify every target.** "Highly available" ❌ → "99.9% uptime, can tolerate 5-min planned maintenance windows" ✅.
4. Flag tradeoffs explicitly: "availability > consistency for the feed, but strong consistency for payments."
5. Capture what you learn in the spec with numbers, not adjectives.

---

## Sample output shape

The NFR section of the spec should end up looking roughly like:

```markdown
## Non-Functional Requirements

- **Availability**: 99.9% uptime; scheduled maintenance windows OK
- **Consistency**:
  - Strong: orders, payments, inventory
  - Eventual (< 5 s staleness): feed, like counts, recommendations
- **Latency** (p95):
  - Login, CRUD ops: < 300 ms
  - Feed load: < 500 ms
  - Search: < 1 s
- **Scale**: 100K DAU at launch, targeting 1M within 18 months
- **Durability**: hourly backups + point-in-time recovery; no tolerance for order/payment data loss
- **Security**: PII + payment data present; PCI DSS via Stripe; GDPR compliance
- **Observability**: Sentry for errors, basic metrics + uptime checks, pager rotation for core services
- **Cost target**: < $5K/month infra at 100K DAU
```
