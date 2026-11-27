# Widget Harness & Portal Builder — PRD (Phase 1)

**Status:** approved-by-recommendation (2026-11-18) · **Reference:** SME widget pipeline
(MiPay widget prompt + workbench + PortalLayoutService) · **Binding:** `AGENTS.md`, `ui-std.md`

## 1. Essence

Developers author **UI widgets** and **register** them in FSP; the **platform admin** composes
**portals** — menus → screens → widget placement — in a harness portal; publishing a layout is a
**four-eyes** operation; portals render from the **published layout**. A **pi skill (`fsp-widget`)**
automates widget creation so a user only states what they need.

## 2. Settled decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | Layout publish integrates the existing **`ApprovalService`** (new OpType `UI_LAYOUT_PUBLISH` + handler), NOT a bespoke state machine | reuses four-eyes queue, audit evidence, `requireDistinctPerson` — no parallel approval logic |
| 2 | Harness = **`/harness` HTML entry** (developer tool), gated on `ui.*` permission cells; never a public portal | it mutates platform UI metadata; admin-only |
| 3 | i18n: **message keys from day one** (relabel-safe), **English bundles only** this slice | keys-first keeps locale machinery additive later |
| 4 | Schema = **V8** (global Flyway order), two tables, self-contained | applied migrations immutable |
| 5 | Widgets are **React** (Bun/Vite/TS-strict), manifests carry tier spans + permission cells; SME `layout.ts` validation logic ported | FSP toolchain is settled |
| 6 | One network path: widgets call `data.query()/command()` only; cells reused from `@FspOperation` catalog — **never invented** | AGENTS §4b, §5b |

## 3. Domain model (V8)

```
ui_widgets          id "<area>.<name>" PK · version · label · category · bundle · portals[]
                    · requires[] (cells) · status ACTIVE · registered_by · @Version
ui_layouts          portal · status DRAFT|PUBLISHED · layout_json · maker_id
                    · published_approval_id → approval_requests · @Version
                    unique (portal,status); at most one DRAFT + one PUBLISHED per portal
```

Layout JSON (validated at the service boundary, keys-only export later):
`{ portal, menus:[{ id, label:I18nText, screens:[{ uid, label:I18nText,
placedWidgets:[{ instanceId, widgetId, span:{xs,sm,md,lg}, config }], navCell }]}] }`.

## 4. Permissions & operations

New rows (starts with grants to PLATFORM_ADMIN only — deliberate for the harness slice):
`ui.widget.register` (WRITE), `ui.layout.manage` (FOUR_EYES).

| Op | Endpoint | Cell |
|---|---|---|
| register/upsert widget | `PUT /v1/ui/widgets` | `ui.widget.register` |
| list registry | `GET /v1/ui/widgets` | `ui.widget.register` |
| save draft layout | `PUT /v1/ui/layouts/{portal}/draft` | `ui.layout.manage` |
| submit to publish | `POST /v1/ui/layouts/{portal}/submit` | `ui.layout.manage` (maker) |
| approve/reject via queue | `/v1/approvals` (OpType `UI_LAYOUT_PUBLISH`) | `ui.layout.manage` (checker ≠ maker) |
| read published layout | `GET /v1/ui/layouts/{portal}/published` | authenticated-only |

## 5. Build order

A. schema + backend service/controller/handler + unit tests *(this slice)*
B. harness frontend: registry browser + portal builder (drag-drop, tiers, live ui-std validation)
C. tooling: `widgets:gen`, `widgets:register`
D. pi skill `~/.pi/agent/skills/fsp-widget/` (adapted from `mipay-widget-prompt.md`, React/ui-std)
