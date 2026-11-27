# FSP Local Runbook — Rebuild, Deploy, Start (Linux + Windows)

**Last updated:** 2026-11-22
**Scope:** rebuild bootJars, restart all seven services, run the UI (dev + prod build), and the health checks that prove it works.
**Cross-references:** `docs/README-dev.md` · `docs/setup-guide.md` (§5 start order, §5 internal-credential table, troubleshooting) · `Makefile`

---

## 0. Topology & ports

| Component | Port | Notes |
|---|---|---|
| API Gateway | **8090** | SOLE public origin. All external traffic enters here. |
| BFF | **8080** | Confidential client; owns the `HttpOnly` session cookie (`/bff/csrf` for CSRF). |
| core | **8081** | SOLE policy decision point (RBAC, four-eyes, ledger mutations). |
| idp | **8082** | RS256 JWT + DPoP issuer login/throttle/MFA. |
| psp | **8085** | profile `demo-psp --psp-demo-sweep` |
| emi | **8086** | profile `demo-emi` |
| recon | *—* | no profile; invariants sweeper |
| TigerBeetle | **3000** | authoritative booked positions (`infrastructure/bin/tigerbeetle`) |
| PostgreSQL | **5432** | databases `fsp` (core), `fsp_emi`, `fsp_psp` — container `fsp-postgres` |
| UI dev | **5173** | vite; portals `/admin` `/manufacturer` `/distributor` `/wholesaler` `/merchant` + `/widget-workbench` |

**Start order (strict):** PostgreSQL + TigerBeetle → core + idp → emi + psp + bff + recon → **gateway LAST**
(gateway polls `/internal/ops/catalog` on core at boot and exits fail-closed `gateway_boot_failed_empty_catalog` if core isn't green yet).

---

## 1. Toolchain pin (do not skip)

| Machine | Requirement | Gotcha |
|---|---|---|
| Linux (this dev box) | `JAVA_HOME=~/.sdkman/candidates/java/25.0.5.0+8-tem` | plain `java` resolves to **Java 21** → every service crashes with `UnsupportedClassVersionError` (class file 69.0 vs 65.0) |
| Windows (Baskar) | `java -version` must say `25.0.5` | `JAVA_HOME` must point at the JDK-25 install; check before `gradlew` |
| Build tool | Gradle wrapper `./gradlew` (9.9.1), Spring Boot 4.0.9 | Linux: `./gradlew`, Windows: `gradlew.bat` |

---

## 2. Build all bootJars

```bash
# Linux (run from the FSP project root)
cd backend
JAVA_HOME=~/.sdkman/candidates/java/25.0.5.0+8-tem ./gradlew clean bootJar --parallel
# artifacts: <module>/build/libs/<module>-0.0.1-SNAPSHOT.jar  (bff, core, emi, gateway, idp, psp, recon)
```

```powershell
# Windows (run from the FSP project root)
cd backend
.\gradlew.bat clean bootJar --parallel
```

**Before building — source tree freshness rule (the Baskar incident):**
The jar and the database must come **as a pair**. If the checkout is stale, Flyway inside the new jar stops at an
old version (e.g. latest available migration 25) while the shared DB sits at a newer one (e.g. 33) → then
Hibernate `validate` fails with `missing table [notifications]`. Verify the migration folder reaches **V33** first:

```bash
ls backend/core/src/main/resources/db/migration/ | sort -V | tail -3   # must show V31, V32, V33
```

---

## 3. Internal-plane credentials (fail-closed zero trust)

Every service validates inbound internal hops against its own secret; callers carry the **per-target** copy.
Unset = `INTERNAL_PLANE_CLOSED credential_not_configured` (and gateway won't boot).

| Variable | Holds the inbound secret of | Who must carry it |
|---|---|---|
| `FSP_INTERNAL_CREDENTIAL` | core | gateway, recon-worker |
| `FSP_INTERNAL_CREDENTIAL_EMI` | emi | core, gateway, recon-worker |
| `FSP_INTERNAL_CREDENTIAL_PSP` | psp | gateway, recon-worker |
| `FSP_INTERNAL_CREDENTIAL_IDP` | idp | gateway |

Dev default value (matches `infrastructure/docker-compose.yml` fallback): **`dev-internal-credential`**
(every variable gets that same value in dev). Export **all five** on every launching shell — services ignore
the vars that aren't theirs.

---

## 4. Linux quick start (this box) — full sequence

```bash
# 0) infra (skip if already running)
# run from the FSP project root
make infra-up                 # PostgreSQL in Docker
make tb-start                 # TigerBeetle on :3000

# 1) build (§2)
cd backend
JAVA_HOME=~/.sdkman/candidates/java/25.0.5.0+8-tem ./gradlew clean bootJar --parallel

# 2) envs + pinned JDK
export FSP_INTERNAL_CREDENTIAL=dev-internal-credential
export FSP_INTERNAL_CREDENTIAL_CORE=$FSP_INTERNAL_CREDENTIAL
export FSP_INTERNAL_CREDENTIAL_EMI=$FSP_INTERNAL_CREDENTIAL
export FSP_INTERNAL_CREDENTIAL_PSP=$FSP_INTERNAL_CREDENTIAL
export FSP_INTERNAL_CREDENTIAL_IDP=$FSP_INTERNAL_CREDENTIAL
J=~/.sdkman/candidates/java/25.0.5.0+8-tem/bin/java     # NEVER plain `java`

# 3) wave 1 — core + idp
nohup $J -jar core/build/libs/core-0.0.1-SNAPSHOT.jar         --spring.profiles.active=demo       > /tmp/fsp-core.log    2>&1 &
nohup $J -jar idp/build/libs/idp-0.0.1-SNAPSHOT.jar           --spring.profiles.active=demo       > /tmp/fsp-idp.log     2>&1 &

# wait until core is green (REQUIRED before gateway)
for i in $(seq 1 30); do curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health | grep -q 200 && break; sleep 4; done

# 4) wave 2 — emi, psp, bff, recon
nohup $J -jar emi/build/libs/emi-0.0.1-SNAPSHOT.jar           --spring.profiles.active=demo-emi   > /tmp/fsp-emi.log     2>&1 &
nohup $J -jar psp/build/libs/psp-0.0.1-SNAPSHOT.jar           --spring.profiles.active=demo-psp --psp-demo-sweep > /tmp/fsp-psp.log 2>&1 &
nohup $J -jar bff/build/libs/bff-0.0.1-SNAPSHOT.jar           --spring.profiles.active=demo       > /tmp/fsp-bff.log     2>&1 &
nohup $J -jar recon/build/libs/recon-0.0.1-SNAPSHOT.jar                                           > /tmp/fsp-recon.log   2>&1 &

# 5) wave 3 — gateway LAST
nohup $J -jar gateway/build/libs/gateway-0.0.1-SNAPSHOT.jar   --spring.profiles.active=demo       > /tmp/fsp-gateway.log 2>&1 &
```

---

## 5. Windows quick start (PowerShell) — same sequence

```powershell
# run from the FSP project root
cd backend

# envs (same names, same dev value; do this in the SAME shell as the java calls)
$env:FSP_INTERNAL_CREDENTIAL       = "dev-internal-credential"
$env:FSP_INTERNAL_CREDENTIAL_CORE  = "dev-internal-credential"
$env:FSP_INTERNAL_CREDENTIAL_EMI   = "dev-internal-credential"
$env:FSP_INTERNAL_CREDENTIAL_PSP   = "dev-internal-credential"
$env:FSP_INTERNAL_CREDENTIAL_IDP   = "dev-internal-credential"

# wave 1
Start-Process -NoNewWindow java -ArgumentList '-jar','core\build\libs\core-0.0.1-SNAPSHOT.jar','--spring.profiles.active=demo'   -RedirectStandardOutput core.log   -RedirectStandardError core-err.log
Start-Process -NoNewWindow java -ArgumentList '-jar','idp\build\libs\idp-0.0.1-SNAPSHOT.jar','--spring.profiles.active=demo'     -RedirectStandardOutput idp.log     -RedirectStandardError idp-err.log

# wait for core (actuator 200), then wave 2
Start-Process -NoNewWindow java -ArgumentList '-jar','emi\build\libs\emi-0.0.1-SNAPSHOT.jar','--spring.profiles.active=demo-emi' -RedirectStandardOutput emi.log -RedirectStandardError emi-err.log
Start-Process -NoNewWindow java -ArgumentList '-jar','psp\build\libs\psp-0.0.1-SNAPSHOT.jar','--spring.profiles.active=demo-psp','--psp-demo-sweep' -RedirectStandardOutput psp.log -RedirectStandardError psp-err.log
Start-Process -NoNewWindow java -ArgumentList '-jar','bff\build\libs\bff-0.0.1-SNAPSHOT.jar','--spring.profiles.active=demo'     -RedirectStandardOutput bff.log     -RedirectStandardError bff-err.log
Start-Process -NoNewWindow java -ArgumentList '-jar','recon\build\libs\recon-0.0.1-SNAPSHOT.jar'                                 -RedirectStandardOutput recon.log   -RedirectStandardError recon-err.log

# wave 3
Start-Process -NoNewWindow java -ArgumentList '-jar','gateway\build\libs\gateway-0.0.1-SNAPSHOT.jar','--spring.profiles.active=demo' -RedirectStandardOutput gateway.log -RedirectStandardError gateway-err.log
```

Profiles recap: core/idp/bff/gateway `demo` · emi `demo-emi` · psp `demo-psp --psp-demo-sweep` · recon none.
**Never start a service without its profile** — "No active profile set" means no demo seed / wrong defaults.

---

## 6. UI

```bash
# run from the FSP project root
cd frontend
bun install                 # once, or after lockfile change
bun run dev                 # vite :5173 — proxies /api → :8080 (BFF) and /bff → :8080
# prod build + serve
bun run build               # tsc --noEmit && vite build → dist/
bun run preview             # serve dist/ (default :4173)
```

URLs (dev): `http://localhost:5173/admin` · `/manufacturer` · `/distributor` · `/wholesaler` · `/merchant`
Widget workbench: `http://localhost:5173/widget-workbench/`

---

## 7. Stop everything

```bash
# Linux
pkill -f 'core-0.0.1-SNAPSHOT|idp-0.0.1-SNAPSHOT|emi-0.0.1-SNAPSHOT|psp-0.0.1-SNAPSHOT|gateway-0.0.1-SNAPSHOT|bff-0.0.1-SNAPSHOT|recon-0.0.1-SNAPSHOT'
# (also stops nothing but java; TigerBeetle + Postgres keep running — intended)
```

```powershell
# Windows
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'SNAPSHOT\.jar' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

---

## 8. Health / verification checklist

```bash
# ports (6 UP expected: 8080 8081 8082 8085 8086 8090)
for p in 8080 8081 8082 8085 8086 8090; do lsof -iTCP:$p -sTCP:LISTEN -P -n >/dev/null && echo "$p UP" || echo "$p DOWN"; done

# actuator (core answers 200; others don't expose /actuator/health — 404 is FINE, the port listening is the signal)
curl -s -o /dev/null -w "core   %{http_code}\n" http://localhost:8081/actuator/health
curl -s -o /dev/null -w "bff    %{http_code}\n" http://localhost:8080/bff/csrf          # 200 expected
curl -s -o /dev/null -w "5173   %{http_code}\n" http://localhost:5173/bff/csrf          # 200 via vite proxy
curl -s -o /dev/null -w "5173api %{http_code}\n" http://localhost:5173/api/ui/session    # 401 expected if signed out

# logs: zero errors expected
for s in core idp emi psp bff recon gateway; do echo "$s: $(grep -c 'level=ERROR' /tmp/fsp-$s.log) errors"; done
```

---

## 9. Known incidents & fixes (learned the hard way)

| Incident | Signature | Fix |
|---|---|---|
| Wrong runtime JDK | `UnsupportedClassVersionError: ... class file version 69.0, ... up to 65.0` | launch with `~/.sdkman/candidates/java/25.0.5.0+8-tem/bin/java` explicitly — never plain `java` |
| Gateway boots fail-closed | `gateway_boot_failed_empty_catalog` + core logs `internal_plane_closed ... credential_not_configured` | export all five `FSP_INTERNAL_CREDENTIAL*` on the launching shell; start core BEFORE gateway |
| Stale checkout vs shared DB | Flyway: `Schema "public" has a version (N) that is newer than the latest available migration (M)` then Hibernate `missing table [...]` | `git pull` until migrations reach V33, rebuild, then reset the dev DB (`DROP DATABASE fsp; CREATE DATABASE fsp;`) so a correct jar re-migrates 0→33. Jar and DB travel as a pair. |
| No profile set | log: `No active profile set, falling back to "default"` | always pass `--spring.profiles.active=...` (§ table above) |
| vitest under bun runtime | `TypeError: undefined is not an object (evaluating 'z.object')` in ANY suite importing zod | frontend `package.json` test script is pinned to `bunx vitest run` (NOT `bunx --bun vitest`); bare `bunx vitest` watches forever |
| Gateway 404 `ROUTE_NOT_IN_CATALOG` after adding an operation | new `@FspOperation` unseen | restart core (boot gate re-inventories); gateway refreshes within 60 s |

---

## 10. One-liners for restarting a single service (Linux)

```bash
# run from the FSP project root
cd backend
J=~/.sdkman/candidates/java/25.0.5.0+8-tem/bin/java
export FSP_INTERNAL_CREDENTIAL=dev-internal-credential FSP_INTERNAL_CREDENTIAL_CORE=$FSP_INTERNAL_CREDENTIAL FSP_INTERNAL_CREDENTIAL_EMI=$FSP_INTERNAL_CREDENTIAL FSP_INTERNAL_CREDENTIAL_PSP=$FSP_INTERNAL_CREDENTIAL FSP_INTERNAL_CREDENTIAL_IDP=$FSP_INTERNAL_CREDENTIAL

# e.g. restart gateway
pkill -f gateway-0.0.1-SNAPSHOT ; sleep 2
nohup $J -jar gateway/build/libs/gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo > /tmp/fsp-gateway.log 2>&1 &
```
