# Architecture Chapter 10 — Data Model & Storage

**Parent Document:** [docs/architecture.md](../architecture.md)

---

## 1. ER Overview (Core DB, Flyway V1–V31)

```mermaid
erDiagram
    TENANT ||--o{ PERSON : has
    PERSON ||--o{ USER_ROLE : holds
    USER_ROLE }o--|| ROLE : is
    ROLE ||--o{ ROLE_PERMISSION : grants
    ROLE_PERMISSION }o--|| PERMISSION : references
    TENANT ||--o{ BOOK : owns
    BOOK ||--o{ GL_ACCOUNT : contains
    BOOK ||--o{ JOURNAL_ENTRY : receives
    JOURNAL_ENTRY ||--o{ JOURNAL_LINE : contains
    JOURNAL_ENTRY ||--o{ APPROVAL : "may open"
    PERSON ||--o{ IDEMPOTENCY_RECORD : scopes
    TENANT ||--o{ ACCOUNTING_PERIOD : partitions
    PERSON ||--o{ ACT_AS_SESSION : "sudo sessions"
    TENANT ||--o{ IDP_CONFIG : "IdP policy"
    TENANT ||--o{ OUTBOX : emits
    TENANT ||--o{ AUDIT : evidence
    OAUTH_CLIENT ||--o{ CLIENT_GRANT : "machine plane"
```

---

## 2. Migration Ledger

```mermaid
timeline
    title Core DB migrations (next free number: V32 — psp/emi/recon run their own Flyway histories in their own DBs)
    V1 : core schema — tenants, persons, RBAC, books, JE, approvals
    V2 : idempotency_records
    V3 : idp_configs / idp_identities
    V4–V7 : plugin platform identity + registry, outbox, audit hardening
    V8 : widget harness + signed portal delivery (removed in V42)
    V9–V10 : plugin registry install/enable, core KYC retired
    V14 : act_as_sessions
    V16 : person checker flag
    V19 : role.view + PSP sub-type back-fill + anchor-role template back-fill + provisioned-checker flag
    V23 : notifications + push (fsp_notifications schema, chapter 12)
    V29 : notification channels (email/SMS, chapter 12)
    V30 : auth plane (password reset + MFA codes, chapter 02 §7)
    V31 : push_subscriptions @Version column
```

The authoritative, always-current list is the **generated ledger**
(`docs/generated/migrations.md`, refreshed by `node docs/gen/refresh.cjs` and enforced by
`make docs-check`) — this timeline is narrative context only.

Rules:
- Applied migrations are **immutable** — never edit an existing migration; write a new one.
- Demo data exists **only** under `db/seed/demo` gated by the `client-demo` profile (never staging/production).
- System seed (`db/seed/system`) runs in every environment.
- Plugin DBs (e.g. `fsp_plugin_kyc`) have their **own separate Flyway history** — plugins never alter core tables.
