# FSP CI/CD — staging → production

```
feature/* ──PR──▶ staging ──PR──▶ main
             │          │              │
          ci.yml     deliver.yml     deliver.yml
     (checks only)   checks → build → checks → promote SAME images
                     → deploy        → [approval] → deploy
                     *.stage.afriqpay.com          *.afriqpay.com
                     → tag stage-YYYY.MM.DD-N      → tag + GitHub release prod-YYYY.MM.DD-N

rollback.yml (manual): environment + any release tag → those exact images again
```

| Branch | Environment | Hosts | Deploys |
|---|---|---|---|
| `staging` | staging | `fsp.stage.afriqpay.com`, `fsp-auth.stage.afriqpay.com`, `fsp-api.stage.afriqpay.com` | automatically on every push |
| `main` | production | `fsp.afriqpay.com`, `fsp-auth.afriqpay.com`, `fsp-api.afriqpay.com` | on push, after a reviewer approves — only when `PRODUCTION_DEPLOY_ENABLED=true` |

## What runs where

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | every PR into `staging`/`main`; reused by `deliver.yml` | backend `gradlew build` (all tests) · frontend lint, typecheck, vitest, `widgets:check`, `menus:check`, vite build · `docs-check` · shellcheck + compose render of this folder · gitleaks secret scan · dependency review (fails on high) |
| `codeql.yml` | PR, push, weekly | SAST for Java and TypeScript (`security-extended`) |
| `deliver.yml` | push to `staging` / `main` | checks → `_build.yml` → `_deploy.yml` |
| `_build.yml` | called | images tagged by **git tree hash**; if that tree was already built (staging), main reuses it — nothing is rebuilt for production. Jars + SPA scanned (fails on fixable CRITICAL) **before** push; SBOM + SLSA provenance attached; `release.env` = digests |
| `_deploy.yml` | called | ships the bundle over SSH, runs `scripts/deploy.sh` on the server, tags the release |
| `rollback.yml` | manual | resolves a tag's tree → its digests → deploys them with the tag's own bundle |

## On the server (`scripts/deploy.sh`)

Preflight — aborts **before changing anything** if any of these fail:
- a container name it would use belongs to a different compose project
- a loopback port it publishes is held by anything other than its own container
- the network is missing or has a different subnet
- staging: the `fsp-stage.slice` cgroup cap is missing
- production: `~/fsp-data` has no Postgres/TigerBeetle data (never boot prod empty)
- a normal deploy whose newest migration is older than the live database (branch behind prod)
- under 5 GiB free disk

Then: snapshot the hand-made stack as release `pre-pipeline` (first run only) → pull with a
throw-away docker login (the host's own logins are never touched) → `pg_dumpall` backup →
`docker compose up` → wait for every health check → smoke (IdP discovery, gateway JWKS,
`/bff/csrf` locally and over public HTTPS) → publish the SPA → mark `current`.
**Any failure after the first change re-deploys the previous release automatically.**

## Staging isolation and caps

Everything staging owns is namespaced: compose project `fsp-stage`, containers `fsp-stage-*`,
network `fsp-stage-network` (10.201.0.0/24), loopback ports 29080/29082/29090, data in
`~/fsp-stage`, its own secrets and signing keys (generated on the server, never in git or CI).

| Service | CPU ceiling | RAM ceiling | Notes |
|---|---|---|---|
| postgres | 0.25 | 256 MiB | shared_buffers 32 MB, 40 connections, no parallel workers |
| tigerbeetle | 0.25 | 1800 MiB | `--development --cache-grid=64MiB`; measured 1.48 GiB (TB allocates everything up front) |
| core | 0.5 | 512 MiB | `-Xmx256m`, pool 5 |
| idp, gateway, bff | 0.35 each | 384 MiB each | `-Xmx160m`, pool 3, 16 Tomcat threads |
| plugin-kyc, plugin-inventory | 0.25 each | 384 MiB each | same JVM profile |
| **sum of ceilings** | **2.55** | **4.39 GiB** | |
| **`fsp-stage.slice` (hard cap on the total)** | **1.5 CPUs** | **4.5 GiB** | CPUWeight/IOWeight 20 (default 100), no swap |

JVMs: Serial GC, C1 only, one visible CPU, exit on OOM. Every container: `cpu_shares 128`
(prod default 1024) and `oom_score_adj 500`, so under pressure staging yields CPU first and is
OOM-killed first. Container logs are capped at 3 × 10 MB. Expect a slow cold start (a few minutes).

## One-time setup

1. **Rotate the leaked production secrets first.** The production private keys in
   `deploy/afriqpay-runtime/pki/` and the admin passwords were committed to this **public** repo.
   Generate new keys on the server, update `.env`, then remove `deploy/afriqpay-runtime/pki/` from git.
2. **Deploy user + SSH key** (on the server, as `ubuntu`):
   `ssh-keygen -t ed25519 -N '' -f fsp-deploy -C fsp-github-deploy` → append `fsp-deploy.pub`
   to `~/.ssh/authorized_keys`. Known hosts: `ssh-keyscan -t ed25519 web.afriqpay.com`.
3. **GitHub → Settings → Environments**
   - `staging`: secrets `DEPLOY_HOST`, `DEPLOY_USER` (`ubuntu`), `DEPLOY_SSH_KEY` (private key),
     `DEPLOY_KNOWN_HOSTS`; deployment branches: `staging` only.
   - `production`: same four secrets; **Required reviewers** (not the person merging); prevent
     self-review; deployment branches: `main` and tags `prod-*`.
4. **Branch protection** for `staging` and `main`: require PR, 1 approval, Code Owners review,
   status checks `backend`, `frontend`, `docs`, `security`, `deploy-config`,
   `analyze (java-kotlin)`, `analyze (javascript-typescript)` (path-skipped jobs count as passed);
   no force pushes. `main` accepts PRs from `staging` (and hotfix branches) only.
5. **Staging host bootstrap** (once; creates secrets, keys, network, slice, nginx vhosts, TLS):
   ```
   scp -r deploy/pipeline web.afriqpay.com:/tmp/fsp-pipeline
   ssh -t web.afriqpay.com 'bash /tmp/fsp-pipeline/scripts/stage-bootstrap.sh'
   ```
6. Push `staging` → first staging deploy. Then do the same plugin registration / bootstrap steps
   on staging as on production (`deploy/afriqpay-runtime/README-deployment.md`, four-eyes).
7. **Production** stays off until `main` contains what production runs today (production was
   hand-deployed from a newer branch). Then set repository variable `PRODUCTION_DEPLOY_ENABLED=true`.

## Rolling back

Actions → **rollback** → environment, tag (e.g. `prod-2026.12.01-42`), reason.
Production only accepts `prod-*` tags and needs the same approval as a deploy.

- Images, SPA, compose and config: exactly as at that tag.
- Database: **not** rolled back. Migrations must be expand/contract; core ignores `*:future`
  migrations, so an older core runs on a newer schema. Before every deploy a `pg_dumpall` is kept in
  the backup directory (`~/fsp-stage/backups`, `~/fsp-backups/production`) for a deliberate restore.
- TigerBeetle: append-only, never rolled back. Fix bad postings with reversing journal entries.

## Not covered yet

- Full Playwright e2e against a live stack in CI (needs a scripted bootstrap of plugins/menus).
- OpenID FAPI 2.0 conformance suite and OWASP ZAP baseline against staging (nightly).
- TigerBeetle data-file backup (needs a brief stop; Postgres is backed up on every deploy).
- An SSH `command=` restriction on the deploy key.
