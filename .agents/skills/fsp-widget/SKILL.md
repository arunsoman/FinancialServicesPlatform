---
name: fsp-widget
description: Builds an FSP portal widget end to end. In an agent harness (repo + shell) it runs two gated stages — stage 1 designs, builds, verifies, e2e-tests and registers the React UI on fixtures until the user approves it; stage 2 then builds/reuses the backend (FAPI 2.0, four-eyes, minor-unit money, audit, tests) and proves it live with Playwright. In the widget workbench (chat LLM, prompt carries "MODE: WORKBENCH-PACK") it answers with a paste-able widget pack instead. Use for any new or changed portal screen, panel, list, card, form, queue or widget.
---

# fsp-widget — one requirement → one shipped widget

## 0. Which mode am I in? (decide before anything else)

| Signal | Mode | What you produce |
|---|---|---|
| The prompt contains `MODE: WORKBENCH-PACK`, **or** you cannot read/write files or run commands | **WORKBENCH-PACK** | Follow `docs/skills/fsp-widget-author/SKILL.md` exactly (it is in the prompt already). One `=== FSP-WIDGET-PACK v1 ===` block or a `NEEDS BACKEND` note. Nothing below applies. |
| You have the repository and a shell (Claude Code, pi, …) | **AGENT** (default) | Files in the repo, verified by tools, in two gated stages — the rest of this file. |

The user can force a pack in agent mode ("give me a pack") — then answer like WORKBENCH-PACK.

## 1. Non-negotiables (agent mode)

- **Read first, once:** `AGENTS.md` §1–§2 (FAPI 2.0 + domain invariants). Load the **fapi2-compliance** skill (`.agents/skills/fapi2-compliance/SKILL.md`) before stage 2 or before touching anything a browser calls.
- **Tools before reading.** Never open whole controllers, DTO files, test logs or the generated skill's endpoint table to "look around". Ask the tools (§3) — they answer in a screen. Open a file only to edit it, or at the exact `file:line` a tool printed.
- **Reuse before create:** an existing endpoint (`widgets:survey`), an existing widget (extend it), an SME widget (`/home/arun/sme/frontend/projects/shared/widgets/…` — port its UX), a kit component (`widgets/kit/`).
- **The stage file is the state:** `frontend/src/portals/shared/widgets/<area>/<name>/.widget-stage.json`. Read it with `bun run widgets:stage <id>` at the start of every session — it tells you the stage, the operations sheet and which gate items are still open. Never edit it by hand except the `screen` block (§5).
- **Gates are hard.** Never run `widgets:accept` unless the user explicitly approved the UI in this conversation, quoting who approved. Never start stage-2 work before `accept` succeeded.
- **Shared tree:** other sessions and a live Vite run in this checkout — no `git stash`, no `tsc -b` (`bun run typecheck` only), no destructive git. Commit only when asked.

## 2. The two stages

```
 requirement
     │
 ┌───▼──────────── STAGE 1 · UI/UX (context: ui-std, design system, fixtures) ─────────────┐
 │ survey → plan (REUSE/NEW) → build widget on fixtures → verify → e2e workbench lane      │
 │ → wire screen/menu → REGISTER in core's widget registry → present for review ⟲ feedback │
 └───┬──────────────────────────────────────────────────────────────────────────────────────┘
     │  user says "approved"  →  bun run widgets:accept <id> --by "<who>"   (locks UI + contract)
 ┌───▼──────────── STAGE 2 · BACKEND (context: backend/AGENTS.md, FAPI 2.0, invariants) ────┐
 │ implement every NEW op to the locked contract → backend tests → widgets:skill            │
 │ → plan flips NEW→REUSE → verify → e2e --lane all --live → widgets:done                  │
 └──────────────────────────────────────────────────────────────────────────────────────────┘
```

**Context discipline.** Stage 1 loads UI material only: `references/stage1-ui.md`, the widget contract (§3–§7 of `docs/skills/fsp-widget-author/SKILL.md`), `frontend/AGENTS.md`, and the `ui-std.md` sections you need. Backend knowledge in stage 1 comes **only** from `widgets:survey` / `widgets:dto` output — no Java beyond that. Stage 2 loads `references/stage2-backend.md`, `backend/AGENTS.md` and the FAPI skill, and treats the accepted UI as fixed: if the backend cannot honour the locked contract, stop and ask (a contract change goes back through approval).

Full procedures: **`references/stage1-ui.md`**, **`references/stage2-backend.md`**, **`references/e2e.md`**.

## 3. Tool belt (run from `frontend/`; each prints a compact answer, full logs under `node_modules/.cache/fsp-widget-agent/<id>/`)

| Command | Use it to | Stage |
|---|---|---|
| `bun run widgets:survey <terms…>` | find backend operations (code-derived: method, `/api` path, op id, permission, risk, query params, body + return types, handler `file`), logical names already mapped, permission keys, existing widgets and SME widgets matching the terms | 1, 2 |
| `bun run widgets:dto <Outer.Name…> --ts [--depth 2]` | print a backend DTO as a TS interface for `data.ts` / `model.ts` contracts | 1, 2 |
| `bun run widgets:plan <id> --requirement "…"` | derive the operations sheet (REUSE / NEW) from the manifest + `data.ts`; creates/updates the stage file | 1, 2 |
| `bun run widgets:verify <id>` | code rules (no fetch/storage/any/hex/@media/raw URLs, `retry: 0` on mutations, tests exist) · `widgets:check` · `tsc` · eslint · vitest · catalog freshness | 1, 2 |
| `bun run widgets:e2e <id> --scaffold` / `widgets:e2e <id> [--lane workbench\|portal\|all] [--live]` | write / run the Playwright spec (§ `references/e2e.md`) | 1, 2 |
| `bun run widgets:register-bff <id>` | register the widget with core's widget registry through a real BFF session (no tokens, FAPI 2.0) | end of 1 |
| `bun run widgets:stage <id>` | stage, operations, approval, and the open gate items with the command that fixes each | any |
| `bun run widgets:accept <id> --by "<who>" [--note "…"]` | record the user's UI approval → stage 2 (refuses unless verify + registration + workbench e2e are green on the current files) | gate |
| `bun run widgets:done <id>` | finish: no NEW ops left, UI/contract unchanged since approval, live e2e green, skill fresh | end of 2 |
| `bun run widgets:skill` | regenerate the chat-mode skill + `endpoints.json` from code (run after adding endpoints) | 2 |

Backend tests: `cd backend && ./gradlew :core:test --tests '<FQCN>'` (scope it — the full suite is slow).

## 4. Stage 1 in one screen (details: `references/stage1-ui.md`)

1. `widgets:stage <id>` (resume?) → otherwise `widgets:survey <domain terms>` and, for each candidate response, `widgets:dto … --ts`.
2. Decide the screen: new menu point vs. attach to an existing screen (`docs/widget-workbench.md` §9, `frontend/AGENTS.md` §3.3). Look for an SME counterpart and existing widget to extend.
3. Draft the operations sheet in chat (each read/write: endpoint, permission, KIND view/create/modify/approve, request/response shape, behaviour on success / 403 / 409 / 422). REUSE what exists; NEW only when nothing fits — define its exact request/response shapes now (they become the stage-2 spec).
4. Build `widgets/<area>/<name>/`: `index.tsx`, `widget.tsx`, `data.ts` (sources/commands + fixtures for **every** op incl. NEW ones), `model.ts` (types + pure logic), `<name>.test.ts`. `bun run widgets:plan <id> --requirement "…"`.
5. `widgets:verify <id>` until green.
6. `widgets:e2e <id> --scaffold`, write the real journeys, `widgets:e2e <id> --lane workbench` until green.
7. Wire the screen (menu.ts / screens.tsx or an existing screen), set the stage file's `screen` block; if the stack is up, also run `--lane portal` (NEW ops are stubbed from fixtures).
8. **Register:** `widgets:register-bff <id>` — mandatory before review; the widget now appears in the workbench registry (sort "Newest created first" to find it).
9. Present for review (§6) and iterate. Every change re-runs verify + e2e (gates track file hashes).

## 5. Stage 2 in one screen (details: `references/stage2-backend.md`)

1. `widgets:stage <id>` → the NEW ops list is your backlog; the accepted contract in `data.ts`/`model.ts` is the spec.
2. Load the fapi2-compliance skill + `backend/AGENTS.md`. For each NEW op: `@FspOperation` handler (correct permission + risk), service, migration (permission key + role grants), four-eyes for approve-kind writes, idempotency, outbox + audit, tenant-subtree check, REST collection standard, concurrency rules. Tests that fail before / pass after.
3. `widgets:skill` → `widgets:plan <id>` (NEW flips to REUSE) → `widgets:verify <id>`.
4. Stack up (rebuild core) → `widgets:register-bff <id>` if the version changed → `widgets:e2e <id> --lane all --live`.
5. `widgets:done <id>`. Report (§7).

## 6. Presenting stage 1 for approval (exact shape)

```
Widget <id> — ready for your review (stage 1 · UI on fixtures)
Preview:   http://localhost:5173/widget-workbench/w/<id>          (every tier; ?scenario=empty|error; ?cells=none)
Screen:    <portal route>                                          (or: attaches to <screen>)
Registry:  registered <id>@<version> as <user>                     (/widget-workbench/registry?sort=created-desc)
Operations: <REUSE n> · <NEW n> — NEW ones (built in stage 2 to exactly these shapes):
  POST /v1/… <permission> — request {…} → response {…}
Checks:    verify ✓ · e2e workbench ✓ (<n> tests) · portal lane <✓ | not run: stack down>
Decisions I made: <one line each>
Reply "approved" (or with changes). Approval locks the UI and these shapes; the backend is built next.
```

## 7. Final report (end of stage 2)

What shipped (widget path, screen, endpoints with permission + risk), migrations, tests added (frontend unit, backend, e2e) with their last results, FAPI checklist items touched, anything left open. Commands to re-run: `widgets:verify <id>`, `widgets:e2e <id> --lane all --live`.
