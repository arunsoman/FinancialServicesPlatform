# The Onboarding Story — text script for the E2E Playwright spec

**What this is:** a plain-text, step-by-step description of what the real
Playwright script must do — the full screenplay the automation then performs.
Anyone (QA, product, new engineer) can read this and follow the same chain by
hand in a browser.

**The story in one line:**
the Platform creates the Manufacturer (PSP); the Manufacturer onboards a
Distributor through KYC; the Distributor onboards a Merchant organization;
inside the Merchant, an admin creates a named checker user (four-eyes); the
Merchant posts its first balanced journal entry to the ledger.

**Legend**

- `[UI]`     — done in the real browser (clicks/typing on portal screens)
- `[API]`    — done through the BFF `/api/**` lane with the same signed-in session
- `[SHIM]`   — a temporary, clearly-marked workaround (see Gap register)
- `GAP-n`    — a missing screen/API; see "Gap register" at the bottom

---

## THE CAST (personas + real credentials)

| # | Persona | Username | Password | Signs in at | Portal renders? |
|---|---------|----------|----------|-------------|-----------------|
| 1 | Platform maker | `platform-admin` | `$FSP_BOOTSTRAP_PASSWORD` (default `fsp-admin`) | `/admin` | **Yes** ✓ (core signs the seeded menus at boot — GAP-7 fixed) |
| 2 | Platform checker | `admin-checker` | same | `/admin` | **Yes** ✓ (same) |
| 3 | Manufacturer admin | `acme.admin.<suffix>` | `$FSP_PERSON_PASSWORD` (default `fsp-person-2026`) | `/manufacturer` | **Yes** ✓ (GAP-5 fixed) |
| 4 | Manufacturer checker | `acme.checker.<suffix>` | same | `/manufacturer` | **Yes** ✓ — and is a designated checker (GAP-9 fixed) |
| 5 | Distributor admin | `sunrise.distribution.<caseNoTail>` | same | `/distributor` | **Yes** ✓ |
| 6 | Distributor checker | `checker-sunrise.distribution.<tail>` | same | `/distributor` | **Yes** ✓ |
| 7 | Merchant admin | `namib.fresh.produce.<caseNoTail>` | same | `/merchant` | **Yes** ✓ (GAP-6 fixed) |
| 8 | Merchant auto-checker | `checker-namib.fresh.produce.<tail>` | same | `/merchant` | **Yes** ✓ |
| 9 | Named merchant checker | `tulonga.checker` | same | `/merchant` | **Yes** ✓ |

Personas 3, 5, 8 are created by the system during the story itself
(names derive from the legal name + case number, per `UsernamePolicy`).

---

## SCENE 0 — PREFLIGHT

The script first verifies the stage is set. If anything is down it stops with a
clear message telling which `make` target to run.

1. Probe, in order: SPA `:5173`, IdP `:8082`, gateway `:8090`, BFF `:8080`,
   KYC plugin `:8083`, TigerBeetle `:3000`, Postgres `:5432`.
2. Confirm the platform seed is live (the sign-in in Scene 1 failing proves it
   is missing).

---

## SCENE 1 — PLATFORM OPENS THE SHOW

**Goal:** establish two independent, fully-authenticated sessions for the
platform pair (four-eyes needs maker ≠ checker; a browser context per persona).

1. `[UI]` Open `http://localhost:5173/admin/login`.
2. `[UI]` The SPA auto-redirects: portal login → `GET /bff/auth/login` (PAR
   initiation) → gateway-style hop → IdP hosted sign-in page. **Assert** the
   IdP page rendered (inputs `#u`, `#p`, button "Sign in").
3. `[UI]` Fill username `platform-admin`, password `fsp-admin`, click "Sign in".
4. `[UI]` Control returns to the admin portal. **Assert** a session exists —
   `GET /bff/session` returns `platform-admin` — and that the console renders
   its real navigation menu (GAP-7 closed: core signs the seeded menus at boot,
   docs/portal-menus.md; the spec asserts the portal navigation is visible).
5. `[UI]` Repeat steps 1–4 in a **separate browser context** for
   `admin-checker`. Never share one context: the four-eyes law is
   maker ≠ checker at the `person_id` level.

---

## SCENE 2 — THE KYC PLUGIN (register → approve → install → approve → ready)

The KYC plugin is the onboarding engine. It must exist as a registered type and
be installed on the Manufacturer before any KYC case can be filed.

1. `[API]` As platform-admin: `GET /api/platform/plugins/kyc/1.1.0`.
   If already registered (a previous run), skip to step 4.
2. `[API]` As platform-admin (the **maker**): `POST /api/platform/plugins` with
   `{code:"kyc", version:"1.1.0", baseUrl:"http://localhost:8083", manifest:
   <backend/plugins/kyc/plugin-manifest.json>, manifestHash: sha256(manifest)}`.
   **Assert** it lands as `PENDING_APPROVAL` with an approval opened.
3. `[API]` As admin-checker (the **checker**): find the pending
   `PLUGIN_REGISTER` approval — `GET /api/approvals?tenantId=<platform-root>&status=PENDING_CHECKER`
   — then `POST /api/approvals/{id}/approve` (the chain keeps this one in the
   API lane for setup speed; the governance screens exist — GAP-4/F-4 done —
   so it can equally be a click in `/admin/plugins`).
   **Assert** it resolves; the plugin type becomes `REGISTERED`, a machine
   client `plugin-kyc` + permission grants are provisioned for it.
4. `[API]` As platform-admin (maker): create the Manufacturer tenant —
   `POST /api/tenants` with `legalName:"Acme Manufacturers (Pty) Ltd"`,
   `adminUsername`, `checkerUsername`.
   **Assert** HTTP 200 with `tenantId`; note the created usernames.
   The tenant is ACTIVE/VERIFIED at birth (the platform vouches for PSPs).
5. `[UI]` As platform-admin (maker): open `/admin/plugins?org=<mfr>` and
   **Request installation** of `kyc 1.1.0` on the Manufacturer
   (plugins.governance screen; an equivalent `POST /api/tenants/{mfr}/plugins`
   with `{code:"kyc", version:"1.1.0"}` exists for the API lane) → pending
   PLUGIN_INSTALL.
6. `[UI · act-as]` The approval lives on the **PSP's own queue** — the PSP
   consents to a machine client entering its perimeter (customer-consent model,
   V20), so the platform does NOT decide from outside: admin-checker opens the
   org list on `/admin`, clicks “Act as <PSP>” (the banner shows the person on
   record and the target tenant; the browser never holds `X-Act-As` — the BFF
   injects it on the internal hop; one live act-as session per person), then
   approves in the queue with real clicks. **Assert** the audit names the real
   person, and that exiting act-as returns the checker to their own context.
7. ~~`[SHIM]` SQL grant~~ — **removed.** A new PSP's `TENANT_ADMIN` / `TENANT_CHECKER`
   are born with the keys the saga needs (`RoleTemplates`; V19 back-fills older orgs).
   Further rights are granted in **Roles and permissions** (`/rbac`, four-eyes).
8. `[API]` As manufacturer admin: poll `GET /api/plugins/kyc/master-values?category=ENTITY_TYPE`
   until HTTP 200. Until the plugin reports its readiness gates
   (CLIENT_ACTIVE / PERMISSIONS_SEEDED / SECRETS_PROVISIONED / DEPENDENCIES_READY),
   the gateway answers `503 PLUGIN_NOT_READY`. **Assert** it becomes 200.

---

## SCENE 3 — MANUFACTURER SIGNS IN

1. `[UI]` Open `/manufacturer/login` in the manufacturer admin's **own browser
   context**; complete the hosted IdP sign-in as `acme.admin.<suffix>` /
   `fsp-person-2026`.
2. **Assert** the manufacturer portal renders (sidebar navigation, no "No access to this
   portal" wall) — GAP-5 fixed: PSPs carry sub-type `MANUFACTURER`.
3. `[UI]` Repeat for the manufacturer checker `acme.checker.<suffix>` (a designated
   checker from birth — GAP-9 fixed).

---

## SCENE 4 — MANUFACTURER ONBOARDS THE DISTRIBUTOR (the KYC saga)

**Goal:** one KYC case, four-eyes decisioned, child tenant goes ACTIVE with its
own admin + checker + suspended books; the book auto-opens on verdict.

1. `[API]` As manufacturer admin (maker): `POST /api/plugins/kyc/cases` with a
   full application — `subType:"DISTRIBUTOR"`, legalName
   "Sunrise Distribution (Pty) Ltd", an 8-digit TIN, a numeric registration
   number, registered address, one UBO at 100% (not a PEP), one bank account
   (branch code 5-6 digits), money fields as
   **strings** of minor units. Do NOT pass `screeningOutcome` — GAP-10: the
   client value is refused `400 SCREENING_NOT_CLIENT_SETTABLE`; the plugin runs
   server-side sandbox screening (identity completeness/expiry, PEP, watch-list)
   and persists the checks itself.
   **Assert** HTTP 201: case `PENDING_REVIEW` (saga already: screening ran,
   child tenant PENDING + applicant admin + auto checker `checker-…` + suspended
   books), and an approval opened on the manufacturer's tenant so the checker
   can decide. The filing form UI is future work (F-2 remainder) — the 5-tab
   application still goes through the plugin API.
2. `[API]` As manufacturer admin: poll `GET /api/tenants/{child}`.
3. `[API]` As manufacturer checker: `POST /api/plugins/kyc/cases/{caseId}/decisions`
   `{decision:"APPROVE", reason:"screening clean, UBO verified"}`.
   **Assert** case `APPROVED`; the plugin then calls core (machine actor) to
   record the verdict.
4. `[API]` Poll `GET /api/tenants/{child}` until `status:"ACTIVE"` — the plugin
   recorded VERIFIED and core activated the tenant and opened its books.
5. ~~`[SHIM]`~~ — **removed** (GAP-1 fixed: the distributor's anchor roles are born complete).
6. Compute the distributor usernames exactly like core does:
   `sunrise.distribution.<caseNoTail>` and `checker-sunrise.distribution.<tail>`.

---

## SCENE 5 — DISTRIBUTOR PORTAL, FULLY RENDERED (the one real UI lap)

This is the scene where actual widgets are driven — today the distributor is
the only fully-eligible, fully-rendered stop in the chain.

1. `[UI]` Open `/distributor/login` in the distributor admin's context; hosted
   IdP sign-in as `sunrise.distribution.<tail>`.
2. `[UI]` **Assert** the Overview screen renders ("permission keys held" tile) —
   no /no-access wall: subType DISTRIBUTOR matches the portal.
3. `[UI]` Navigate to **My network** (`/distributor/network`).
   **Assert** the `kyc.orgs` widget renders (the organization tree with KYC
   status). Optional deep-check: the new child appears in the tree data.
4. `[UI]` Repeat sign-in for the distributor checker
   (`checker-sunrise.distribution.<tail>`) — needed as the four-eyes partner
   for Scene 6.

*(When the `onboard.child` / `kyc.tasks` / `kyc.manage` menu wiring lands
(Gap 2), Scenes 4 and 6 move here: file the case in the form UI, decide it in
the case UI, and this spec becomes click-only.)*

---

## SCENE 6 — DISTRIBUTOR ONBOARDS THE MERCHANT (same saga, one level down)

1. `[API]` As distributor admin (maker): `POST /api/plugins/kyc/cases` with
   `subType:"MERCHANT"`, legalName "Namib Fresh Produce (Pty) Ltd", full
   applicant data (as Scene 4.1).
2. `[API]` As distributor checker: decide `APPROVE`.
3. `[API]` Poll `GET /api/tenants/{merchant}` until ACTIVE.
4. ~~`[SHIM]`~~ — **removed** (GAP-1 fixed; a MERCHANT admin gets no onboarding keys — it is the leaf).
5. Merchant usernames derive as usual: `namib.fresh.produce.<tail>` +
   `checker-namib.fresh.produce.<tail>`.

---

## SCENE 7 — INNER FLOW: THE MERCHANT CREATES A NAMED CHECKER USER (four-eyes)

The request that started this story: onboarding an organization must include
creating its checker — and that creation is itself a maker/checker act.

1. `[UI→API]` Sign in the merchant admin and the merchant auto-checker. The
   merchant portal renders — GAP-6 closed (`merchant.html` identity, GAP-5
   eligibility); the spec asserts the /no-access wall is ABSENT.
2. `[API]` As merchant admin (maker): `GET /api/tenants/{mer}/user-roles`, pick
   the `TENANT_CHECKER` role id; `POST /api/tenants/{mer}/users` with
   `{username:"tulonga.checker", fullName:"Tulonga Shipanga", email,
   roleIds:[checkerRole], checker:true}`.
   **Assert** HTTP 202: user is staged PENDING — no sign-in, no roles — and a
   USER_CREATE approval opened. The users widget shows the staged user as
   `PENDING` with an “Awaiting approval” pointer to Approvals (GAP-8 closed).
3. `[UI]` As merchant auto-checker (a different person — four-eyes): open
   `/merchant/approvals` and approve the pending `USER_CREATE` approval with
   real clicks (Approve → reason → Confirm).
   **Assert** resolution; roles assigned; person ACTIVE.
4. `[UI]` Sign in as `tulonga.checker` (fresh context, `/merchant/login`).
   **Assert** the IdP accepts the credentials AND the merchant portal renders
   as a live person (GAP-6 closed — no wall).

---

## SCENE 8 — FIRST MONEY (CoA → JE → checker → ledger, all clicks)

**Goal:** prove the onboarded merchant can move money: balanced JE, four-eyes,
TigerBeetle commit, FINALIZED. The chain now drives this scene by clicking the
real widgets (journal.entry, ledger.accounts); only the sanity reads (books
list, the poll for FINALIZED) still ride the API lane.

1. `[UI]` As merchant admin: open `/merchant/ledger` — the ACTIVE book is
   selected (opened automatically at verification). If the book is empty, click
   **Provision default chart** (`data-action=coa.provision`; `gl.account.create`,
   not four-eyes). **Assert** the accounts table is populated.
2. `[UI]` As merchant admin (maker): open `/merchant/journal`, click **New
   entry**, fill posting date (defaults to today — the open period), description,
   and two lines — Account 0 (asset side) DEBIT 2500.00 / highest-code account
   CREDIT 2500.00 — then **Save draft**. **Assert** the detail panel opens with
   the `DRAFT` posting-state pill and the entry is selected in the URL.
3. `[UI]` Maker: click **Submit for approval** → PENDING_CHECKER pill.
4. `[UI]` Merchant checker (four-eyes, different person): open
   `/merchant/journal?status=PENDING_CHECKER`, select the entry, click **Approve
   and post**, confirm in the inline AlertDialog. **Assert** the ledger commit —
   poll the entry until `FINALIZED`, then the pill shows Posted.
   A `COMMIT_UNKNOWN` here would demand the check-status recovery path (`data-action=je.check`)
   — out of scope for the happy chain, noted below.

---

## SCENE 9 — CURTAIN CALL

1. Print the chain summary (tenant ids, book, JE id, all usernames/passwords).
2. Save a final screenshot.
3. Leave every session open until teardown — Playwright closes contexts.

---

## GAP REGISTER — status after the fix (2026-11-18)

Each gap has a probe that asserts the FIXED behaviour — it failed before the fix and guards
it after: `frontend/tests/e2e/gap-probes.spec.ts` (live stack) and
`frontend/src/portals/gap-probes.test.ts` (unit; 16 of its assertions failed before).

| # | Gap | Status | Fix |
|---|-----|--------|-----|
| GAP-1 | No roles CRUD; anchor roles lacked `org.onboard_child`/`org.create`/`kyc.*`/`user.manage` | ✅ fixed | `rbac/RoleTemplates` (anchor-role templates by sub-type; MERCHANT is a leaf), `RoleAdminService` + `/v1/tenants/{id}/roles` (four-eyes `ROLE_CREATE/UPDATE/DELETE`, no-escalation for maker AND checker, version-pinned), `role.view` permission + back-fill in V19, `rbac.matrix` screen on every portal. SQL shim deleted from the spec. |
| GAP-2 | Menu named unregistered widgets | ✅ fixed | All 13 menu widgets registered; org onboarding + review queue mount `kyc.manage`; new `kyc.application`, `journal.entry`, `ledger.accounts`, `period.control`, `permissions.catalogue`, `overview.tiles`, `onboard.psp`. |
| GAP-3 | No approvals screen | ✅ fixed | `approvals.queue` widget; queue opens for any checker permission and returns per-caller `canDecide`/`blockedReason`, names and (core rows) the proposal. |
| GAP-4 | No plugin governance surface | ✅ fixed | `GET /v1/platform/plugins`, `GET /v1/tenants/{id}/plugins` (with readiness gates) + `plugins.governance` screen (register / install requests stay four-eyes). |
| GAP-5 | PSP had no sub-type → manufacturer wall | ✅ fixed | PSPs stamped `MANUFACTURER` at creation + V19 back-fill; `portalEligible` admits a legacy sub-type-less PSP via `admitsTypes`. |
| GAP-6 | No merchant portal | ✅ fixed | `merchant.html` + `PORTAL_MERCHANT` (+ workbench/chooser/vite entries). |
| GAP-7 | Admin console fail-closed without a signed bundle | ✅ fixed for fresh DBs | The repo seed `R__seed_portal_menus.sql` carries every portal's menus; core signs them at boot (`UiBundleBootSync`) — docs/portal-menus.md. |
| GAP-8 | Users widget hid staged users | ✅ fixed | `PENDING` status + "Awaiting approval" chip/filter + banner pointing to Approvals; every user action now says it was REQUESTED (it used to claim "Added"/"was removed"). |

### Gaps found while fixing (not in the original register)

| # | Gap | Fix |
|---|-----|-----|
| GAP-9 | Auto-provisioned checkers never got `is_checker=true`, so every approval they tried (KYC decision, USER_CREATE, JE approve) failed `CHECKER_NOT_ENABLED` — the original script could not have passed Scene 4. | `PersonProvisioning.designateChecker` for the born-with checker; V19 back-fill (never flips a user created with `checker:false`). |
| GAP-10 | KYC screening outcome was taken from the request (`screeningOutcome`, default PASS) — a maker asserted their own applicant's screening. Checks were never persisted. | Server-side sandbox screening from the application data (identity completeness/expiry, PEP, watch-list) persisted to `kyc_checks`; a client `screeningOutcome` is refused `400 SCREENING_NOT_CLIENT_SETTABLE`. |
| GAP-11 | Approval resolution and its domain effect were not atomic (self-invoked `@Transactional`): a failing effect left an APPROVED row with no change. | DB-only handlers (`USER_*`, `ROLE_*`) run in one transaction with the approval; `JE_POSTING` deliberately keeps its own boundaries (plan COMMITTED before TigerBeetle). Unit-tested both ways. |
| GAP-12 | A person could resolve a `PLUGIN:*` approval directly in core, leaving the plugin's case undecided. | `409 APPROVAL_PLUGIN_OWNED`; the queue shows a link to the plugin's screen instead. |
| GAP-13 | KYC plugin `GET /organizations/{id}` was a stub (`kycStatus: UNKNOWN`). | Real read: core decides visibility (`tenant.detail.read`, now admitting DELEGATED), plugin adds the latest case. |
| GAP-14 | The original spec itself was broken: `/bff/session` read as `{username}` (it is `{authenticated,user}`), one CSRF token shared by all personas, manifest path pointed outside the repo, polling without delay; Playwright under `--bun` cannot resolve relative request URLs. | `tests/e2e/support.ts`; `make test-e2e` runs Playwright on Node (still launched by `bunx`). |

**Still open (honest list):** Scene 8 is now fully click-driven (CoA provision + JE create/
submit/approve via the journal.entry and ledger.accounts widgets). The chain's filing and
decision legs are now UI-driven too — Scenes 4/6 file through the kyc.manage five-step form
(`fileKycUi`) and the checker approves in the real decision panel on `/kyc/reviews`
(`approveKycUi`); only the twin's REQUEST_INFO/REJECT legs remain API-lane (negative paths
assert the API contract directly). Period open/close has no API yet (`period.control` is
read-only and says so). F-10 is done (Sc 8.5 — maker files in the UI, checker approves in the UI,
platform-root files its own account). F-12 is done (Sc 8.6 — an open approval queues a
deduped checker pointer + `Notification.PushRequired` outbox row, exposes the inbox and
unread-count API, the SPA badge on the Approvals nav item lights up from a poll without
visiting the queue, and a Web Push/APNs/FCM-capable spine dispatches data-minimized
payloads once credentials are set — docs/architecture/12-notifications-and-push.md).
F-8 is done (Sc 8.7 — an API-created person born with the bootstrap password is forced
through the IdP hosted change page at first sign-in: policy reject, confirm mismatch reject,
same-password reject, then accept; the parked sign-in resumes into the portal, the bootstrap
password is dead, and the replacement signs in cleanly — docs/architecture/02 §6). F-9 is done (see the happy-twin note below).

### How to verify

```bash
cd backend && ./gradlew :core:test :plugins:kyc:test       # role/approval/provisioning/screening tests
cd frontend && bunx vitest run src/portals/gap-probes.test.ts
# live stack (restart core/gateway/plugin so V19 + new routes load), then:
# portal menus: none needed — core signs the seeded menus at boot (docs/portal-menus.md)
cd frontend && bunx playwright test tests/e2e/gap-probes.spec.ts tests/e2e/onboarding-chain.spec.ts tests/e2e/unhappy-twin.spec.ts
```

---

## MISSING-FEATURE CHECKLIST (what the story exposes)

Every `[SHIM]`/`[API]` step above exists only because a feature is missing.
This table is the backlog the story generates — deliver a row and the script
flips those steps to `[UI]` automatically.

| # | Missing feature | Status | Type | Unblocks (scene → step) | Acceptance: the script is UI-only here when… |
|---|-----------------|--------|------|--------------------------|----------------------------------------------|
| F-1 | **Roles & permissions CRUD** + a “grant right” screen (four-eyes grant) | **✅ done** | core API + portal screen | GAP-1 shims (Sc 2.7, 4.5, 6.4) | the TENANT_ADMIN of a fresh tenant already holds `org.onboard_child`/`org.create`/`kyc.*`/`user.manage`, or an admin grants them via a screen |
| F-2 | **Register + route the onboarding widgets** (`onboard.child`, `kyc.tasks`, `kyc.application`, `kyc.decisions` — or wire `kyc.manage` into the org menu) | **✅ done — chain filing/decision now click-driven** (Scenes 4/6 via the five-step form + `/kyc/reviews` decision panel, `fileKycUi`/`approveKycUi`) | frontend | GAP-2 (Sc 4.1–4.3, Sc 6.1–6.2) | a maker files the KYC case and a checker decides it by clicking |
| F-3 | **Approvals queue screen** (`approvals.queue` widget: list, approve, reject+reason across USER_*/PLUGIN_*/KYC) | **✅ done** | frontend | GAP-3, GAP-8 (Sc 7.3) | every four-eyes checker half is a click in a screen |
| F-4 | **Plugin governance screen** (types: register; per tenant: install/uninstall, readiness state) | **✅ done** | frontend | GAP-4 (Sc 2.1–2.6) | platform admins register/install the plugin by clicking |
| F-5 | **Portal eligibility fix + merchant portal entry** (stamp PSP subType or type-aware eligibility; add `merchant.html` identity) | **✅ done** | frontend + core | GAP-5, GAP-6 (Sc 3.2, Sc 7.1) | manufacturer and merchant personnel land in a rendered portal, not the wall |
| F-6 | **Signed admin bundle in dev** (menus seeded from the repo, signed by core at boot) | **✅ done** | infra/frontend | GAP-7 (Sc 1.4) | the admin console shell renders its real menu after sign-in |
| F-7 | **Staging/approval affordance in the users widget** (PENDING banner + “awaiting <checker>” pointer) | **✅ done** | frontend | GAP-8 (Sc 7.2) | the maker sees the staged user and who must act next |
| F-8 | **Password reset / first-login change** for API-created persons (today they all share `$FSP_PERSON_PASSWORD`) | **✅ done** (`persons.password_must_change` V24 + IdP hosted change, Sc 8.7) | IdP (credential owner) + core flag | Sc 9 note (credentials hygiene) | every person born with the bootstrap password (`persons.password_must_change`) is forced through the IdP hosted change form — credentials never leave the IdP origin, the PAR flow is parked in a 10-min single-use ticket and resumes with the standard auth code; direct token grants get `403 PASSWORD_CHANGE_REQUIRED`; E2E asserts policy/mismatch/same-password rejections, portal resumption, and that the bootstrap password is dead after the change |
| F-9 | **Adverse-path E2E coverage**: KYC `REJECT`/`REQUEST_INFO`, maker self-approve → explicit refusal + `MAKER_CANNOT_APPROVE` block state, stalled `TENANT_PENDING` recovery (KycRecoveryService) | **✅ done** (`tests/e2e/unhappy-twin.spec.ts` — scenes A–D) | tests + UI states | beyond happy path | the chain's unhappy twin passes with explicit UI states per ui-std §5.2; note: born-role makers never hold `kyc.approve`, so the KYC maker-block surfaces as `PERMISSION_DENIED` + server `block=MAKER` (the person-level `MAKER_CANNOT_APPROVE` is asserted on the USER_CREATE lane) |
| F-10 | **Bank account management screen** (`bank.account.create`/`approve` seeded + new `bank.account.view` READ key) | **✅ done** (`widgets/bank/accounts`, Sc 8.5) | backend (core ledger) + frontend | first-money depth | the org's ADMIN files its settlement account (7 Namibian banks registered) → `BANK_ACCOUNT_CREATE` four-eyes approval → checker approves → PENDING→ACTIVE (one ACTIVE per tenant, partial unique index; full number only in DB, `maskAccountNumber` last-4 on the wire); maker self-approve refused 403; platform-root files its own account (platform checker approves); E2E asserts masked numbers on the wire and UI pending banner + ACTIVE state |
| F-11 | **Ledger/period screens** (`ledger.accounts`, `period.control` widgets exist as ids only) | **✅ done — ledger.accounts driven by the chain (provision + table); period.control read-only** | frontend | Sc 8 depth | CoA and period state are visible, not assumed |
| F-12 | **Checker inbox/notification** (today a checker must poll the queue) | **✅ done** (`notify/` spine + `web-core/push-core.ts`, Sc 8.6) | backend (core notify + outbox) + frontend | every four-eyes step | an APPROVAL_OPEN notification row is written in the open transaction (maker excluded, one per checker per resource, generic PII-free copy), `GET /v1/notifications` + `/unread-count` + `/read-all` serve the inbox, the Approvals nav badge lights up from a 30 s poll without visiting the queue, and the credential-gated push spine (Web Push w/ RFC 8291+VAPID, APNs, FCM) dispatches once `FSP_PUSH_*` env credentials are set; E2E asserts unread ≥1, no digits in the notification body, and badge cleared after `read-all` |

**Priority reading of the table:** F-1 + F-2 + F-3 collapse most of the script’s
API legs (Scenes 2/4/6/7 become clicks). F-5/F-6 fix *what the user sees*. F-8
is the credentials gap every real onboarding hits on day one.

---

## HOW THE SCRIPT MAPS TO CODE (for the implementer)

- IdP hosted sign-in page is rendered by `IdpController#loginPage`
  (`#u`, `#p`, "Sign in") — the only browser-visible credential surface.
- Portal login chain: `<portal>/login` → `GET /bff/auth/login` (PAR) →
  `/idp/authorize` → `/idp/login` → `/idp/authorize/callback` →
  `/bff/auth/callback` (session + CSRF rotation) → back to the portal.
- `/api/**` rides `ApiProxyController` → gateway `/v1/**`; mutating calls must
  carry `X-XSRF-TOKEN` (from `GET /bff/csrf`) and an `Idempotency-Key`.
- Usernames for saga-born tenants: lowercase legal name → dots, truncated, then
  `.<caseNoTail>`; checker is `checker-` prefixed (see `UsernamePolicy` and
  `TenantChildrenService`).
- Money and case fields: minor units as strings; TIN `^\d{8}$`; registration
  number 3-10 digits; branch code 5-6 digits (`NamibiaRules`).
- The KYC saga had two gaps the unhappy twin (F-9) exposed and fixed:
  - `POST /cases/{caseId}/submit` after a REQUEST_INFO/return used to leave the case in
    `SUBMITTED` with **no re-opened approval** (the loop died; the case stalled out).
    The saga now resumes on submit (`KycOnboardingSagaService.resume`): it re-screens the
    amended application server-side, then opens a NEW approval revision
    (`kyc-approval-<case>-<rev>`, approvalRev bumps to 2). The case returns to
    `PENDING_REVIEW` and the checker decides again.
  - The core-approval resolve idempotency key (`kyc-approval-<caseId>-resolve-<epochSecond>`)
    collided when two revisions of the same case resolved within the same second
    (`409 IDEMPOTENCY_KEY_REUSED`). The key is now approval-scoped:
    `kyc-approval-<approvalId>-resolve-<epochSecond>` — retries still collapse, revisions never
    collide.
- Run the twin: `cd frontend && bunx playwright test tests/e2e/unhappy-twin.spec.ts`
  (~60 s; scenes A REQUEST_INFO loop → B REJECT→terminal → C self-approve blocks
  (KYC PERMISSION_DENIED + block=MAKER, JE/USER_CREATE `MAKER_CANNOT_APPROVE`) →
  D stalled `TENANT_PENDING` → `STALLED` via the recovery job).
