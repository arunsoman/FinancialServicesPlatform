# Stage 1 — UI/UX on fixtures (agent mode)

Goal: a widget the user can judge in the workbench and on its real screen, built to the FSP UI
standard, backed by fixtures for everything the backend does not have yet, verified, e2e-tested
and **registered in core's widget registry** — then the user's approval.

Context budget: UI material only. Backend facts come from `widgets:survey` / `widgets:dto`
output. Do not open Java files in this stage.

---

## 1. Resume or start

```bash
cd frontend
bun run widgets:stage <area>.<name>     # exists? → continue from the open gate items it lists
```

Pick the id once — `<area>.<name>`, lower case, permanent (layouts and RBAC refer to it). Reuse
the area of the domain (`journal`, `ledger`, `users`, `kyc`, `approvals`, …).

## 2. Discover (tools, not files)

```bash
bun run widgets:survey <2–4 domain words>         # e.g. widgets:survey bank account
bun run widgets:dto BankAccounts.BankAccountListResponse --ts --depth 2
```

Read the survey in this order and stop as soon as the requirement is covered:

1. **Existing widgets mentioning it** — if one already shows this data, *extend it* (or attach a
   sibling to its screen) rather than duplicating.
2. **SME reference widgets** — if listed, open that SME widget's template/component and port its
   layout, flows and naming (adapted to React + FSP atoms).
3. **Backend operations** — every line is an endpoint you may REUSE: method, `/api` path,
   permission, risk (READ / WRITE / FOUR_EYES), query params, body type, return type.
4. **Logical names already mapped** — `core/sources.ts` names can be used directly in the
   manifest; `data.ts` names belong to another widget (copy the mapping under your id).
5. **Permission keys** — `requires` and action `cell`s must be keys from
   `permission-catalogue.ts`. A genuinely new capability gets a new key — mark it NEW (stage 2
   seeds it).

## 3. The operations sheet (write it in chat before coding)

One row per read and per write (a draft → submit → approve flow is several rows):

| name (`<id>.<thing>`) | KIND | method + `/api` path | permission | request → response | after success / 403 / 409 / 422 | REUSE / NEW |
|---|---|---|---|---|---|---|

- KIND: `view` (reads) · `create` · `modify` · `approve` (approve, reject, execute, cancel, …).
- REUSE: copy the shapes from `widgets:dto … --ts`, field for field.
- NEW: you are *designing the API*. Follow the platform's conventions so stage 2 can build it
  without reshaping: `/api/tenants/{tenantId}/<resource>` for tenant data; collections are
  paginated `{ items, page, size, totalElements, totalPages, hasNext }` with `page,size,sort,q`
  + domain filters (backend AGENTS §2f); money is a string of minor units with an ISO-4217
  `currency`; ids are UUID strings; timestamps ISO-8601; optimistic concurrency via `version`
  (`expectedVersion` on writes); approve-kind writes return the pending approval
  (four-eyes) and never apply immediately.
- Shape the NEW rows so a reviewer can read them — they are part of what the user approves.

## 4. Build the widget

Read the widget contract **once**, only the sections you need:

```bash
sed -n '/^## 3\./,/^## 8\./p' docs/skills/fsp-widget-author/SKILL.md   # manifest, component, data.ts, design system, hard rules
```

Folder `frontend/src/portals/shared/widgets/<area>/<name>/`:

| File | Holds |
|---|---|
| `index.tsx` | `defineWidget({...})` manifest — id, version, label, description, category, contexts, `requires`, `sources`, `commands`, `actions` (`{ id, label, cell, command, kind }`), `commandOperations`, `size` for xs/sm/md/lg, `hideOn` |
| `widget.tsx` | the component (presentation only) — `useWidgetRuntime()` + `usePortData()` from `../../core/runtime`; all five states; `data-action="<action id>"` on every gated control |
| `data.ts` | `sources` (GET) and `commands` (writes) named `<id>.<thing>` → `{ method, path: '/api/…', query: [...] }` (no `?` in paths) + `fixtures` for every op, REUSE and NEW alike |
| `model.ts` | exported response/request types and **all pure logic** (mapping, formatting, validation schemas, state machines) — this is what unit tests exercise |
| `<name>.test.ts` | Vitest, `import { describe, expect, it } from 'vitest'` (no globals) |
| extra `*.tsx` | one component per file for anything non-trivial (forms, drawers, rows) |

Agent mode may also use `../../kit/*` (Drawer, FormField, SelectField, TextAreaField,
InlineConfirm, Notice, StatePanel, Pager, BookPicker, …) and existing `core/sources.ts` names.

### Standards to meet (read the section, not the whole file)

- `frontend/AGENTS.md` §1–§2 (toolchain + binding mandates).
- `ui-std.md`: §5 financial patterns (posting state explicit, four-eyes separation, amounts) ·
  §8 forms (zod on blur + submit, errors on the field) · §9.5 empty/loading/error states
  (skeletons, never spinners) · §10 tables (wide tables scroll inside their own box) · §11
  accessibility · Addendum v1.1 (mobile-first, server-side search/pagination).
- Four-eyes UI: `403 MAKER_CANNOT_APPROVE` → "You submitted this — another person must decide";
  `403 CHECKER_NOT_ENABLED` → "You aren't enabled as a checker". Never hide either.
- Writes: `useMutation` with `retry: 0`, button disabled while busy, server message on failure,
  `reload()` the affected reads; never optimistic for money or approvals. `port.command` already
  sends the Idempotency-Key and CSRF — do not add your own.

### FAPI 2.0 in the browser (the verify step enforces most of it)

Only `port.request` / `port.command`; never `fetch`, `XMLHttpRequest`, `WebSocket`, storage,
cookies, `window.location`/`open`/`postMessage`, absolute URLs, tokens, ID numbers or bank
details in UI, logs or fixtures. Permissions in `context.permissions` only choose what to show —
core re-checks every call.

### Fixtures that make every state reachable

- `default`: 3–6 realistic Namibian rows (organisations, people, NAD amounts as minor-unit
  strings, ISO dates) that satisfy the contract exactly — every field present, nulls where the
  server sends nulls.
- `empty`, `error: null` for every source.
- **Conditional UI needs a resolver.** The `empty` scenario empties *every* source, so a state
  such as "book exists but has no accounts" needs a resolver fixture that plays the server:
  `default: (req) => req.params.bookId === EMPTY_BOOK ? [] : rows` — and the default `books`
  fixture includes that book. Commands may have resolvers that keep state so a write → reload
  round-trip behaves like the server.
- NEW operations get fixtures in exactly the shape you put in the operations sheet.

### Unit tests (`<name>.test.ts`, at least)

1. The manifest: id, `requires`, every action's `cell` + `command`, `commandOperations`, all four
   `size` tiers.
2. `data.ts`: every manifest source/command is mapped; fixtures match the `model.ts` types
   (typed constants make `tsc` prove it).
3. Each pure function in `model.ts` (mapping, validation incl. the error per field, money
   formatting from minor units, state → tone).
4. For each write: the request body built from form values (what `port.command` will send).

```bash
bun run widgets:plan <id> --requirement "<the requirement in one line>"
bun run widgets:verify <id>          # repeat until all green
```

## 5. End-to-end (see `e2e.md`)

```bash
bun run widgets:e2e <id> --scaffold   # then write the real journeys
bun run widgets:e2e <id> --lane workbench
```

## 6. Screen and menu

Decide with `docs/widget-workbench.md` §9 / `frontend/AGENTS.md` §3.3:

- **New operational screen** (own workflow + own permission): add the id to `type MenuWidget`
  and a `MenuItem` in `frontend/src/portals/shared/web-core/menu.ts`, mount with `WidgetScreen`
  in `screens.tsx`.
- **Extension of an existing screen**: add the id to that screen's `widgets` in `menu.ts`.

Then record where the portal lane finds it — the one part of the stage file you edit by hand:

```json
"screen": { "portalPath": "/admin", "screenPath": "/admin/<route>", "user": "platform-admin" }
```

Signed portals render the *published* layout: after wiring a new menu point in the builder,
export it (`make menus-publish`) and restart core (it applies the seed and signs at boot) before
the portal lane can see it — docs/portal-menus.md. If the stack is down, say so in the review and skip the portal lane.

## 7. Register — required before review

```bash
bun run widgets:register-bff <id>
```

Signs in through the hosted IdP (BFF session; no token reaches the tool), registers the widget
through the workbench registry (`PUT /api/ui/widgets`, core re-checks `ui.widget.register`) and
records it in the stage file. Needs idp, gateway, bff, core and Vite up. It is a gate item:
`widgets:accept` refuses until it has run. The widget then shows in
`/widget-workbench/registry?sort=created-desc` at the top.

## 8. Review loop

`bun run widgets:stage <id>` must show every gate item ✓. Present with the template in
SKILL.md §6. On feedback: change, `widgets:verify`, `widgets:e2e --lane workbench`, re-present
(the gates track file hashes, so stale greens do not count). Bump `version` in the manifest when
a reviewed widget changes and re-register.

Only after the user explicitly approves:

```bash
bun run widgets:accept <id> --by "<name of the approver>" --note "<what was approved>"
```
