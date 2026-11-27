# Stage 2 — backend to the approved contract (agent mode)

Starts only after `bun run widgets:accept <id> --by …` succeeded. The UI and the wire contract
(`data.ts` + `model.ts`) are **locked**: the backend serves exactly the shapes the user approved.
If a shape cannot be honoured (security, invariant, data that does not exist), stop, explain,
propose the change and get a new approval (`widgets:accept` again) — never silently reshape.

Context now: `backend/AGENTS.md`, `AGENTS.md` §1–§2, and the **fapi2-compliance** skill
(`.agents/skills/fapi2-compliance/SKILL.md`; sign-off with its `references/acceptance-checklist.md`).
UI files are not reopened except to wire a changed `path` (which needs re-approval anyway).

---

## 1. Backlog

```bash
cd frontend && bun run widgets:stage <id>       # NEW rows = what to build; REUSE rows need nothing
```

For each NEW row you have: method, `/api` path (→ `/v1` in core), the KIND, the permission key
you proposed, and the request/response types in `model.ts`.

## 2. Copy the closest existing slice, don't invent structure

```bash
bun run widgets:survey <domain>     # nearest controller + handler file:line to model on
```

Reference slice for a **four-eyes write** (create → pending approval → checker applies):
`backend/core/src/main/java/com/fsp/core/ledger/` — `BankAccountController` (thin, `@FspOperation`,
`fsp.actor`), `BankAccountService` (`approvals.open(..., OpType.BANK_ACCOUNT_CREATE, ...)`, audit,
outbox, `KeyLocks`), `BankAccountApprovalHandlers` (`ApprovalHandler.apply`, `atomicWithResolution`),
`BankAccounts` (records in a final container), migration `V21__bank_accounts.sql`, test
`ledger/BankAccountServiceTest`. Paginated reads: `identity/UserAdminController` + `UserDtos.UserPage`.

## 3. Build each NEW operation — checklist

**Endpoint (core)**
- [ ] Handler in the domain package's `*Controller` (class `@RequestMapping("/v1/…")`), path and
      method exactly as approved (the `/api` path minus the prefix).
- [ ] `@FspOperation(id = "<area>.<resource>.<verb>", component, permission = "<key>",
      risk = READ | WRITE | FOUR_EYES, resource, resourceVariable, title)` — the gateway routes
      and authorizes from this catalog; a handler without it is unreachable. Risk: reads READ;
      create/modify WRITE; approve-kind or money/RBAC-affecting writes FOUR_EYES.
- [ ] Actor from `request.getAttribute("fsp.actor")` (401 `ACTOR_MISSING` when absent). Never read
      identity from headers or the body — core is the sole PDP (AGENTS §1.3).
- [ ] Tenant from the path is checked against the actor's subtree (`hierarchy_path`) in the
      service — a cross-tenant read is a P0 defect (AGENTS §2.4).
- [ ] Records for request/response DTOs (grouped in a `final` container class), `@NotBlank` /
      `@NotNull` on required fields, primitives for never-null numbers/flags; JSON field names
      identical to `model.ts`. Money: `String` minor units + `currency`; never `double`.
- [ ] Collections: `page`/`size` (≤100)/`sort` (safelisted)/`q` + filters pushed into SQL
      (AGENTS §2.7, backend §2f) and the page record the UI expects.
- [ ] Errors via `ApiException` with stable codes the UI handles (409 version conflict, 422 domain
      refusal, 403 `MAKER_CANNOT_APPROVE` / `CHECKER_NOT_ENABLED`).

**Domain**
- [ ] Four-eyes for approve-kind writes: `ApprovalService.open(...)` with a new `OpType` constant +
      an `ApprovalHandler`; maker ≠ checker at `person_id` (also under act-as); machine actors
      never make or check.
- [ ] DB mutation + outbox row in one transaction; `AuditService` record with `correlation_id`.
- [ ] Concurrency (backend §2d): `@Version` optimistic locking + `expectedVersion`; deterministic
      lock order; atomic status-precondition updates; no DB transaction or JVM lock held across
      network I/O. Idempotency is handled by `IdempotencyFilter` — do not re-implement it, but
      make the domain safe to replay (backend §2e.4).
- [ ] Money moves only through a balanced journal entry (AGENTS §2.3); TigerBeetle via `TbService`.

**Data + permissions**
- [ ] New table / column / permission key → a **new** migration
      `backend/core/src/main/resources/db/migration/V<next>__<desc>.sql` (next number:
      `ls backend/core/src/main/resources/db/migration | sort -V | tail -1`, +1; applied
      migrations are immutable). New key: `INSERT INTO permissions (key, title, risk, component)`
      + `role_permissions` grants for the roles that must hold it (see V21). Never put demo data
      in migrations or system seed (backend §2).
- [ ] Same key added to `frontend/src/portals/shared/widgets/permission-catalogue.ts`.
- [ ] Plugin-owned operation (KYC, inventory …): implement in that plugin's own process + schema,
      and add it to `backend/plugins/<code>/plugin-manifest.json` `operations[]` (AGENTS §2.6).

**FAPI 2.0 (sign off against `fapi2-compliance/references/acceptance-checklist.md`)**
- [ ] Browser reaches it only as `/api/**` → BFF (session cookie + CSRF) → gateway (JWT + DPoP,
      route from the operation catalog) → core. No new direct route to core, no bearer token
      handling outside BFF/gateway, no new CORS, no secrets or tokens in logs.
- [ ] Internal hops (core → plugin / psp / emi) use the per-target internal credential.

**Tests (fail before, pass after)**
- [ ] Service unit tests (Mockito, style of `BankAccountServiceTest`): happy path, the four-eyes
      refusal (maker approves own → 403), tenant-subtree refusal, version conflict, validation.
- [ ] A controller/DTO test pinning the JSON shape the widget consumes when it is not obvious.
- [ ] `./gradlew :core:test --tests '<FQCN>'` green (scope it; record the command + result).

## 4. Close the loop

```bash
cd frontend
bun run widgets:skill                 # chat skill + endpoints.json now include the new endpoints
bun run widgets:plan <id>             # NEW → REUSE once the scan sees the @FspOperation
bun run widgets:verify <id>
```

Restart core with the change (`make backend-core`), keep the rest of the stack up, then:

```bash
bun run widgets:register-bff <id>          # only if the widget version changed
bun run widgets:e2e <id> --lane all --live # nothing stubbed: browser → BFF → gateway → core
bun run widgets:done <id>
```

`widgets:done` refuses while any NEW op remains, while the UI or contract differs from what was
approved, without a green **live** portal run, or with a stale generated skill.

## 5. Report

Use SKILL.md §7: files and endpoints (permission + risk), migrations, tests with their last
result, FAPI checklist rows touched, open items.
