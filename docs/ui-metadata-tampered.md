# Runbook — "UI metadata tampered — this portal will not load"

Works the same on **Linux, macOS and Windows**. Every step is a `bun run …` command from
`frontend/`. You don't need `make`, bash, openssl or python, and no step changes per OS.

> **Before you fix anything:** this screen is a security control. It means the browser could not
> verify the portal layout's ES256 signature against the key pinned into the SPA. On a **dev
> machine** the cause is almost always key drift (below). On a **shared, staging or production
> environment**, do **not** re-sign. Capture the *Evidence id* from the card and escalate.
> Re-signing would hide real tampering.

---

## 1. How the check works (30 seconds)

```
infrastructure/pki/dev/ui-builder-signing.pem      core SIGNS portal bundles with this (loaded at core startup)
infrastructure/pki/dev/ui-builder-signing-pub.pem  its public half
frontend/src/portals/signed/trust-store.json       the SPA VERIFIES against this pinned public key (kid ui-builder-dev-1)
Postgres (core schema)                             the published bundle + its stored signature
```

The portal renders only when all three agree: the stored bundle was signed by the key core
holds, and that key's public half is the one pinned in `trust-store.json`.

## 2. Why it happens on dev machines

| Cause | Typical trigger | Card reason |
|---|---|---|
| **A. Stored bundle signed by an older key** | `make pki` / `dev-windows.ps1 pki` was re-run (it regenerates keys if they are missing), or the DB came from another machine | *Signature does not validate* |
| **B. SPA pins someone else's key** | `trust-store.json` is a **tracked** file. You pulled a teammate's pin, or never ran the pki step after cloning | *Signature does not validate* |
| **C. No menus for this portal** | Fresh database whose seed has no layout for the portal, or core has not booted since the seed was applied | *No menu configured* |
| **D. Core still holds the old key** | Keys regenerated while core was running (core reads them only at startup) | *Signature does not validate* (persists after re-signing) |

## 3. Fix (all OSes)

Prerequisites: the full stack is up (Postgres, TigerBeetle, IdP, core, gateway, BFF, Vite on
`:5173`). See `docs/README-dev.md` (Linux/macOS) or `docs/windows-dev.md` (Windows).

```bash
cd frontend

# 1. Diagnose. Tells you whether the SPA pin matches this machine's signing key.
bun run portal:check

# 2. Only if step 1 reported a DIFFERENT key (cause B): re-pin the SPA to your key.
bun run portal:pin

# 3. Restart core (causes A/C/D). At boot it re-signs every portal bundle that is missing,
#    stale, or signed by another key (UiBundleBootSync: `ui_bundle_boot_signed` in its log).
```

5. Reload `http://localhost:5173/admin` and sign in again. The console should render.

If `portal:check` reports **missing keys**, generate them once and restart core:

| OS | Command (repo root) |
|---|---|
| Linux / macOS | `make pki` |
| Windows | `powershell -File scripts\dev-windows.ps1 pki` (uses Git for Windows' bash) |

Then run steps 1 to 5 again.

## 4. Things not to do

- **Don't commit your `trust-store.json` change** from `portal:pin` or the pki step. It pins
  *your* machine's key, and committing it causes cause B for everyone else. Leave the diff local.
- **Don't edit the bundle in the database** or hand-craft a signature. Restart core: it signs
  through the same path as production.
- **Don't delete `ui-builder-signing.pem` casually.** A new key invalidates every bundle; core
  re-signs them on its next start.
- **Don't treat this as a dev-only nuisance outside dev.** See the warning at the top.

## 5. Reference

| Command | What it does |
|---|---|
| `bun run portal:check` | Verifies the key pair belongs together and that `trust-store.json` pins it. Reads public material only. |
| `bun run portal:pin` | Rewrites `trust-store.json` from `ui-builder-signing-pub.pem` (same output as `dev-pki.sh`). |
| restart core | Signs every portal bundle that is missing, stale or signed by another key (`UiBundleBootSync`). |

Implementation: `frontend/tools/portal-trust.ts` (Bun + `node:crypto`). Menus ship with the code:
`docs/portal-menus.md`. Design: `plans/signed-portal-delivery-plan.md`.
Seeded credentials: `platform-admin` / `admin-checker`, password `fsp-admin` or `$FSP_BOOTSTRAP_PASSWORD`.
