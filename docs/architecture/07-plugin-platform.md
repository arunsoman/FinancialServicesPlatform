# Architecture Chapter 7 — Plugin Platform & Process/Schema Isolation

**Parent Document:** [docs/architecture.md](../architecture.md)  
**Detailed Implementation Plan:** [plans/plugin-platform-plan.md](../../plans/plugin-platform-plan.md)

---

## 1. Process & Database Isolation Mandate

Every plugin attaches **around** the non-pluggable core (RBAC, journal, audit, hierarchy, CoA are never inside a plugin) and conforms to strict isolation:

```mermaid
flowchart TB
    subgraph Host["FSP Host Edge & Core"]
        GW["Gateway :8090 — routes /v1/plugins/<code>/** after readiness check"]
        C["Core :8081 — registry + grant rows only"]
        PG_CORE[("Core DB: fsp_core<br/>Flyway V1–V...")]
        C --> PG_CORE
    end

    subgraph PluginKyc["Plugin Process 1: KYC (:8083)"]
        API_KYC["KYC REST API :8083"]
        DB_KYC[("fsp_plugin_kyc DB<br/>own Flyway history")]
        API_KYC --> DB_KYC
    end

    subgraph PluginVas["Plugin Process 2:Inventory (:8084)"]
        API_VAS["Inventory REST API :8084"]
        DB_VAS[("fsp_plugin_inventory DB<br/>own Flyway history")]
        API_VAS --> DB_VAS
    end

    GW -->|actor assertion| API_KYC
    GW -->|actor assertion| API_VAS
    API_KYC -->|on-behalf core calls| GW
    API_VAS -->|on-behalf core calls| GW
```

### Isolation Invariants
1. **Separate Standalone OS Processes:** Every plugin runs as an independent Spring Boot application in its own container or host process with dedicated port and lifecycle.
2. **Dedicated Database & Schema:** Every plugin owns its private database schema/database. Plugins NEVER connect to the core database or share tables with other plugins.
3. **Independent Flyway Migrations:** Schema migrations live entirely in the plugin repository/classpath (`db/plugin/<name>/migration/`) and run independently. Plugins never alter core schemas.
4. **Zero Cross-Database Queries:** No cross-database SQL joins or direct DB calls exist between Core and Plugins or between Plugins. Inter-service data exchange is strictly via REST API over the Gateway with actor assertions.
5. **Instant Revocation via Core DB Truth:** Plugin enable/disable flips rows in Core `plugin_registry` and `client_grants`, terminating access at the next call without requiring token blacklists.

---

## 2. KYC Plugin — The Onboarding Saga

```mermaid
sequenceDiagram
    autonumber
    participant PSP as PSP admin (portal)
    participant G as gateway
    participant K as kyc plugin :8083
    participant C as core :8081
    PSP->>G: POST /v1/plugins/kyc/cases (Idempotency-Key)
    G->>K: route (actor assertion minted: aud=plugin:kyc, htm+htu bound)
    K->>K: ActorAssertionFilter — verify ES256, single-use jti<br/>persist case (own DB, own Flyway)
    K->>K: run saga step with deterministic idempotency keys<br/>(crash-safe: resume, never duplicate)
    K->>G: on-behalf core call (machine creds + assertion Path D DELEGATED)
    G->>C: create tenant PENDING + applicant admin + (books suspended)
    C-->>K: ok (if refused → saga marks step failed, rolls forward safely)
    K-->>PSP: 201 case view (via G)
```

- **Case state machine:** `DRAFT → PENDING_REVIEW → APPROVED | REJECTED | REQUEST_INFO`
  (REQUEST_INFO → resubmit loop; four-eyes on the decision; re-opened on bank change).
- **Five-tab data capture:** business profile · operations · addresses · UBO & key management (person 1 = applicant admin, `UsernamePolicy` generates their username) · settlement bank accounts.
- **Screening (sandbox provider, `ScreeningService`):** IDENTITY + SANCTIONS checks (PASS / FAIL / MANUAL_REVIEW) decided server-side.
- **Namibian validation** (`NamibiaRules`): BIPA `^([A-Z]{2})?\d{3,10}$` (normalized), TIN `^\d{8}$`, branch code `^\d{5,6}$`, UBO threshold ≥ 25%, records kept ≥ 5 years.
- **Approval → activation:** case APPROVED ⇒ core tenant ACTIVE + books ACTIVE + CoA provisioned + audit chain.
