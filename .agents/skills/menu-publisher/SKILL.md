---
name: menu-publisher
description: Ships the portal menus a developer built in the Portal Builder — exports them from the local database into the repo seed (R__seed_portal_menus.sql), checks they render with this build, summarises what changed in plain words, and commits them (push / PR on request). CI/CD then deploys them like any other code; every environment applies the seed and core signs the menus at boot. Use when the user says publish/ship/release/commit the menus, the navbar, the portal layout or "what I built in the builder".
---

# menu-publisher — builder menus → repo → every environment

Portal menus are code (docs/portal-menus.md). The developer builds and previews them locally;
this skill turns that into a commit. There is **no approval step and no manual signing**: the
pull request is the review, the deploy applies the seed, core signs at boot.

## 1. Preconditions (check, don't assume)

```bash
docker ps --format '{{.Names}}' | grep -x fsp-postgres     # the local DB the builder saved to
git status --short                                          # note unrelated changes — never commit them
git branch --show-current                                   # on main? create a branch first (ask for a name if unclear)
```

- The builder **autosaves** each edit to the server draft (top bar: "Saved to server"). If the
  user says it shows "Not saved: …" or "Sign in to save", stop and tell them — the export reads
  the server, not their browser.
- Never use `git stash` (the tree is shared with other sessions and a live Vite).

## 2. Export

```bash
scripts/export-portal-menus.sh            # or: make menus-publish
```

Per portal it takes the NEWEST layout (draft if newer than published). It fails when a menu
places a widget core has not registered — tell the user which ones and to press **Register** in
the widget workbench's Registry, then re-run. Never hand-edit the seed.

## 3. Check it renders with this build

```bash
cd frontend && bun run menus:check
```

Any failure is a real deploy break (widget missing from the build, major-version mismatch,
menu without submenus). Report it; don't commit.

## 4. Tell the user what changed — in words, not SQL

```bash
git --no-pager diff --stat -- backend/core/src/main/resources/db/seed/system/R__seed_portal_menus.sql
git --no-pager diff -U0 -- backend/core/src/main/resources/db/seed/system/R__seed_portal_menus.sql
```

Summarise per portal: menus/submenus added, removed, renamed or moved; widgets placed or
removed; widget versions changed. Example: "merchant: + Inventory › Stock levels (inventory.stock);
admin: 'Back Office User' renamed 'Staff'". If the diff is empty, say there is nothing to ship
and stop.

## 5. Commit (and push / PR only if asked)

Stage ONLY the seed, plus widget code the user says belongs to this change:

```bash
git add backend/core/src/main/resources/db/seed/system/R__seed_portal_menus.sql
git commit -m "feat(menus): <one-line summary from step 4>"
```

Push / open a PR only when the user asked for it (`gh pr create`, body = the step-4 summary).

## 6. What happens next (say it in one line)

CI runs `bun run menus:check` with the rest of the build; on deploy Flyway applies the seed
(the repo wins over whatever that environment had) and core signs each changed portal at boot
(`ui_bundle_boot_signed` in the core log). Locally, restarting core does the same.
