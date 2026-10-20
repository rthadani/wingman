---
name: system-design-interview
description: Answer a system design question end-to-end in a single response — requirements, capacity, architecture, deep dives, and trade-offs, with assumptions stated rather than asked.
---

# System Design (live, single response)

The candidate is in a live interview. Deliver the whole design in ONE response. Never ask a question and wait — state your assumptions and proceed.

## Response structure

Use these sections in order, compactly:

1. **Assumptions** — 3-5 bullets covering what you would otherwise ask: scale, read/write ratio, consistency needs, latency target, data volume, whether this is v1.
2. **Requirements** — functional (3-5 capabilities) and non-functional (scale, latency, availability, consistency) in one line each.
3. **Capacity estimate** — back-of-envelope: users, QPS, storage per year, bandwidth. Show the arithmetic in two or three lines; name the number that drives the design.
4. **Entities and API** — the core nouns and the 3-5 endpoints that matter.
5. **Data model** — tables/keys/indexes, one line each. Name the storage choice and why.
6. **Architecture** — the components and the request path, written as a short flow (client → gateway → service → cache → store), plus the one or two components that carry the design.
7. **Deep dives** — the 2-3 hardest problems for THIS system, each as: problem → options → choice → trade-off. Include the invariant each choice protects.
8. **Failure and consistency** — what breaks first, and how it degrades.
9. **Build plan** — phased: what ships first, what proves it works, what comes later.
10. **Scaling extension** — answer the follow-up they will ask ("now 10 billion records across 500 stores"): what breaks, what changes, what stays.
11. **Risks** — two or three, one line each.

## Rules

- One response, full design. No "shall I continue?", no phase-by-phase drip.
- Recommend, do not offer menus: "Postgres with a composite index" beats "SQL or NoSQL?"
- Be concrete about scale numbers and why they drive the choice.
- Prefer boring, proven components; call out over-engineering explicitly.
- State trade-offs in one line each; never pretend a choice has none.
- Keep the whole answer tight enough to read aloud in a few minutes.
- If the candidate's prompt lacks context, assume sensible defaults (B2C, read-heavy, eventual consistency, v1) and say so in Assumptions.
