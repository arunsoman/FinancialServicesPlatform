# Frontend & UI Development Rules

**Scope:** `frontend/` SPA, widgets, portals, and UI components.
**Parent Rule:** [AGENTS.md](../AGENTS.md) (Universal Invariants & FAPI 2.0 Compliance).
**Normative Standard:** [ui-std.md](../ui-std.md) (Complete Design System & React Guide).
**Architecture Reference:** [docs/architecture/09-frontend-portals.md](../docs/architecture/09-frontend-portals.md).

---

## 1. Toolchain rules (hard)

| Area | Rule |
|---|---|
| Package manager | **Bun only.** Never `npm` or `yarn` (`package.json` uses `bunx --bun` scripts; lockfile is `bun.lock`). |
| Stack | Vite + React 18 + TypeScript **strict** (no `any`; ESLint blocks it). |
| Scripts | `bun run lint`, `bun run typecheck`, `bun run build`. |
| Bundle budget | Shell < 200 KB gzipped. |

---

## 2. Binding UI Standard Mandates (from ui-std.md)

1. **Design tokens only (`ui-std.md` §2):**
   - The required semantic color-role set (`bg`, `text`, `border`, `action`, `focus`, `status`, `financial`) is closed. No component may invent a role or use raw hex, px, or durations.
   - `danger` is reserved for destructive/error actions.
   - `success` is reserved only for confirmed positive outcomes.
   - `financial.pending/committed/unknown` are separate roles from status colors.

2. **Posting state is always explicit (`ui-std.md` §5.1):**
   - Every Journal Entry (JE) and `PostingGroup` surface renders the posting state via `PostingStatePill`.
   - Mapping: `DRAFT` (neutral), `PENDING_CHECKER` (warning: "Awaiting approval"), `SUBMITTING` (info pulsing), `COMMIT_UNKNOWN` (**danger**: "Ledger outcome unknown — do not retry"), `FINALIZED` (success: "Posted").
   - **Nothing is "Success" before `FINALIZED`.**
   - `COMMIT_UNKNOWN` surfaces NO retry affordance, provides an explicit **Check status** action, displays `plan_hash` + `correlation_id` + original transfer IDs, and links to the Uncertain Postings queue.

3. **Four-eyes UI separation (`ui-std.md` §5.2):**
   - Maker and checker are strictly different surfaces.
   - Checker surface renders read-only diff, maker identity, `plan_hash`, and affected accounts.
   - Maker never sees an approve control.
   - An API `403 MAKER_CANNOT_APPROVE` renders an explicit blocked banner (never silently hidden).

4. **Amounts formatting (`ui-std.md` §5.3):**
   - Use the `<Amount>` atom exclusively.
   - Minor units are the input contract (`<Amount value={1234567} currency="NAD" minorUnits />`).
   - Must use `tabular-nums` and right alignment.
   - Debit/credit coloring via `financial.debit/credit`. (A debit is NOT an error).
   - Totals come from the API; the client never sums amounts to verify.

5. **React engineering standards (`ui-std.md` §6):**
   - Function components only. Named exports (default exports only for route modules).
   - One component per file. Props named `{Component}Props`.
   - No `any` (`unknown` at boundaries).
   - Hooks return objects when returning > 2 values.
   - No business logic in components; extract to domain hooks or pure helpers.
   - Error boundaries wrap every route and independent data region.
   - Skeletons, never spinners. Mobile-first, no window scroll.

6. **State management & data fetching (`ui-std.md` §7):**
   - Server state: TanStack Query (structured array keys with domain prefix).
   - **Server-driven Pagination, Sorting & Search:** Tables, lists, and collections MUST pass `page`, `size`, `sort`, and search query `q` to backend APIs as URL parameters. Never perform in-memory pagination or full-dataset searching on the client.
   - URL-first state: If state can live in the URL (filters, sort, page, tabs, as-of), it must.
   - Every mutation explicitly invalidates affected query keys.
   - **Financial mutations are NEVER optimistic.**
   - Retries = 1 for idempotent GETs.
   - **Zero auto-retries for mutating financial requests** (POST/PUT/DELETE) — uncertain outcomes surface as `COMMIT_UNKNOWN` UI with user-driven check-status.

7. **Forms & validation (`ui-std.md` §8):**
   - Zod schemas per endpoint.
   - Validation occurs on blur and submit per §8 of `ui-std.md`.

---

## 3. The Widget Pipeline, Portal Architecture & Menu Decision Rules

Every portal screen is composed of **Widgets** hosted in a responsive grid. The agent must understand how portals are built and follow strict rules on when to engage with the widget pipeline and when to add menu items.

### 3.1 Portal Generation Architecture
- **5 Portal Applications:** `admin.html` (PLATFORM), `manufacturer.html` (MANUFACTURER/PSP), `distributor.html` (DISTRIBUTOR), `wholesaler.html` (WHOLESALER), and `merchant.html` (MERCHANT).
- **Single SPA Codebase:** All portals share `OrgApp.tsx` and are configured via `portal-config.ts` (`tenantSubType`, `basePath`, `--portal-accent`, `storageKey`, `signedDelivery`).
- **Dynamic Layouts:** Screens are NOT hardcoded templates. Screens are widget compositions rendered via `WidgetGrid` and `WidgetHost`.

### 3.2 The Development Pipeline (How a Requirement becomes UI)
Agents follow **`.agents/skills/fsp-widget/SKILL.md`** (agent mode) — two gated stages driven by `bun run widgets:*` tools:
1. **Survey, not scan by hand:** `bun run widgets:survey <terms>` lists the code-derived operations (`@FspOperation` + plugin manifests: path, permission, risk, params, DTO types), mapped logical names, permission keys, existing and SME widgets; `bun run widgets:dto <Outer.Name> --ts` prints a response as a TS interface.
2. **Stage 1 — UI on fixtures:** build `frontend/src/portals/shared/widgets/<area>/<name>/` — `index.tsx` (`defineWidget`), `widget.tsx` (ui-std atoms, tokens, skeletons), `data.ts` (sources/commands named `<id>.<thing>` → `/api/...` paths + fixtures for every op), `model.ts` (types + pure logic), `<name>.test.ts`. Built-in shared names live in `core/sources.ts`; a widget's own mappings live in its `data.ts`. Then `widgets:plan` (REUSE/NEW sheet), `widgets:verify`, `widgets:e2e --lane workbench`, wire the screen, and **register**: `bun run widgets:register-bff <id>` (BFF session, FAPI 2.0). The user reviews; on approval `widgets:accept <id> --by "<who>"` locks UI + contract.
3. **Stage 2 — backend to the locked contract:** implement every NEW op per `backend/AGENTS.md` + the fapi2-compliance skill, `widgets:skill`, `widgets:verify`, `widgets:e2e --lane all --live`, `widgets:done`.
4. **Layout & Publishing:** Portal Builder (`/widget-workbench/builder`) arranges widgets into grid layouts (autosaved; **Preview → Desktop/Tablet/Mobile** opens the real portal at that size, live) → the **menu-publisher** skill exports them into the repo seed and commits → CI/CD deploys; core signs the menus at boot. See `docs/portal-menus.md`.

### 3.3 Agent Decision Matrix: When to Add a Menu Item (Implicit Requirements)
When a user requirement describes a capability (e.g. *"add VAS airtime top-up"*, *"enable dispute management"*, *"inventory tracking for merchants"*), the agent must evaluate whether a new navigation item is required:

1. **Rule 1: New Operational Capability / Standalone Domain -> ADD MENU ITEM**
   - If the feature introduces a new operational screen or business domain gated by its own permission (e.g. `inventory.view`, `vas.purchase`, `disputes.manage`), the agent MUST proactively:
     - Register the widget in `type MenuWidget` in `frontend/src/portals/shared/web-core/menu.ts`.
     - Add a new `MenuItem` to `MENU_ITEMS` in `menu.ts` (with `id`, `label`, `icon`, `link`, `perm`, `portals`, `group`, and `widgets: [widgetId]`).
     - Wire the route in `screens.tsx` using `WidgetScreen`.
2. **Rule 2: Enhancement to Existing Domain -> ATTACH TO EXISTING SCREEN**
   - If the feature adds a new summary tile, action panel, or sub-table to an existing workflow (e.g. adding a new KPI to Overview, or adding a transaction filter to Journal Entries), do NOT create a new menu item. Instead, register the widget in the existing screen's `widgets` array or enhance the existing widget.
3. **Rule 3: Coherence Verification Gate**
   - Always execute `bun run widgets:check` to ensure no widget, menu point, or permission key is orphan or mismatched.
