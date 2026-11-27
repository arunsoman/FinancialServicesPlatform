# Portal menus ship like code

Portal menus (the navbar: menus, submenus and the widgets placed on each screen) are built in the
Portal Builder, previewed locally, committed to the repo and deployed by CI/CD with everything
else. No approval step, no manual publish.

The whole widget lifecycle, from idea to deploy, is in [widget-workflow.md](widget-workflow.md).

## The developer's loop

1. **Build** the widget (fsp-widget skill) and register it (workbench → Registry → Register).
2. **Place it** in the Portal Builder: `http://localhost:5173/widget-workbench/builder`, signed in.
   Every edit autosaves to the server draft. The top bar says "Saved to server".
3. **Preview** → **Desktop / Tablet / Mobile** (1440×900, 820×1180, 390×844). Each opens the real
   portal in its own window at that size, with your current menus and widgets and live data. It
   redraws on every edit in the builder. Sign in there as a user of that portal (for example a
   merchant user for the merchant portal). A "PREVIEW" pill marks the window, and **Exit** leaves it.
4. **Ship** with the menu-publisher skill ("publish my menus"), or by hand:
   ```bash
   make menus-publish              # export → backend/core/src/main/resources/db/seed/system/R__seed_portal_menus.sql
   cd frontend && bun run menus:check
   git add backend/core/src/main/resources/db/seed/system/R__seed_portal_menus.sql && git commit
   ```
5. **CI/CD** builds and deploys as usual. On boot every environment applies the seed, and portals read
   the published menus straight from core (`GET /api/ui/menus/{portal}`). Locally, restarting core does the same.

## What runs where

| Piece | What it does |
|---|---|
| `frontend/src/portals/signed/preview.ts` | Dev-only preview. The portal draws the builder's layout from this browser. It is compiled out of production builds. Sign-in and every API permission check still apply. |
| `scripts/export-portal-menus.sh` | Exports each portal's **newest** server layout (draft or published) and every ACTIVE widget into the seed. Fails if a menu places an unregistered widget. |
| `R__seed_portal_menus.sql` | Flyway repeatable seed. **The repo wins**: each portal's published layout and the listed widgets are set to exactly this. Unchanged rows are not touched. |
| `PortalMenuService` (core) | Serves a portal's published layout plus the widgets that work in it. Read live on every request; nothing is signed or cached. |
| `bun run menus:check` | CI gate. Checks that every seeded layout has the shape the portal accepts and that every placed widget is in the seed and in this build, at the same major version. |

## Good to know

- **The review is the pull request.** Menu changes don't go through four-eyes approval. The seed is
  reviewed code. Menus only steer navigation; core checks every API call a widget makes.
- **The builder has no Publish or Export button.** Every edit autosaves to the server draft; the
  menu publisher ships it. **Discard my changes** puts the builder back to the menus that ship
  today (the published layout). A widget the system doesn't hold yet shows **Register** in the
  palette (the widget page has a **Register in the system** button too).
- **A portal that can't load its menus** says so and offers **Try again**; one with nothing published
  says "No menu configured".
- **Removing a widget from the code:** take it off every menu first, then export. `menus:check`
  fails the build if a menu still places it.
