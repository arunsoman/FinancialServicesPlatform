# Architecture Chapter 4 — The Money Engine (Books, CoA, JE & TigerBeetle)

**Parent Document:** [docs/architecture.md](../architecture.md)

---

## 1. Tenancy ≠ Entity ≠ Book ≠ Ledger Chain

```mermaid
flowchart LR
    TEN["Tenant"] --> LE["Legal entity"]
    LE --> BOOK["Book (bookId, currency)"]
    BOOK --> LED["TigerBeetle ledger<br/>id = ISO-4217 numeric code<br/>(e.g. NAD → 516)"]
    LED --> ACC["TB account 1:1 with GL account<br/>uuid5(platformNamespace, book:glAccountId)"]
    GLA["GL account (code, accountType, normalBalance, postingAllowed, balanceBound)"] --> ACC
```

- One book per (tenant, legal entity, currency); shared TB ledger **per currency**.
- TB accounts carry `user_data_128 = glAccountId`, `user_data_64 = tenantHash`, `user_data_32 = accountTypeOrdinal`.
- Provisioning model (accounts *and* journal legs): **persist plan → submit to TB → confirm → activate**. Any TB rejection rolls back the Postgres transaction; re-runs are safe.

---

## 2. Chart of Accounts (Seeded Templates)

```mermaid
flowchart TB
    subgraph PSP["PSP CoA"]
        A1300["1300 Acquirer Clearing"] --- A2100["2100 Merchant Payable"]
        A2200["2200 Chargeback Payable"] --- A1150["1150 Merchant Reserve"]
        A2110["2110 Payouts in Transit"] --- A1350["1350 Network Fees Payable"]
        A4100["4100 MDR Revenue"] --- A5100["5100 Interchange Expense"]
        A5200["5200 Scheme Fee Expense"] --- A1000P["1000 Cash at Bank"]
    end
    subgraph MER["Merchant CoA"]
        M2000["2000 Customer Deposits"] --- M1000["1000 Cash at Bank"] --- M4000["4000 Fee Revenue"]
    end
```

---

## 3. Journal Entry Anatomy & Posting Planner

A JE = `entryId, tenantId, bookId, periodId, currency, lines[] (glAccountId, direction DEBIT|CREDIT, amount minor units, memo), totalDebits/totalCredits, status, planHash, tbTransferIds[], makerId, checkerId`.

Balancing is *by construction* (create API rejects unbalanced input). The planner turns lines into **legs** by *direct debit-to-credit matching* (never "star"):

```mermaid
flowchart TB
    L["lines: 3 debits, 2 credits (per book+currency)"] --> SORT["stable sort: debits then credits"]
    SORT --> PAIR["pair smallest remainders first<br/>(min-remainder matching)"]
    PAIR --> LEGS["legs[] → deterministic transfer IDs<br/>uuid5(ns, entryId + ':' + legIndex)"]
    LEGS --> PLAN["canonical plan persisted BEFORE submission<br/>plan_hash + tb transfer ids"]
    PLAN --> LINKED["TB transfers: flags.linked except the FINAL leg<br/>(all-or-nothing at TigerBeetle)"]
```

Determinism guarantee: Two independent nodes replaying the same entry produce byte-identical transfer IDs — the recovery protocol depends on this determinism.

---

## 4. Posting State Machine

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> PENDING_CHECKER: maker submits (four-eyes opens)
    PENDING_CHECKER --> READY: checker approves<br/>(plan persisted, maker≠checker)
    PENDING_CHECKER --> REJECTED: checker rejects
    DRAFT --> REJECTED: direct rejection path
    READY --> SUBMITTING: dispatcher locked (KeyLocks)
    SUBMITTING --> COMMITTED: TB Ok
    SUBMITTING --> COMMIT_UNKNOWN: TB timeout/transport
    COMMIT_UNKNOWN --> COMMITTED: check-status → lookup_transfers<br/>proves commit (same person OK — not four-eyes)
    COMMIT_UNKNOWN --> REJECTED: lookup proves absence<br/>→ resubmit identical ids allowed
    COMMITTED --> FINALIZED: finalize (status+audit+outbox in one tx)
    FINALIZED --> REVERSED: reversal (new balanced JE)
    REJECTED --> [*]
```

`JournalTx` exists as a separate bean so `@Transactional` transition methods are never bypassed by self-invocation inside `JournalService`. The sealed `PostingOutcome` (`Committed | Rejected | Unknown`) is consumed by exhaustive switch.

---

## 5. Uncertain Commit — The Recovery Protocol

```mermaid
flowchart TB
    U["SUBMITTING: TB call timed out / connection died"] --> CW["COMMIT_UNKNOWN<br/>+ P1 operator-visible event<br/>+ Uncertain Postings queue"]
    CW --> CS["check-status (operator or auto):<br/>lookup_transfers with ORIGINAL ids — never fresh ids"]
    CS -->|all found| C["→ COMMITTED"]
    CS -->|none found| RS["→ resubmit identical transfer ids (determinism!)"]
    CS -->|partial / mismatch| P1["→ stays COMMIT_UNKNOWN, P1 raised<br/>manual ops + audit; never guess"]
```

Hard rules: never retry with new IDs while uncertain; original IDs only, even for the retry, until the outcome is definitively known.

---

## 6. TigerBeetle Driver Isolation

```mermaid
flowchart LR
    JS["JournalService · GLAccountService · BankAccountService"] -->|"TbResult: Ok \| DefinitiveRejection \| UnknownTransport"| TBS["TbService<br/>ONLY importer of com.tigerbeetle.*"]
    TBS --> TB[("TigerBeetle 0.17.9<br/>127.0.0.1:3000")]
```

`TbResult` is a sealed algebra: a *definitive* rejection is a business event (log `WARN`, terminalize), an *unknown transport* result drives the recovery protocol above.

---

## 7. Money In Flight — Pending Transfers as the Universal Two-Phase (Chapter 13)

The pending/commit/void mechanism above is the same construct chapter 13 scales into
bank rails: every in-flight external movement (bill payments, SKU holds, custodian transfers)
posts as a **linked pending transfer** into a nominal clearing account, and one of three
dispositions resolves it — **commit** (confirmed), **void** (reversible window), or
**stays pending** (pending recognition, until a `probe()` or a recon finding clears the
ambiguity). A timeout on an already-submitted message is **never** a failure; only a
definitive `RailResult.Rejected(permanent=true)` voids immediately. Normative shapes, the
exactly-once stack, and the failure doctrine live in
[chapter 13](13-money-movement-and-rails.md);
the executable plan (stages, regulatory citations) in
[plans/bank-connectors-bill-payments-plan.md](../../plans/bank-connectors-bill-payments-plan.md).
