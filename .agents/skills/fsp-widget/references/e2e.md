# End-to-end Playwright for a widget

One spec per widget: `frontend/tests/e2e/widgets/<id>.spec.ts`, scaffolded from a template and
built on `frontend/tests/e2e/widget-support.ts`. It has two lanes, which the CLI selects.

| Lane | Needs | Proves | When |
|---|---|---|---|
| **workbench** | Vite (`bun run dev` or `bun run workbench`) | fixtures: every scenario × every tier renders the expected frame state, no horizontal overflow / nothing outside the grid cell (the page's `window.__wb.measure`), gating (no cells → forbidden, no `data-action` reachable), each action visible with its cell, the main journeys | stage 1 (gate) and stage 2 |
| **portal** | full stack (idp, gateway, bff, core, plugins) + Vite | a real BFF sign-in through the hosted IdP (session cookie only — FAPI 2.0), the real screen from the stage file's `screen` block, the widget registered | stage 1 with NEW ops stubbed from fixtures; stage 2 `--live` with nothing stubbed (gate) |

```bash
cd frontend
bun run widgets:e2e <id> --scaffold                # once
bun run widgets:e2e <id> --lane workbench          # stage 1 gate
bun run widgets:e2e <id> --lane portal             # stage 1, stack up: NEW ops answered from data.ts fixtures via page.route
bun run widgets:e2e <id> --lane all --live         # stage 2 gate: FSP_WIDGET_LIVE=1, no stubs
```

Results (with the widget's file hash) go into the stage file; a later edit makes them stale.
On failure the CLI prints the tail and the log path; screenshots and traces are in
`frontend/test-results/`.

## Writing the journeys (what the scaffold leaves to you)

The scaffold gives scenario sweep, gating and per-action visibility. Replace the two
`INTERACTIONS` placeholders with the widget's real use, the same steps in both lanes:

- open/select (row click, drawer opens, detail renders the fixture values),
- search / filter / page / sort when the widget has them (assert the params reach the request:
  `page.waitForRequest(r => r.url().includes('q=…'))` in the portal lane),
- each write: fill → validation messages on blur and submit → confirm (InlineConfirm) → busy
  state → success notice → the list reloads; and one refusal (409/422) showing the server text,
- four-eyes: as maker the approve control says another person must decide; as checker it
  applies (portal lane signs in a second user via `portalSession` with
  `FSP_WIDGET_E2E_USER`/`_PASS`, or extend the helper with a second `signIn`).

Locate by role and label (`getByRole('button', { name: … })`) or `data-action`; never by CSS
class or text that the design system may change. Scope to a tier frame in the workbench:
`page.locator('[data-tier="md"]')`.

Conditional actions: set `scenarios` on the action in the spec's `ACTIONS` list and give
`data.ts` a resolver fixture that produces that state (see stage1-ui.md "Fixtures").

## Environment knobs

| Variable | Default | Meaning |
|---|---|---|
| `FSP_SPA_URL` | `http://localhost:5173` | Vite origin (portals + workbench + `/bff` + `/api` proxy) |
| `FSP_WIDGET_LANES` | set by the CLI | `workbench`, `portal` or both |
| `FSP_WIDGET_LIVE` | set by `--live` | `1` = never stub |
| `FSP_WIDGET_E2E_USER` / `FSP_WIDGET_E2E_PASS` | stage file `screen.user` / `fsp-admin` | portal-lane identity |
| `FSP_WIDGET_REGISTER_USER` / `_PASS` | `platform-admin` / `fsp-admin` | identity for `widgets:register-bff` (needs `ui.widget.register`) |
