# Implementation Guidance: LLD, Principles, OOP, Patterns

Read this file when you need to produce or review code, or when populating the `implementation.md` file in the generated skill's output.

This is **implementation-side** content — it's about how code gets structured, not which components sit in the architecture. The companion to `tech-decisions.md` (which is about *which* components) and `interview-phases.md` (which is about conducting the interview).

## The cardinal rules

**1. Correctness tests come before implementation.** Before writing the body of a non-trivial class or method, write down — in prose or as concrete test cases — the specific behaviors that must hold for it to be correct. Happy path, edge cases, error modes, invariants. This serves three purposes: it forces the design to be precise, it surfaces ambiguity in the requirements, and it gives you an executable definition of "done." Vague designs hide behind vague tests; concrete tests-first thinking exposes both.

**2. Most good implementations use zero or one design pattern.** The single most common LLD mistake is forcing patterns or principles where simpler code would work. If you catch yourself reaching for Factory + Builder + Strategy on a 200-line project, stop.

Principles and patterns are tools for when simpler code stops working — not starting points. Start simple. Add structure when the simple approach genuinely breaks down.

## Table of Contents
1. [Implementation-time delivery framework](#1-implementation-time-delivery-framework)
2. [Design principles](#2-design-principles)
3. [OOP concepts](#3-oop-concepts)
4. [Design patterns](#4-design-patterns)
5. [Common over-engineering mistakes](#5-common-over-engineering-mistakes)

---

## 1. Implementation-time delivery framework

When writing a meaningful class or module (not a one-off script), follow this order. It mirrors the architecture-interview flow, scaled down to a single subsystem.

The crucial property of this framework: **steps 1–5 happen before any implementation code exists**. By the time you start step 6, you should already know what "correct" means in concrete, checkable terms. If you can't articulate the tests, you don't yet understand the design.

### Step 1: Nail requirements for this slice
What does this class/module need to do? What's in scope, what's out? Don't skip this even for code-level work — it's how you avoid scope creep and over-engineering. If building from `build-plan.md`, re-read the acceptance criteria for the current phase.

### Step 2: Identify entities and relationships
Scan the requirements and pull out the nouns. For each:
- If it holds state or enforces rules → probably its own class
- If it's just data attached to something else → probably a field

Map relationships: has-a (composition), uses (dependency), contains (aggregation). Identify the **orchestrator** — the entity that drives the main flow.

### Step 3: Design each class (state + behavior)
Go entity by entity, top-down starting with the orchestrator.

For each class, answer:
- **State**: what does it need to remember? Derive directly from requirements — for each requirement, ask "what must this class track to satisfy it?"
- **Behavior**: what operations does the outside world need? Each method should correspond to a real action or question implied by the requirements.

Anchor on **"Tell, Don't Ask"**: objects manage their own state and expose behavior. Callers should not reach in, read fields, make decisions, and write back. Rules live with the entity that owns the relevant state.

### Step 4: Specify correctness tests *before* writing implementation
This is the load-bearing step. For each meaningful class or method, write down the specific behaviors that prove it correct — *before* you implement it. The list should be concrete enough that someone else could turn it into a passing test suite.

For each method/behavior, enumerate at least:

- **Happy path** — given valid inputs and the expected starting state, the result is X.
- **Edge cases** — empty inputs, single-element inputs, max-size inputs, boundary values (0, 1, N, N+1), unicode/whitespace, duplicates, ordering, concurrency races where relevant.
- **Error / illegal-operation modes** — invalid input shape, operation called in the wrong state, missing prerequisites, expired tokens, exceeded limits. State the exception or error each one produces.
- **Invariants** — properties that hold across *all* operations (e.g., "balance never goes negative," "every order has exactly one final terminal state," "ids are stable across reads").
- **State transitions** — for stateful objects, list the legal transitions and at least one test per illegal transition.

A useful template per behavior:

> *Given* [starting state] *when* [operation with these inputs] *then* [observable result] *and* [invariant still holds].

Three rules for this step:

1. **Write the test list before coding.** If a test is hard to express, the design probably has a hole — fix the design before writing code that papers over it.
2. **Prefer behavioral assertions over implementation assertions.** Test what the class does, not how it does it. "After deposit(100), balance() == 100" — not "the internal `_amount` field equals 100."
3. **Cover the unhappy paths explicitly.** Most bugs hide in the cases nobody listed. If you can't name the error modes, you can't claim correctness.

For algorithmic code, also write down 2–3 **property-based** assertions if any apply (sort produces a permutation; encode/decode round-trips; idempotent operations are idempotent under retry).

These tests are the spec for step 6. They should also flow directly into the actual test suite — keep the wording close to test names.

### Step 5: Trace a concrete scenario
Pick a realistic example and walk through it step by step against the design from step 3 and the tests from step 4. Show initial state, each operation, state changes, and transitions, and confirm that each step would satisfy the relevant test from step 4. This catches logical errors and missing tests before any code is written.

### Step 6: Implement to satisfy the tests — happy path first, then edge cases
For each method:
1. Write the straight-line happy path first — make the happy-path tests pass.
2. Then handle the edge cases and error modes from step 4 explicitly; don't let them propagate as unhandled exceptions.
3. Re-check invariants — every public method should preserve them on entry and exit.

If during implementation you discover a case you didn't list in step 4, **add it to the test list first**, then implement. Don't let cases sneak in untested.

### Step 7: Consider extensibility last
Once it works, ask: "what would I do if requirement X were added?" If the answer is "rewrite everything," reconsider the design. If it's "add a new class and wire it in," you're probably fine. **Don't** preemptively build the extensibility — just verify the seams exist.

---

## 2. Design principles

Two tiers: **general** (apply everywhere) and **SOLID** (apply to class hierarchies specifically).

### General (the big three)

**KISS — Keep It Simple.** The simplest solution that works is usually right. Pick a straightforward approach. A single class beats a factory-strategy-decorator trio for most problems. Add complexity only when simplicity has measurably stopped working (file > 500 lines, change requires edits in 5 places, etc.). *This is the single most violated principle.*

**DRY — Don't Repeat Yourself.** When the same *conceptual* logic appears in multiple places, consolidate it. One place to fix bugs, one place to change rules. But: textually similar ≠ conceptually same. Two functions that look alike but serve different purposes may rightfully be duplicates. DRY also conflicts with KISS — sometimes simple duplication beats a premature abstraction. Rule of thumb: duplicate until the third occurrence, then consolidate.

**YAGNI — You Aren't Gonna Need It.** Build what's needed now, not what might be needed later. You will usually guess wrong about the future, and now you're maintaining unused code. Design *with* future extension in mind (clean seams), but don't *implement* the extension until it's actually needed.

### General (other)

**Separation of Concerns.** Different parts of the code should handle different responsibilities and not know about each other's internals. UI doesn't contain business logic; business logic doesn't know about storage; storage doesn't format strings for display. Lets you change one layer without touching the others, and test them independently.

**Law of Demeter** ("least knowledge"). A method should talk only to its immediate friends. `order.getCustomer().getAddress().getZipCode()` is a smell — now your code knows the internal shape of three objects. Prefer `order.getCustomerZipCode()` that handles the traversal internally. (Fluent builders like `b.setName().setAge().build()` are fine — they return the same object type. The problem is chains across different types.)

### SOLID (for class design specifically)

Heads up: SOLID originated in Java's deep-inheritance era. Modern languages favor composition over class hierarchies, and **excessive SOLID can itself be an over-engineering mistake**. Apply when the problem calls for it; skip when simpler works.

**SRP — Single Responsibility Principle.** A class should have one reason to change. A `Report` class that generates content, formats PDFs, and writes files has three reasons to change. Split them.

**OCP — Open/Closed Principle.** Open for extension, closed for modification. Use interfaces/abstractions so adding new behavior means writing new classes, not editing existing ones. Classic trigger: a method with `if type == "X" / elif type == "Y" / elif type == "Z"` that keeps growing.

**LSP — Liskov Substitution Principle.** Subclasses must work wherever the base class works. If `Penguin extends Bird` but `Penguin.fly()` throws, you've broken LSP — callers of `Bird.fly()` can no longer substitute any `Bird`. Red flag: subclasses that throw "not implemented" or require callers to `if instanceof` check.

**ISP — Interface Segregation Principle.** Small focused interfaces beat fat ones. Classes should not be forced to implement methods they don't use. If `Robot implements Worker` has to stub `eat()` and `sleep()`, split `Worker` into `Workable`, `Feedable`, `Restable`.

**DIP — Dependency Inversion Principle.** Depend on abstractions, not concrete implementations. `NotificationService` should accept a `MessageSender` interface in its constructor, not `new EmailSender()` inside. Enables swapping implementations (email → SMS) and testing with mocks. Note: DIP is the principle; *dependency injection* is one technique for achieving it.

### Meta-principle

You don't need to name these in comments or commit messages. Use them as tools for thinking, not a checklist to recite. A good design that silently follows them beats a mediocre one that name-drops them every few lines.

---

## 3. OOP concepts

The four core OOP mechanisms, applied with modern sensibility.

### Encapsulation — hide state, expose behavior
Keep fields private. Modify state through methods that can enforce rules (`Account.withdraw(amount)` can check for overdraft; a public `balance` field can't). Returning mutable internal collections is a leak — return copies or unmodifiable views if the caller shouldn't be able to mutate.

### Abstraction — define interfaces for variation
Where the system has variation (multiple payment methods, multiple notification channels, multiple storage backends), define an interface for the variation and keep implementations behind it. Callers depend on the interface; swapping implementations doesn't touch callers.

The hard part is picking the right level. Too abstract (`doWork()`, `handleRequest()`) is meaningless; too specific isn't really an abstraction. Think about what operations callers genuinely need — that's your interface.

### Polymorphism — let objects handle themselves
The replacement for `if type == "X" / elif type == "Y"`. Once you have an abstraction, each implementation handles its own variant. Callers don't switch on type; they call the interface method and let dispatch happen.

Cost: polymorphic flow is harder to trace than explicit branches. If there are only 2 variants that will never grow, explicit conditionals can be clearer. The balance leans toward polymorphism once you have 3+ variants or expect more.

### Inheritance — prefer composition
This is the deprecated-by-modern-sensibility one. Inheritance couples subclasses to parent internals; any change in the parent can break every child ("fragile base class" problem).

**Use inheritance when** you genuinely need to share stable implementation across multiple classes AND the is-a relationship is semantically correct AND it'll stay stable. `SavingsAccount extends BankAccount` — shared balance, deposit, withdraw logic; all accounts genuinely are accounts. Fine.

**Use composition when** behavior varies. Don't `ElectricCar extends Car` and override `startEngine` — electric cars don't have engines. Instead, `Car` has a `Drivetrain` (interface), and `GasEngine` and `ElectricMotor` implement it. Adding hybrid? New `Drivetrain` implementation, no inheritance drama.

**Default to composition + interfaces.** Only reach for inheritance when shared implementation is stable, meaningful, and the is-a relationship is genuine.

---

## 4. Design patterns

The patterns that actually matter in modern code. The original Gang of Four catalog has 23; most are obsolete or rarely useful. These 8 cover nearly all real LLD needs.

**Remember**: most clean designs use zero or one pattern. If you're using three or more, you're probably forcing them.

### Creational patterns

#### Factory — centralize "which concrete class do I create"
**Use when** callers shouldn't have to know or decide which subclass to instantiate. Requirements like "support different notification types" or "handle multiple payment methods" are the classic triggers.

**Skip when** the caller naturally knows (or should know) which type they want. Don't wrap `new Foo()` in a factory unless there's actual decision logic.

```python
class NotificationFactory:
    @staticmethod
    def create(type_: str) -> Notification:
        if type_ == "email": return EmailNotification()
        if type_ == "sms": return SMSNotification()
        raise ValueError(f"Unknown type: {type_}")
```

#### Builder — step-by-step construction for complex objects
**Use when** an object has many optional fields and constructors with 8 null arguments get unwieldy (HTTP request objects, SQL query objects, deeply configurable objects).

**Skip when** the object has 2–4 required fields and a normal constructor works. In Python, dataclasses with defaults or keyword arguments replace most Builder use cases.

```python
request = (HttpRequest.Builder()
    .url("https://api.example.com")
    .method("POST")
    .header("Content-Type", "application/json")
    .body(payload)
    .build())
```

#### Singleton — exactly one instance
**Use when** you genuinely need a single shared resource: connection pool, logger, global config manager.

**Skip** 90% of the time. Singletons hide dependencies and make testing harder. Usually, "pass the shared object through the constructor" (dependency injection) is cleaner. In Python, module-level variables are already singletons because modules are only imported once — prefer that to the pattern.

### Structural patterns

#### Decorator — stack optional behaviors at runtime
**Use when** you need to add optional, combinable behaviors (logging, encryption, compression, caching) to an object without a subclass explosion (`LoggedEncryptedCompressedDataSource`). Each decorator wraps the object and adds one piece.

**Skip when** the extra behavior is fixed at design time — that's just a normal subclass. The signal for decorator is "optional" and "combinable" and "runtime."

#### Facade — simple interface over a messy subsystem
**Use when** you want to hide internal complexity behind a single clean entry point. Your orchestrator classes (`Game`, `OrderService`, `CheckoutFlow`) are almost always facades — they coordinate `Board`, `Player`, `State` internally and expose `makeMove()` to the caller.

You're probably already building facades without calling them that. The name is most useful when wrapping inherited legacy complexity; in greenfield design, the orchestrator pattern is facade by another name.

### Behavioral patterns

#### Strategy — interchangeable behaviors, swapped at runtime
**Use when** you have multiple ways to do the same thing and need to pick at runtime (payment methods, pricing algorithms, sorting strategies, compression algorithms). The cleanest replacement for `if/elif` chains over a type enum.

**The single highest-value pattern.** If you learn only one pattern from this list, make it Strategy. It's polymorphism + composition, which is how most well-structured modern code works.

```python
class ShoppingCart:
    def __init__(self):
        self.payment: PaymentStrategy | None = None
    def set_payment(self, strategy: PaymentStrategy):
        self.payment = strategy
    def checkout(self, amount: float):
        self.payment.pay(amount)
```

#### Observer — subscribe to events, react to changes
**Use when** multiple components need to react when something happens. Price changes → update display + trigger alert + log analytics. Order placed → update inventory + send email + notify warehouse.

Trigger phrases: "notify," "update multiple components," "react to change," "event." If the problem describes fan-out on an event, it's Observer.

**Distinguish from pub/sub messaging** at the infrastructure level: in-process observer is the pattern; Redis pub/sub or Kafka is the infrastructure. They solve similar problems at different scales.

#### State Machine — behavior changes based on current state
**Use when** an object's valid operations depend on its current state, and state transitions have rules (vending machine, order lifecycle, document workflow, connection lifecycle). Instead of `if state == X` branches everywhere, each state is a class that knows which operations are valid and which state comes next.

Trigger phrase: "state" appears multiple times in the requirements. If your solution has a state machine, it's probably the centerpiece of the design. A state diagram (circles for states, labeled arrows for transitions) is the best way to communicate it.

```python
class VendingMachine:
    def __init__(self):
        self._state: State = NoCoinState()
    def insert_coin(self): self._state.insert_coin(self)
    def select_product(self): self._state.select_product(self)
    def set_state(self, s: State): self._state = s
```

### Pattern cheat sheet

| Pattern | Trigger phrase | Frequency |
|---|---|---|
| Strategy | "multiple ways to do X" / type-based if/elif | Very high |
| Observer | "notify N components when X changes" | High |
| Facade | orchestrator / coordinator class | Very high (usually unnamed) |
| Factory | "create the right kind of X" | Medium |
| State Machine | "state" + transitions with rules | Medium (when it applies, it's central) |
| Decorator | "optional, combinable, runtime behaviors" | Low |
| Builder | many optional construction fields | Low |
| Singleton | single shared resource | Usually avoid |

---

## 5. Common over-engineering mistakes

These are failure modes — watch for them in your own output and in the codebase.

- **Factory for a single constructor.** If there's no decision logic, it's just `new Foo()` with extra steps.
- **Singleton when DI would do.** Pass the thing through the constructor. Testability returns.
- **Strategy with one implementation.** An interface with one implementor is dead weight. Create the interface when the second implementation arrives.
- **Builder for 3 fields.** Constructor or keyword args.
- **Inheritance for behavior variation.** `ElectricCar extends Car` then overrides `startEngine`. Use composition.
- **Deep class hierarchies.** More than 2 levels is a smell. Composition scales; inheritance doesn't.
- **SOLID-driven over-decomposition.** 15 classes for a 100-line problem.
- **Pattern name-dropping.** If a method name or comment says "Factory" or "Adapter," but the thing is just a function, the pattern isn't earning its keep.
- **Abstracting before the second concrete case exists.** You'll build the wrong abstraction. Wait for the second case; let it pressure-test the interface.

When in doubt: write the straightforward code first. If it's painful later, refactor. Refactoring from simple code is far easier than disentangling premature abstractions.

---

## How to populate `implementation.md` in the generated skill

When generating the output skill, the `implementation.md` reference should capture:

1. **The language + framework** (with rationale)
2. **Code structure** — top-level modules/folders and what goes where
3. **Conventions** — naming, error handling, logging, test layout, any style rules
4. **Patterns deliberately chosen for this project** — e.g., "Strategy for PaymentProvider, State Machine for OrderStatus, no Singletons, composition over inheritance everywhere"
5. **Patterns deliberately avoided** (and why) — to prevent cargo-culting later
6. **Principles to hold tightly** — usually KISS + YAGNI + SRP, plus anything domain-specific
7. **Testing approach** — unit vs integration, what's mocked, coverage expectations, and an explicit **correctness-tests-first** rule (write the test list per slice before implementation, per Step 4 of the delivery framework above)
8. **Per-entity correctness sketches** — for the 3–5 most important domain classes/services, a starter list of behaviors, edge cases, and invariants that must hold. This is not the full test suite, but the seed list that step 4 of the delivery framework expects to find when implementation starts.
9. **Cross-cutting concerns** — how auth, rate limiting, observability are wired in

See `output-template.md` for the full file template.
