# FSP Project Instructions

All project rules, invariants, and agent operating procedures live in **[AGENTS.md](AGENTS.md)** at the repository root. AGENTS.md is the single source of truth — read it before writing code, and follow it on every request.

## What AGENTS.md covers

- **FAPI 2.0 Security Profile** compliance (tokens never in browser, BFF owns session, gateway validates JWT + DPoP, core is sole PDP)
- **Universal domain invariants** (four-eyes at `person_id`, minor-unit money, double-entry journaling, tenant subtree isolation, transactional outbox + audit, plugin process/schema isolation, REST collection standard, concurrency/deadlock safety)
- **Conditional documentation index** (backend, frontend, architecture, build/run, plugin platform, act-as-tenant, widget harness, code review)

## How to use

1. Read **AGENTS.md** first — it governs everything.
2. Read the specific guide matching your task from the table in AGENTS.md §3 (conditional rules index).
3. Never silently resolve a conflict with AGENTS.md — raise it before writing code.
