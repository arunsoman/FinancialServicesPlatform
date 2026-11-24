# FSP Phase-0 — Local Development & Build Guide

> **Full bootJar runbook (rebuild → deploy → start → verify, Linux + Windows):** [runbook-fsp-local.md](runbook-fsp-local.md)
> — includes per-target internal-credential envs, JDK-25 pin, profiles, start order, stop-all, health checklist, and the known-incident table (JDK mismatch, gateway catalog fail-closed, stale-checkout vs DB version skew).

## Architecture Ports & Topology

| Service | Technology | Port | Command |
|---|---|---|---|
| PostgreSQL 17 | Docker container (`fsp-postgres`) | `5432` | `make infra-up` |
| TigerBeetle 0.17.9 | Host binary (`infrastructure/bin/tigerbeetle`) | `3000` | `make tb-start` |
| IdP | Native FSP IdP, Spring Boot (Java 25) | `8082` | `make backend-idp` |
| Core | Spring Boot (Java 25, RBAC + Ledger) | `8081` | `make backend-core` |
| API Gateway | Spring Cloud Gateway (sole public origin) | `8090` | `make backend-gateway` |
| BFF | Spring Boot (Java 25, Session + CSRF) | `8080` | `make backend-bff` |
| Plugin KYC | Spring Boot (Java 25, own DB `fsp_plugin_kyc`) | `8083` | `make backend-plugin-kyc` |
| Frontend SPA | React 18 + Vite (Bun) | `5173` | `make frontend-dev` |

---

## Local Run Commands

```bash
make infra-up            # start PostgreSQL in Docker
make tb-start            # start TigerBeetle host binary (port 3000)
make backend-idp         # idp    :8082 (keys via make pki)
make backend-core        # core   :8081 (add --spring.profiles.active=client-demo for demo seed)
make backend-gateway     # gateway:8090 (catalog routes + JWT edge; start after core+idp)
make backend-bff         # bff    :8080 (session; login = BFF -> gateway -> IdP)
make backend-plugin-kyc  # plugin-kyc :8083 (KYC plugin)
make frontend-dev        # spa    :5173 (bun)
make stack-up            # OR: all services in docker (compose stack profile)
make tb-reset            # wipe TigerBeetle ledger data
```

---

## Smoke Verification Chain

1. `GET :8080/bff/csrf` — get CSRF token
2. `POST :8080/bff/auth/login` — authenticate against IdP, session cookie issued
3. `GET :8080/api/**` — BFF proxies to Gateway (`:8090`) with re-issued identity
4. Direct `:8090/v1/**` — requires `Authorization: Bearer <token from /idp/token>`; unknown routes return `404 ROUTE_NOT_IN_CATALOG`

### Credentials

- **Dev Login (System Seed):** `platform-admin` / `fsp-admin` (or `$FSP_BOOTSTRAP_PASSWORD`)
- **Demo Login (`client-demo` profile only):** `psp-admin`, `psp-ops`, `merchant-admin`, `merchant-checker` / `demo1234`
- **Building a widget or changing a menu?** Follow [widget-workflow.md](widget-workflow.md): idea → UI on test data → approval → backend → builder → preview → menus as code → deploy, plus fixes for menu and signature errors.
- **Portal shows "UI metadata tampered"?** See [ui-metadata-tampered.md](ui-metadata-tampered.md) (`cd frontend && bun run portal:check`, then restart core — it re-signs at boot).

---

## Toolchain & Build

- **Java 25:** Temurin JDK 25 via `.sdkmanrc` (`sdk env`).
- **Gradle 9.9.1:** Wrapper `./gradlew`.
- **Bun:** Frontend uses `bun` (`bun install`, `bun run lint`, `bun run typecheck`, `bun run build`).

```bash
# Backend build & test
cd backend
./gradlew check
./gradlew build

# Frontend build & check
cd frontend
bun run lint
bun run typecheck
bun run build
```
