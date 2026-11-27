# FSP Code Reviewer Checklist

Use this checklist during PR reviews or when auditing local modifications.

---

## 1. Concurrency & Deadlocks
- [ ] Are rows updated/locked across multiple entities in **sorted primary key order** (`ORDER BY id ASC`)?
- [ ] Are `@Transactional` boundaries minimal, with **zero external I/O** (network, TigerBeetle, HTTP, sleep)?
- [ ] Do mutable database entities have JPA `@Version` optimistic concurrency protection?
- [ ] Are state transitions atomic and conditioned on the expected previous state (`status = :fromStatus`)?
- [ ] Are there zero `synchronized` blocks on Spring singletons, `this`, or strings?
- [ ] Is `KeyLocks.withLock` used for dispatch serialization without nested lock inversion?

---

## 2. Universal FSP Invariants & FAPI 2.0 Security
- [ ] Do OAuth or IdP tokens enter browser storage or UI memory? (Answer must be NO; BFF session only).
- [ ] Are identity headers (`X-FSP-*`) stripped and re-issued exclusively by the API Gateway?
- [ ] Is Four-Eyes enforced at real `person_id` (Maker != Checker)?
- [ ] Can machine actors act as makers or checkers? (Answer must be NO).
- [ ] Are money amounts represented in minor units (`long` in Java, `NUMERIC(20,0)` in DB, string on wire)?
- [ ] Does TigerBeetle access occur exclusively through `TbService` and `TigerBeetleConfig`?
- [ ] Are secrets, passwords, DPoP proofs, or full account numbers excluded from logs?

---

## 3. Performance & REST Standards
- [ ] Do all collection-fetching controller endpoints support server-side pagination (`Pageable` / `page`, `size`)?
- [ ] Are sort fields checked against an explicit domain safelist?
- [ ] Are search queries (`q`) and domain filters pushed down to database `WHERE` queries (no in-memory stream filtering)?
- [ ] Are JPA queries free of N+1 select hazards (using `JOIN FETCH` or `@EntityGraph`)?
- [ ] Do queries on large tables utilize appropriate database indexes?

---

## 4. Plugin Process & Schema Isolation
- [ ] Does every plugin run as a standalone OS process on its own port?
- [ ] Does every plugin use its own dedicated database schema / database (`fsp_plugin_<name>`)?
- [ ] Are Flyway migrations isolated within the plugin classpath?
- [ ] Are plugins communicating with Core strictly via Gateway REST APIs with actor assertions?

---

## 5. Frontend & UI Standards
- [ ] Does UI code use semantic design tokens only (no raw hex colors or inline pixel styles)?
- [ ] Is posting state mapped via `PostingStatePill`?
- [ ] Are money values displayed using the `<Amount>` atom?
- [ ] Are financial mutations non-optimistic?
- [ ] Do mutating financial requests have zero auto-retries?
- [ ] Does `bun run widgets:check` pass without errors?
