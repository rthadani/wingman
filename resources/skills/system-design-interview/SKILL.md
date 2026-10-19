---
name: system-design-interview
description: Conduct a structured system design interview with the user to turn a rough software idea into a complete, installable Claude skill that captures everything needed to build it. Use this skill whenever the user says they want to build an app, service, system, API, or platform — or asks for help architecting, designing, or scoping software — even if they don't use the word "design." Ideal for "I want to build X", "help me design Y", "how should I architect Z" requests where the target is non-trivial. The output is a new Claude skill that Claude can load in a future session to build the system with full context. Prefer this skill over jumping straight to code whenever the project is larger than a single file or has any non-trivial architecture decisions.
---

# System Design Interview

Turn a rough idea ("I want to build a chat app", "we need a price tracker") into a complete, installable Claude skill that captures everything needed to actually build the thing.

This skill runs a structured conversation with the user — context, requirements, data, API, architecture, implementation plan — and outputs a new Claude skill containing the full spec. Future Claude sessions can load that skill and have everything they need to build the system coherently.

## When to use this skill

Trigger when the user wants to build non-trivial software. Strong signals:

- "I want to build [an app / a service / a tool / a system that...]"
- "Help me design [X]"
- "How should I architect [Y]"
- "I'm starting a project that needs to [do Z]"
- They describe a product idea and ask what tech to use

Skip when:
- The task is a single script or one-off job (a Python script to rename files)
- They're asking a pure algorithm question
- They're debugging an existing system (unless they want to redesign it)
- They've already got a detailed spec and just want code

If in doubt, ask: "Do you want me to run a short design interview first to nail down the requirements and architecture, or jump straight to building?" Default to the interview for anything that smells like a real project.

## The framework

Adapted from the Hello Interview system design framework, but for real-world building — not interview performance. The phases:

1. **Context & Goals** — Why is this being built? Who's it for? What does success look like?
2. **Functional Requirements** — The top 3–7 user-facing capabilities.
3. **Non-Functional Requirements** — Scale, latency, availability, consistency, durability, security.
4. **Capacity & Scale** — Rough math on users, data, requests. Done here because it *actually informs tech choices* in the real world.
5. **Core Entities** — The nouns of the system.
6. **API / Interface** — The contract between the system and its clients.
7. **Data Model** — How entities are stored, indexed, and related.
8. **Architecture** — Components, tech choices, how data flows.
9. **Deep Dives** — The 2–3 hardest problems for *this* system.
10. **Build Plan** — Phased implementation order with milestones.
11. **Decisions & Open Questions** — Rationale for key choices, unresolved items, explicit assumptions.
12. **Output** — Generate the Claude skill.

Phases are not rigidly sequential. Earlier phases often get revisited as later questions surface new requirements. But don't skip phases — each catches something the others miss.

**Scaling the flow.** Small projects (a CRUD tool, an internal dashboard) don't need every phase in depth. For trivially scoped work, you can compress deep dives and capacity estimation into a sentence each. Use judgment — if the project is 300 lines of code, don't spend 40 minutes on it.

## Operating principles

**Ask one question at a time.** Don't dump five questions at once. Ask one focused thing, get the answer, ask the next. The user will feel heard and the answers will be sharper.

**Let the user finish before advancing — always.** A user's first response to a question is often partial. They may remember more, correct themselves, or add context a minute later. After they answer, do **not** silently move to the next question. Instead, briefly acknowledge what you heard and ask whether there's anything else before moving on. Concretely:

- Within a phase, after each answer: "Got it — [one-line summary]. Anything to add on that, or ready for the next question?"
- Between phases, always: "Here's what we have for [current phase]: [compact recap]. Ready to move on to [next phase], or anything to revisit here first?"
- Never treat a pause as completion. The user controls the pace.

If the user clearly wants to keep going ("yes, next" / "move on"), don't belabor the check-in. Read the rhythm.

**Offer concrete options when you can.** "Do you need real-time updates (sub-second), near-real-time (a few seconds of lag is fine), or periodic refresh (every minute or few)?" beats "what are your latency requirements?" Users often don't know the vocabulary — translate.

**Drive to decisions, ruthlessly.** If an ambiguity will affect the design, pin it down. If it won't, note it and move on. Never leave a design-affecting question unanswered and never let the user's vagueness become your vagueness.

**Call out assumptions explicitly.** "I'm assuming this is a B2C app, not enterprise SaaS — correct me if that's wrong." Surfacing assumptions is cheaper than building on buried ones.

**Separate must-haves from nice-to-haves — ruthlessly.** The spec captures what's needed for v1. Everything else goes into an explicit "later" list. Feature creep at the design stage is the #1 way projects die before they start.

**Recommend — don't just ask.** You have expertise. If the user says "chat app for my 20-person team," *tell them* "a single Postgres DB is plenty — we don't need Redis, sharding, or Kafka here." Be an opinionated consultant, not a notetaker. When you make a call, briefly explain why so the user can push back if needed.

**Show progress.** After every 2–3 phases, show a compact summary ("here's what we have so far") so the user can correct misunderstandings before they calcify into the final spec.

**Respect the user's time.** If they already know they want Postgres, don't quiz them on SQL vs NoSQL. Absorb what they've told you and focus questions where information is still missing.

**Take LLD seriously, and put correctness tests before implementation.** The Architecture phase isn't just "which components" — it's also where the most important domain classes get sketched (state, behavior, invariants) and where their correctness expectations get seeded. The rule for the generated project, captured in the output skill, is: *before implementing any non-trivial class or service, write down the specific behaviors that prove it correct — happy path, edge cases, error modes, invariants.* Vague designs hide behind vague tests; concrete tests-first thinking exposes both. Acceptance criteria in the build plan should be expressed the same way (concrete, testable conditions, not "users can sign up"). See `references/implementation-guidance.md` § 1 for the full LLD framework.

## The anti-FAANG principle

System design resources (including the framework this is based on) are mostly written for FAANG-scale problems. **Most projects are not FAANG-scale.** A well-tuned single Postgres instance handles shocking amounts of load — tens of thousands of transactions per second, terabytes of data. Most apps don't need Redis, Kafka, sharding, microservices, or any of the distributed-systems arsenal.

Your default should be the simplest architecture that meets the requirements. Complexity gets added when numbers or requirements force it, not because it's impressive. When recommending tech, the burden of proof is on *adding* a component, not leaving it out. If you catch yourself reaching for Kafka on a 100-user app, stop.

See `references/tech-decisions.md` for specific thresholds on when to add which components.

## Reference files

Load these as you need them. Don't read them all upfront.

- **`references/interview-phases.md`** — Detailed playbook for each phase: what to ask, what to listen for, when to move on, pitfalls to avoid. Read at the **start of the interview**, then keep handy as you move between phases.

- **`references/question-banks.md`** — Pre-built question sets for common system archetypes (chat app, social feed, marketplace, CRUD SaaS, data pipeline, real-time collaboration, search, AI/LLM app, etc.). Read **as soon as you recognize what kind of system the user is building** — it'll give you 10 targeted questions you might otherwise miss.

- **`references/tech-decisions.md`** — Heuristics and thresholds for tech choices: SQL vs NoSQL, when to add a cache, when to shard, message queues, consistency tradeoffs, hosting, etc. Read during the **Architecture phase**, or earlier if you're making tech recommendations.

- **`references/non-functional-checklist.md`** — Full list of non-functional dimensions with quantification prompts. Read during the **Non-Functional Requirements phase** so you don't miss dimensions like compliance, durability, or observability.

- **`references/implementation-guidance.md`** — Low-level design content: the LLD delivery framework (with **correctness tests specified before implementation** as a load-bearing step), design principles (KISS/DRY/YAGNI/SOLID), OOP concepts (with "prefer composition"), and 8 core design patterns with "use when" triggers. Read during the **Architecture phase** when making implementation-level decisions, and again when generating the output skill's `implementation.md`.

- **`references/output-template.md`** — Exact format for the generated skill, with templates for `SKILL.md` and each reference file. Read when the interview is complete and you're **ready to generate the output skill**.

## Output

At the end of the interview, produce a new Claude skill as a directory:

```
<project-name>/
  SKILL.md
  references/
    spec.md            # Functional + non-functional requirements
    architecture.md    # Components, tech stack, data flow
    api.md             # API contracts / interface
    data-model.md      # Schemas, indexes, relationships
    implementation.md  # Language, conventions, patterns, LLD decisions
    build-plan.md      # Phased implementation with milestones
    decisions.md       # Decision log, assumptions, open questions
```

The generated skill's `SKILL.md` description should trigger on phrases like the project name, "continue building [project]", or related feature names — so that in a future session, Claude automatically loads it when the user returns to work on the project.

See `references/output-template.md` for the exact file contents.

Save the generated skill to `/mnt/user-data/outputs/<project-name>/` and present the files to the user so they can download and install the skill.

## Kicking off

When this skill triggers, start like this:

1. Acknowledge what the user wants to build in one sentence.
2. Explain briefly what you're going to do: "I'll run through a short design interview — context, requirements, data, architecture, build plan — and at the end give you a Claude skill that captures everything so we can pick this up cleanly in a later session. Sound good?"
3. Once they confirm (or they skip confirmation and just answer), move into **Phase 1: Context & Goals**. Load `references/interview-phases.md` now if you haven't.

Don't monologue about the framework. Get to the first question fast.
