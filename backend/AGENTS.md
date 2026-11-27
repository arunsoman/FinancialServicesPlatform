# Backend Development Rules & Mandates

**Scope:** `backend/` services (`core`, `bff`, `idp`, `gateway`, `plugins/*`, `shared`).
**Parent Rule:** [AGENTS.md](../AGENTS.md) (Universal Invariants & FAPI 2.0 Compliance).
**Guides:** [FSP-Spring-Boot-FAPI2-Implementation-Guide (1).md](../FSP-Spring-Boot-FAPI2-Implementation-Guide (1).md) · [vision.md](../vision.md).

---

## 1. Toolchain rules (hard)

| Area | Rule |
|---|---|
| Backend language | **Pure Java 25.** NO Kotlin anywhere in `backend/`. Do not reintroduce Kotlin plugins/sources. |
| Spring Boot | **4.0.9** (pinned in root `backend/build.gradle.kts`) |
| Gradle | **9.9.1** via wrapper (`backend/gradlew`). Do not downgrade. |
| TigerBeetle | Server **0.17.9** host binary at `infrastructure/bin/tigerbeetle`; Java client pinned **0.17.9** — bump both together, never one alone. |
| Docker compose | **PostgreSQL only.** TigerBeetle runs as a host binary (Docker 25+ seccomp blocks io_uring). Do not add TB back to compose. |
| JDK install | `.sdkmanrc` pins `java=25.0.5.0+8-tem`. Use `sdk env`. |

---

## 2. Seeding rules (CRITICAL — read twice)

There are exactly **two kinds of seed migrations**, and they are isolated:

| Type | Location | Loaded when | Loaded never |
|---|---|---|---|
| **System seed** | `backend/core/src/main/resources/db/seed/system/` | Always (every environment) | — |
| **Demo seed** | `backend/core/src/main/resources/db/seed/demo/` | **ONLY when Spring profile `client-demo` is active** | In ANY other profile |

Enforced by Flyway locations:
- Default (`application.yml`): `classpath:db/migration,classpath:db/seed/system`
- `application-client-demo.yml`: adds `classpath:db/seed/demo`

**Rules:**
1. NEVER add demo data (`demo1234` passwords, fake tenants, Namib Fresh, etc.) to system seed or to migrations in `db/migration/`.
2. NEVER let demo seed run in staging/production. The profile gate is the only mechanism — do not add code that seeds demo data outside `db/seed/demo`.
3. Demo credentials exist ONLY in demo seed: username `psp-admin`, `psp-ops`, `merchant-admin`, `merchant-checker`, password `demo1234`. Password hashes are produced with pgcrypto `crypt(x, gen_salt('bf',10))` — identical bcrypt format to Spring Security.
4. The platform admin bootstrap password is a Flyway placeholder `${fspBootstrapPassword}` supplied from `FSP_BOOTSTRAP_PASSWORD` (default `fsp-admin`, dev only; set the env in real environments).
5. Schema migrations live in `db/migration/V<n>__<desc>.sql`. Applied migrations are **immutable** — never edit one; write a new one. Next: V38. (psp/emi/recon own separate databases with their own Flyway histories — their migrations start at their own V1; core + both seed folders share ONE sequence; V17/V18 are seed versions.)
6. If a demo profile feature needs runtime-only data (e.g. TB transfers), it belongs to a `@Profile("client-demo")` bean, not to SQL seed, and must be idempotent.

---

## 2b. Java style mandate

- **Records everywhere a value crosses a boundary**: wire DTOs, service views, plan legs, decision results.
- **Sealed interfaces for domain outcomes**, consumed with switch pattern matching:
  - `ledger.TbResult` (`Ok | DefinitiveRejection | UnknownTransport`) — every TigerBeetle write returns one; no exception-driven control flow for money.
  - `journal.PostingOutcome` (`Committed | Unknown | Rejected`) — posting state transitions.
  - `kyc.KycDecision` (`Approved | Rejected | RequestInfo`).
- **JPA entities stay plain classes with public fields** (JPA requires a no-arg constructor; records are not entities). Everything else prefers records.
- **Where sealed applies:** only to closed, switch-consumed outcome algebras (TbResult, PostingOutcome, KycDecision) — all three must hold: closed variant set, exhaustive-switch consumers, same-concept-different-modes. Wire DTOs are records grouped in `final` container classes (private ctor); never sealed — they are consumed by field access, not dispatched on.
- **Lombok is allowed for logging only**: `@Slf4j` on services/components/filters/advice — never hand-write `Logger` fields. Lombok is NOT to be used for getters/setters/builders on domain or DTO types (records + public-field entities already cover those). Declared in `build.gradle.kts` as `compileOnly` + `annotationProcessor` (BOM-managed version).

---

## 2c. Logging mandate (Lombok `@Slf4j` + SLF4J/Spring Boot logger)

Log **every important incident** — any exception, and **any negative path the system took** (permission denial, four-eyes rejection, ledger rejection, uncertain commit, recovery P1, KYC manual-review). One structured `key=value` line per incident; `traceId`/`tenantId`/`userId` come from MDC automatically (`ActorContextFilter` sets them, and clears them in `finally`).

| Level | Use for | Examples |
|---|---|---|
| `ERROR` (with exception where present) | Money-integrity / uncertain / P1, unhandled exceptions | `je_commit_unknown`, `je_recovery_p1_...`, TB transport failure, 500s |
| `WARN` | Definitive business rejections & denials (4xx negative paths) | `permission_denied`, `maker_cannot_approve`, `je_transition_conflict`, TB definitive rejection, bad internal credential |
| `INFO` | Milestones of the happy path that auditors care about | `je_submitted`, `coa_provisioned`, `je_posting_committed`, KYC decision, recovery resolved |
| `DEBUG` | Everything else (detailed context for troubleshooting) | plan legs, TB specs, entity snapshots, screening details, per-request actor resolution |

**Output format — two modes:**
- **Default (machine-parsable):** exactly one line per event — `ts=... level=... traceId=... tenantId=... userId=... logger=... msg=... exception=<flattened ' | ' stack>` (pattern lives in `application.yml`).
- **Debug mode (`debug` Spring profile, e.g. `--spring.profiles.active=debug`):** detailed multi-line log with full stack traces, caller line numbers, and `com.fsp` + Hibernate SQL at DEBUG (`application-debug.yml`).

Never log secrets, passwords, tokens, or full identity numbers (mask to last 4). Never re-wrap a Spring `@Transactional` boundary inside try/catch just for logging — the layer that made the decision logs it.

---

## 2d. Concurrency, Race Condition & Deadlock Prevention Mandate

Every backend system modification MUST explicitly evaluate whether it introduces race conditions, lost updates, or deadlocks in PostgreSQL or in the Java JVM.

Money paths and transactional workflows must be strictly safe under concurrent access (two checkers clicking approve simultaneously, duplicate callbacks, simultaneous batch operations, etc.). Three layers, in order of authority:

### 1. Database-Level Protections (PostgreSQL)
1. **Optimistic Locking by Default:** Every mutable aggregate carrying state machines (`JournalEntry`, KYC case, tenant, approval request) MUST use JPA optimistic locking (`@Version` + `version` column). Wrong-from-state or concurrent updates fail with `JE_STATE`/`KYC_STATE` or `OptimisticLockException` — never silently overwrite.
2. **Deterministic Row Lock Ordering (Deadlock Prevention):**
   - Whenever updating or locking multiple records across transactions (e.g. multiple GL accounts, transfer legs, or ledger balances), records MUST ALWAYS be locked/updated in **sorted primary key (UUID/ID) order** (e.g. `items.stream().sorted(Comparator.comparing(Item::getId))`).
   - Updating rows in arbitrary or opposite order across concurrent transactions causes PostgreSQL deadlocks (`40P01: deadlock detected`).
3. **Never Hold DB Transactions Across External I/O:**
   - Keep `@Transactional` scope minimal.
   - **NEVER execute external network calls** (TigerBeetle socket writes, external HTTP calls, slow I/O) inside an open database transaction.
   - Holding row locks while awaiting network responses leads to database connection exhaustion and system-wide lock stalls.
4. **Partial Unique Indexes for Invariant Exclusivity:**
   - Business invariants like "only one active bank account per tenant" must be anchored in PostgreSQL schema via partial unique indexes (e.g. `CREATE UNIQUE INDEX ux_bank_accounts_one_active ON bank_accounts (tenant_id) WHERE status = 'ACTIVE';`), not in-memory Java validations alone.

### 2. Java Process (In-Memory) Protections
1. **No Shared Singleton Synchronization:**
   - NEVER use `synchronized` on shared singletons (`this` of a Spring `@Service`, `static` monitors, string literals). These create severe throughput bottlenecks, thread contention, and deadlock traps.
2. **In-Process Critical Sections (`common/KeyLocks`):**
   - Non-idempotent sections that must not interleave inside one JVM — e.g. TigerBeetle dispatch + terminalization of the SAME entry ID — are wrapped in a fair `ReentrantLock` keyed by `domain:id` via `KeyLocks.withLock("domain", id, () -> ...)`.
   - Always use `withLock` (try/finally unlock inside utility); never call `lock()` manually.
   - Locks guard **dispatch sections**, not read paths and not DB-only transitions (optimistic `@Version` handles those).
3. **Zero Lock Inversion:**
   - Avoid acquiring multiple `KeyLocks` in nested calls. If two locks must ever be held together, they must be acquired in strict deterministic key order to prevent circular wait deadlocks.
4. **No Remote Calls or Long Sleeps Inside Locks:**
   - Never hold a `KeyLocks` lock while waiting on slow remote client calls, webhooks, or inside an HTTP callback.

### 3. Cross-Instance Synchronization
When core runs across N replicas, the in-JVM `KeyLocks` stripe transitions to PostgreSQL transaction advisory locks (`pg_advisory_xact_lock(hashtext(key))`) around dispatch sections; the `KeyLocks` API is designed so only its backing implementation adapts.

---

## 2e. Idempotency mandate (every mutating request)

**Positioning:** FAPI 2.0 is an OAuth2/token-security profile (PAR, PKCE, DPoP) and does NOT define idempotency. The FSP implementation guide places idempotency squarely in **FSP core** ("Maker-checker, idempotency, entitlement, journal construction, ledger recovery"; core endpoint samples take `@RequestHeader("Idempotency-Key")`). So idempotency lives at the core resource-server layer (`IdempotencyFilter`, order 2, `/v1/*` mutating methods), orthogonal to the FAPI2 authn wiring.

Rules:
1. Every mutating core request (`POST/PUT/PATCH/DELETE /v1/**`) MUST carry `Idempotency-Key` (1–128 chars of `A-Za-z0-9._-`, UUIDv4 recommended). Missing → `400 IDEMPOTENCY_KEY_REQUIRED`. The BFF forwards the SPA's key; reads/GETs and `/internal/**` are exempt.
2. Semantics per (actor, key): identical retry (same method+path+body hash, original DONE) → **replay the stored response** with header `Idempotency-Replayed: true`; concurrent duplicate while original still PENDING → `409 IDEMPOTENCY_IN_PROGRESS` (+ `Retry-After: 1`); same key with a different payload → `409 IDEMPOTENCY_KEY_REUSED`. 5xx originals are NOT cached (record cleared) so a retry with the same key is allowed; failure handling follows the implementation-guide rule: retry only when the command's idempotency and outcome resolution support it.
3. Records live in `idempotency_records` (V5), unique on `(user_id, request_key)`; insert-first (`PENDING`) before dispatch, updated to `DONE` with status + content-type + response bytes after; a lost insert race is reported as `IDEMPOTENCY_IN_PROGRESS`.
4. This layer NEVER replaces domain invariants — four-eyes, state-machine preconditions (`JE_STATE`), `KeyLocks` dispatch serialization and deterministic TigerBeetle transfer ids stay authoritative. Idempotency deduplicates accidental duplicate HTTP calls; the domain decides whether a retry is semantically meaningful.
5. Key scoping is per maker (`person_id`), matching four-eyes; a checker must always supply their OWN key. Response body cap for cached replay: 1 MiB; retention is unbounded in Phase-0.

---

## 2f. REST Collection Standard (Pagination, Sorting, Search) — Core & Plugins

Every REST controller endpoint fetching a collection or dataset from the server MUST implement:
1. **Server-side Pagination by default:**
   - Parameter signature: `page` (0-indexed, default `0`), `size` (default `20`, hard ceiling `100`).
   - Unbounded queries (`findAll()`) are forbidden in API paths.
   - Return type: Spring Data `Page<T>` or a typed record with pagination metadata (`items`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`).
2. **Server-side Sorting by default:**
   - Parameter signature: `sort` (e.g. `sort=createdAt,desc`).
   - **Safelist validation:** Sort fields MUST be explicitly mapped against a safelist of allowed entity properties/columns to protect against JPA injection or leaking internal persistence structure.
3. **Server-side Search & Filtering by default:**
   - Text search via `q` parameter (e.g. `q=keyword`) pushed to SQL `ILIKE` or full-text index.
   - Domain filters (e.g. `status`, `tenantId`, `asOf`, date ranges) MUST execute as database `WHERE` criteria via Spring Data Specifications, Criteria API, or indexed repository queries.
   - In-memory collection filtering or sorting in Java is strictly prohibited for API responses.

---

## 3. Posting Engine & Ledger Details

1. **JE engine** (`com.fsp.core.journal`): balanced lines ≥2; **direct debit-to-credit matching** (no star); deterministic transfer IDs `UUIDv5(ns, entryId + ":" + legIndex)`; linked chain except final leg; same-account-both-sides rejected.
2. **Posting state machine**: `DRAFT → PENDING_CHECKER → READY → SUBMITTING → COMMITTED → FINALIZED`, plus `COMMIT_UNKNOWN` and `REJECTED`. Canonical plan (plan_hash + tb transfer ids) is persisted BEFORE submission. Uncertain commits resolve via `lookup_transfers` with the ORIGINAL ids — never fresh ids while uncertain.
3. **TigerBeetle access restriction**: TigerBeetle is written only through `TbService` and configured via `TigerBeetleConfig` — nothing else imports `com.tigerbeetle.*`.
4. **Namibian validation** (`NamibiaRules`): BIPA `^([A-Z]{2})?\d{3,10}$` normalized; TIN `^\d{8}$`; branch code `^\d{5,6}$`; UBO threshold ≥ 25%; keep records ≥ 5 years.
5. **Operation catalog**: Controller methods declare `@FspOperation`. Catalog boot inventory fails boot on duplicate or unclassified routes.

---

## 4. Plugin Process & Schema Isolation Mandate

1. **Independent Process:** Every plugin MUST run as a separate, standalone OS/container process on its own dedicated port (e.g. KYC `:8083`, Inventory `:8084`).
2. **Isolated Database Schema:** Every plugin MUST connect to its own database schema or database instance (e.g. `fsp_plugin_kyc`, `fsp_plugin_inventory`).
3. **Independent Flyway Migrations:** Every plugin manages its own schema migrations completely separately from Core (e.g. `classpath:db/plugin/kyc/migration/`). Plugins NEVER modify core tables.
4. **Zero Shared Storage:** Plugins cannot query core tables directly or execute cross-database joins. Cross-domain communication is strictly via API Gateway HTTP routes with actor assertions.

---

## 5. Internal Origin Security & Gateway Interop

- The PUBLIC origin is the API Gateway (`:8090`). Nothing but the gateway (and BFF for session surface) reaches core or IdP directly.
- Every `/v1/**` + `/internal/**` request must carry `X-FSP-Internal-Credential` (validated by core filter).
- Gateway strips ALL client-sent identity headers (`X-FSP-*`, `Authorization`, `Cookie`) and re-issues `X-FSP-User-Id`, `X-FSP-Tenant-Id`, `X-FSP-Username`, `X-FSP-Client-Id` from verified context.
- `ActorContextFilter` serves as core's internal defense-in-depth line.
- Errors: RFC-7807 `application/problem+json` with stable `code` + `correlationId` (see `common/ApiException`).

---

## 6. Layout map

```
backend/  Gradle multi-project: core (:8081), bff (:8080), shared, plugins/kyc (:8083)
  core/src/main/java/com/fsp/core/{identity,rbac,tenant,ledger,journal,approval,audit,outbox,common,config,actas,plugin}
  core/src/main/resources/db/{migration,seed/system,seed/demo}
  plugins/kyc/   KYC plugin — own DB (fsp_plugin_kyc), own Flyway history, no core/TB dependency
```
