# AGENTS.md — Mandatory Read for Every Agent Working on FSP

**Last updated:** 2026-11-19 · **Owner:** platform engineering  
**Governance:** Single source of truth. If a task conflicts with this file, the conflict MUST be raised before code is written — never silently resolved.

---

## 1. Universal Security & Strict FAPI 2.0 Compliance (MANDATORY on every request)

The platform strictly enforces the **FAPI 2.0 Security Profile Final**. Every component (UI, BFF, Gateway, Core, IdP, Plugins) MUST conform to these boundaries:

1. **Tokens Never Enter the Browser:** The browser never touches OAuth, IdP, or Bearer tokens. The confidential BFF (`:8080`) owns the user session via an `HttpOnly`, `SameSite=Lax` cookie with CSRF double-submit protection (`/bff/csrf`).
2. **API Gateway (`:8090`) is the SOLE Public Origin:** All external requests enter through the gateway. Gateway strips incoming client identity headers, validates the RS256 JWT + DPoP (RFC 9449) proof, checks route authorization against `@FspOperation`, and re-issues trusted internal headers. Direct origin access to internal services is forbidden.
3. **Core (`:8081`) is the SOLE Policy Decision Point (PDP):** RBAC, permissions, four-eyes approval, and ledger mutations are decided strictly in core. Token claims are advisory; core re-evaluates all policies and domain preconditions against database truth.
4. **Internal Hop Zero Trust:** Downstream internal calls require verified identity headers (`X-FSP-User-Id`, `X-FSP-Tenant-Id`, `X-FSP-Username`, `X-FSP-Client-Id`) plus a valid internal credential (`X-FSP-Internal-Credential`).
5. **Zero Token/Secret Leakage:** Never log credentials, passwords, tokens, DPoP proofs, full identity numbers, or raw bank details.
6. **Mandatory FAPI Skill:** Before touching authentication, authorization, tokens, certificates, gateway/BFF/IdP security wiring, or any endpoint a browser calls, **LOAD THE SKILL** (vendored in the repo so every agent and machine has it):
   ```
   /skill:fapi2-compliance
   (file: .agents/skills/fapi2-compliance/SKILL.md — also linked at .claude/skills/fapi2-compliance)
   ```

---

## 2. Universal Domain & Engineering Invariants (MANDATORY on every request)

1. **Four-Eyes at `person_id`:** Maker ≠ Checker. A person can never approve their own request (KYC, JE, CoA, bank accounts, plugins). Enforced at real `person_id` (even during delegated act-as). Machine actors can NEVER act as makers or checkers. *Exception — portal menus ship as code:* the repo seed `R__seed_portal_menus.sql` is reviewed in the pull request and applied on deploy with no approval row (`docs/portal-menus.md`); the layout-publish approval API (`UI_LAYOUT_PUBLISH`) stays four-eyes. *Exception — plugins ship as code:* the deploy registers the plugin manifests a release ships (`POST /internal/release/plugins`, release credential held only by the deploy); the four eyes are the pull-request review and, in production, a second person on the PR or the deploy approval — recorded as `provenance` on the registration (`docs/plugin-registration.md`). Suspend / resume / manual register stay four-eyes (`PLUGIN_SUSPEND` / `PLUGIN_RESUME` / `PLUGIN_REGISTER`), and a person's suspension is never lifted by a deploy.
2. **Money Representation:** Minor units only. `NUMERIC(20,0)` in database, `long` in Java/TigerBeetle, **string on the wire** (JS-safe). Currency ISO-4217 alpha-3; TB ledger id = ISO-4217 numeric.
3. **Double-Entry Journaling:** No money moves without a balanced Journal Entry (JE) per book + currency. Direct debit-to-credit matching (no star). TigerBeetle is the authoritative record of booked positions.
4. **Tenant Subtree Isolation:** Platform (depth 0) → PSP (depth 1) → Onboarded Tenant (depth 2). Subtree visibility is strictly enforced by `hierarchy_path`. Cross-tenant data leakage is a P0 defect.
5. **Transactional & Audit Integrity:** Database mutation + Outbox row in one transaction. Append-only audit (`AuditService`) at core boundaries; every record carries `correlation_id`.
6. **Plugin Process & Schema Isolation:** Every plugin MUST run as an **isolated, separate process** with its **own dedicated database schema / database** and its own independent Flyway migration history. Plugins NEVER share a database or schema with Core or other plugins.
7. **REST Collection Standard (Pagination, Sorting, Search):** Every REST controller endpoint that fetches collections/data MUST support **pagination** (bounded `page`/`size`), **server-side sorting** (`sort=field,dir`), and **server-side search / filtering** (`q` / filter params pushed to database queries) by default. Unbounded collection fetches or client-side filtering are strictly prohibited across both Core and Plugins.
8. **Concurrency, Race Condition & Deadlock Safety:** Every backend modification MUST be audited to ensure it cannot introduce race conditions, lost updates, or deadlocks in the database or the Java process. Enforce JPA optimistic locking (`@Version`), deterministic resource locking order (sort IDs before locking/updating multiple rows), atomic status-precondition transitions, and fair striped locks (`KeyLocks`). Never hold DB transactions or JVM locks across remote network I/O.

---

## 3. Conditional Rules & Documentation Index (Read conditionally on demand)

Do NOT read all documents upfront. Read ONLY the specific guide matching your immediate task:

| Task / Domain | Condition to Read | Reference Document |
|---|---|---|
| **Backend Engineering** | When writing Java, Spring Boot, TigerBeetle integration, JPA, migrations, concurrency, idempotency, or REST endpoints | [backend/AGENTS.md](backend/AGENTS.md) |
| **Frontend / UI Engineering** | When writing React, TypeScript, styles, forms, widgets, portals, or interacting with user-facing workflows | [frontend/AGENTS.md](frontend/AGENTS.md) & [ui-std.md](ui-std.md) |
| **Architecture & Data Flow** | When modifying cross-service communication, reviewing topology, data models, or updating living specs | [docs/architecture.md](docs/architecture.md) & [vision.md](vision.md) |
| **Build, Run & Local Dev** | When running services, setting up environments, executing tests, or debugging port/container issues | [docs/README-dev.md](docs/README-dev.md) & [docs/setup-guide.md](docs/setup-guide.md) |
| **CI/CD, Deploy & Rollback** | When changing `.github/workflows`, anything under `deploy/`, release tags, staging/production hosts, or rolling back | [deploy/pipeline/README.md](deploy/pipeline/README.md) |
| **Plugin Platform & KYC** | When working on plugin architecture, machine identity plane, client grants, or KYC/KYB workflows | [plans/plugin-platform-plan.md](plans/plugin-platform-plan.md) |
| **Act-As Tenant ("Sudo")** | When implementing or testing delegated tenant administration, session caps, or permission ceilings | [plans/act-as-tenant-plan.md](plans/act-as-tenant-plan.md) |
| **Widget Harness & Workbench** | When building or testing portal widgets, layouts, manifests, or deciding on new menu items. Agents run the skill's two gated stages (UI on fixtures → registered → user approval → backend → live e2e) with the `widgets:*` tools | [.agents/skills/fsp-widget/SKILL.md](.agents/skills/fsp-widget/SKILL.md) & [docs/widget-workbench.md](docs/widget-workbench.md) |
| **Code Review & Quality Audit** | When reviewing PRs, checking for deadlocks, race conditions, or standard violations | [.agents/skills/code-reviewer/SKILL.md](.agents/skills/code-reviewer/SKILL.md) |
