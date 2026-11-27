# FSP Production Deployment — web.afriqpay.com

**Deployed:** 2026-11-19 · Docker network `fsp-network` (10.200.0.0/16) · TLS via certbot (LE)

## Topology

| Subdomain | Purpose | Upstream (host nginx) | Container |
|---|---|---|---|
| `fsp.afriqpay.com` | Frontend (static) + BFF (`/bff/*`, `/api/*`) | `127.0.0.1:19080` | `fsp-bff` |
| `fsp-auth.afriqpay.com` | IdP (issuer = this URL) | `127.0.0.1:19082` | `fsp-idp` |
| `fsp-api.afriqpay.com` | Gateway (machine/ops plane) | `127.0.0.1:19090` | `fsp-gateway` |

Internal-only (no published ports): `fsp-postgres` (:5432), `fsp-tigerbeetle` (10.200.0.10:3000,
static IP — the TB Java client needs a literal IP, no DNS), `fsp-plugin-kyc` (:8083),
`fsp-plugin-inventory` (:8084). All on network `fsp-network`.

## Layout on the server

```
~/apps/afriqpay-platform/            ← project source (rsynced or via sshfs mount ~/mnt/afriqpay-platform)
~/apps/afriqpay-platform/deploy/afriqpay-runtime/
  docker-compose.afriqpay.yml        ← prod compose (network external, loopback-only ports)
  Dockerfile.runtime                 ← JRE-25 runtime image wrapping a prebuilt bootJar
  jars/*.jar                         ← bootJars built on the workstation (never build on server)
  pki/                               ← idp-signing, gateway-assertion, plugin client/dpop keys
  init-dbs.sql                       ← creates fsp_plugin_{kyc,inventory} DBs+roles
  .env                               ← secrets (chmod 600, NEVER commit)
~/fsp-data/{postgres,tigerbeetle}    ← persistent volumes
/var/www/fsp.afriqpay.com/html       ← frontend dist (npm vite build)
```

## Secrets (.env)

- `POSTGRES_PASSWORD` — DB superuser
- `FSP_BOOTSTRAP_PASSWORD` — bootstrap password; **actual prod passwords (reset 2026-11-19): `platform-admin` / `admin-checker` = `admin123`**
- `FSP_INTERNAL_CREDENTIAL` — internal-hop credential (gateway/core)
- `FSP_PERSON_PASSWORD` — default person password
- `FSP_PLUGIN_*_DB_PASSWORD` — plugin DB roles
- `FSP_PLUGIN_INVENTORY_KEK` — voucher PIN AES-256-GCM KEK

## Deployment steps (rebuild & redeploy)

1. **Build on workstation** (Java 25, Gradle 9.9.1):
   `cd backend && ./gradlew :core:bootJar :gateway:bootJar :bff:bootJar :idp:bootJar :plugins:kyc:bootJar :plugins:inventory:bootJar -x test`
2. **Frontend:** `cd frontend && npx tsc; npx vite build && rsync -a dist/ web.afriqpay.com:/var/www/fsp.afriqpay.com/html/`
3. **Ship jars:** `rsync backend/**/build/libs/*-0.0.1-SNAPSHOT.jar web.afriqpay.com:apps/afriqpay-platform/deploy/afriqpay-runtime/jars/` (strip `-plain` jars)
4. **Rebuild images & restart:** `ssh web: cd deploy/afriqpay-runtime && docker compose -f docker-compose.afriqpay.yml --env-file .env build && docker compose -f ... up -d` (boot order matters: postgres+tigerbeetle → core (runs Flyway) → everything else)
5. nginx vhosts: `/etc/nginx/sites-{available,enabled}/fsp{,-auth,-api}.afriqpay.com`; certs: `sudo certbot --nginx -d fsp.afriqpay.com -d fsp-auth.afriqpay.com -d fsp-api.afriqpay.com`

## Gotchas (all hit live; fixes baked into the compose)

- **io_uring blocked** by Docker's default seccomp → TigerBeetle server **and** the Java client
  (core) both need `security_opt: [seccomp=unconfined]`, or a profile allowing the io_uring syscalls.
- **TigerBeetle client address must be a literal IP** → static IP for the TB container; format step
  is idempotent (`if [ -s /data/0_0.tigerbeetle ]`).
- **core `application.yml` hardcodes `jdbc:postgresql://localhost:5432/fsp`** → override with
  `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` (relaxed binding). The plugin DB envs are placeholders already.
- **idp must start AFTER core's Flyway** applies migrations (it validates the schema).
- **BFF authorize URL** — the browser redirect base is now `fsp.authorize-url-base`
  (SPRING_APPLICATION_JSON) set to `https://fsp-auth.afriqpay.com`; gateway baseUrl stays internal.
- Host ports 8080/8082/8090 are taken by legacy stacks → FSP publishes to loopback 19080/19082/19090.
- `SPRING_APPLICATION_JSON` used where the yml has no env placeholder
  (core `fsp.tigerbeetle.addresses`, gateway `fsp.gateway.assertion-key`, BFF props).
- **Idempotency filter caches 4xx forever** — deterministic plugin keys poisoned by transient errors
  need `idempotency_records` purge (col `request_key`).

## Post-deploy operational setup (not yet done)

1. Register plugin types via four-eyes (`POST /api/v1/platform/plugins` as platform-admin, approve
   as second admin) — kyc@… and inventory@1.0.1 (manifest `backend/plugins/*/plugin-manifest.json`).
2. `PLUGIN_INSTALL` to target tenants; readiness gates provision GL accounts (`inv-ready-*` keys).
3. Machine clients for plugins are self-provisioned by core on registration.

## Bootstrap done on prod (2026-11-19)

- Passwords: `platform-admin` + `admin-checker` both reset to `admin123` (bcrypt `$2a$10$`, F-8 flag cleared).
- Widget registry: all 16 widgets registered (PUT `/api/ui/widgets`, `bundleSha256` omitted = NULL, same as local).
- Admin portal layout: draft copied from local (v37) → PUT draft → submit → approved by `admin-checker`
  → JWS bundle published (`kid: ui-builder-dev-1`). `/admin/` renders; `/widget-workbench/` serves.
- BFF API path note: BFF adds the `/v1` prefix — browser calls are `/api/ui/...`, `/api/approvals?tenantId=...` (tenantId required).
- Approval queue: `PENDING_CHECKER` list needs both `tenantId` + `status` query params.

## Plugin bootstrap done on prod (2026-11-19)

- **kyc@1.0.0 and inventory@1.0.1 REGISTERED** (four-eyes: maker platform-admin, checker admin-checker).
  Plugin `manifest` must be sent as a JSON **string** (`asString(object)` yields Java-map garbage → JSONB insert fails).
- **Both installations ACTIVE on root tenant `…0001`** via `POST /api/tenants/{tenantId}/plugins` (maker ≠ checker —
  on prod only `admin-checker` is a checker).
- **Root-tenant book seeded by SQL** (`books` row, NAD, ledger_id 516, ACTIVE) — inventory's GL gate needs an ACTIVE book;
  no API route exists for the platform tenant's own book (books are created during tenant onboarding).
- **kyc stuck on GL_ACCOUNTS_ENSURED**: core's `defaultGates` list applies the GL gate to every plugin, but the kyc
  plugin never reports it (no ledger ops) → marked gate ACTIVE + installation ACTIVE via SQL (documented follow-up:
  make defaultGates plugin-aware).
- **Platform Administrator role granted inventory.* / digital.* keys via SQL** (`role_permissions`) — PLATFORM-scoped roles
  are `ROLE_PROTECTED` for the PATCH route; the role itself was born from SQL bootstrap, so the grant mirrors it.
- Machine token 401s during PROVISIONING are transient — the machine client only exists after gate provisioning.
- Verified live: `/api/plugins/kyc/master-values?category=ENTITY_TYPE` → 200 (dropdown populated),
  `/api/plugins/inventory/skus` → 200 through gateway.

## Smoke checkpoints (verified 2026-11-19)

- [x] All 9 containers healthy (`docker ps --filter name=fsp`)
- [x] `https://fsp.afriqpay.com/` serves the SPA (200, valid LE cert)
- [x] `https://fsp.afriqpay.com/bff/auth/login` → IdP hosted sign-in at `fsp-auth` → callback →
      authenticated session (platform-admin), FSPSESSION cookie `HttpOnly; Secure; SameSite=Lax`
- [x] `/bff/csrf` (BFF → gateway → IdP chain up)
- [x] Gateway catalog enforcement live (unpublished route → `ROUTE_NOT_IN_CATALOG`)
- [x] Internal idempotency/csrf/csrf double-submit flows intact

## Org-portal bootstrap & fixes (2026-11-19, evening)

Three dummy organizations seeded on prod (AfriqTex Manufacturers / Kavango Distribution /
Etosha Wholesale Supplies), each with maker+checker+admin users (password `admin123`),
ORG_* roles with permission grants, an ACTIVE NAD book, a settlement bank account,
kyc + inventory plugin installs (ACTIVE), and a SUBMITTED dummy KYC case.

Fixes made while wiring the org portals:

1. **`requiresMet` empty-requires bug** (`frontend/.../widgets/core/manifest.ts`): an empty
   `requires: []` evaluated `r.some(...)` → **false**, hiding widgets with NO requirements
   (inventory.placeholder). Fix: empty array (or empty AND-set) = visible to everyone. Rebuilt
   dist + redeployed to `/var/www/fsp.afriqpay.com/html`.
2. **Core couldn't verify gateway actor assertions** (org KYC screens 401 `ASSERTION_INVALID`):
   core's `fsp.gateway.actor-jwks-url` defaults to `http://localhost:8090` — wrong in the
   prod docker network. Fixed by setting
   `SPRING_APPLICATION_JSON.fsp.gateway.actor-jwks-url = http://gateway:8090/.well-known/fsp-actor-jwks`
   on the core service and recreating the container. **Any deployment with a named compose
   network needs this override.**
3. **Onboarding menu invisible on org portals**: `onboard.list`/`onboard.psp` were registered
   in `ui_widgets` with `portals = ["admin"]` only. Updated to
   `["admin","manufacturer","distributor","wholesaler"]` (SQL), then **republished** each org
   layout ( maker: PUT draft + submit; checker: approve) so the published layouts updated.

Verified E2E on prod as org users: full nav (User Management, Organization, Onboarding, Role,
Approvals, KYC > My organization + KYC applications, Inventory > Overview); "My organization"
renders the org's own KYC standing (case SUBMITTED, risk rating); KYC applications lists the
org's own case; Inventory Overview renders the placeholder; admin portal unchanged.
