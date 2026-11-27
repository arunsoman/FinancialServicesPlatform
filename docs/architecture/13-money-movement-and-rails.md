# Architecture Chapter 13 — Money Movement, Bank Connectors, Bill Payments (PSP & EMI)

**Parent Document:** [docs/architecture.md](../architecture.md)
**Detailed Plan:** [plans/bank-connectors-bill-payments-plan.md](../../plans/bank-connectors-bill-payments-plan.md)

---

> **Status:** **PARTIAL — Stage 1 LANDED** (sealed taxonomy + connector guard in `shared`, 2026-11-19).
> The normative shapes below are **implemented** in
> `backend/shared/src/main/java/com/fsp/shared/rails/` (`ProviderProtocol`, `RailCapability`,
> `RailResult`, `RailStatus`, `RailFamily`, `ProtocolAdapter`, `RailGuard`, `RailTransportException`,
> `ReversalClass`) with tests in `shared/src/test/.../rails/`; psp/emi build in stages 2+.
> The authoritative executable plan — stages 0–6, sealed-interface sketches, regulatory citations — lives in
> `plans/bank-connectors-bill-payments-plan.md`. This chapter is the normative architecture:
> the shapes, invariants, and boundaries every future PR for this domain is measured against.

---

## 1. What this domain adds, and what it deliberately does not touch

Two new services, both fully isolated (plugin-platform rules for process/schema/credentials):

| Service | Port | Book | Owns |
|---|---|---|---|
| `psp-service` | :8085 | TigerBeetle `PSP` book (:805) | Rail abstraction, connectors, clearing positions (zero per cycle), merchant payable, settlement sweep |
| `emi-service` | :8086 | TigerBeetle `EMI` book (:806) | Customer wallets (prepaid, BoN-regulated, **NAD-only**), bill-payment sagas, digital-asset ledgers |
| `recon-worker` | (no port) | read-only | Statement/recon ingests; feeds saga dispositions; pages on invariant breaks |

> **Stage 0 LANDED (2026-11-21):** modules `backend/emi`, `backend/psp`, `backend/recon` exist
> and boot-verify — each with its own DB, Flyway history (V1), and TB book id in config (R5
> isolation): `emi/.../db/migration/V1__emi_schema.sql` (wallet **NAD-only CHECK**, biller w/
> reversal_class default C, bill_payment_saga, connector_call, asset_ledger closed-loop
> defaults, tax_rate_bucket, emi_outbox) · `psp/.../db/migration/V1__psp_schema.sql`
> (bank_connector §3.4.3 row shape, merchant, clearing_position, merchant_payable,
> settlement_batch, statement_ingest hash-claim-once, psp_outbox). Dev `init-dbs.sql` creates
> `fsp_psp` / `fsp_emi` DBs + roles. Core's next free migration stays V32.
>
> **Stage 2 LANDED (2026-11-21):** emi-service owns the wallet ENGINE — `EmiTbService`
> (wallet = CREDIT_BOUND liability account, float = DEBIT_BOUND mirror, book from config),
> `EmiWalletService` (`open`/`deposit`/`redeem`/`balance`): deposits idempotent by bank
> statement ref, redemptions preconditioned on TB balance + daily cap (counted only on
> BOOKED), every movement journaled PENDING → TB (deterministic id) → BOOKED — §6's
> exactly-once ladder applied per movement. V2 `wallet_journal` + `wallet_daily_turnover`.
> Real-TB smoke: `:emi:bootRun --spring.profiles.active=demo-wallet` → balance == float
> mirror. TB cluster id in dev is **0** (a mismatched cluster id hangs the client).
>
> **Stage 3 LANDED (2026-11-22):** bill-payment sagas on TB two-phase transfers —
> `BillPaymentSagaEngine` (HOLD → SUBMITTED → CONFIRMED/VOIDED/PENDING_RECOGNITION/EXPIRED
> ladder; TB PENDING authorization hold with `timeout = fsp.emi.saga-grace-seconds`,
> deterministic Uuid5 hold/commit/void ids, TTL sweeper, four-eyes force-void, append-only
> `saga_event` audit, `ConnectorCall` exactly-once layer-3 rows) + `BillerRestGateway`
> BILLER_REST adapter under the Stage-1 `RailGuard` admission ladder (429 → breaker →
> bulkhead). `BillerGateway` is the adapter seam — a new biller dialect is a new adapter
> registering on the sealed `ProviderProtocol` taxonomy, never engine surgery. Real-TB
> smoke: `:emi:bootRun --spring.profiles.active=demo-biller` → committed bill leaves the
> wallet and parks in the derived bill-clearing account; pre-submit voids release fully.
> Ambiguity doctrine enforced in code: timeout-after-submit parks in `PENDING_RECOGNITION`
> — void blocked, only recon probes or force-void resolve.
>
> **Stage 3 WIRE LANDED (2026-11-22):** the EMI REST surface is gateway-routed. `emi/api`
> carries a local `@EmiOperation` declaration (service-local mirror of core's
> `@FspOperation` — services never import core classes), a boot-validated `EmiOperations`
> catalog that publishes a digest-stable descriptor at `GET :8086/internal/ops/catalog`,
> and an `EmiIdentityFilter` zero-trust edge: `/v1/**` is served only when the gateway
> proves origin with the shared internal hop credential, then the trusted `X-FSP-*`
> identity headers are believed and the person-plane (machine tokens → 403) is enforced.
> The gateway's `CatalogRouteTable` now also fetches the descriptor and routes `/v1/emi/**`
> to EMI — owner-scoped ops (empty permission) enforce resource ownership in-domain (a
> foreign wallet/saga answers 404, never 403); an op that later declares a permission key
> gets a core `/internal/authz/decide` PDP call before forwarding. §2f standards hold on
> the history endpoint (bounded page/size, server-side sort whitelist, `q` pushed to SQL);
> §2e idempotency: bill pay and redemption are keyed (`UNIQUE (kind, reference)` replays
> return the original movement). Ops: `emi.wallet.open/view/redeem`,
> `emi.bill.pay/history/view/refresh/cancel` — table in generated `operations.md`.

This chapter is **additive**: the CORE book, the approval engine, the notification plane
(chapter 12), and the auth plane (chapter 02 §7) change **not one line**. Both services
are client applications of core (identity, permission lookups) and consumers of the
connector registry (a shared library, not a network hop).

**Business models:** PSP = pass-through acquiring — in-flight funds in **nominal clearing
positions that must be zero every settlement cycle** (never customer-facing wallets);
EMI = **real prepaid wallets** (platform-held float, mirrored 1:1 in a trust account).
The regulatory grounding: Namibia PSD-1 (PSP licensing), PSD-3 (e-money), VAA 10/2023 +
BoN Custody Rules — full mapping in plan §12.2/§12.7.

**Hard wallet constraint (PSD-3 §9.1.1):** regulated e-money balances are **NAD-only,
redeemable at par**. ZAR/USD/GBP positions are bank-linked/on-sent accounts, not wallet
balances. Loyalty/points are never convertible to cash or e-money (PSD-3 §9.1.3).

## 2. Sealed protocol taxonomy (normative shape)

```java
public sealed interface ProviderProtocol {
    sealed interface SessionBound extends ProviderProtocol
            permits Iso8583Protocol, TerminalStreamProtocol {}
    sealed interface MessageTyped extends ProviderProtocol
            permits Iso20022PacsProtocol, Iso20022PainProtocol, NachaProtocol {}
    sealed interface HttpBased extends ProviderProtocol
            permits RestOpenBankingProtocol, UpiProtocol, BillerRestProtocol,
                    WalletRailProtocol, CustodianRestProtocol {}
}
public sealed interface RailCapability {
    record Sequential(int maxInFlightPerSession) implements RailCapability {}
    record Parallel(int maxInFlight, Duration connectTimeout, Duration readTimeout) implements RailCapability {}
    record StatusProbe(Duration freshnessWindow) implements RailCapability {}
}
public sealed interface RailResult {
    record Accepted(String railRef, Instant eta) implements RailResult {}
    record AcceptedPending(String railRef) implements RailResult {}
    record Rejected(String code, boolean permanent) implements RailResult {}
    record Failed(FailureKind kind) implements RailResult {}
}
public sealed interface FailureKind {
    record CommUnreachable(Duration observedFor) implements FailureKind {}
    record TemporarilyUnavailable(int retryAfterSeconds) implements FailureKind {}
    record RateLimited(Duration movingWindowReset) implements FailureKind {}
    record DefinitiveFailure(String railCode) implements FailureKind {}
}
```

Rules (all compile-time-enforced by these seals):
- Adding a bank/rail = **config row + credentials**, never a redeploy. Adapters are per
  *rail family* (~6 classes for N banks), selected by `RailCapability` per config row.
- Services **never see HTTP status codes** — only these seals.
- Parallel rails are guarded by a bulkhead semaphore per `(connector, host)`; sequential
  rails by a per-session `KeyLocks` mutex (second concurrent caller fails fast, never corrupts).
- **Bank communication-protocol support (plan §3.4):** each configured bank pins a
  **transport channel + message format + ISO 20022 dictionary version** as a profile row —
  a new bank is config, never code.
  * *Transports (sealed `TransportChannel`):* `MtlsHttpChannel`, `VpnHttpChannel`,
    `TcpSessionChannel` (ISO 8583 sockets), `SftpChannel` (batch in/out-baskets), `MqChannel`.
  * *Formats:* ISO 8583 (auth/reversal/probe MTIs) · ISO 20022 (`pain.001`, `pacs.008`,
    `pacs.002`, `camt.053/.054`, dictionary version pinned per bank) · SWIFT MT wrapper
    (legacy only) · NACHA/ACH batch · bank REST/JSON · biller REST · CSV/statement batches.
  * *Failure alphabet (sealed, implemented):* `CommUnreachable`, `TemporarilyUnavailable`,
    `RateLimited`, `Backpressure` (the one LOCAL kind — the guard refused admission: bulkhead
    full / window reset; no provider contact happened, so it never advances a saga's
    submitted-state machine and never trips the breaker), `DefinitiveFailure`.
  * *Acceptance gate:* sandbox conformance suite per bank profile (pay/query/reverse/
    statement round-trip + breaker drill) before the bank is enabled; Stage 0 protocol
    survey signs off one profile per registry bank (FNB, Nedbank, Standard Bank, Bank BIC,
    Letshego Namibia).
  * Session-bound channels own logon/keepalive and map session loss to
    `FailureKind.CommUnreachable` (never silent retry); SFTP cadence misses are recon
    warnings, not breaker events; raw message text never survives parsing (§2c).
* The pins go into `shared/` — **Stage 1 delivered them:**
  `backend/shared/src/main/java/com/fsp/shared/rails/` so psp/emi compile against one module.
* `StatusProbe` capabilities take no bulkhead lease (cheap, idempotent); session/parallel
  capabilities map to per-`(connector, host)` bulkheads with fail-fast (zero-queue) admission.

## 3. The bill-payment space ladder (pending-transfer two-phase)

Bill payments are a permissible e-money service by name (PSD-3 §10.1.9). Money **never
moves irreversibly until confirmation**:

1. **Hold:** linked **pending transfer** `DR WALLET, CR BILL_CLEARING` — customer sees
   "pending"; funds are held, reversible, and *no provider has been trusted yet*.
2. **Submit** to biller via connector (outside any DB/TB transaction).
3. **done → commit pending** · **revert → void pending** · **ambiguous → stays pending**.

**Ambiguity doctrine (the invariant with teeth):** a timeout on an *already-submitted*
message is **never** treated as failure. Void requires only: definitive rejection,
TTL expiry, a `probe()` verdict, or a recon finding. Enforced in the saga engine, not the
adapter, so no connector implementation can violate it.

**The configurable "space"** = `fsp.emi-space-grace-seconds` plus a **per-biller
reversal-class** column (`biller_contract.reversal_class ∈ A|B|C`, plan §12.3: A reversible-anytime,
B reversible-by-contract-window with recon proof, C non-reversible). Contract-silent billers
default to **C** (conservative). Post-commit reversal is always four-eyes + recon evidence.

## 4. Exactly-once stack (no double debit/credit)

Six independent layers — any one is sufficient:

1. Unique `(service, kind, idempotency_key)` — dedup of client retries and internal replays.
2. Provider-side idempotency key = SHA-256 of (saga id + counterparty ref + amount + currency)
   — a variation in any of those changes the key, so money can never collide two obligations.
3. TigerBeetle pending uniqueness on `user_data_128 = sagaId` — a duplicate commit is a no-op.
4. **Outbox is the sole rail submitter** (claim via conditional UPDATE — same pattern as
   the §12.9 `ChannelDispatcher`); paused/resumed clusters can never double-submit.
5. Connector replay cache: the same message replays its recorded result.
6. Daily recon equality per book and cross-book — breach = P0 page.

## 5. Failure doctrine (fail fast, gracefully)

> **Implemented (Stage 1):** `RailGuard` enforces exactly this — resilience4j core 2.4.0
> (`resilience4j-circuitbreaker` + `resilience4j-bulkhead`) inside `shared`; the 429 window-freeze
> is a CAS-updated per-connector deadline. Note the 2.4.0 record API: `onSuccess/onError` take the
> **call duration**, not a start timestamp — passing a raw `System.nanoTime()` marks every call
> SLOW and opens the breaker on successes alone.

| Mechanism | Rule |
|---|---|
| Timeouts | connect 2 s, read 8 s (`HttpBased`) / 15 s (`SessionBound`) |
| Circuit breaker | resilience4j per `(connector, counterparty)`: 3 consecutive comm failures or 5/60 s → **OPEN**; in OPEN every message **fails locally with zero provider contact** until cooldown; one HALF_OPEN probe to close |
| Rate limiting | sliding-window counter per connector honoring the provider window; a `429` re-seeds the window from `Retry-After`/`X-RateLimit-Reset` and **does not open the breaker** (rate limit ≠ failure); requests during reset fail locally |
| Ambiguity | §3 rule above applies to breaker outcomes too: comm failure after submit ⇒ `PENDING_RECOGNITION`, breaker opens, nothing re-issued until cooldown **and** a probe clears the ambiguity |
| Scales out how | single instance per service (advisory-lock boot guard on the sweepers); distributed breaker state only when load demands (plan §12.5) |

## 6. SKU & digital-asset value capture

* **SKU purchases:** core is the order brain (inventory plugin reserves); EMI executes the
  money as a **linked pending chain** (wallet → SKU clearing + tax liability + fee),
  committed on fulfilment confirm, voided on expiry/refund. Price is fingerprint-quoted;
  a repo price change between quote and charge is a four-eyes-visible event.

> **Stage 4 LANDED (2026-11-22):** SKU money is live end-to-end on TB book 806.
> `emi/charge/ChargeService` executes the linked pending chain as one `LegPhase {HOLD,
> COMMIT, VOID}` transfer batch: hold = wallet DR pending → sku/fee/tax CR clearing
> (codes 305/306/307, `Bound.NONE` + history; zero legs skipped, all-but-last legs
> `FLAG_LINKED` so the chain is atomic all-or-nothing); confirm posts against the pending
> ids, void void-pends them; `PendingTransferExpired` repairs both sides to EXPIRED and
> the bounded sweeper recovers stale holds through the same deterministic `Uuid5` ids.
> The **price fingerprint** is the audit bond: core hashes
> `"sku|<walletId>|<orderRef>|<merch>|<fee>|<tax>|<ccy>"` at quote time and EMI re-hashes
> at charge time — a mismatch (or a changed replay) fails closed 409 before any money
> call, and the golden vector is pinned byte-identically in both suites. Core's
> `orders/SkuOrderService` owns order truth (CREATED → CHARGE_HELD → CONFIRMED/VOIDED/
> EXPIRED/FAILED, folded from EMI money-truth by `sync`), hops to EMI via
> `EmiMoneyClient` (internal credential + `X-FSP-*`, TX1/TX2 — remote hop outside the
> DB transaction), and stamps the core book with a mirror JE (DR SKU-clearing **2211** /
> CR SKU-revenue **4011**, `order-ref=… fingerprint=…` in the description, four-eyes +
> `je.post.immediate`). Wire: `emi.order.charge/confirm/cancel/view/history` on the EMI
> edge; `sku.order.charge/confirm/cancel/sync/mirror/view/history` (component `orders`)
> on core. Live smoke vs real TB: hold → replay-idempotent → confirm moves the wallet
> 7500→7385; single-leg zero-fee hold voids back to the exact balance. Confirmed charges
> refuse void (`EMI_CHARGE_COMMITTED`/`SKU_ORDER_COMMITTED`) — resolution is dispute-flow
> only.
* **Tax:** rate-bucket accounts (`TAX_LIABILITY_STD` 15%, `TAX_LIABILITY_ZERO` marker),
  bucket four-eyes-configured and snapshotted per JE; monthly `Σ tax booked == VAT return` recon.
* **Digital assets:** separate TigerBeetle **ledgers** per asset class; every asset leg mirrors
  a fiat leg (cross-ledger recon). **Closed-loop-first:** platform-issued units are
  non-transferable/non-resalable (VAA Schedule-2 exclusion — no VASP licence triggered);
  public virtual assets are blocked until a VASP licence-class decision (plan §12.1) —
  never self-custody at launch; use a Class R custodian.

## 7. Regulatory traceability (full table in plan §12.7)

Selected bindings: trust/merchant-pool **100% daily** coverage with ≤1-business-day fix
(PSD-3 §13.1.5 / PSD-1 §12.2.4) → recon-worker P0 page; merchant pay-over ≤2 business days
(PSD-1 §12.2.3) → settlement sweep; bill-payment disputes resolved ≤15 business days
(PSD-1 §16.2) → saga dispute SLA timer; custody record checks + external recon ≤2 months
(VAA Rules 13/15) → asset recon cadence; **dormancy sweeper** (6mo → 1mo notice → 3yr hold
→ transfer to BoN, PSD-3 §15) → new Stage-2 scheduled job.

## 8. PSP clearing (Stage 5 — LANDED 2026-11-22)

Merchant acquiring with a per-cycle zero-clearing invariant, all on the PSP book (805):

- **Accounts:** `psp:settlement-float:<ccy>` (custody mirror of acquired funds),
  `psp:clearing:<connector>:<ccy>` (must be ZERO per settled cycle),
  `psp:merchant-payable:<merchantId>:<ccy>` (accrual), `psp:fee-revenue:<ccy>`.
- **Legs:** capture = two-phase LINKED pending chain (doctrine as §3 ladder, book 805,
  codes 501 gross / 502 fee-extraction): leg0 DR float gross → CR clearing carries
  `FLAG_LINKED` (all-but-last), leg1 DR clearing fee → CR fee-revenue is the plain-PENDING
  chain terminator. Sweep resolves both pendings with post-pending legs in the SAME linked
  layout, then posts `closing_debit` (DR clearing NET → CR merchant-payable, code 503,
  id `Uuid5("psp:acquire:<id>:close")`).
- **Invariant:** at the end of every sweep `assertClearingZero` requires TB clearing posted
  balance == 0 **and** the `clearing_position` bookkeeping row drained to zero —
  otherwise ERROR `psp_sweep_clearing_residual`. The position row is written by capture
  (+gross) and decremented by the sweep (−gross: fee extraction + closing debit together
  drain the whole gross — never decrement by net, or the fee strands in the mirror).
- **Exactly once:** the `settlement_batch` row is the claim (OPEN→SWEEPING→COMPLETED);
  COMPLETED sweeps replay their counts; deterministic Uuid5 ids make every TB leg replayable;
  pending-expiry commit failures repair VOIDED (`PSP_CAPTURE_TIMEOUT` — timeout is not
  failure). Acquire window 48 h (`PSP_AUTH_WINDOW_EXCEEDED`), sweep-batch bounded-paged.
- **Recon:** `statement_ingest` files are sha256 hash-claimed once (UNIQUE file_hash),
  rows matched by rail ref → taxonomy `UNKNOWN_RAIL_REF` / `AMBIGUOUS_RAIL_REF` /
  `AMOUNT_OR_STATE_DRIFT` (recon-worker Stage 6 consumes the drifts).

## 9. Recon-worker invariants & paging (Stage 6 — LANDED 2026-11-22)

A stateless, read-only invariant worker (`backend/recon`, **no own DB, no port**,
`spring.main.web-application-type: none`, `@Scheduled` sweep) — its ONLY mutator is core's
machine-plane op `POST /v1/recon/pages`:

- **Faces:** `GET /internal/recon/snapshot` on PSP (:8085) and EMI (:8086), credential-only
  (`X-FSP-Internal-Credential`), never gateway-routed. PSP feed: positions (mirror vs `tbNet`),
  overdue payables, stale captures, float lines; EMI feed: custody lines with the proven
  invariant **floatNet == walletSum + heldSum** (held funds parked in bill/SKU/fee/tax clearing
  accounts stay physically in the float until external settlement — PSD-3 §13).
- **Doctrine — only PROVEN breaks page:** probe failures (feed down, unparseable/missing/oversize
  money, unknown TB account → `tbNet` null) log `recon_probe_failed` and **never page, never
  default to zero** — recon outage must not page the platform into numbness. Invariants:
  `PSP_CLEARING_RESIDUAL` (settled-cycle clearing ≠ 0), `PSP_CAPTURE_STALE` (capture > 2 d),
  `PSP_PAY_OVER_BREACH` (PSD-1 §12.2.3 pay-over SLA), `EMI_CUSTODY_MISMATCH` (PSD-3 §13.1.5),
  `EMI_FLOAT_UNPROVEN`.
- **Violation → page:** `POST /v1/recon/pages` (`@FspOperation(id="recon.page")`, MACHINE actors
  only) → `NotificationService.reconPage` creates `RECON_VIOLATION` notifications for every active
  holder of `recon.view` at the recon tenant (V33 grant) and records a valid-JSON
  `Notification.PushRequired` outbox row for the channel plane. Worker-side dedupe: violation key,
  24 h window; **failed dispatch does not burn the dedupe** (core-unreachable retried next sweep).
- **Page idempotency:** deterministic key `recon-<invariant>-<severity>-<UTC-day>` sanitized to
  core's `[A-Za-z0-9._-]` charset — retries within a dispatch day replay instead of
  duplicating recipients (core 5xx deletes the PENDING idempotency row, so retries stay clean).
- **Verified live:** induced demo violation (backdated capture cycle) → `recon_violation` P0 ×3,
  core page op → `recipients=2` + notification rows + outbox event with parseable payload,
  `idempotency_recorded 200`, repeat sweeps `recon_page_suppressed` (24 h dedupe), core-outage
  path retried without burning dedupe; clean demo state → silent sweeps (probe-fail-only logging).

## 10. Stage plan (summary)

Stages 0–6 in plan §11 produce, in order: schemas (V32 EMI / V33 PSP) + recon-worker skeleton →
sealed registry + breakers/limiter + tests → EMI wallets → bill-payment sagas → SKU money →
PSP clearing sweep → recon invariants & paging. Each stage is independently verifiable and
gated by `make docs-check` + gradle suites + the boot-verified migration ledger.
