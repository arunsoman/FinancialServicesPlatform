# Architecture Chapter 6 — Audit, Outbox & Evidence

**Parent Document:** [docs/architecture.md](../architecture.md)

---

## 1. Write Path — One Transaction, Three Writes

```mermaid
flowchart LR
    BIZ["domain mutation<br/>(entity @Version optimistic)"] -->|"same tx"| AUD["audit row<br/>(append-only, correlationId, channel,<br/>channel_fingerprint, before/after state hash,<br/>checkerId, acting_tenant_id)"]
    BIZ -->|"same tx"| OUT["outbox row (canonical event)"]
    BIZ -->|"same tx"| PG2[(Postgres)]
    OUT -->|relay → later| BUS["events for the outside world"]
```

---

## 2. Evidence Chain

```mermaid
flowchart LR
    E1["intent acceptance"] --> E2["approval resolution"] --> E3["plan persistence"] --> E4["submission"] --> E5["commit confirmation"] --> E6["finalization"]
    E1 -.->|same correlationId| E6
```

Milestones audited at core boundaries (`AuditService`); the correlation ID ties the whole chain; while acting as a tenant every row carries `acting_tenant_id` + `act_session_id`.

---

## 3. Logging Contract (One Line Per Incident)

| Level | Money path example |
|---|---|
| ERROR + exception | `je_commit_unknown`, `je_recovery_p1_*`, TB transport failure |
| WARN | `permission_denied`, `maker_cannot_approve`, `je_transition_conflict`, TB definitive rejection |
| INFO | `je_submitted`, `coa_provisioned`, `je_posting_committed`, KYC decision |
| DEBUG | plan legs, TB specs, screening details, per-request actor resolution |

Format: single machine-parsable line with `ts/level/traceId/tenantId/userId` from MDC (`application.yml`); multi-line detail only under the `debug` profile. Secrets, tokens, DPoP proofs and identity numbers (mask last 4) are never logged.
