# Plugin Platform + KYC Plugin — Build Plan

**Status:** REVISED (rev 2) — awaiting sign-off (§16 lists the decisions that need an explicit yes).
**Date:** 2026-11-18 · **Owner:** platform engineering
**Binding sources:** `vision.md` §2.3, §2.4, §4.8, §6, §13 · `AGENTS.md` §2b–§2e, §3, §4, §5b, §6, §7 ·
`plans/phase-0-prd.md` §3.10 + "Slice 1 — Extract KYC to plugin" · pi skill `fapi2-compliance`.

> **Rev 2 change log** (what changed from rev 1, and why — codebase-verified):
> 1. **§1.4 FAPI 2.0 sequencing added** — plugin machine identity is declared *tranche 1 of the
>    FAPI2 slice*; AGENTS §5b ledger row is updated by this plan instead of being silently
>    contradicted (rev 1 shipped DPoP/client_credentials while the ledger said FAPI2 §2 = ⛏ not started).
> 2. **§8 event feed ownership settled** — core serves `GET /v1/plugins/events` + ack via
>    `PluginEventsController`; the plugin polls core THROUGH the gateway. Rev 1 was ambiguous.
> 3. **§9.1 idempotency key scoping table added** — per-actor key ownership at every hop
>    (person → BFF → gateway → plugin → core), rev 1 had three dedupe layers with no scoping rule.
> 4. **§9.5 abuse controls added** — 429/body caps/timeouts for plugin routes (AGENTS §6: rev 1 was silent).
> 5. **§12.4 `Page<T>` fixed** — totals removed (AGENTS §4A: "no totals by default"); cursor +
>    `nextAfter` instead. Rev 1's `totalItems/totalPages` contradicted AGENTS.
> 6. **§12.4 sort names mapped** public ⇄ persistence (AGENTS §4A requires decoupling; rev 1 leaked column names).
> 7. **§11.1 `Actor` change audited** — exactly 2 construction sites exist (`ActorContextFilter`,
>    `UserAdminService`); a compatibility constructor keeps all accessor call sites compiling.
> 8. **§11.1 `@FspOperation` inventory added** — all 9 declaring controllers listed; only new
>    plugin-facing ops opt into `actors()`.
> 9. **§7 approval changes made precise** — `canonicalOpType` gains a `PLUGIN:` prefix rule;
>    `ApprovalService.open` gains the machine-maker refusal; `approvals` gains `client_id` (V11 ALTER).
> 10. **§12.5 saga recovery SLA defined** — stuck `TENANT_PENDING` cases are an operator-visible
>     queue with a bounded SLA and a documented escalation, not an unbounded wait.
> 11. **§14.3 corrected** — `tenant.activate.force` does NOT exist in the codebase; the claim is
>     removed. Retention is stated as what actually stays: tenant activation logic in core.
> 12. **§11.0 build changes added** — `settings.gradle.kts`, root `build.gradle.kts`, AGENTS §6b
>     layout-map amendment (rev 1 said "Gradle subproject" but listed no build changes).
> 13. **§13.1 widget `requires` clarified** — `requires` = permission cells (same list the
>     permission catalogue and `widgets:check` validate); no separate concept.
> 14. **§4.4 assertion reuse (N=5) made explicit and auditable** — one assertion may back at most
>     N on-behalf uses within its 60 s life, each use audited; single-use per `(jti, useIndex)`.

This document is the single source of truth for (a) how ANY plugin plugs into FSP and (b) the
first plugin built on it: KYC/KYB. If a build step meets something this plan does not answer,
the plan is amended first — nothing is improvised in code.

---

## 0. Reading guide

| § | What it settles |
|---|---|
| 1 | Goals, non-goals, glossary, **FAPI 2.0 sequencing** |
| 2 | The invariants no plugin may ever break |
| 3 | Topology — every process, port, trust boundary |
| 4 | The four call paths and their FAPI 2.0 controls (hop by hop) |
| 5 | Plugin identity, registration, installation, readiness |
| 6 | Authorization — who decides, who enforces |
| 7 | Four-eyes for plugin actions (incl. exact `ApprovalService` changes) |
| 8 | Events — core → plugin delivery (ownership: core serves the feed) |
| 9 | Idempotency (with key-scoping table), audit, logging, errors, **abuse controls** |
| 10 | The plugin contract (what a plugin author must implement) |
| 11 | Changes per component — **build files, Actor audit, `@FspOperation` inventory** |
| 12 | The KYC plugin — domain, data, APIs, flows, **recovery SLA** |
| 13 | The `kyc.manage` + `kyc.orgs` widgets |
| 14 | Migration of today's in-core KYC |
| 15 | Build phases, exit criteria, test plan |
| 16 | Decisions that need sign-off |

---

## 1. Goals, non-goals, glossary

### 1.1 Goals
1. Any third-party plugin can be added **without code changes to core, gateway or BFF** — only
   registration data (manifest, client keys) approved under four-eyes.
2. Every hop stays **FAPI 2.0 compliant** (DPoP-bound tokens, `private_key_jwt`, no ROPC, no
   bearer-only, tokens never in the browser, no tokens in URLs) — see sequencing in §1.4.
3. **Core stays the only policy decision point** (AGENTS §4b) and the only money boundary
   (vision §4.8): plugins never decide permissions, never touch TigerBeetle, never write audit.
4. KYC moves out of core into the first plugin (`fsp-plugin-kyc`) with one widget that lists,
   searches, sorts, pages, views, creates, edits, approves, rejects and requests info.

### 1.2 Non-goals (this slice)
- Remotely loaded widget bundles (widgets for plugins ship in the FSP SPA build this slice; see §13.4).
- Real KYC providers (OCR, liveness, sanctions/PEP feeds) — screening stays simulated, behind a
  provider port so providers drop in later (PRD Slice 1 hardening).
- Webhook (push) event delivery — pull feed only (§8); push is a later, additive transport.
- Cross-instance locks / multi-replica plugins (single replica per plugin this slice).
- **User-facing PAR/PKCE/RAR flows** (FAPI2 tranche 2) — machine client_credentials + DPoP only (§1.4).

### 1.3 Glossary
| Term | Meaning |
|---|---|
| **Plugin type** | Code + version + manifest (vision §6.2). Registered once, platform-wide. |
| **Installation** | A tenant-scoped activation of a plugin type, with config + its own secret scope. |
| **Effective installation** | The installation that applies to a tenant: a DIRECT one on the tenant, else the nearest ancestor's with an inheritance policy that reaches it (§5.4). |
| **Machine client** | A plugin's OAuth client at the IdP (client_credentials + `private_key_jwt` + DPoP). Never a person. |
| **Actor assertion** | Short-lived JWT the gateway mints for one forwarded request: "person P, via client C, operation O, tenant T, permission decided by core". Audience = one plugin. |
| **On-behalf call** | A plugin → core call that carries BOTH the plugin's machine token AND the actor assertion it received, so core can act for the person (§4.4). |
| **Contributed operation** | A plugin route declared in its manifest; enters the gateway only through core's signed catalog. |

### 1.4 FAPI 2.0 sequencing (settled — resolves the rev 1 ledger contradiction)

AGENTS §5b marks FAPI2 guide §2 (PAR/PKCE/DPoP/RAR) as `⛏ FAPI2 slice — never expose to external
audiences before it`. This plan brings part of that forward. The rule:

- **Tranche 1 (this plan):** *machine-plane* FAPI2 — `client_credentials` + `private_key_jwt`
  (aud = issuer **string**) + DPoP sender-constrained machine tokens, consumed exclusively by
  **cluster-internal, separately-registered plugin clients**. No browser flow changes; no person
  tokens change; nothing is exposed to external audiences — plugins are registered by core under
  four-eyes and can only reach the gateway.
- **Tranche 2 (existing FAPI2 slice, unchanged):** person-plane hosted flows (PAR + PKCE + RAR,
  mTLS sender-constraint option for partners), which replace the Phase-0 password grant.

On landing P1 (§15), AGENTS §5b row "§2 FAPI2 (PAR/PKCE/DPoP/RAR)" is updated to
`🟡 machine-plane tranche (this plan) · person-plane ⛏ tranche 2`. The ledger is amended by this
plan, not contradicted by it. **If a plugin ever leaves the cluster (external audience), tranche 2
must land first** — this is a hard gate, restated in §16 (D12).

---

## 2. Invariants (non-negotiable — every plugin, every slice)

| # | Invariant | Enforced by |
|---|---|---|
| I1 | Core is the ONLY permission decision point; plugins never evaluate RBAC rows. | Gateway PEP asks core `authz/decide` before forwarding (§6); core re-checks on every on-behalf call. |
| I2 | A machine actor is never a maker or a checker. Four-eyes is person-anchored at `person_id`. | `ApprovalService.open` refuses non-person makers; `requireDistinctPerson` on resolve; plugin approvals carry the person from the actor assertion (§7). |
| I3 | Plugins never receive: the user's access token, the core internal credential, a TigerBeetle client, DB credentials of core. | Gateway strips `Authorization`/`DPoP`/`X-FSP-*`; separate DB; no TB client in plugin deps (boundary test §15). |
| I4 | Plugins never write the audit store. Core records plugin-mediated actions and returns an `auditReceipt`. | No audit endpoint for plugins; core audits at every plugin-facing mutation. |
| I5 | No route reaches a plugin unless it is in core's **signed** catalog AND its installation for the target tenant is ACTIVE. | Gateway catalog + readiness check → `404 ROUTE_NOT_IN_CATALOG` / `503 PLUGIN_NOT_READY`. |
| I6 | A plugin sees only tenants inside the effective installation's reach AND inside the person's subtree. | Core subtree APIs evaluated for the person (on-behalf); plugin stores `owner_tenant_id`. |
| I7 | Revocation is DB-truth and immediate: disabling a client/installation/grant denies the next call. No token blacklist. | IdP + gateway + core read status per request; gateway authz pre-check is NOT cached (§6). |
| I8 | Every mutating request carries `Idempotency-Key` end to end (AGENTS §2e), **scoped to the acting plane at each hop** (§9.1 table). | Gateway requires it on plugin routes; plugin + core both dedupe per their own actor scope. |
| I9 | No secrets / tokens / DPoP proofs / full identity numbers in logs (mask to last 4). | Logging mandate §9.4; log-scan test. |
| I10 | A plugin can be disabled without data loss: routes go `503`, data becomes read-only, nothing is hard-deleted (vision §6.5). | Installation status machine (§5.3). |
| I11 | Plugin routes get the same abuse controls as core (bounded bodies/headers/timeouts, `429`+`Retry-After`, early shed). | Gateway edge checks §9.5; no catch-all forwarding. |

---

## 3. Topology

```
                          (public)                         (cluster-internal, mTLS in prod)
Browser ──cookie+CSRF──► BFF :8080 ──DPoP user token──► API GATEWAY :8090 ─┬─ /v1/**            → core :8081
  SPA :5173 (dev)        (session, private_key_jwt                         ├─ /v1/plugins/kyc/** → plugin-kyc :8083
                          OAuth client "fsp-bff")                          ├─ /v1/plugins/<p>/** → plugin-<p>
                                                                           └─ /idp/**            → idp :8082
plugin-kyc :8083 ──client_credentials+private_key_jwt+DPoP──► gateway /idp/token
plugin-kyc :8083 ──DPoP machine token (+ actor assertion)──► gateway /v1/** ──► core
```

| Process | Port | Owns | Talks to |
|---|---|---|---|
| core | 8081 | RBAC, tenants, approvals, audit, outbox, ledger, **plugin registry/installations/grants**, signed catalog, **event feed (serves it)** | Postgres `fsp`, TigerBeetle |
| idp | 8082 | Credentials, **OAuth client registry** (BFF + plugins), token issuance, JWKS | Postgres `fsp` (idp tables) |
| gateway | 8090 | Edge PEP: token+DPoP validation, catalog routing, authz pre-check, readiness, actor assertions, idempotency-key presence | core, idp, plugins |
| bff | 8080 | Browser session; only OAuth client that acts for browsers | gateway |
| **plugin-kyc** | **8083** | KYC domain + its own data | gateway only (never core/idp directly), Postgres **`fsp_plugin_kyc`** |

Rules:
- A plugin's ONLY network peer is the gateway (inbound from it, outbound to it). Core and IdP do
  not accept plugin traffic except through the gateway. In compose, the plugin sits on a network
  that can reach the gateway and its own DB only.
- Each plugin has its **own database** (own role/password, own Flyway history). Core never reads
  it; the plugin never reads core's. The plugin calls core's event feed THROUGH the gateway as a
  machine client (Path C, §4.3) — core serves the feed, owns the table, publishes the cursor.
- Dev: plaintext + audience-bound signed assertions. Prod: mTLS gateway↔plugin with the dev-PKI /
  managed-CA identities (AGENTS §7) — the assertion stays (defense in depth), mTLS is added.

---

## 4. The four call paths (FAPI 2.0, hop by hop)

```mermaid
flowchart TB
    subgraph Browser["Browser (SPA :5173)"]
        B[SPA + session cookie]
    end

    subgraph BFF["BFF (:8080) — session + CSRF"]
        BF[session → DPoP user token]
    end

    subgraph Gateway["API Gateway (:8090) — sole public origin"]
        GW[token + DPoP validation, catalog routing, authz pre-check, readiness]
    end

    subgraph Core["core (:8081) — RBAC, domain, TigerBeetle"]
        CO[RBAC + domain decisions]
    end

    subgraph IdP["IdP (:8082) — native IdP, RS256/JWKS"]
        IP[token issuance, JWKS]
    end

    subgraph Plugin["plugin-kyc (:8083) — own DB (fsp_plugin_kyc)"]
        PK[KYC domain + data]
    end

    B -->|cookie+CSRF| BF
    BF -->|DPoP user token| GW
    GW -->|DPoP machine token + DPoP| IP
    GW -->|DPoP machine token (+ actor assertion)| PK
    GW -->|re-issued X-FSP-* headers| CO
    PK -->|machine token + DPoP + actor assertion| GW
    GW --> CO
    IP -.->|JWKS verification| GW

    style Browser fill:#e1f5e1
    style BFF fill:#e1f5e1
    style Gateway fill:#fff3cd
    style Core fill:#fff3cd
    style IdP fill:#e1f5e1
    style Plugin fill:#f5e1e1
```

### 4.1 Path A — person → plugin (e.g. "list KYC cases")
```
SPA ─GET /api/plugins/kyc/cases?page=0──► BFF
BFF: session → user's DPoP-bound access token; mints DPoP proof (htu = gateway URL, htm, jti, iat)
BFF ─GET /v1/plugins/kyc/cases + Authorization: DPoP <at> + DPoP: <proof>──► gateway
gateway:
  1. strip all inbound X-FSP-*, Forwarded, Cookie (existing)
  2. verify token (sig, iss, aud, exp, nbf) + DPoP (sig, typ, alg∈{ES256,PS256}, htu, htm, iat±skew,
     jti replay cache, cnf.jkt == thumbprint)                                  [existing code]
  3. match route in the signed catalog → op {id, plugin, permission, resource template}   [§5.2]
  4. resolve target tenant (path {tenantId} | ?tenantId | actor's tenant)
  5. readiness: effective installation of plugin for target tenant is ACTIVE, else 503     [§5.5]
  6. authz: POST core /internal/authz/decide {personId, permission, tenantId, op} → allow/deny
     deny → 403 with the specific permission (ui-std); never forwarded                     [§6]
  7. mutating method → require Idempotency-Key header (else 400 IDEMPOTENCY_KEY_REQUIRED)
  8. mint actor assertion (§4.5), aud = "plugin:kyc"; forward WITHOUT Authorization/DPoP
gateway ─GET http://plugin-kyc:8083/v1/plugins/kyc/cases + X-FSP-Actor-Assertion──► plugin
plugin: verify assertion (gateway JWKS, aud, exp ≤ 60 s, jti single-use, op matches route) → serve
```
FAPI controls: tokens never leave BFF/gateway; DPoP per request; the plugin never sees a token it
could replay against core; the decision is core's.

### 4.2 Path B — plugin → IdP (machine token)
```
plugin ─POST gateway /idp/token──► idp
  grant_type=client_credentials
  client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
  client_assertion=<JWT {iss=sub=client_id, aud="<issuer>" (STRING), jti, iat, exp≤60 s}, ES256 by plugin key>
  DPoP: <proof {htu=<issuer>/idp/token, htm=POST, jti, iat}, header jwk = plugin's DPoP key>
  scope=<subset of the client's registered grants>   (optional; default = all grants)
idp: client ACTIVE? assertion sig vs registered JWKS? aud string == issuer? jti unseen? DPoP valid?
  → 200 {access_token, token_type:"DPoP", expires_in: 300}
     claims: iss, aud="fsp-financial-api", sub="client:<client_id>", clid, tid=<owner tenant>,
             typ="machine", roles=[], scope, cnf.jkt, iat, exp, jti
```
- **No refresh tokens** for machines (FAPI §5.3.2: client re-authenticates). TTL 300 s.
- The client's DPoP key and its `private_key_jwt` key are **different key pairs**; both P-256.
- ROPC stays rejected for clients (the Phase-0 password seam is person-only and slated for removal).
- `aud` MUST be the issuer as a **string**; an `aud` **array** in the client assertion → `401`
  (FAPI requirement, regression-tested in §15).

### 4.3 Path C — plugin → core (machine, e.g. "read the event feed")
```
plugin ─GET gateway /v1/plugins/events?after=… + Authorization: DPoP <machine at> + DPoP: <proof>──►
gateway: same token + DPoP validation as Path A; typ=machine → re-issues
         X-FSP-Client-Id, X-FSP-Tenant-Id (=tid), X-FSP-Actor-Type: MACHINE, internal credential
core: ActorContextFilter builds Actor{PERSON|MACHINE|DELEGATED} (here MACHINE: clientId, ownerTenantId,
      personId=null); FspOperationInterceptor checks the op against CLIENT GRANTS (§11.1), not roles
```
- **Ownership:** `GET /v1/plugins/events` and `POST /v1/plugins/events/ack` are **core operations**
  served by `PluginEventsController` (core owns `plugin_event_feed` + cursors). The plugin polls
  core through the gateway. Machine-only operations are flagged `actors={MACHINE}` in
  `@FspOperation` and refuse persons — see the operation inventory in §11.1.

### 4.4 Path D — plugin → core on behalf of a person (e.g. "create the applicant tenant")
```
plugin ─POST gateway /v1/tenants/{parent}/children
        Authorization: DPoP <machine at> + DPoP: <proof>
        X-FSP-Actor-Assertion: <the assertion the plugin received on Path A, unmodified>
        Idempotency-Key: kyc-onboard-<caseId>──►
gateway: validates machine token + DPoP; validates the assertion:
         sig (gateway key), aud == "plugin:" + <machine token's plugin>, exp,
         on-behalf use budget: at most N=5 uses per assertion within its life, each use recorded
           (audit row: assertJti + useIndex + op), jti single-use PER USE INDEX; op ∈ the
           operation's allowed on-behalf sources (§5.2)
         → re-issues X-FSP-User-Id/-Tenant-Id/-Username from the ASSERTION, X-FSP-Client-Id from the
           machine token, X-FSP-Actor-Type: DELEGATED
core: Actor{type=DELEGATED, personId, clientId}
      → permission = person's RBAC (FspOperationInterceptor, unchanged)
      AND client grant for the op (the plugin may only do what it was granted)
      → audit records actor_id=person, client_id=plugin
```
**Why N=5 (explicit, rev 1 was vague):** one Path A request (e.g. "create case") yields ONE
assertion, but the saga makes several on-behalf calls (tenant create, approval open, later
resolves). The assertion is the delegation credential; it stays 60 s-lived and audience-bound, and
its budget is bounded at N=5 uses per life with every use audited. A plugin needing more calls
gets fresh assertions by serving fresh person requests. **Why not RFC 8693 token exchange:** it
would mint a second user-bound token per call and widen the token surface; the assertion is
internal-hop only, and core still re-decides both planes. (Revisit if plugins ever leave the
cluster — gated by §1.4 tranche 2.)

### 4.5 Actor assertion — exact format
JWS compact, **ES256**, `kid` = gateway assertion key (separate from IdP + UI signing keys),
JWKS published at gateway `/.well-known/fsp-gateway-jwks` (cluster-internal).
```json
{ "iss": "fsp-gateway", "aud": "plugin:kyc", "iat": 1794982000, "exp": 1794982060,
  "jti": "uuid", "sub": "<personId>", "tid": "<person's tenant>", "uname": "platform-admin",
  "clid": "fsp-bff", "op": "kyc.case.list", "perm": "kyc.review", "target": "<target tenantId>",
  "corr": "<correlationId>", "htm": "GET", "htu": "/v1/plugins/kyc/cases" }
```
The plugin MUST reject: wrong `aud`, expired, reused `jti` (in-memory cache ≥ 120 s), `htm/htu`
not equal to the request it is serving. It MUST NOT store or log the assertion.

---

## 5. Plugin identity, registration, installation, readiness

### 5.1 Registration (once per plugin type + version) — four-eyes

> **Superseded 2026-11-25 — EASY INSTALL (AGENTS.md §2.1):** registration and installation are ONE step with NO four-eyes; a release registers and installs on the platform root at once. Only suspend / resume stay four-eyes.
1. Plugin vendor supplies a **manifest** (§5.2) + two public JWKs (client-auth key, DPoP key) +
   its base URL.
2. Platform admin (`plugin.register`, FOUR_EYES) submits `POST /v1/platform/plugins` → core stores
   it `PENDING_APPROVAL` and opens approval `PLUGIN_REGISTER` (payload = manifest hash).
3. A distinct platform admin approves → core:
   - inserts contributed permissions into the catalog (append-only; **zero grants** — AGENTS §5b);
   - registers the OAuth client at the IdP via `POST idp /internal/clients` (internal credential,
     core→idp only): `{client_id, jwks, grant_types:["client_credentials"], status:ACTIVE, owner_tenant_id}`;
   - inserts the machine grants the manifest requests (`client_grants`) — only from the closed
     list in §5.2.4;
   - marks the type `REGISTERED`; republishes the **signed catalog** with the plugin's operations
     (still unreachable until an installation is ACTIVE).
4. Version bump = new registration of the same `code` with new `version`; routes of a major bump
   are new operation ids; old installations keep the old version until upgraded (four-eyes).

### 5.2 Manifest (JSON, hashed + stored verbatim; vision §6.1 in FSP terms)
```json
{
  "code": "kyc", "version": "1.0.0", "family": null,
  "baseUrl": "http://plugin-kyc:8083",
  "hierarchyInheritance": "INHERIT",
  "installationModel": "SINGLETON_PER_TENANT",
  "minAncestorRoleToInstall": "plugin.install",
  "dependencies": [],
  "contributedPermissions": [
    {"key":"kyc.case.create","label":"Create KYC applications","risk":"FOUR_EYES"}, …],
  "operations": [
    {"id":"kyc.case.list","method":"GET","path":"/v1/plugins/kyc/cases",
     "permission":"kyc.review","resource":"TENANT","tenantFrom":"actor","risk":"READ"},
    {"id":"kyc.case.decide","method":"POST","path":"/v1/plugins/kyc/cases/{caseId}/decisions",
     "permission":"kyc.approve","resource":"KYC_CASE","tenantFrom":"actor","risk":"FOUR_EYES"}, …],
  "onBehalfOperations": ["tenant.child.create","approval.plugin.open","approval.resolve.approve",
                         "approval.resolve.reject","tenant.subtree.read","tenant.detail.read"],
  "machineGrants": ["plugin.events.read","plugin.events.ack","tenant.verification.record",
                    "tenant.directory.read"],
  "events": {"subscribes":["Approval.Granted","Approval.Rejected","Tenant.Activated","Tenant.Deactivated"],
             "emits":[]},
  "widgets": ["kyc.manage","kyc.orgs"],
  "secretScope": {"required": false},
  "readiness": ["PERMISSIONS_SEEDDED","CLIENT_ACTIVE","SECRETS_PROVISIONED","DEPENDENCIES_READY"]
}
```
Rules: every operation path is under `/v1/plugins/<code>/`; `permission` must be a core or
contributed key; `tenantFrom` ∈ `path:<var>` | `query:<var>` | `actor`; operation ids are
`<code>.*` and stable forever; `onBehalfOperations` and `machineGrants` are drawn ONLY from the
closed lists core publishes (§5.2.4) — anything else fails registration.

#### 5.2.4 Closed lists core offers to plugins (this slice)
| On-behalf (person's RBAC + client grant) | Machine-only (client grant) |
|---|---|
| `tenant.child.create` (needs `org.onboard_child`) | `plugin.events.read` — own feed only |
| `tenant.detail.read`, `tenant.subtree.read` (needs `kyc.review`\|`org.create`) | `plugin.events.ack` |
| `approval.plugin.open` (maker = person) | `tenant.verification.record` (§7.3) |
| `approval.resolve.approve` / `.reject` (checker = person, ≠ maker) | `tenant.directory.read` (id, name, status, path — no PII) |
| `user.list` (read) | |

### 5.3 Installation (per tenant) — four-eyes

> **Superseded 2026-11-25 — EASY INSTALL (AGENTS.md §2.1):** registration and installation are ONE step with NO four-eyes; a release registers and installs on the platform root at once. Only suspend / resume stay four-eyes.
State machine: `PENDING_APPROVAL → PROVISIONING → ACTIVE ⇄ SUSPENDED → DEACTIVATED` (never deleted).
- Install: `POST /v1/tenants/{tenantId}/plugins` `{code, version, config}` by a person with
  `plugin.install` at that tenant or an ancestor (manifest `minAncestorRoleToInstall`) → approval
  `PLUGIN_INSTALL` → on approve: `PROVISIONING`; core emits `PluginInstallation.Created` on the
  plugin's feed; the plugin provisions (secret scope, defaults) and calls
  `POST /v1/plugins/installations/{id}/ready {gate, status}` (machine) per gate → core flips to
  `ACTIVE` when every manifest gate is `ACTIVE` (vision §6.6, gap G14).
- Suspend / deactivate: four-eyes; routes → `503 PLUGIN_NOT_READY`; data read-only (plugin
  enforces by refusing writes when its installation view says not ACTIVE).
- `inheritance_origin`: `DIRECT` | `INHERITED_FROM:<tenantId>` (vision §6.3, gap G15).

### 5.4 Effective installation (one rule, used by gateway + core + plugin)
For target tenant T and plugin P: walk T's `hierarchy_path` from T up to root; the first
installation of P found decides —
- on T itself (DIRECT): it applies;
- on an ancestor A with policy `REQUIRED`/`INHERIT`/`OPT_OUT` (and no `OPT_OUT` opt-out recorded
  for T or a node between): it applies;
- `OPT_IN`: applies only if T (or a node between) has an `OPT_IN` acceptance row;
- `PROHIBITED` on any ancestor: P never applies below it (overrides everything).
Core exposes it as `GET /internal/plugins/{code}/effective?tenantId=` (gateway, cached 10 s — the
10 s cache is a readiness hint only; revocation latency bound = 10 s, see I7/D8 for authz which is NOT cached)
and `GET /v1/plugins/{code}/installations/effective` (machine, for the plugin's own checks).

### 5.5 Readiness at the edge
Gateway: route matched → effective installation for the operation's target tenant must be
`ACTIVE`; else `503 application/problem+json {code: PLUGIN_NOT_READY, plugin, gate?}` — **never**
`403` (vision §6.6: 403 is reserved for authorization).

### 5.6 Secrets (per installation, gap G15)
Plugin-owned: `plugin_secrets(installation_id, name, ciphertext, key_version)` encrypted with a
per-installation data key derived from the plugin's KEK (env/KMS) + installation id. Shared
provider credentials are a **brokered capability** registered in core, never a shared row. KYC
this slice: no provider secrets (simulated screening) → gate `SECRETS_PROVISIONED` is set ACTIVE
immediately by the plugin with `{"required":false}`.

---

## 6. Authorization (PDP = core, PEPs = gateway + core)

| Request | PEP | PDP call | Plane |
|---|---|---|---|
| Person → plugin route | gateway | `POST core /internal/authz/decide` | person RBAC at target tenant |
| Plugin → core (machine) | core `FspOperationInterceptor` | in-process | client grants |
| Plugin → core (on-behalf) | core interceptor | in-process | person RBAC **AND** client grant **AND** op ∈ manifest `onBehalfOperations` |
| Person → core (unchanged) | core interceptor | in-process | person RBAC |

`/internal/authz/decide` (internal credential, gateway-only):
`{personId, permission, tenantId, operationId}` → `{allow: bool, reason: "OK"|"PERMISSION_DENIED"|
"NOT_REACHABLE"|"PERSON_INACTIVE", permission}`; logged at core (`authz_decision`) and audited for
denials (vision §11, guide §7 evidence tier). **No caching at the gateway** (revocation must hit
the next call — I7). This is a synchronous dependency: core must protect it like any bounded
query (size caps, timeouts; a slow core sheds at the gateway with `503`, matching AGENTS §6
slow-dependency protection).

Inside the plugin, data scoping still applies (I6): every query is filtered by
`owner_tenant_id ∈ reach(person)` obtained from core (`tenant.subtree.read`, on-behalf, cached
per request only).

---

## 7. Four-eyes for plugin actions

> **Superseded 2026-11-25 — EASY INSTALL (AGENTS.md §2.1):** registration and installation are ONE step with NO four-eyes; a release registers and installs on the platform root at once. Only suspend / resume stay four-eyes.

### 7.1 Generic plugin approvals in core
New core op type family: any `operationType` string with the `PLUGIN:` prefix, e.g.
`PLUGIN:kyc:CASE_DECISION`.
- **`canonicalOpType` change (exact):** in `ApprovalService.canonicalOpType`, before the
  catch-all `default -> GENERIC`, add: `if (raw starts with "PLUGIN:") return raw;` — plugin op
  types pass through verbatim and dispatch to the generic plugin handler. No enumeration of
  plugin types is ever added to `OpType`.
- **Maker plane refusal (exact):** `ApprovalService.open(...)` gains — at entry — a check that
  the maker plane is a person: when `maker` is an `Actor` it must have `personId != null` and
  `type != MACHINE` (DELEGATED carries a personId and is allowed — the person is the maker, the
  plugin is only the channel). The `open(contextActor, makerId, …)` overload validates
  `makerId != null` for `PLUGIN:*` types. Violation → `403 MACHINE MAKER` (`WARN`-logged,
  AGENTS §2c). `requireDistinctPerson` on resolve already guarantees checker ≠ maker.
- **Client attribution:** `approvals` table gains `client_id` (V11 `ALTER TABLE approvals ADD
  COLUMN client_id varchar(64)`); `open` records `client_id` from the actor (null for direct
  person calls, the plugin's `clid` for on-behalf). Queue views surface it.
- **Checker permission:** resolution permission comes from the manifest's action map
  (`action → permission`), stored with the registration; `requirePermissionFor` gains a
  `PLUGIN:` branch that looks it up (defaults to the op's declared permission registered with the
  action). The checker's `kyc.approve` at the subject tenant is enforced by core, not by the plugin.
- `POST /v1/approvals/plugin` (on-behalf, `approval.plugin.open`) `{action, targetResourceId,
  payloadHash, summary}` → maker = the person from the assertion; plugin client recorded.
- Resolution via the existing `POST /v1/approvals/{id}/approve|reject` (on-behalf from the plugin's
  decide route, or directly from the core Approvals screen).
- A generic `PluginApprovalHandler` (one bean, `operationType()` → sentinel `"PLUGIN:*"` matched
  by prefix in `ApprovalService`, since handlers are keyed by exact OpType today) records the
  outcome and emits `Approval.Granted|Approval.Rejected {approvalId, action, targetResourceId,
  payloadHash, makerId, checkerId, reason}` on the plugin's feed. Core never interprets plugin
  payloads.

### 7.2 Approval ↔ plugin state
The plugin moves its aggregate ONLY on the event (or on the synchronous 200 of an on-behalf
resolve — the event then arrives and is a no-op by `approvalId` idempotency).

### 7.3 Verdicts that change core state (`tenant.verification.record`)
`POST /v1/tenants/{tenantId}/verification` (machine, `actors={MACHINE}`) `{verdict: VERIFIED|REJECTED,
approvalId, payloadHash}`. Core accepts ONLY if: approval exists, is `APPROVED` (for VERIFIED) or
`REJECTED`, its action is in the plugin's verdict-capable actions, its `payloadHash` matches, the
approval's target belongs to this tenant, and maker ≠ checker. Then core (one transaction):
sets `verification_status`, and for VERIFIED + `status=PENDING` runs today's activation
(`ACTIVE`, books opened, `Tenant.Activated`), audits with actor=checker, client=plugin,
returns `auditReceipt`. So a plugin can never self-verify (vision §2.3).

---

## 8. Events — core → plugin (pull feed; core serves it)

**Ownership (settled):** core owns the feed table and serves both endpoints through the gateway
(Path C); the plugin only polls and acks.

- Core outbox rows get consumer routing: `plugin_event_subscriptions(plugin_code, event_name)`
  from the manifest; a dispatcher (core, scheduled + outbox-tail) copies matching outbox events
  into `plugin_event_feed (plugin_code, seq BIGSERIAL, event_name, payload JSONB, tenant_id,
  correlation_id, created_at)`. Payloads are **minimal** (ids, statuses, hashes — no PII).
- `GET /v1/plugins/events?after=<seq>&limit<=100&wait<=20s` (machine, `plugin.events.read`,
  `actors={MACHINE}`) → long-poll served by core `PluginEventsController`. The long poll holds a
  bounded DB query; wait is capped at 20 s and the gateway enforces its own read timeout (§9.5).
- `POST /v1/plugins/events/ack {seq}` (`plugin.events.ack`, `actors={MACHINE}`) records the cursor
  in `plugin_event_cursors(plugin_code, last_ack_seq)`.
- At-least-once; plugins MUST be idempotent per `seq` (store `last_seq` in their DB, same tx as
  the effect). Gaps are a reconciliation signal (vision §13); the feed exposes `hasGap` on the
  poll response so the plugin can trigger a re-read instead of silently skipping.

---

## 9. Idempotency, audit, logging, errors, abuse controls

### 9.1 Idempotency — key scoping per hop (settled)
AGENTS §2e scopes keys per maker (`person_id`). With three planes in play the scoping is:

| Hop | Actor the key is scoped to | Who enforces | Effect |
|---|---|---|---|
| SPA → BFF → gateway (Path A mutation) | person (`person_id` from the session) | core `IdempotencyFilter` (unchanged) for /v1; gateway only *requires presence* on plugin routes | replay/409 against core semantics |
| plugin inbound (plugin's own API) | person from the assertion (`sub`) | plugin `idempotency_records` unique on `(person_id, key)` | AGENTS §2e semantics verbatim |
| plugin → core on-behalf (Path D) | **hybrid**: `(client_id ‖ person_id, key)` — dedupe must hold even if the person retries with a different plugin instance | core filter extends scope key with `client_id` when actor type = DELEGATED | crash-and-retry replays |
| plugin → core machine (Path C/verdict) | client (`client_id`) | core filter, `client_id` scope | deterministic keys (`kyc-verdict-<approvalId>`) replay safely |

- Plugin → core keys are deterministic and derived from the plugin aggregate
  (`kyc-onboard-<caseId>`, `kyc-approval-<caseId>-<rev>`, `kyc-verdict-<approvalId>`), so a
  crash-and-retry replays instead of duplicating. Approval resolution is additionally idempotent
  by approval state (`ApprovalService.resolveRow` no-ops on identical re-resolution).
- **Rule (I8):** the PERSON-facing journey keeps person-scoped keys; every PLUGIN-originated core
  call uses plugin-derived deterministic keys — two different key namespaces that must never
  collide because the scope tuple differs.

### 9.2 Audit (core is authoritative — I4)
Core audits every plugin-facing mutation: approvals open/resolve, tenant create, verification,
installation changes, registration, grant changes, every assertion on-behalf use, and every
gateway authz **denial**. Each record carries `actor_id` (person or null), `client_id` (plugin),
`correlation_id` (the gateway's, propagated through the assertion `corr` → plugin →
`X-Correlation-Id` on its core calls). Plugins keep a domain history table for their UI (not
evidence).

### 9.3 Error contract (all hops) — RFC 7807 + stable `code` + `correlationId`
| Situation | Status | code | SPA behaviour |
|---|---|---|---|
| No/expired session, invalid token, bad DPoP | 401 | `INVALID_TOKEN` / `INVALID_DPOP_PROOF` | force re-auth (hosted sign-in) |
| Route not in catalog | 404 | `ROUTE_NOT_IN_CATALOG` | treat as not available |
| Installation not ACTIVE | 503 | `PLUGIN_NOT_READY` (+`gate`) | "KYC is not enabled for this organization" |
| Permission denied | 403 | `PERMISSION_DENIED` (+`permission`) | show the missing permission (ui-std) |
| Four-eyes self-decide | 403 | `MAKER_CANNOT_APPROVE` | explicit blocked state |
| Machine actor tried as maker/checker | 403 | `MACHINE_MAKER` | operator error — check plugin version |
| State machine | 409 | `KYC_STATE` / `STALE_WRITE` / `APPROVAL_STATE` | reload |
| Plugin unreachable | 502 | `PLUGIN_UNAVAILABLE` | retry later (reads only) |
| Quota exceeded (§9.5) | 429 | `RATE_LIMITED` (+`Retry-After`) | back off |
| Validation | 400 | field codes | inline field errors |

### 9.4 Logging
Same mandate as core (AGENTS §2c): one structured line, MDC
`traceId/tenantId/userId/clientId`. Plugins never log assertions, tokens, DPoP proofs, bank
account numbers, ID numbers (last 4 only). Machine/DELEGATED actors set MDC `clientId`
automatically via the extended `ActorContextFilter`.

### 9.5 Abuse controls on plugin routes (new — AGENTS §6 was silent in rev 1)
- Bounded request body (default 256 KiB on plugin routes), bounded header count/size, bounded
  `wait` on the event poll (≤ 20 s), bounded `limit` (≤ 100) everywhere.
- Per-client (clid) token-bucket at the gateway on plugin routes: `429 RATE_LIMITED` +
  `Retry-After`; unauthenticated denial telemetry aggregated (guide §6).
- Gateway outbound timeouts to plugins: connect 1 s / response 5 s for reads and mutations alike;
  timeout → `502 PLUGIN_UNAVAILABLE`; **no automatic retry** (the plugin's own idempotency +
  saga recovery owns retries, §12.5).
- No catch-all forwarding: only catalog ops route to plugins; unknown paths → `404
  ROUTE_NOT_IN_CATALOG` before any plugin hop.

---

## 10. The plugin contract (what every plugin author implements)

1. **Process**: own deployable, own DB, Java 25 / Spring Boot 4 recommended (any language allowed
   if it satisfies this contract; FSP's own plugins use the FSP toolchain — AGENTS §1).
2. **Inbound**: serve ONLY manifest operations under `/v1/plugins/<code>/`; verify the actor
   assertion on every request (§4.5); reject anything else with 404.
3. **Outbound**: only to the gateway; obtain machine tokens via §4.2; DPoP proof per request with
   exact `htu`/`htm`; send `X-FSP-Actor-Assertion` only on on-behalf operations (budget N=5 per
   assertion, §4.4); deterministic `Idempotency-Key` on mutations (§9.1).
4. **Keys**: two P-256 key pairs (client auth, DPoP), private halves in the plugin's secret store;
   rotation = register new JWK (overlap) → switch → remove old (four-eyes each).
5. **Events**: long-poll the feed, idempotent handlers, persist the cursor transactionally.
6. **Installations**: report readiness gates; refuse writes when the effective installation is
   not ACTIVE; never assume an installation from the request alone — ask core (cached ≤ 10 s).
7. **Data**: every row carries `owner_tenant_id` (the installation tenant) and, where relevant,
   `subject_tenant_id`; queries are always scoped (I6).
8. **API style**: RFC 7807 errors with stable codes; list endpoints bounded (`size` 1–100), sort
   by allow-listed public names only (mapped to internals, §12.4), `page` 0-based, `q` 2–100
   chars (LIKE-escaped, parameterized — guide §4A); records/DTO immutability; optimistic locking
   with `expectedVersion`; money as strings in minor units; **no totals in list responses
   (AGENTS §4A) — pager uses `nextAfter`** (§12.4).
9. **Health**: `/actuator/health` (internal only). **No** endpoint outside the manifest is reachable
   through the gateway.
10. **Tests the plugin must ship**: assertion verification negative cases, idempotency replay,
    scope isolation (another tenant's id → 404), readiness refusal.

---

## 11. Changes per component

### 11.0 Toolchain / build (new — rev 1 omitted these)
- `backend/settings.gradle.kts`: add `include("plugins:kyc")` (plugin keeps its own Boot
  application plugin + bootJar).
- Root `backend/build.gradle.kts`: register the `plugins` Gradle subproject group; NO Kotlin, no
  new language plugins (AGENTS §1). The KYC plugin is pure Java 25, Spring Boot 4.0.9, Lombok
  `@Slf4j` for logging only — same constraints as core.
- `Makefile`: `backend-plugin-kyc` target (+ `stack-up` compose service).
- `infrastructure/docker-compose.yml`: `plugin-kyc` service on the `stack` profile network that
  reaches gateway + its own Postgres DB only; DB + role for `fsp_plugin_kyc`.
- `AGENTS.md` §6b layout map gains `backend/plugins/kyc` — the AGENTS amendment is part of this
  plan's P0 exit criteria, not an afterthought.

### 11.1 Core (new migration **V11__plugin_platform.sql**; never edit V1–V10)
Tables: `plugin_types(code, version, manifest JSONB, manifest_hash, status, registered_by,
approved_by, created_at)`, `plugin_installations(id, plugin_code, plugin_version, tenant_id,
status, inheritance_policy, inheritance_origin, config JSONB, version, created_at, updated_at)`,
`plugin_installation_gates(installation_id, gate, status, updated_at)`,
`plugin_opt_choices(tenant_id, plugin_code, choice)`, `client_grants(client_id, owner_tenant_id,
operation_or_permission, granted_by, created_at)`, `plugin_event_subscriptions`,
`plugin_event_feed`, `plugin_event_cursors(plugin_code, last_ack_seq)`;
**plus `ALTER TABLE approvals ADD COLUMN client_id varchar(64)`** (§7.1).

`Actor` change — **audited (exactly 2 construction sites in the codebase)**:
- `Actor` becomes `record Actor(UUID personId, String username, UUID activeTenantId, ActorType type, String clientId)`
  with `enum ActorType { PERSON, MACHINE, DELEGATED }` — plus a **compatibility constructor**
  `Actor(personId, username, activeTenantId)` delegating with `(PERSON, null)`, so all
  accessor-only call sites compile unchanged.
- Construction site 1: `ActorContextFilter` — reads `X-FSP-Actor-Type` (`PERSON` default)
  + `X-FSP-Client-Id` (already extracted for MDC today); builds the typed actor. Gateway re-issues
  these headers (§4.3/§4.4), so core is still last-line defense only (AGENTS §4c).
- Construction site 2: `UserAdminService` (checker reconstruction) — stays `PERSON` explicit.
- Machine actors carry `personId = null`; every existing `permissions.require(...)` path already
  dereferences `personId()` — machine actors never reach person ops because the interceptor
  rejects them first (`actors()` gate below), and `effectiveKeys` throws `ACTOR_MISSING`
  semantics for null personId (`401`).

`@FspOperation` change — **inventory (all 9 declaring controllers, codebase-verified)**:
`UserAdminController`, `TenantController`, `RbacController`, `AuditController`, `KycController`,
`LedgerController`, `JournalController`, `ApprovalController`, `UiHarnessController`.
- `@FspOperation` gains `actors() default {PERSON}` — **existing declarations change nothing**
  (persons only, as today). Only new machine/delegated ops opt in explicitly:
  `PluginEventsController` (MACHINE), `TenantVerificationController` (MACHINE),
  `PluginInstallationController` ready/effective (MACHINE), `AuthzDecisionController` (internal),
  `ApprovalController` plugin-open/resolve (DELEGATED ∪ PERSON), `TenantChildrenController` +
  `TenantSubtreeController` (DELEGATED ∪ PERSON).
- `FspOperationInterceptor`: actor type outside the op's `actors()` → `403 ACTOR_TYPE_REJECTED`
  (WARN-logged). MACHINE → client-grant check; DELEGATED → person check + grant check + op ∈
  manifest `onBehalfOperations` (registration-time verified list, stored in
  `client_grants.operation_or_permission` with kind = ON_BEHALF | MACHINE).
- `PermissionService.effectiveKeysForClient(String clientId, UUID tenantId)` (exact contract):
  returns the set of permission keys granted to `client_id` in `client_grants` rows whose
  `owner_tenant_id` is the client's owning tenant **or an ancestor of the target tenant**
  (same chain math as `effectiveKeys` — subtree reachability, AGENTS §6.2); additionally filters
  keys to those the target tenant's subtree can reach. No person tables are read.
- `ApprovalService` changes as per §7.1 (canonicalOpType prefix rule, machine-maker refusal,
  `client_id` attribution).
- New controllers: `PluginRegistryController` (`/v1/platform/plugins`), `PluginInstallationController`
  (`/v1/tenants/{id}/plugins`, `/v1/plugins/installations/{id}/ready`, `/…/effective`),
  `PluginEventsController` (**core serves the feed**, §8), `AuthzDecisionController`
  (`/internal/authz/decide`), `TenantChildrenController` (`POST /v1/tenants/{parentId}/children`
  — PENDING tenant + applicant admin + suspended books, atomic), `TenantSubtreeController`
  (`GET /v1/tenants/{id}/subtree` paged, `size` ≤ 100), `TenantVerificationController`.
- `OperationCatalog` / `CatalogDescriptorSigner`: the signed descriptor now = core ops + ops of
  REGISTERED plugin types (`{plugin, baseUrl, tenantFrom}` per op). Same ES256 key, same trust store.
- Permissions seed (system, `V11` part): `plugin.register`, `plugin.install`,
  `plugin.manage` (FOUR_EYES) granted to PLATFORM_ADMIN only.

### 11.2 IdP
- `oauth_clients(client_id, client_type PERSON_APP|MACHINE, jwks JSONB, grant_types, status,
  owner_tenant_id, token_ttl_s, created_at)` — the BFF moves into this table too (no config-only client).
- `client_credentials` grant (§4.2) with `private_key_jwt` (aud = issuer **string**; array →
  401), DPoP binding (`cnf.jkt`), `jti` replay cache for assertions, no refresh token.
- `POST /internal/clients`, `PATCH /internal/clients/{id}` (internal credential; core-only).
- Status checked on every token request (I7).

### 11.3 Gateway
- Catalog routes carry `upstream` (core | plugin baseUrl) — forwarding target chosen per op.
- Machine-token recognition (`typ=machine`) and the on-behalf validation of §4.4 (incl. the
  N=5 use budget, each use audited).
- Readiness check (§5.5), authz pre-check (§6, uncached), Idempotency-Key presence on plugin
  mutations, abuse controls (§9.5).
- Actor-assertion signer (own ES256 key, JWKS endpoint) + per-assertion use counter
  (`(jti, useIndex)`), audited.
- Strips `Authorization`/`DPoP`/`Cookie`/`X-FSP-*` before forwarding to plugins (I3); re-issues
  its own internal credential toward core on plugin→core calls.

### 11.4 BFF
No change: `/api/**` → gateway `/v1/**` already covers `/api/plugins/kyc/**`. (Session-invalidate
on upstream 401 already shipped.)

### 11.5 SPA
- `sources.ts`: plugin sources resolve to `/api/plugins/<code>/…` (BFF lane only — never `/v1`).
- Widget manifest gains optional `plugin: '<code>'`; the host shows a "not enabled for this
  organization" state on `503 PLUGIN_NOT_READY`; the workbench + builder show the plugin badge.
- Shared `Page<T>` type + `usePagedList` hook (URL-held page/size/q/sort, debounced q,
  `placeholderData` keep-previous) for every server-paged widget — built on the totals-free
  contract (§12.4).

---

## 12. The KYC plugin (`fsp-plugin-kyc`)

### 12.1 Module + runtime
`backend/plugins/kyc` (subproject per §11.0; Java 25, Spring Boot 4.0.9, Lombok logging only),
port 8083, DB `fsp_plugin_kyc` (own Flyway: `db/migration`, `db/seed/demo` under the same
`client-demo` profile rule as core — AGENTS §2). Compose service `plugin-kyc` in the `stack`
profile; `make backend-plugin-kyc`.

### 12.2 Domain
Case states (sealed `KycDecision` stays the switch-consumed algebra — AGENTS §2b):
```
DRAFT ──submit──► SUBMITTED ──(approval opened)──► PENDING_REVIEW
PENDING_REVIEW ──approve (checker≠maker)──► APPROVED      (→ verdict VERIFIED to core → tenant ACTIVE)
PENDING_REVIEW ──reject  (checker≠maker)──► REJECTED      (→ verdict REJECTED to core; terminal)
PENDING_REVIEW ──request-info──► INFO_REQUESTED ──edit+resubmit──► SUBMITTED …
APPROVED ──periodic review due──► (next slice: REVIEW_DUE)
```
Plus saga states for onboarding (§12.5): `TENANT_PENDING` (core tenant requested, not yet
confirmed). `@Version` + status precondition on every transition (`KYC_STATE` on wrong state).

### 12.3 Data (plugin DB, migration `V1__kyc_schema.sql`)
- `kyc_cases`: today's columns (core KYC V1 shape) + `owner_tenant_id` (installation tenant),
  `subject_tenant_id` (the applicant org, from core), `case_no` (human ref `KYC-2026-000123`),
  `approval_id`, `approval_rev`, `maker_person_id`, `checker_person_id`, `sub_type`,
  `search_vector tsvector` (generated: legal_name, dba_name, registration_number_norm, tax_id,
  case_no), `created_at`, `updated_at`, `version`.
- `kyc_checks`, `kyc_ubos`, `kyc_bank_accounts`, `kyc_master_values` — as in core today
  (ID / account numbers stored encrypted at rest with the installation data key; API returns
  masked `•••• 1234` unless `kyc.pii.read`).
- `kyc_case_events` (domain history for the UI), `idempotency_records` (unique on
  `(person_id, key)` — the plugin's inbound actor is always a person from the assertion, §9.1),
  `event_cursor`, `installations_cache`.
- Indexes: `(owner_tenant_id, status, submitted_at DESC)`, GIN on `search_vector`,
  `(owner_tenant_id, legal_name)`, `(owner_tenant_id, risk_rating)`, `(owner_tenant_id, next_review_date)`.

### 12.4 API (all under `/v1/plugins/kyc`, via BFF as `/api/plugins/kyc`)
| op id | Method + path | Permission | Notes |
|---|---|---|---|
| `kyc.case.list` | `GET /cases` | `kyc.review` | `page`(0..), `size`(1–100, def 25), `q`(2–100, FTS + prefix on reg/TIN/case_no; LIKE-escaped), `status`(multi, closed enum → 400 unknown), `risk`(multi), `subType`, `sort` (public names below) → `Page<CaseSummary>` (totals-free) |
| `kyc.case.get` | `GET /cases/{caseId}` | `kyc.review` | full 5-tab view + checks + approval state + `canDecide` / `blockedReason` for the caller |
| `kyc.case.history` | `GET /cases/{caseId}/history` | `kyc.review` | paged |
| `kyc.case.create` | `POST /cases` | `kyc.case.create` (+core `org.onboard_child` on-behalf) | onboarding saga §12.5; 201 `CaseView` |
| `kyc.case.update` | `PATCH /cases/{caseId}` | `kyc.case.create` | DRAFT / INFO_REQUESTED only; `expectedVersion` |
| `kyc.case.submit` | `POST /cases/{caseId}/submit` | `kyc.case.create` | opens core approval |
| `kyc.case.decide` | `POST /cases/{caseId}/decisions` | `kyc.approve` | `{decision: APPROVE\|REJECT\|REQUEST_INFO, reason, expectedVersion}`; reason required for REJECT/REQUEST_INFO |
| `kyc.org.list` | `GET /organizations` | `kyc.review` | person's subtree (core) ⨝ KYC status; same contract |
| `kyc.org.get` | `GET /organizations/{tenantId}` | `kyc.review` | org + latest case summary |
| `kyc.master.list` | `GET /master-values?category=` | `kyc.review` | reference data |

**`Page<T>` — totals-free (AGENTS §4A: no totals by default; settles the rev 1 contradiction):**
```json
{ "items": [ … ], "page": 0, "size": 25, "hasNext": true, "nextAfter": 1, "sort": "submittedAt,desc", "q": "coffee" }
```
Pager semantics: `items.size() == size` ⇒ the client MAY render "page n + 1" affordance via
`nextAfter`; no `totalItems`/`totalPages` anywhere. (If a future screen genuinely needs totals it
is a deliberate §4A override per endpoint, not a contract change.)

**Sort names — public ⇄ persistence map (AGENTS §4A decoupling; settles rev 1 leak):**
| Public sort key | Persisted expression (pinned, tiebroken by `id`) |
|---|---|
| `submittedAt` | `submitted_at` |
| `legalName` | `legal_name` |
| `status` | `case_status` |
| `riskRating` | `risk_rating` |
| `nextReviewDate` | `next_review_date` |
| `updatedAt` | `updated_at` |

Unknown public sort key → `400`. `CaseSummary`: `{caseId, caseNo, subjectTenantId, legalName,
dbaName, subType, registrationNumber, status, riskRating, submittedAt, nextReviewDate, makerName,
updatedAt, version}`.

Contributed permissions (new, zero grants until a role migration assigns them):
`kyc.case.create` (FOUR_EYES), `kyc.pii.read` (READ). Existing `kyc.review`, `kyc.approve`,
`kyc.reject` are reused (no rename — AGENTS §5b).

### 12.5 Flow — onboard a new organization (replaces `POST /v1/tenants/with-kyc`)

```mermaid
flowchart LR
    subgraph SPA["SPA (Browser)"]
        S1[POST /api/plugins/kyc/cases {5 tabs}]
    end

    subgraph Gateway["Gateway (:8090)"]
        G1[authz kyc.case.create]
        G2[mint actor assertion]
    end

    subgraph Plugin["plugin-kyc (:8083)"]
        P1[validate + insert case TENANT_PENDING]
        P2[screening simulated → checks]
        P3[status SUBMITTED]
    end

    subgraph Core["core (:8081)"]
        C1[tenant PENDING + admin + books]
        C2[approval PENDING_CHECKER]
        C3[verdict VERIFIED → ACTIVE + books open]
    end

    S1 --> G1
    G1 --> G2
    G2 --> P1
    P1 --> C1
    C1 --> C2
    P1 --> P2
    P2 --> P3
    C2 --> C3

    style SPA fill:#e1f5e1
    style Gateway fill:#fff3cd
    style Plugin fill:#f5e1e1
    style Core fill:#fff3cd
```

```
1 SPA POST /api/plugins/kyc/cases {5 tabs, subType}  (Idempotency-Key k, person-scoped, §9.1)
2 gateway: authz kyc.case.create @ actor tenant → assertion → plugin
3 plugin tx1: validate (NamibiaRules, UBO ≥25%…); insert case TENANT_PENDING (caseId), record k
4 plugin → core on-behalf POST /v1/tenants/{parent}/children {legalName, subType}
        Idempotency-Key kyc-onboard-<caseId> (DELEGATED scope = client‖person, §9.1)
   core (one tx): tenant PENDING/verification PENDING + applicant admin + suspended books + audit
5 plugin tx2: case.subject_tenant_id = tenantId; run screening (simulated) → checks; status SUBMITTED
6 plugin → core on-behalf POST /v1/approvals/plugin {action CASE_DECISION, target caseId,
        payloadHash = sha256(canonical case snapshot)}   Idempotency-Key kyc-approval-<caseId>-1
7 plugin tx3: approval_id, status PENDING_REVIEW → 201
Failure after 4: case stays TENANT_PENDING; the recovery job (every 60 s) re-runs 4–7 with the SAME
keys (core replays) — the tenant can never activate without the verdict (§7.3), so nothing unsafe
is visible meanwhile.
```
**This replaces AGENTS §3.9's "one transaction" with: core part atomic + plugin saga with
deterministic idempotency.** (Sign-off item D2.)

**Recovery SLA for stuck sagas (new — settles the rev 1 open end):**
- `TENANT_PENDING > 10 minutes` or `SUBMITTED without approval_id > 10 minutes` → plugin logs
  `WARN kyc_saga_stalled` and surfaces the case on an operator-visible "stalled onboardings"
  filter in the widget (status chip `STALLED`).
- `> 60 minutes` → `ERROR kyc_saga_stalled_p1` (P1 per AGENTS §2c money-adjacent incident table;
  here it is data-integrity adjacent) — the stalled case is added to the same queue surfaced to
  platform admins.
- The recovery job is idempotent (same keys); repeated recovery attempts do NOT create duplicate
  tenants/approvals (core replays deterministically). There is **no auto-cancellation** this
  slice: cancellation of a half-built saga (tenant created, verdict absent) is a documented
  operator action (reject the case → verdict REJECTED → tenant stays PENDING with verification
  REJECTED, non-activatable).

### 12.6 Flow — decide (approve / reject / request info)

```mermaid
flowchart LR
    subgraph SPA["SPA (Checker)"]
        S1[POST /cases/{id}/decisions {APPROVE}]
    end

    subgraph Gateway["Gateway (:8090)"]
        G1[authz kyc.approve]
        G2[mint actor assertion]
    end

    subgraph Plugin["plugin-kyc (:8083)"]
        P1[state PENDING_REVIEW? version ok?]
        P2[case APPROVED tx]
    end

    subgraph Core["core (:8081)"]
        C1[checker ≠ maker]
        C2[approval APPROVED + audit]
        C3[verification VERIFIED → ACTIVE]
    end

    S1 --> G1
    G1 --> G2
    G2 --> P1
    P1 --> C1
    C1 --> C2
    P1 --> P2
    C2 --> C3
    P2 --> C3

    style SPA fill:#e1f5e1
    style Gateway fill:#fff3cd
    style Plugin fill:#f5e1e1
    style Core fill:#fff3cd
```

```
SPA POST /api/plugins/kyc/cases/{id}/decisions {APPROVE}
gateway: authz kyc.approve → assertion → plugin
plugin: state PENDING_REVIEW? version ok? → on-behalf POST core /v1/approvals/{approvalId}/approve
core: checker ≠ maker (403 MAKER_CANNOT_APPROVE otherwise), kyc.approve at subject tenant,
      approval APPROVED, audit, feed event Approval.Granted
plugin: case APPROVED (tx); → machine POST core /v1/tenants/{subject}/verification
        {VERIFIED, approvalId, payloadHash}  Idempotency-Key kyc-verdict-<approvalId> (client scope)
core: verifies approval (§7.3) → tenant ACTIVE + books open + Tenant.Activated → auditReceipt
REJECT: approval rejected (reason) → case REJECTED → verdict REJECTED (tenant stays PENDING, verification REJECTED)
REQUEST_INFO: approval rejected with reason prefix REQUEST_INFO → case INFO_REQUESTED;
              resubmit opens approval rev+1 (new key)
```
`canDecide=false` with `blockedReason=MAKER` is computed by the plugin for the UI and ALSO
enforced by core — the UI never relies on its own check.

### 12.7 Organizations API (for the Organizations widget)
`GET /organizations` = core `tenant.subtree.read` (on-behalf → the person's reach, paged by core
on `legalName`/`status`, page ≤ 100 ids) joined with the plugin's latest case per subject tenant.
Search/sort on KYC fields (`verification`, `risk`) happen in the plugin over the subtree ids
(bounded: subtree pages of ≤ 100 ids; for search the plugin queries core with `q` first, then
joins). Response row: `{tenantId, legalName, type, subType, status, verificationStatus, depth,
parentTenantId, caseId?, caseStatus?, riskRating?, nextReviewDate?}`.

---

## 13. Widgets

### 13.1 `kyc.manage` (one widget, SME-styled, mirrors `users.organization`)
- **List**: table (cards on xs) — case no, organization, type, status chip, risk chip, submitted,
  next review, maker. Toolbar: search (debounced 300 ms, min 2 chars, server-side), status +
  risk filter pills, sort by clicking headers (server-side, public sort names, `aria-sort`),
  page size 10/25/50/100, pager "page 2" + next/prev via `nextAfter` (no totals — §12.4).
  All in the URL (`kq`, `kstatus`, `krisk`, `ksort`, `kpage`, `ksize`, `case`, `edit`).
  Skeleton rows while loading; keep previous page visible while fetching the next.
- **View** (drawer): header with status + risk; tabs Company · Contact & business · Addresses ·
  Owners (UBOs) · Bank accounts · Screening · History; masked PII unless `kyc.pii.read`.
- **Add** (drawer, 5-step form with per-step zod validation on blur/submit, server field codes
  mapped back to fields), **Edit** (same form; only DRAFT / INFO_REQUESTED; `expectedVersion`).
- **Decide**: Approve (primary) / Reject (danger, reason required) / Request info (reason
  required), inline confirm; if `canDecide=false` → explicit blocked banner
  ("You submitted this application — another reviewer must decide", ui-std §5.2); never hidden.
- Mutations never optimistic, zero retries; after success: write-through + invalidate list + case.
- Manifest: `id kyc.manage`, `plugin: 'kyc'`, `requires ['kyc.review','kyc.case.create','kyc.approve']`
  — **`requires` = the permission cells this widget needs** (same list the permission catalogue
  and `widgets:check` validate; no separate concept), actions
  create/edit→`kyc.case.create`, approve/reject/request-info→`kyc.approve`,
  `commandOperations` create/modify/approve; size `xs 4 · sm 8 · md 12 · lg 12`.
- Fixtures: `kyc.cases.page` (default 3 pages of data; empty; error), `kyc.case.get`,
  `kyc.case.history`, `kyc.master.list`; scenarios for "maker cannot decide" and "plugin not
  enabled (503)".

### 13.2 `kyc.orgs` (Organizations) — moved to the plugin API
Sources `kyc.orgs.page` → `/api/plugins/kyc/organizations`; adds search/sort/paging the same way;
keeps "pick one to focus the page selection (orgId)".

### 13.3 Not-enabled state
Any `503 PLUGIN_NOT_READY` → widget shows "KYC isn't enabled for this organization" (no retry
loop); `layout` renderer still lists the screen only if the widget is approved in the signed
bundle — readiness is a runtime state, not a layout state.

### 13.4 Third-party widget bundles (later slice, recorded so nobody improvises)
Remote widgets will be ES modules published with `bundleSha256` in the core widget registry,
loaded through an integrity-checked loader (hash-checked before execution) and listed in the
signed bundle. Until then plugin widgets ship in the SPA build.

---

## 14. Migration of today's in-core KYC

1. Plugin ships with schema + a one-off **import job** reading a core export endpoint
   (`GET /internal/kyc/export`, internal credential — the same `/internal` seam
   `ActorContextFilter` already guards; removed after migration) → inserts cases with
   `owner_tenant_id` = the tenant that onboarded them. Demo seed is re-expressed in the plugin's
   `db/seed/demo` (client-demo only).
2. Core: `KYC_TENANT_ACTIVATION` approvals still PENDING are migrated to `PLUGIN:kyc:CASE_DECISION`
   (same maker, client_id = null, new handler) by the migration job.
3. Core migration `V12__retire_core_kyc.sql`: drop `kyc_*` tables AFTER the import verified
   (row counts + per-case hash). Remove `com.fsp.core.kyc` and the `/v1/tenants/with-kyc` +
   `/v1/tenants/{id}/kyc/**` operations (catalog shrinks; gateway stops routing them).
   **Retention:** tenant creation + activation logic stays in core — it is what
   `TenantChildrenController` (saga step 4) and `TenantVerificationController` (§7.3) now own.
   *(Rev 1 wrongly claimed a `tenant.activate.force` op is retained — no such op exists in the
   codebase; the claim is removed. Any emergency-activation tooling is out of scope here.)*
4. AGENTS.md updates: §3.9 (saga — gated on D2 sign-off), §4c topology (+plugin-kyc), §5b ledger
   FAPI2 row (§1.4), §6 (implement status), §6b layout map (+`backend/plugins/kyc`), §8 local run
   (+`make backend-plugin-kyc`).

---

## 15. Build phases, exit criteria, tests

| Phase | Scope | Exit criteria |
|---|---|---|
| **P0 Toolchain** | §11.0: settings/build/Makefile/compose, empty plugin module boots, ArchUnit boundary test scaffold | `make backend-plugin-kyc` boots; boundary test fails any core/TB import; AGENTS §6b amended |
| **P1 Machine identity** | IdP `oauth_clients`, client_credentials + private_key_jwt + DPoP; gateway machine tokens; core `Actor.type` (compat), `client_grants`, `@FspOperation.actors`; **AGENTS §5b ledger row updated** | A test client gets a DPoP-bound machine token; replayed DPoP/assertion/expired token → 401; machine call to a person-only op → 403; `aud` array → 401; FAPI playbook V1–V10 still green |
| **P2 Registry + installations + readiness** | V11 tables, registry/installations/gates APIs (four-eyes), effective-installation rule, signed catalog with plugin ops, gateway upstream routing + 503 | Unregistered route → 404; registered but not installed → 503; installed+ready → forwarded; inheritance matrix unit-tested (5 policies × 3 depths) |
| **P3 Authz + assertions + events** | `/internal/authz/decide`, actor assertion signer/verifier (incl. N=5 budget), on-behalf path, `PLUGIN:` approvals + handler + `client_id` column, verification endpoint, event feed served by core | Denied person never reaches plugin (log proof); assertion with wrong aud/expired/reused/over-budget → rejected; plugin cannot self-verify; maker==checker → 403 through the plugin path; machine maker → open refused |
| **P4 KYC plugin** | service, DB, APIs, saga + recovery + SLA alerting, import job, demo seed | All §12.4 APIs; paging/sort contract tests (totals-free, sort-map negative cases); saga crash-at-each-step tests converge; scope isolation tests; stalled-saga SLA test |
| **P5 Widgets** | `kyc.manage`, `kyc.orgs` on plugin API, `usePagedList`, fixtures | Workbench: all tiers, scenarios, geometry clean; live E2E: create → maker blocked → checker approves → tenant ACTIVE |
| **P6 Retire core KYC** | V12, code removal, docs | Catalog no longer has core KYC ops; full E2E green; AGENTS/PRD updated |

Cross-cutting tests (must exist before P6 closes):
- **Boundary test**: plugin module has no dependency on core modules, TigerBeetle client, or core
  DB driver config (ArchUnit + Gradle dependency check).
- **FAPI checks** per hop (§4): bearer-only call → 401; DPoP htu/htm mismatch → 401; token in
  query string → 400; ROPC for a machine client → 400; `aud` array in client assertion → 401.
- **Revocation**: disable client → next token request 401; suspend installation → next call 503;
  remove grant → next on-behalf call 403 (uncached authz — verified within the same second).
- **Abuse controls**: oversized body → 413; poll `wait` > 20 s → capped; quota → 429 +
  `Retry-After`; no catch-all plugin route exists.
- **Logs**: scan for tokens / assertions / account numbers → none.

---

## 16. Decisions that need sign-off (defaults proposed)

| # | Decision | Proposed default | Why |
|---|---|---|---|
| D1 | Out-of-process plugin (separate service + DB) | **Yes** | AGENTS §6 + vision: plugins are third parties over the API |
| D2 | Replace AGENTS §3.9 single-transaction onboarding with core-atomic tenant + plugin saga (§12.5) | **Yes** | Atomicity across two services is impossible; safety is kept because activation requires a four-eyes verdict; SLA + operator queue bound the stuck state |
| D3 | On-behalf via gateway actor assertion (§4.4), not RFC 8693 token exchange | **Assertion** | Smaller token surface, internal-hop only, core re-decides both planes |
| D4 | Event delivery: pull feed served by core, webhooks later | **Pull (core serves)** | No inbound path into plugins; simpler at-least-once; core owns the table |
| D5 | Existing KYC data: import into the plugin (§14) vs start empty | **Import** | Keeps demo + any dev data; job is one-off |
| D6 | Plugin widgets ship in the SPA build this slice (§13.4) | **Yes** | Remote bundles need the integrity loader slice |
| D7 | New permissions `kyc.case.create`, `kyc.pii.read`, `plugin.register/install/manage` | **Yes** (zero grants; PLATFORM_ADMIN gets plugin.*; KYC roles via role migration) | AGENTS §5b: new permission = deliberate role migration |
| D8 | Authz pre-check synchronous & uncached (readiness hint cached ≤ 10 s; revocation latency ≤ 10 s) | **Yes** | I7: revocation must hit the next call |
| D9 | `PLUGIN:` op-type prefix rule in `canonicalOpType` (no enumerated plugin types in `OpType`) | **Yes** | Core stays generic; plugins never require core code changes |
| D10 | Totals-free `Page<T>` (`hasNext` + `nextAfter`), sort-name map per §12.4 | **Yes** | AGENTS §4A compliance; deliberate overrides only per-endpoint later |
| D11 | Assertion on-behalf budget N=5 per assertion life, audited per use | **Yes** | One Path A request must back one saga's calls; bounded + audited beats unbounded reuse |
| D12 | FAPI2 tranche 1 (machine plane) lands with this plan; tranche 2 (person PAR/PKCE/RAR) unchanged; plugins leaving the cluster is hard-gated on tranche 2 | **Yes** | AGENTS §5b ledger amended, not contradicted (§1.4) |
| D13 | Saga SLA: stalled > 10 min WARN + widget queue; > 60 min P1 ERROR; no auto-cancellation | **Yes** | Rev 1 left stuck-tenant behaviour undefined |
| D14 | Departments = org-unit table (persons.department_id), NOT child tenants | **Yes** | See `plans/federation-departments-plan.md` FD1 — dept books/KYC/IdP remain possible later via child tenants |
| D15 | Federated users (org IdP) + role-claim mapping is a SEPARATE plan, not part of the plugin slice | **Yes** | `plans/federation-departments-plan.md` (FD1–FD9) — V13+ migrations, phases F1–F5; AGENTS §4b amended at its F3 |
