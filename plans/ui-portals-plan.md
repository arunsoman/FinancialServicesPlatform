# FSP Multi-Portal UI — Understanding & Build Plan (pre-build, from SME study)

Status: **UNDERSTANDING — not yet built**. Ported from a full study of the SME frontend
on 2026-07-22. Nothing here starts until the open questions below are answered.

---

## 1. What the SME frontend actually is (verified facts)

### 1.1 Workspace shape — Angular 20, one repo, 5 apps + 1 shared kernel
| Project entry (`angular.json`) | Root | What it is |
|---|---|---|
| `admin-portal` | `projects/admin-portal` | Own app — platform/MiPay staff console |
| `manufacturer-portal` | `projects/org-portal` | **Build variant** of the org portal |
| `distributor-portal` | `projects/org-portal` | **Build variant** of the org portal |
| `supplier-portal` | `projects/org-portal` | **Build variant** of the org portal |
| `widget-workbench` | `projects` | Widget dev/test harness |
| `sme-ui` | `src/app` | Legacy single app, 5 personas (merchant/supplier/lender/ops/admin) |

The three org portals are **one source tree**. Each angular.json project points at
`projects/org-portal` and swaps ONE file via `fileReplacements`:

```
portal.config.ts            → MANUFACTURER, accent #f0a35e, storageKey mp.manufacturer.token
portal.config.distributor.ts→ DISTRIBUTOR,   accent #a78bfa, storageKey mp.distributor.token
portal.config.supplier.ts   → SUPPLIER,     ...
```

`PortalConfig = { orgType, label, accent, storageKey }` — org type whose members may sign in,
the brand accent, and a distinct localStorage key so sessions never bleed across portals.

**→ The "4 portals" are really: 1 admin app + 1 org app × N build-time identities.**

### 1.2 The shared kernel (`projects/shared/*`)
| Module | Contents |
|---|---|
| `web-core/` | session (browser session vs OAuth token! guards, `menu.ts` (THE menu registry), `perm.ts`/`perm-directive.ts` (permGuard + button gating), `access.ts`, `empty-shell.ts` (no-access page), `act-as.ts`, `widget-page.ts`, `portal-layout.ts`, permission inventory |
| `kyc/` | `kyc-api.ts` (types), `kyc-conditions.ts` (master condition grammar), `kyc-schema-editor.ts` (schema-driven form), `kyc-progress.ts` (5-tab bar), `kyc-workspace.ts` (tree+tasks+editor screen), `onboard-child.ts` |
| `ui/` | `portal-theme.scss` — the ONE theme |
| ` rbac/`, `users/` | role model, user management screen |
| `widgets/` | registry: each widget = `manifest` (pure data) + `contract` + `component` + `widget.ts` (`defineWidget`) + `fixtures` + `spec` — e.g. `kyc/{application,orgs,status,tasks}`, `rbac/{roles,assignments,...}`, `users/organization-users`, `admin/organizations` |

### 1.3 The menu registry — one truth for sidebar AND guards (`shared/web-core/menu.ts`)
- Every point: `{ id, label, icon, link, perm[], group?, portals[] }`.
- **Rules:** one label per point everywhere; one permission list per point (sidebar and
  route guards read the SAME registry — `menuPerms(portal)`); **no ungated items** (fail-closed:
  an unmapped route denies via sentinel `['__no_menu_point__']`); shared pages gated by the union
  of the rows their panels need.
- `navEntries(portal, visible)` renders group headers once + gated items.

### 1.4 Session & guard chain
```
/auth/session (BFF) → BrowserSession {uid, sub, csrf, expires_at}  ← browser NEVER sees a token
  → hydrate GET /api/me → MeView {orgId, orgType, ...}
  → portalGuard: eligibility = me.orgType === PORTAL.orgType  (else /no-access)
  → per-route permGuard(menuPerms(portal)) (rows "kyc.task" or cells "kyc.task:authorize")
logout = top-level POST form /auth/logout w/ csrf (browser follows IdP redirect)
```
Platform staff use admin portal (`adminGuard` = ADMIN persona); org portals accept ANY member
of a matching orgType — what they see is decided by permissions, not a flag.

### 1.5 The KYC hierarchy (the thing the user asked to preserve — exactly)
**Five fixed tabs** (same wording as `kyc-filed.md`):
1. `Business Profile & General Information`
2. `Operational & Store Details`
3. `Registered & Operational Address`
4. `Ultimate Beneficial Owners (UBO) & Key Management`
5. `Settlement & Payout Bank Account Details`

The form is **fully server-schema-driven** (`GET /api/kyc/schema/{orgId}`):
- Sections: `OBJECT` (one card) | `LIST` (repeatable, min/max items) | `DOCUMENTS`
- Fields: dataType, list + listParent (**cascading choices** bank→branch), `requiredWhen` /
  `visibleWhen` master condition grammar (`eq/notEq/in/contains/gt…`, `all/any/not`,
  `item.x` paths, `context.orgType|country|licenceType`), pattern/min/max, `derivedFrom`,
  `sensitivity` → **masked when caller is a higher ancestor**.
- The **same grammar runs in the browser** so hidden fields vanish and required `*`s appear
  live; server problems (`ubos[0].dateOfBirth: …`) map back onto the named field.
- Workspace layout (every portal): **org tree (depth/lineage) + "Waiting for review" task
  queue (ENTRY_CHECK = second check, STAGE = ops review) + the application editor**.
- Decisions: entry-check `CONFIRM | SEND_BACK`; review `APPROVE | RETURN | REJECT | BLOCK`.
  Server says `canEdit / canSubmit / canDecide`; masked for higher ancestors.
- Statuses: `NOT_STARTED → DRAFT → ENTRY_CHECK → VERIFYING → IN_REVIEW → APPROVED/REJECTED/BLOCKED/WITHDRAWN`.

### 1.6 The theme — one file (`projects/shared/ui/portal-theme.scss`)
TPTS brand, dark: near-black ladder `#070707 / #111 / #1a1a1a` (panel gradient),
text `#f4f4f4`, dim `#a3a3a3`; **accent: TPTS gold `#f0a10e`** (deep `#f7bc55`, ink-on-accent
`#080808`); green `#84efb2`/red `#ff6b5e`/amber `#f7c66b` statuses; **Figtree**; pill controls
(`border-radius: 999px`), panels radius 16, chips (uppercase tiny pills), inputs on `#0c0c0c`
with gold focus ring; hero background = faint 48px grid + amber radial glow; scrollbar/selection
themed. Base tokens live in `src/app/design-system/tokens.scss` (the richer set, incl. cream
light-mode for the legacy sme-ui and `--st-*` status palette consumed by `status-maps.ts`).
Per-portal identity: PortalConfig.accent → `--portal-accent` (shell brand marks only); everything
else stays the shared gold/dark.

### 1.7 Widget system (manifest = pure data, portal-builder)
`defineWidget({ id, version, label, category, contexts, requires(rows/cells), actions[{cell→command}],
sources, operations(view|create|modify|approve), size })` — the registry tool, builder palette and
RBAC model all read the manifest without loading the component; `WidgetPage` composes
`main` + `detail[]` slots; published builder pages mount at `p/:menu/:screen`.

### 1.8 Design system inventory (what we can port)
- **atoms:** mp-button, mp-card, mp-input, mp-select, mp-checkbox, mp-spinner, currency-amount, status-badge
- **molecules:** alert, empty-state, form-field, kv-pair, stat-tile, table-view, tabs, act-as-banner, simulated-banner
- **organisms:** page-header, data-table, mp-table, modal, kpi-row, approval-actions, event-timeline, nav-shell, operations-grid, mp-entity-form, role-menu-builder
- **`status-maps.ts`**: one map status→{color,label}; badges everywhere speak it.

---

## 2. How this maps to FSP (our world)

### 2.1 Constraints that stay (mandated by `ui-std.md` / AGENTS.md)
- **Stack: Bun + Vite + React 18 + TS + Tailwind, SPA `:5173`, React Router.** Same *layout*,
  not same framework — we port the SME architecture onto React.
- **The SPA is never the OAuth client** (FAPI2 UI doc): session cookie via BFF `/bff/*`,
  token server-side only, CSRF double-submit, gateway is the sole public origin (`:8090`).
- `ui-std.md` §2.3 semantic color roles, §5 financial patterns (explicit posting state,
  four-eyes UI separation, minor-unit amounts) must survive the theme port.

### 2.2 FSP's four portals = the SME pattern exactly
| Portal | Tenant predicate | Accent proposal |
|---|---|---|
| **platform-admin** | platform org tenant (`type=PLATFORM`) | default gold `#f0a10e` |
| **manufacturer** | `type in (PSP,TENANT) ∧ subType=MANUFACTURER` | `#f0a35e` (SME manufacturer) |
| **distributor** | `subType=DISTRIBUTOR` | `#a78bfa` (SME distributor) |
| **wholesaler** | `subType=WHOLESALER` | `#7cecff` (SME cyan, supplier-analogue) |

FSP `tenants.sub_type = MANUFACTURER|DISTRIBUTOR|DEALER|WHOLESALER|MERCHANT` — dealer/merchant
portals are one config file away later (exactly why the SME pattern is right for us).
Each portal gets its own `storageKey` and brand accent; **one org source**, 3+ build identities.

### 2.3 Target layout (frontend/)
```
frontend/
  index.html                    ← dev entry chooser (links to the 4 portals)
  admin.html  manufacturer.html  distributor.html  wholesaler.html   ← 4 bootstraps
  vite.config.ts                ← rollupOptions.input = the 4 htmls; one build, 4 bundles
  src/
    portals/
      shared/                   ← THE shared kernel (mirrors SME projects/shared)
        web-core/               session.ts, guards.tsx, menu.ts, perm.tsx, access.ts,
                                empty-shell.tsx, portal-layout.tsx, widget-page.tsx
        kyc/                    api.ts, conditions.ts, schema.ts(5-section master),
                                editor.tsx, progress.tsx(5-tab bar), workspace.tsx
        widgets/                manifest.ts, fsp-widget.tsx, page.tsx + widget/ dirs
        ui/                     tokens.css (THE theme), status-maps.ts
      admin/                    App.tsx, routes.tsx, shell.tsx, login, features/
      org/                      App.tsx, routes.tsx, shell.tsx, login, features/
                                portal.config.ts | .manufacturer | .distributor | .wholesaler
    design-system/              (existing; extended to atoms/molecules/organisms)
```
One HTML entry per portal imports `portals/org/app` with its own `portal.config` import —
this is our `fileReplacements` equivalent (Vite native, zero custom tooling).
Dev: one server `:5173` serving `/admin/`, `/manufacturer/`, `/distributor/`, `/wholesaler/`
(index chooser links them) — simpler than SME's four ng-serves, same isolation of build identity.

### 2.4 The menu registry for FSP (single source of truth, fail-closed)
Ported from SME design; permission keys = **the 30 FSP catalog keys + seeded keys**.
Draft (labels follow SME wording where the screen matches):

| point | link | perm rows (FSP) | portals |
|---|---|---|---|
| Applications (KYC queue) | `/kyc/applications` | `kyc.review` | admin |
| Review queue | `/kyc/reviews` | `kyc.approve` | admin |
| Organizations directory | `/tenants` | `org.create` | admin |
| Overview | `/overview` | `audit.view` | admin |
| Approvals (four-eyes) | `/approvals` | `kyc.approve`,`je.approve` | admin, org |
| Audit trail | `/audit` | `audit.view` | admin, org |
| Roles & permissions | `/rbac` | `role.manage` | admin |
| All permissions | `/permissions` | `audit.view` (read-only catalogue) | admin, org |
| Onboarding | `/onboarding` | `org.create` | admin, org |
| My network (org tree) | `/network` | (reachable-tenant read) | org |
| My organization (KYC of own org) | `/kyc` | `kyc.review` | org |
| Users | `/users` | `user.manage` | admin, org |
| Journal (four-eyes) | `/journal` | `je.create`,`je.approve` | org |

Route guards read `menuPerms(portal)`; per-route `permGuard` checks rows/cells against
`GET /api/me/permissions` (through BFF → gateway → core). Unmapped path ⇒ deny.

### 2.5 KYC port — hierarchy preserved verbatim, engine adapted to FSP
- **The 5 tabs are copied word-for-word** (`kyc-progress.tsx` = SME `mp-kyc-progress`).
- SME is schema-from-server. FSP core (Phase-0) has a **fixed model** (`KycCase`, `KycCheck`,
  `KycUbo`, `KycBankAccount`, Namibia rules) and NO schema endpoint. Decision: the 5-section
  schema ships as a **shared TS constant in `portals/shared/kyc/schema.ts`** (sections/fields/
  requiredWhen grammar preserved — fields drawn from the filed spec + `NamibiaRules`), and the
  editor validates locally with the SAME condition grammar (ported from `kyc-conditions.ts`),
  posting to the existing FSP endpoints (`POST /v1/tenants/{id}/kyc`, `/kyc/checks`, decisions,
  `/kyc/ubo` etc.). When the FAPI2 slice adds a server schema endpoint, the editor swaps its
  source — the tab hierarchy and grammar stay identical.
- Workspace = tree (`/v1/tenants/{id}/children` + detail) + task queue (FSP approvals +
  kyc cases) + editor; decisions map: approve/reject/request-info (FSP four-eyes is
  **second-person at person_id** — UI shows "you approved this / awaiting second checker",
  enforced server-side; UI only hides what the server would refuse).

### 2.6 Theme port — SME look, FSP token names
`portals/shared/ui/tokens.css` = the ONE theme file, visual identity copied from
`portal-theme.scss`: dark ladder #070707/#111/#1a1a1a, gold #f0a10e, Figtree (`@fontsource/figtree`
— offline-safe), pill buttons, chips, panels, grid+glow hero, themed scrollbars.
FSP semantic roles (ui-std §2.3: canvas/surface/action-primary/destructive/status-*/financial-*)
are driven FROM the SME palette so every existing Tailwind utility class keeps working:
`action-primary: #f0a10e`, `canvas: #070707`, `surface: #111`, statuses mapped per `status-maps.ts`
+ FSP posting states (DRAFT/POSTED/MATCHED/UNCERTAIN…). Per-portal accent = `--portal-accent`
set from PortalConfig on the shell root.

### 2.7 Auth flows per portal (SME session model, FSP endpoints)
```
login page ─ POST /bff/auth/login {username,password}  (BFF→gateway→IdP; token stays server-side)
CSRF: GET /bff/csrf (cookie) → X-XSRF-TOKEN header
hydrate: GET /bff/api/me? …  → FSP /v1/tenants/{tid} + /v1/me/permissions (session identity)
eligibility: admin portal → platform tenant member; org portal → tenant.subType === PORTAL.orgType
logout: POST form → /bff/auth/logout
```

---

## 3. THE SPINE FIRST — widget core, workbench, layout-driven menus (user direction, verified in SME)

**Verified fact from SME step 5–7 + `portal-layout.ts`:** a portal's sidebar/menus/submenus are
NOT hand-written routes in the live model — a portal with a published layout draws navigation
from it; screens render at `/p/<menu>/<screen>` composed of placeable widgets; WITHOUT a layout
the portal falls back to its built-in default (`default-layouts.ts`) — publishing never loses a
screen. The builder (workbench `/builder`) authors `PortalLayout { portal, menus → screens →
placed widgets }`; the registry (`registry.generated.ts` ← `widgets:gen` from the pure-data
manifests) is what the builder palette, RBAC screen and layout renderer all read.

### 3.1 What each piece is (SME-verified)
| Piece | Essence |
|---|---|
| **Widget contract** | folder `widgets/<area>/<name>/`: `<name>.widget.ts` = manifest PURE DATA (read by builder/RBAC/Node tests without loading Angular/React), `<name>.tsx` = UI from shared design-system ONLY (lint refuses own components, raw `<table>/<button>`), `<name>.fixtures.ts` (default/empty/error scenarios), `<name>.spec.ts` |
| **`defineWidget`** | typed constructor → missing size/tier, bad span (xs > 4 cols), unknown config field = COMPILE error. Fields: `id` (stable forever), `version`, `contexts[]`, `requires` (rows OR `{all:[…]}`), `actions[{id,label,cell,command}]`, `sources[]` (logical, never URLs), `config` schema, `size` per tier (`xs:4 <600px, sm:8 <960px, md:12, lg:12`), `hideOn:['forbidden','empty']`, lazy `load()` |
| **WidgetDataPort** | THE one network path: in-portal = `GatewayDataPort` (relative `/api` only, BFF session lane, maps 401→login, 403→widget state `forbidden`, ProblemDetail→ApiProblem); standalone/workbench/tests = `FixtureDataPort` (fixtures + call recording). Data-source map: logical source → `{method,path} per portal context` |
| **WidgetContext** | host provides `{portal, orgId, orgType, screenKey, routeParams, mode:'live'\|'design'\|'fixture'}`, page-level selection (widget inputs/publishes), config, ResizeObserver tier/span |
| **Registry** | `npm run widgets:gen` → `registry.generated.ts` + `widget-manifest.json` (CI `--check`); registry server (SME phase 6+) is deploy-time registration; **until then the manifest JSON IS the registry** — same for FSP Phase-0 |
| **Layout renderer** | `PortalLayoutService` loads published layout (or default fallback) → sidebar from layouts; `LayoutPage` renders placed widgets in the container grid, guarded by the UNION of the screen's widgets' `requires` (screen resources computed automatically — `permGuard`/`navAllows` unchanged) |
| **Workbench** | standalone app on fixtures, no gateway/session: widget harness (4 tiers side-by-side, scenario switches, network panel, diagnostics), `/registry` page, `/builder` (menu tree + phone-first canvas + inspector; saves `layouts/<portal>.json`) |

### 3.2 FSP-adapted decisions (adopted from portal-builder.md as-is)
1. **Layouts are build-time first**: `frontend/layouts/<portal>.json` in-repo; menus+submenus for
each of the 4 portals come from these layouts; the coded menu registry remains the FALLBACK
(no layout = coded menus), one truth: builder output must become the catalogue, never a second model.
2. **One network path**: widgets use only the data port (React equivalent: context hook), FAPI
compliant by construction through the BFF/gateway lane.
3. **Visibility follows permissions only** (no per-widget nav cells); server stays the authority;
403 collapses the cell and the grid reflows.
4. **Screen ids immutable once published**; labels may change.
5. **Permission coherence blocks merges**: every endpoint a widget calls must enforce a cell the
widget declares, and every declared cell must exist in the FSP permission catalogue
(= our seeded keys: kyc.review, kyc.approve, org.create, je.*, audit.view, role.manage, user.manage…).
6. **Mobile-first tiers with compile-checked spans** (xs4/sm8/md12/lg12); widgets style by container,
not media query.
7. Manifest JSON stands in for the registry server in Phase-0 (SME phase-6 equivalence).

### 3.3 Revised build order (supersedes old §3)
1. **Theme + design-system atoms** (tokens.css, button/card/input/chip/status-badge/page-header/panel) — unchanged.
2. **Widget core** (`portals/shared/widgets/core/`): `defineWidget` + span/grid types (TS compile
checks), `WidgetContext`, `WidgetDataPort` (gateway via BFF + fixture), `widget-host.tsx`, manifest
gen (`bun run widgets:gen/check`) — the registry mechanism.
3. **Workbench** (`frontend/workbench/` Vite app, port 4300-equivalent): harness (tiers × scenarios),
`/registry` page from generated manifests; builder canvas kept for a follow-up iteration
(author layouts as JSON first).
4. **Layout model + renderer**: `layouts/<portal>.json` for the 4 portals; `LayoutPage` (widget grid,
container tiers) at `/p/:menu/:screen` + screen-resources permGuard; fallback default-layouts.
5. **Portal scaffolding**: 4 entries, PortalConfig, AppShell(portal-layout) + session/guards + login
through native IdP (eligibility per portal).
6. **Widgets per need, one folder each** (manifest+fixtures+spec+contract): kyc.application
(the 5-tab editor — SME Appendix A mirror), kyc.orgs (tenant tree), kyc.tasks (review queue),
tenant.directory (admin), approvals.queue, audit.trail, journal.four-eyes, rbac.matrix.
7. **Admin & org screens** become compositions: each legacy screen = one full-width page widget.

## 4. Build order (once questions confirmed)
1. Theme + tokens.css + design-system port (atoms first: button/card/input/chip; status-badge;
   page-header; panel). Verify visually on the login page of both portal shapes.
2. Portal scaffolding: 4 HTML entries, PortalConfig trio, AppShell port (nav-shell), session
   client + guards + menu registry (fail-closed), no-access shell. Login through native IdP
   working on all four entry points with correct eligibility rejection.
3. Admin portal screens: Organizations directory (kpi + data-table port), Onboarding (with-kyc
   wizard footer), KYC applications + Review queue.
4. Org portals: My network (tree), My KYC (5-tab editor), Approvals, Audit trail.
5. E2E smoke through BFF+gateway with role-specific users (`demo1234` personas).

## 5. Open questions (need answers before building)
1. **Portal trio:** Manufacturer + Distributor + **Wholesaler** as the first 3 org portals?
   (SME's trio analogue; Dealer/Merchant = one more config file later.) Or Dealer instead of Wholesaler?
2. **Dev serving:** my default = ONE vite server, 4 HTML entrypoints (`/admin/`, `/manufacturer/`,
   …) + an index chooser. Alternative = 4 separate dev servers (exactly like SME's 4 ng-serves).
3. **Builder depth in slice 1:** default = registry page + widget harness (tiers/scenarios) NOW,
   drag-and-drop builder canvas LATER (SME did the same: contract→port→harness→KYC widget→layouts→builder).
4. **Fonts:** bundle Figtree locally (`@fontsource/figtree`) — offline-safe (default).

## 6. SME files that are THE references when building
- `projects/shared/web-core/menu.ts` — registry pattern
- `projects/shared/web-core/{session,perm,access,empty-shell,portal-layout}.ts`
- `projects/shared/kyc/*` — conditions grammar, schema editor, progress bar, workspace
- `projects/shared/ui/portal-theme.scss` + `src/app/design-system/tokens.scss` + `status-maps.ts`
- `projects/shared/widgets/kyc/application/*` — widget manifest anatomy
- `projects/org-portal/src/app/{app.routes,core/guards,core/auth,portal.config*}.ts`
- `projects/admin-portal/src/app/app.routes.ts`

---

## 6. OPERATIONS VOCABULARY & THE ROLE MAPPING — FSP keys, NOT SME's (decided)

**The SME idea we adopt; the SME data we do NOT copy** (user decision):
per-widget capabilities declared in the manifest, a small canonical operation vocabulary,
and a role → widget → operation mapping in the RBAC screen. But `roles`/`permissions`/catalog
keys are OURS, and we will NOT build all SME widgets.

### 6.1 What SME does (reference — for the idea only)
- **Manifest**: every widget declares `requires` (rows/cells to even render) +
  `actions[{id,label,cell:'row:verb',command}]` + `commandOperations` mapping each logical
  command → one of **4 canonical operations** `view|create|modify|approve`
  (`kyc.withdraw` → 'approve' — DELETION is APPROVE-like; destructive = high-risk).
- **Backend**: `@Perm(resource, verb)` on every handler — **10 verbs** (READ, CREATE, MODIFY,
  DELETE, SUBMIT, AUTHORIZE, EXECUTE, EXPORT, ADMINISTER, SIMULATE), 245 endpoints, boot-fails
  on unclassified routes (our `@FspOperation` mandate is the same idea).
- **Role model**: `role-operations-model.ts` — the RBAC screen shows
  `menus › screens › widget › [View|Create|Modify|Approve]` checkboxes; ticking View grants
  `requires` (+menu cell); ticking Create/Modify/Approve grants the cells of the actions whose
  command maps to that op, plus View (can't act on what you can't see). Shared cells switch
  together; inherited locked.

### 6.2 FSP adaptation (our reality)
- **Flat permission keys, no verb grid** *(locked §2d)*: our authority = the 23 seeded keys
  (`permissions` table) enforced via `@FspOperation(permission)` + `PermissionService`.
  We do NOT port the 10-verb enum. Each FSP catalog operation already carries exactly ONE key;
  risk class (READ/WRITE/FOUR_EYES) rides on the catalog, not the widget.
- **Widget vocabulary stays the 4 ops** (label consistency with SME's role UX):
  `view → requires`, `create|modify|approve → actions via commandOperations`.
- **`role-menu-model.ts` ported pure**: input = generated widget manifests + the FSP permission
  catalogue (from `/v1/permissions`), state = role's set of **permission keys**. One key may back
  several ops (e.g. `kyc.reject` is both a 'view-adjacent' decision row and the reject action) —
  the model renders that honestly ("also grants: …").

### 6.3 The FSP widget set & its operation→key mapping (what we will build)
FSP permissions table today (23 keys):
`audit.view · bank.account.approve · bank.account.create · gl.account.approve · gl.account.create ·
gl.account.view · idp.manage · je.approve · je.create · je.view · kyc.approve · kyc.reject ·
kyc.review · org.activate · org.approve_child · org.create · org.onboard_child · period.close ·
period.open · platform.bootstrap · role.manage · screening.run · user.manage`

| Widget | requires (view) | actions → op → key | Ops offered on the role screen |
|---|---|---|---|
| `kyc.application` (5-tab editor) | `kyc.review` | save-draft → modify → `kyc.review`¹; submit → modify → `org.onboard_child`; withdraw → approve → `org.onboard_child` | View, Modify |
| `kyc.orgs` (tenant tree + status) | `kyc.review` | — | View |
| `kyc.tasks` (review queue) | `kyc.approve` ∨ `kyc.reject` | decide → approve → `kyc.approve` ∨ `kyc.reject` | View, Approve |
| `tenant.directory` (admin) | `org.create` | activate → approve → `org.activate` | View, Approve |
| `onboard.child` (wizard) | `org.onboard_child` | submit → create → `org.onboard_child`; decide → approve → `org.approve_child`; act-as → approve → (P2: `idp.manage`) | View, Create, Approve |
| `approvals.queue` (four-eyes inbox) | `kyc.approve` ∨ `je.approve` ∨ `kyc.review` | approve/reject → approve → target handler's key (`kyc.approve` / `je.approve`; second-person at person_id) | View, Approve |
| `journal.entry` (four-eyes JE) | `je.view` | create/submit draft → create·modify → `je.create`; approve → approve → `je.approve` | View, Create, Modify, Approve |
| `audit.trail` | `audit.view` | — | View |
| `rbac.matrix` (roles & permissions) | `role.manage` | assign/revoke → modify·approve → `role.manage` | View, Modify, Approve |
| `users.organization` | `user.manage` | add → create → `user.manage`; setup-link/deactivate → approve → `user.manage`² | View, Create, Approve |
| `ledger.accounts` (GL/book) | `gl.account.view` | propose → create → `gl.account.create`; approve → approve → `gl.account.approve` | View, Create, Approve |
| `period.control` | `gl.account.view` | open → approve → `period.open`; close → approve → `period.close` | View, Approve |
| `users.sign-in` (self-serve) | `user.manage` | resend/reset/revoke → approve → `user.manage`² | View, Approve |

¹ FSP Phase-0 has no separate KYC-form write key — the editor's own-org save rides `kyc.review`
(the same key TENANT_ADMIN holders use to maintain their application). When the FAPI2 slice adds
finer keys, only the manifest's cells change — widgets, roles and layouts unchanged.
² Same-key collapse: SME had distinct read/administer verbs; we have one key. The RBAC screen
shows "also grants: ..." honestly; finer keys = new permission rows later, never verb columns.

### 6.4 Seeded roles × widget operations (the mapping we must honour)
From V2/V3 seeds (platform = ALL 23 keys incl `platform.bootstrap`):

| Role (persona) | Keys held | Portals + widgets it will actually see |
|---|---|---|
| **PLATFORM_ADMIN** (...0101) | all 23 | admin portal: directory, onboard, KYC review queue, approvals, journal, audit, rbac, users, ledger, periods |
| **PSP_ADMIN** (subtree, ...01a1) | 19 (onboarding, KYC, GL/bank/je create+approve, period, audit, role, user) | org portal (its subType): onboard-child, own KYC editor, network tree, journal, ledger, approvals, audit, users, rbac |
| **TPTS_OPS** (subtree, ...01a2) | 9 (kyc review/approve/reject, screening.run, bank/gl approve+view, je.view, audit.view) | org portal: KYC review queue + case views, second-eye approvals, audit. NO create rights — pure checker |
| **client-tenant MAKER** (...02a1) | 6 (je.create, je.view, gl.account.create, gl.account.view, bank.account.create, audit.view) | org portal: journal drafts, GL/bank proposals. NO approve anything — pure maker; four-eyes shows "awaiting second approval" |
| **client-tenant CHECKER** (V3 companion) | approve-side keys | sees the SAME screens as maker minus draft actions, with Approve buttons — same widget, ops differ |

This is exactly the SME mechanics: **same widget renders different buttons per role** — the
operation checklist decides, the server still re-checks every call (`PermissionService`, DB truth).

### 6.5 Implementation notes
- `defineWidget` typed with `operations: { view?: string[]; create?: string[]; modify?: string[];
  approve?: string[] }` (FSP keys) — the role screen derives checkboxes from this + `actions`.
- Catalog coherence check (our `widgets:check`): every action key must exist in the FSP
  permission catalogue AND the referenced `/v1` operation must carry that key (parse both —
  backend's own boot fail already guards the server side).
- `permissions` reads for the UI come from `/v1/permissions` (keys exist) and
  `/v1/me/permissions` (held keys) — both already live through gateway.
