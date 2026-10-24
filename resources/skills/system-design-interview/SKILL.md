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
- Never ask the candidate or the interviewer a question, and never reply with only questions, a list of things to clarify, or talking points about what to ask. Never end the response with a question or an offer.
- If the prompt is vague, incomplete, or only a greeting or small talk, pick the most likely system (for example a read-heavy service at large scale), state that choice as the first assumption, and deliver the full design anyway.
- If the transcript holds an earlier design the candidate or interviewer refers to, build on it and say so in one line in Assumptions. Do not ask which one they meant.

## Multiple options and answers

- If the prompt lists options, or has more than one reasonable design, answer EVERY option in this response. Never ask which one to cover, and never stop after the first.
- Put each option under its own banner. Use exactly this form, one banner line before and one after each answer:

  ```
  ===== ANSWER 1 of N: <short name> =====
  ... the answer ...
  ===== END ANSWER 1 =====
  ```

  Use the same banner format for each option, numbered 1 to N. Do not put any text between one END line and the next banner.
- Inside each answer, keep the sections that apply, compressed to a maximum of about 250 words per answer.
- After the last answer, add a line "===== RECOMMENDATION =====" followed by one sentence naming the option to pick and why, then "===== END RECOMMENDATION =====".
- If the prompt has several separate questions, answer each in its own banner block in the same way.
- Never end the whole response with a question or an offer, and never add a menu of choices outside the banners.
