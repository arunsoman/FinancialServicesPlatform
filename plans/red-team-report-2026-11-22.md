# Red-Team Exercise — FSP Fortress Breach Report

**Date:** 2026-11-22 · **Squad:** 7 attacker personas vs the live stack (IdP :8082, BFF :8080, Gateway :8090, Core :8081, PSP :8085, EMI :8086, recon-worker, TigerBeetle, Postgres)

## Executive summary

The **outer shell held completely** — gateway default-deny, RS256+DPoP, CSRF/PAR/session, bounded
pagination, money-string validation, idempotency charset all survived an industrial assault.

The **inner zero-trust hop collapsed in one shot**: a single static, git-tracked internal
credential (`dev-internal-credential`) is a **god-key**. Holding it, the red team impersonated any
person, forged MACHINE actors with unregistered client ids, executed real writes on PSP, created a
real four-eyes journal entry in the ledger, and played BOTH maker and checker. And independently,
a chain-math bug in core RBAC lets any tenant escape into **unrelated tenant trees** — the exact
P0 the platform doctrine forbids.

## Scoreboard

| Wave | Vector | Verdict | Severity |
|---|---|---|---|
| 1 | Perimeter: no-session, CSRF-less POST, forged session cookie, default-deny catalog | **HELD** | — |
| 2 | Forged JWTs (garbage / alg=none), Authorization + X-FSP-* smuggling through BFF proxy | **HELD** | — |
| 3 | Internal credential brute force (6 guesses) | **HELD** | — |
| 3d | **Known credential → full identity forgery (read platform-admin's notifications)** | **BREACH** | **P0** |
| 4 | **Forged MACHINE actor + unregistered client id → accepted recon page** | **BREACH** | **P1** |
| 4c | **God-key → real JE created (four-eyes maker), checker pair accepted (two pens, one hand)** | **BREACH** | **P0** |
| 5b | **God-key → real PSP settlement sweep executed as forged user** | **BREACH** | **P0** |
| 6 | Money tampering: negative / zero / NUMERIC(20,0) overflow amounts | **HELD** | — |
| 7 | Pagination bomb (`size=100000`→100), sort injection (`sort=1;DROP…`→400), idempotency charset | **HELD** | — |
| 7c | Alarm flood: 3 arbitrary-machine recon pages accepted (200 ×3) | **BREACH** | **P1** |
| 8d | **Cross-TENANT-TREE escape: psp-admin journaled entries of unrelated tenant 2712b509** | **BREACH** | **P0** |

## The two P0 root causes

### 1. The god-key (internal hop is bearer-secret-only)

- `dev-internal-credential` is a single static shared secret in git-tracked `application.yml`s and
  module sources — any reader of the repo (or one leaked config) owns it.
- Core/PSP/EMI build the ENTIRE `Actor` from client-supplied `X-FSP-*` headers once the secret is
  presented: identity, tenant, username, actor type, client id — all forgeable at will
  (verified: `unread=2 RECON_VIOLATION` for forged person 201, `0` for b1).
- No per-service credentials, no rotation, no mTLS, no anomaly detection, no rate limiting
  (6 attempts fired with zero throttle/punitive response).
- Machine-plane ops accept ANY `X-FSP-Client-Id` (no allowlist/registration check).

### 2. Chain-math tenant escape (`PermissionService.chainFor`)

`PermissionService.chainFor()` builds the target tenant's ancestor chain **then adds
`actor.activeTenantId()`**:

```java
chain.add(actor.activeTenantId());   // ← the escape hatch
...
if (role == null || !chain.contains(role.tenantId)) continue;
```

Because the actor's **own** tenant is injected into the chain, a role held at tenant X passes the
`chain.contains(role.tenantId)` test for ANY target tenant Y — even a totally unrelated tree.
Verified: PSP_ADMIN (tree a1) read tenant `2712b509-…`'s ledger (different root). The actor's
tenant should never qualify as an "ancestor of the target" — target-tree membership must be proven
from the platform root only.

## What held (give the defenders their credit)

- **Gateway:** route default-deny (`ROUTE_NOT_IN_CATALOG`) on every unpublished path; RS256
  signature + DPoP proof mandatory; `/internal/**` unreachable through the gateway (404 not 200).
- **BFF:** `HttpOnly` session cookie; CSRF double-submit (403 without token); PAR flow state-bound
  (`state_mismatch` on replay); **client-supplied `Authorization`, `X-FSP-*`, internal credential
  headers all stripped before trusted re-issue** — smuggling produced zero effect.
- **RBAC plane:** permission denial surfaced for `tenant.act_as` from PSP_ADMIN (403).
- **Money representation:** negative, zero and 36-digit overflow amounts rejected at the door
  (`JE_AMOUNT` / positive-minor-unit-string rule); `NUMERIC(20,0)` wire-string contract enforced.
- **REST collection standard:** `size=100000` clamped server-side; sort/search parameter injection
  rejected 400.
- **Idempotency:** charset `[A-Za-z0-9._-]` enforced (colons/spaces → 400); 5xx deletes the PENDING
  row (retry-clean).
- **Append-only audit:** even breached hops record decisions (`JE_APPROVAL_RESOLVED` rows observed).

## Remediation plan (prioritized)

1. **P0 — Kill the god-key.** Per-service credentials (secret per caller, git-ignored env only),
   rotation support, mTLS or signed-internal-JWT on the internal hop, machine-client allowlists
   (`recon.page` must accept only `recon-worker`), rate-limit + lockout on credential failures.
2. **P0 — Fix chainFor:** remove `chain.add(actor.activeTenantId())`; permission reachability must
   be computed ONLY from the target tenant's `hierarchy_path` (plus act-as home-tenant
   ceilings). Add a regression test asserting a role at tenant X cannot touch tenant Y of an
   unrelated root.
3. **P1 — Recon paging hardening:** bound `recon.page` recipients, per-invariant rate ceiling,
   reject unregistered machine clients, and consider requiring the page body to echo a
   worker-specific nonce.
4. **P2 — BFF Origin check** (defense-in-depth on top of CSRF), and rate-limiting at the BFF.
5. **P2 — Demo hygiene:** demo passwords (`demo1234`) and the dev internal credential live in git;
   document loudly + gate demo profile-only (already partially done), and never reuse in staging.

## Exercise hygiene

All red-team artifacts were cleaned: forged JEs, REDTEAM/FLOOD notifications, outbox events,
idempotency rows, and the PSP sweep batch. Logs: `/tmp/{core,psp,emi,gw,bff,idp}-red.log`.

---

# Round 2 — Session-Replay & VAPT Battery (2026-11-22)

Squad extended coverage per operator request: session/replay families and classic VAPT
(hardening, headers, cookies, CORS, traversal, brute-force, enumeration, races, mass-assignment).

## Scoreboard — round 2

| Wave | Vector | Verdict | Severity |
|---|---|---|---|
| 9a | **Stolen `FSPSESSION` replay from foreign client (evil UA + evil Origin)** | **BREACH** — full cookie hijack, no binding | **P1** |
| 9b | OAuth auth-code replay (consumed code) | HELD — BFF state consumed, `invalid_state` redirect | — |
| 9c | PAR `request_uri` replay / fabricated PAR | HELD — `request_uri_invalid` at callback; login page renders leniently but fails closed | — (note) |
| 9d | CSRF token pair replay on anonymous session | HELD — reaches controller, `NO_SESSION` closed | — |
| 9e | Idempotency same-key different-body replay | HELD — 409 `IDEMPOTENCY_KEY_REUSED` | — |
| 9f | Stale/foreign act-as session replay | HELD — route denied (catalog) | — |
| 10a | Security headers (BFF/IdP) | PARTIAL — `nosniff` + `X-Frame-Options: DENY` present; **no CSP / HSTS / Referrer-Policy** | P2 |
| 10b | Cookie flags | HELD — `HttpOnly; SameSite=Lax`; `Secure` absent (dev HTTP; TLS profile exists) | note |
| 10c/d | CORS echo + preflight for evil origins | HELD — no ACAO granted anywhere | — |
| 10e | **Login brute-force: 12 consecutive bad passwords** | **BREACH** — zero throttle / lockout / backoff | **P2** |
| 10f | User enumeration (error-shape + timing) | HELD — uniform `authentication_failed` | — |
| 10g | TRACE / `X-HTTP-Method-Override` | HELD — 405, override ignored | — |
| 10h | Path traversal via BFF proxy (`/../`, `%2e%2e`, sidestep) | HELD — 400/404, no static disclosure | — |
| 10i | Mass assignment (`status`, `version=-999` smuggled into JE create) | HELD — record bound, fields ignored | — |
| 10j | **Four-eyes race**: self-approval + 4 parallel approvals | HELD — self 403, duplicates 409, status precondition held, checker stamped once | — |

## Round-2 findings detail

1. **P1 — session replay is trivially weaponizable.** A stolen `FSPSESSION` (network sniffing on
   dev HTTP `secure:false`, XSS, logs) replays from any client with no UA/IP/device binding and no
   re-auth on sensitive ops. Combined with round 1 (no BFF Origin check), recommend: bind sessions
   (or shorten TTL + re-auth for write ops), enable the TLS profile in any shared environment,
   and treat BFF session cookies as equivalent-to-bearer in threat models.
2. **P2 — no login backoff/lockout.** Password guessing meets no friction (uniform 302s). Add
   failed-attempt counter → exponential backoff → temporary lockout + audit `LOGIN_FAILED` pages.
3. **P2 — response hardening gap:** add CSP, HSTS (with TLS), Referrer-Policy, Permissions-Policy.
4. **Note — DPoP replay** at the gateway could not be exercised externally (the BFF mints proofs
   server-side and never exposes the bearer). Recommend a code-review assertion that the gateway
   rejects replayed `jti`/stale `iat` within its DPoP window (single-use proof cache).
5. **Note — IdP login-page leniency:** `GET /idp/login?request_uri=<unknown>` renders the form
   before validating the PAR uri (fails closed at the callback). Harmless but tighten for UX.

Everything else from round 1 stands: the two P0s (god-key internal hop, `chainFor` tenant escape)
remain the decisive breaches of both rounds.

---

# Round 3 — Dedicated RBAC-Bypass Assault (2026-11-22)

Squad tasked to break the RBAC engine itself. Sources inspected (`FspOperationInterceptor`,
`PermissionService`, `UserAdminService`, `RoleAdminService`, `RbacRepository`), then all probes
fired live through the BROWSER plane only (no god-key) unless noted.

## Scoreboard — round 3

| Wave | Vector | Verdict | Severity |
|---|---|---|---|
| 11-pre | Unguarded endpoints scan: every `@`-mapping in core annotated 1:1 with `@FspOperation`; interceptor fails-closed for unannotated handlers | HELD | — |
| 11-pre | Case/precision tricks (`je.create` vs `JE.CREATE` — DB key match is exact-string) | fail-closed | — |
| 11a | psp-admin (a1) `requireOrg` at FOREIGN org 2712b509 (chain-bug) → `POST /users` stages `redteam-admin` + Tenant Admin anchor there | **BREACH** — 202 PENDING staged in foreign org | P0-component |
| 11b/11c | Maker self-approval of own USER_CREATE | HELD — 403 `MAKER_CANNOT_APPROVE` | — |
| 11d | Second pen psp-ops (no `user.manage`) approval attempt | HELD — 403 `PERMISSION_DENIED` | — |
| 11d2 | merchant-admin pen (holds `user.manage` via chain bug, NOT checker-flagged) | HELD — 403 `CHECKER_NOT_ENABLED` | — (the flag saved the day) |
| 11e | Mint 3rd pen in own org + self-activate (`activate` has NO four-eyes on the endpoint; staged approval instead) | BLOCKED by checker flow | note |
| 11g | **merchant-checker (checker-flagged + `user.manage`) approves foreign-org USER_CREATE** → backdoor **ACTIVE** → forced password set → **logs in as Organization Administrator of foreign org, reads its ledger** | **BREACH — total org takeover** | **P0** |

## The kill-chain (all browser-plane, no god-key)

```
PSP_ADMIN (attacker, org a1)
  1. PermissionService.chainFor() adds actor.activeTenantId() to the TARGET's ancestor chain
     → user.manage / role.manage effectively global across ALL tenant trees (requireOrg accepts)
  2. POST /v1/tenants/<FOREIGN_ORG>/users {roleIds:[TENANT_ADMIN]}      → 202 PENDING backdoor
  3. requireAssignable anchor-carve-out: Admin/CHECKER anchor roles are grantable WITHOUT
     holding their keys, as long as subject ≠ self  → no privilege-ceiling proof required
  4. SECOND attacker-controlled person (merchant-checker of a DIFFERENT org, checker-flagged,
     also holds user.manage) approves the USER_CREATE → four-eyes satisfied at person-level
  5. Backdoor ACTIVE + bootstrap password + forced-change → logs in as foreign-org ADMIN
  6. Reads/writes foreign org ledger (verified live)
```

## Root causes (3 stacked)

1. **P0 — `chainFor` tenant-escape (unchanged from round 1).** Makes `user.manage`, `role.manage`,
   `je.create`, everything, effectively global for any role-holder. Every org-admin is a
   super-admin at every OTHER org.
2. **P1 — anchor-role carve-out needs tenant-affinity.** Granting a role in tenant T should require
   the granter to hold the keys IN T's tree (not anywhere) AND the actor must belong to T's
   subtree; the "born role" carve-out lets a foreign chain-bugged actor mint admins anywhere.
3. **P1 — checker pens are fungible across orgs.** Four-eyes is enforced at person_id only, but
   checker-flagged `user.manage` holders exist in a sibling org, and the chain-bug makes them
   eligible checkers for ANY org's approvals. Checker eligibility must be scoped to the target
   tenant's subtree AND require an `user.approve`-style key distinct from `user.manage`.

## Defenses that held (credit)

- `MAKER_CANNOT_APPROVE` (person-level four-eyes) — blocked every self-approval.
- `CHECKER_NOT_ENABLED` — caught the non-checker pen; without this flag gate the chain
  needed only TWO accounts.
- `requireNotSelf` on user removal; `expectedVersion` optimistic-lock on every user mutation.
- `load()` concealed-404 keeps person existence private across orgs (no enumeration).
- Role-deletion protections (`ROLE_PROTECTED`, `ROLE_IN_USE`), staged role changes.

## Cleanup

Backdoor persons + staged approvals deleted; user_roles orphans purged. Sibling findings intact:
P0 god-key, P0 chainFor, P1 carve-out/checker-fungibility, P1 session hijack, P2 lockout/headers.
