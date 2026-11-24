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
monitoring.yml: deploy/monitoring → Grafana + Loki + Prometheus + Alloy (one stack, both environments)
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
| `monitoring.yml` | push to `staging` touching `deploy/monitoring/`, or manual | syncs and (re)starts the monitoring stack |
| `ci.yml › plugins` | every PR | plugin manifest gate (version bump; minor/patch may only add) + plugin folders generate a valid bundle |

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
   The plugin client keys are the ones in the committed manifests (`client.jwks`), so after rotating
   them Grafana shows `key_mismatch` for each plugin: register the new per-environment manifest
   (maker + checker) to finish the rotation.
2. **Deploy user + SSH key** (on the server, as `ubuntu`):
   `ssh-keygen -t ed25519 -N '' -f fsp-deploy -C fsp-github-deploy` → append `fsp-deploy.pub`
   to `~/.ssh/authorized_keys`. Known hosts: `ssh-keyscan -t ed25519 web.afriqpay.com`.
3. **GitHub → Settings → Environments**
   - `staging`: secrets `DEPLOY_HOST`, `DEPLOY_USER` (`ubuntu`), `DEPLOY_SSH_KEY` (private key),
     `DEPLOY_KNOWN_HOSTS`; deployment branches: `staging` only.
   - `production`: same four secrets; **Required reviewers** (not the person merging); prevent
     self-review; deployment branches: `main` and tags `prod-*`.
4. **Branch protection** for `staging` and `main`: require PR, 1 approval, Code Owners review,
   status checks `backend`, `frontend`, `docs`, `security`, `deploy-config`, `plugins`,
   `analyze (java-kotlin)`, `analyze (javascript-typescript)` (path-skipped jobs count as passed);
   no force pushes. `main` accepts PRs from `staging` (and hotfix branches) only.
5. **Staging host bootstrap** (once; creates secrets, keys, network, slice, nginx vhosts, TLS):
   ```
   scp -r deploy/pipeline web.afriqpay.com:/tmp/fsp-pipeline
   ssh -t web.afriqpay.com 'bash /tmp/fsp-pipeline/scripts/stage-bootstrap.sh'
   ```
6. **Monitoring bootstrap** (once; slice, secrets, nginx + TLS for `fsp-monitor.stage` and
   `prom.stage`, staging log timer, cron):
   ```
   rsync -a --delete deploy/monitoring/ web.afriqpay.com:fsp-monitor/stack/
   ssh -t web.afriqpay.com 'bash ~/fsp-monitor/stack/scripts/monitor-bootstrap.sh'
   ```
   Later changes deploy through `monitoring.yml`.
7. Push `staging` → first staging deploy. Then do the same plugin registration / bootstrap steps
   on staging as on production (`deploy/afriqpay-runtime/README-deployment.md`, four-eyes).
8. **Production** stays off until `main` contains what production runs today (production was
   hand-deployed from a newer branch). Then set repository variable `PRODUCTION_DEPLOY_ENABLED=true`.

## Plugins

Every `backend/plugins/<code>/` with a `plugin-manifest.json` **and** a `deploy.json` is a plugin.
Nothing else in the pipeline names a plugin — `gen-plugins.py` generates its container, its own
database and role, its keys and its staging caps. Adding a plugin = add the folder, open a PR.

```json
// backend/plugins/<code>/deploy.json
{ "port": 8085, "secrets": ["FSP_PLUGIN_<CODE>_API_KEY"], "staging": { "cpus": 0.25, "mem": "384m", "heap": "160m" } }
```

| Concern | Owner | How |
|---|---|---|
| code, manifest, migrations | the plugin's developers (CODEOWNERS line per plugin) | PR → `ci › plugins` gate |
| process, image, database + role, keys, caps, health, rollback | the pipeline | automatic. Staging creates missing secrets/keys; production stops and names what to provision in `.env` / `pki/` |
| **registering a manifest version** (what the gateway routes) | platform admins, **two different people, per environment** | admin portal › Plugins › Register (maker) → Approvals (checker). Never automated: AGENTS.md §2.1 forbids machine makers/checkers |

**Drift and upgrades.** After every deploy and every 5 minutes, `observe.py` compares each running
plugin with what is `REGISTERED` in that environment and publishes the result to Grafana
(*FSP releases & plugins*), the deploy run summary, and the `FspPluginDrift` alert:

| State | Meaning | Action |
|---|---|---|
| `in_sync` | registered manifest = shipped manifest with this environment's key | — |
| `behind` | older version registered; N new operations not routed | register the shipped version |
| `not_registered` | process runs, nothing registered | register it (fresh staging starts here) |
| `pending_approval` | shipped version submitted, waiting for the checker | checker approves |
| `ahead` | newer version registered (after a rollback) | register the older version, or roll forward |
| `key_mismatch` | same version, registered key is not this environment's | register again (after a key rotation) |

The manifest to register is **per environment**: the committed manifest with that environment's
live public key (`/.well-known/jwks.json` of the plugin) in `client.jwks`. Grafana links to it
(`https://fsp-monitor.stage.afriqpay.com/manifests/<env>/<code>-<version>.json`, basic auth) —
every version ever shipped to that environment is listed, so you can register any of them
(upgrade or downgrade). Paste it into the Register form; the checker sees the same SHA-256.

## Monitoring, logs and disk hygiene

| URL | What | Login |
|---|---|---|
| `https://fsp-monitor.stage.afriqpay.com` | Grafana: *FSP releases & plugins* (home), *FSP logs*, *FSP containers & disk* | `admin` / `GRAFANA_ADMIN_PASSWORD` in `~/fsp-monitor/.env` |
| `https://prom.stage.afriqpay.com` | Prometheus (metrics, alert rules) | `fsp-ops` / `OPS_BASIC_AUTH_PASSWORD` |

- **Logs:** Alloy ships the logs of FSP containers only (`fsp-stage`, `afriqpay-runtime`,
  `fsp-monitor`) to Loki, labelled `env`, `service`, `level`. Other stacks on the host are never read.
- **Metrics:** CPU / RAM against each container's cap, OOM kills, network, host disk, FSP
  directory sizes, release + plugin drift. Alert rules: disk < 15 %, container > 90 % of its cap,
  OOM kill, plugin drift > 30 min, observer stale.
- **Rollback page:** *FSP releases & plugins* lists what runs where, staging-vs-production drift and
  every release kept on the server; *Roll back to this release* opens the GitHub rollback workflow
  (GitHub cannot pre-fill its form: paste the release name shown).

Disk hygiene — nothing grows without bound:

| What | Staging | Production |
|---|---|---|
| Loki logs | deleted after **4 days** | 30 days |
| Docker log files per container | 2 × 5 MB; files idle > 4 days deleted hourly (root timer, staging containers only) | 3 × 10 MB |
| Postgres backups | > 4 days deleted (newest always kept) | > 30 days deleted (≥ 7 kept) |
| Release bundles on the server | > 4 days deleted (never `current` or the one before) | > 30 days |
| Per-environment plugin manifests | > 4 days (live ones refreshed every 5 min) | > 30 days |
| Prometheus | 15 days, max 2 GB | (same stack) |
| Monitoring stack itself | `fsp-monitor.slice`: 2 GiB RAM, 1 CPU, lowest weight | |

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
- One-click rollback from Grafana (needs a GitHub token stored in Grafana); today it opens the form.
- JVM metrics (heap, GC, HTTP latency) — needs micrometer-registry-prometheus in the services.
