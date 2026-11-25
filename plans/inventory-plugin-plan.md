# Inventory & Digital Value Plugin (`fsp-plugin-inventory`) — Build Plan rev 1

**Status:** BEING BUILT (backend slice) · **Date:** 2026-11-19 · **Owner:** platform engineering
**Binding sources:** `AGENTS.md` + `backend/AGENTS.md` §4/§2d/§2e/2f · `plans/plugin-platform-plan.md`
(full contract: §2 invariants, §3 topology, §4 call paths, §5 manifest, §7 plugin approvals, §9 idempotency,
§10 plugin contract) · `docs/inventory-and-services-script-steps.md` (GAP-INV-1..3, GAP-VAS-1 subset).

## 0. What this slice delivers (backend only)

The GAPS this closes:
- **GAP-INV-1** — `backend/plugins/inventory` subproject: manifest, SKU entities, batch storage.
- **GAP-VAS-1 (subset)** — digital products, encrypted voucher pools, float deposits, dispense
  (the voucher vault seam GAP-VAS-2 and fee matrix GAP-VAS-3 land here as first-cut code).

Out of scope this slice: widgets (`GAP-INV-2..4`, `GAP-VAS-4`), multi-book inter-org POs
(screen P3/P4 — one PO slice later), channel adapters, HSM broker (dev uses env KEK).

## 1. Decisions (read before touching anything)

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Port 8084, DB `fsp_plugin_inventory`.** `docs/inventory-and-services-script-steps.md` assigns :8084 to inventory and :8085 to digital-services; `docs/architecture.md` lumps them ("VAS/Inventory :8085"). Digital services keeps :8085 per the (inventory-specific, more recent) screenplay — hence inventory takes :8084. **Conflict with `backend/AGENTS.md` §4 ("Inventory :8085") is acknowledged and resolved the same way; AGENTS layout map to be amended when the plugin lands.** | the two plugins are separate registrations and must not share a port |
| D2 | Plugin = **single combined plugin `inventory`** serving both physical SKU and digital-voucher domains in one process/schema (first cut). Digital services may be split out later as `digital-services` with its own registration; the domain tables are namespace-separated (`sku_*`, `voucher_*`, `float_*`). | one isolated process/DB per AGENTS §4; splitting later is a manifest+registration change, not a schema redesign |
| D3 | **Money stays in core.** Stock receipts and float funding move money through the four-eyes JE engine (on-behalf `je.create`/`je.approve`, maker ≠ checker anchored at `person_id`). Retail/dispense flows move money through a **new core fast path `POST /v1/journal/intents`** (`je.post.immediate`, DELEGATED) — one-actor immediate posting, still balanced-JE + planner + TB-bound enforced and audited in core. Four-eyes stays where it is configured; POS/airtime retail is deliberately not per-item four-eyes (vision "four-eyes where configured"), bounded by `je.post.immediate` being a separately-grantable permission. | screenplay §P2/P5/D2/D4; `POST /v1/journal/intents` is the endpoint the screenplay architecture already names (GAP register); keeps TB writes exclusively in core |
| D4 | **GL accounts:** the plugin resolves GL accounts **by code** from the tenant's book at posting time and needs 1200/2010/2050/2100/2110/4200 to exist. Core gains an additive, idempotent `POST /v1/tenants/{tenantId}/books/{bookId}/gl-accounts/ensure` machine op (`ledger.coa.ensure`, `gl.account.create` MACHINE grant) the plugin's readiness step calls at activation (gate `GL_ACCOUNTS_ENSURED` reported locally; no core gate change). Code collisions (e.g. PSP template 2110 "Payouts in Transit") are respected: **ensure is upsert-by-code** — an already-existing account at that code is used as-is, never recreated. | existing books lack inventory rows; `provision-default-coa` is provision-once |
| D4a | **GL on first use** (owner, 2026-11-25 — follows easy install, AGENTS.md §2.1): the plugin is installed once on the platform root and inherited, and the root has no book. Readiness ensures CoA rows only where the installation tenant HAS an ACTIVE book; otherwise `GL_ACCOUNTS_ENSURED` is reported with nothing to wire (`inventory_gl_deferred`). Each organization gets its rows in **its own** book the first time `GlBookResolver` misses a code (ensure once, re-read). No book → `409 INVENTORY_NO_BOOK` with a plain sentence. | a root-level install could never go ACTIVE |
| D5 | **Core amendments (flagged, minimal, all DELEGATED/machine-enabling consistent with plugin-plan §11.1):** (a) `actors()` += `DELEGATED` on `journal.entry.create/submit/approve`, `ledger.book.list`, `ledger.gl_account.list`; (b) `EntryView` gains `openApprovalId` so the plugin can resolve/reject a JE approval row without a core approvals-search hop; (c) new ops `journal.entry.post_intent` and `ledger.coa.ensure`; (d) V26 migration = inventory/digital permission catalog + role-grant mirror; (e) `RoleTemplates` gains the new keys; (f) demo seed V27 grants the new keys to demo roles. | without (a) the plugin cannot move money; without (b) rejects are unresolvable by-plugin |
| D6 | **Voucher PINs are plaintext-in-DB never.** AES-256-GCM with a data key derived (HKDF) from env `FSP_PLUGIN_INVENTORY_KEK` + batch id; ciphertext+nonce at rest; release endpoint returns the pin **once** (status flips AVAILABLE→RELEASED in the same tx as the money move), masked everywhere else. No PIN ever logged. | GAP-VAS-2 seam; HSM/KMS swap later behind `VaultCipher` port |
| D7 | Fee split for dispense (vision §3.4): deterministic minor-unit allocation `telco 94% / distributor 2% / platform 1% / merchant 3% retained` — split computed in the plugin, residual goes to telco leg so the JE always balances. Percentages come from the digital product row (configurable, first-cut defaults). JE: `Dr 2050 float / Cr 2100 telco payable + 2110 distributor commission + 4200 platform fee`. | screenplay D3/D4B |
| D8 | **Idempotency:** plugin inbound keys scoped to `(person_id, request_key)` in the plugin DB (same table shape as KYC); outbound plugin→core keys deterministic from the aggregate (`inv-batch-<id>-<rev>`, `inv-voucher-auth-<id>`, `inv-dispense-<saleId>`). | AGENTS §2e, plan §9.1 |
| D9 | **Concurrency:** batches/voucher pools are `@Version` optimistic; voucher release + float drawdown serialize per batch/account with an in-JVM fair striped `KeyLocks` (copied into the plugin as its own copy — plugins do not import core classes); stock/batch rows are always locked/updated in sorted id order. | AGENTS §2d; GAP-VAS-5 pattern |
| D10 | REST collection standard everywhere: `page`/`size`(≤100)/`sort` (public-name safelist) /`q`/`status`; totals-free paged responses (plan §12.4). | AGENTS §2f |

## 2. Manifest surface (`inventory:1.0.0`, all under `/v1/plugins/inventory`)

Operations:
| op id | method + path | permission | risk |
|---|---|---|---|
| `inventory.sku.list` | GET `/skus` | `inventory.view` | READ |
| `inventory.sku.get` | GET `/skus/{skuId}` | `inventory.view` | READ |
| `inventory.sku.create` | POST `/skus` | `inventory.sku.manage` | FOUR_EYES |
| `inventory.sku.decide` | POST `/skus/{skuId}/pricing-decision` | `inventory.pricing.approve` | FOUR_EYES |
| `inventory.stock.batch.list` | GET `/stock-batches` | `inventory.view` | READ |
| `inventory.stock.batch.get` | GET `/stock-batches/{batchId}` | `inventory.view` | READ |
| `inventory.stock.batch.create` | POST `/stock-batches` | `inventory.stock.upload` | FOUR_EYES |
| `inventory.stock.batch.decide` | POST `/stock-batches/{batchId}/decision` | `inventory.stock.approve` | FOUR_EYES |
| `inventory.voucher.batch.list` | GET `/voucher-batches` | `digital.view` | READ |
| `inventory.voucher.batch.get` | GET `/voucher-batches/{batchId}` | `digital.view` | READ |
| `inventory.voucher.batch.upload` | POST `/voucher-batches` | `digital.voucher.upload` | FOUR_EYES |
| `inventory.voucher.batch.authorize` | POST `/voucher-batches/{batchId}/authorization` | `digital.voucher.authorize` | FOUR_EYES |
| `inventory.voucher.dispense` | POST `/vouchers/dispense` | `digital.dispense` | WRITE |
| `inventory.digital.product.list` | GET `/digital-products` | `digital.view` | READ |
| `inventory.digital.product.create` | POST `/digital-products` | `digital.product.manage` | WRITE |
| `inventory.float.deposit.list` | GET `/float-deposits` | `digital.view` | READ |
| `inventory.float.deposit.create` | POST `/float-deposits` | `digital.float.deposit` | FOUR_EYES |
| `inventory.float.deposit.decide` | POST `/float-deposits/{depositId}/decision` | `digital.float.deposit` | FOUR_EYES |

Approvals (manifest `approvals`): `SKU_PRICING` (maker `inventory.sku.manage` / checker `inventory.pricing.approve`),
`STOCK_RECEIPT` (`inventory.stock.upload` / `inventory.stock.approve`),
`VOUCHER_RELEASE` (`digital.voucher.upload` / `digital.voucher.authorize`),
`FLOAT_FUNDING` (`digital.float.deposit` / `digital.float.deposit`).

Client grants: ON_BEHALF `je.create`, `je.approve`, `je.post.immediate`, `gl.account.view`,
`inventory.*`, `digital.*`; MACHINE `gl.account.create` (GL ensure at readiness).

## 3. State machines

- SKU: `PENDING_APPROVAL → (pricing decision) ACTIVE | REJECTED`; edits re-enter PENDING_APPROVAL (next slice).
- Stock batch: `STAGED → COMMITTED | REJECTED` — JE drafted+submitted as maker at staging
  (`Dr 1200 Inventory On Hand / Cr 2010 Goods Received Clearing`, batch cost), core JE `PENDING_CHECKER`;
  checker approve → JE FINALIZED → `COMMITTED` (qty_on_hand set); reject → JE REJECTED → `REJECTED`.
- Voucher batch: `PENDING_CLEARANCE → ACTIVE → EXHAUSTED`; release per voucher `AVAILABLE → RELEASED`.
- Float deposit: `PENDING_APPROVAL → COMMITTED | REJECTED` — JE `Dr 1000 Cash at Bank / Cr 2050 Merchant Digital Float`.

## 4. Core ops enabled for the plugin (executed)

- `journal.entry.create/submit/approve` — actors += `DELEGATED`
- `ledger.book.list`, `ledger.gl_account.list` — actors += `DELEGATED`
- `journal.entry.post_intent` (NEW) — POST `/v1/tenants/{tenantId}/journal/intents`, permission
  `je.post.immediate`, actors `{DELEGATED}`: balanced ≥2 lines, OPEN period fence, planner + dispatch +
  FINALIZED with the same keyLock + JournalTx pipeline (entry born `PENDING_CHECKER`, saved READY, posted)
  — one actor, zero four-eyes, fully audited `JE_INTENT_POSTED`.
- `ledger.coa.ensure` (NEW) — POST `/v1/tenants/{tenantId}/books/{bookId}/gl-accounts/ensure`,
  permission `gl.account.create`, actors `{MACHINE, PERSON}`: additive upsert-by-code GL rows + TB accounts.

## 5. Build targets

- `make backend-plugin-inventory` (`bootRun`); compose service `plugin-inventory` + DB `fsp_plugin_inventory`.
- `./gradlew :plugins:inventory:test`
- Core: V26 migration; next shared number after this slice starts at V28 (V27 = demo-only seed).

## 6. Implementation status (2026-11-19)

**Compiling green:** `:core:compileJava`, `:core:test`, `:plugins:inventory:compileJava` (whole backend builds).

### Core (executed)
- `JournalService.postIntent` + `JournalTx.persistIntent` — the `journal.entry.post_intent` fast path.
- `LedgerService.ensureAdditive` + `POST .../gl-accounts/ensure` (`ledger.coa.ensure`).
- `CoaTemplates.inventory()`; `RoleTemplates` admin/checker inventory keys; V26 permissions + V27 demo grants.

### Plugin (executed)
- Full scaffold: entities (11), repos (10, cursor-paged `search` w/ public sort safelist),
  KeyLocks copy, VaultCipher (HKDF data key = KEK ⊕ batch id), CoreClient (all core trips + `getJeEntry`
  recovery read), GlBookResolver, actor-assertion filter (`inventory.actor`), manifest (18 ops / 4 approvals).
- SKU pricing four-eyes: `InventorySkuService` (create → PLUGIN:SKU_PRICING approval → decide).
- Stock receipts four-eyes: `StockBatchService` (stage JE maker + PLUGIN:STOCK_RECEIPT; decide approve
  → core approves JE → FINALIZED → COMMITTED + RECEIPT movement; approve tolerates crashed re-approve via
  `getJeEntry` FINALIZED check; MAKER_NOT_CHECKER always rethrows).
- Digital plane: `DigitalProductService` (split bps validation, margin GL must exist), `VoucherService`
  (upload seals PINs AES-256-GCM per-batch, never logged/stored plaintext; authorize four-eyes),
  `FloatService` (four-eyes + Dr 1000/Cr 2050 JE), fee split `Split` (remainder = merchant margin;
  JE always balances).

### Dispense saga (invariant-safe, AGENTS §8)
1. **TX1** atomic claim `AVAILABLE → RESERVED` (JVM stripe lock per tenant; `@Version` + partial unique
   `release_sale_id` as cross-instance backstops; pools visited in deterministic order).
2. **NET** journal intent at core — NO lock or tx held across the network hop; non-FINALIZED intent
   (e.g. COMMIT_UNKNOWN) → 503, NO release.
3. **TX2** (`VoucherTx.commitSale`) sale row + `RESERVED → RELEASED` (+ pool EXHAUSTED when drained).
4. Failed money call → best-effort `rollbackClaim`; crashed process → `@Scheduled` sweeper returns
   120s-old reservations with no committed sale to AVAILABLE (`VoucherService.reclaimExpiredReservations`).
- Known residual (registered in the risk register): if the JE commits at core but the plugin crashes
  before TX2 and the sweeper reclaims the voucher, the sale row is missing → reconciler in the next slice
  must check `getJeEntry` + reference `INV-VOUCHER-<saleRef>` before re-releasing.

### Infra
- compose `plugin-inventory` (:8084, DB `fsp_plugin_inventory`); Makefile `backend-plugin-inventory`;
  `make backend-plugin-dbs` = `infrastructure/bin/init-plugin-dbs.sh` (idempotent DB+role create, also
  fixes the never-documented `fsp_plugin_kyc` gap). Dockerfiles for plugins are pending for BOTH plugins
  (compose `build:` references still dangling — pre-existing gap).

### Pending (next slice)
1. E2E: boot core+plugin+gateway, register the plugin type (four-eyes, manifest JWKS `/.well-known/jwks.json`),
   install to demo merchant, watch readiness report `GL_ACCOUNTS_ENSURED` (calls `/gl-accounts/ensure` with
   `CoaRows`).
2. `:plugins:inventory:test` unit tests (VaultCipher roundtrip, Split math, dispense claim/sweeper).
3. Gateway route table entries for `/v1/plugins/inventory/**` (mirror `kyc`).
4. Split `digital_*` plane into `fsp-plugin-digital-services` (:8085) — manifest + registration change only.

### Live-stack milestone (2026-11-19, later same day)
- **Registration + four-eyes + installation + readiness: verified LIVE.**
  - Core restarted with `client-demo` after backfilling `flyway_schema_history` rows for V3/V18/V22
    (pre-applied data; inline-CRC32 checksums). V26 (platform permissions) + V27 (demo role grants) applied.
  - Manifest v1.0.1 registered (maker `platform-admin`), approved (checker `admin-checker`) — 17 client
    grants incl. MACHINE `plugin.manage` (needed by `/installations/provisioning`; KYC manifest parity).
  - Install on Namib Fresh `...0a2` (four-eyes), gates ALL ACTIVE (incl. `GL_ACCOUNTS_ENSURED` via
    `/gl-accounts/ensure` additive-by-code at book `...0c2`), installation `7af8ddae…` **ACTIVE**.
  - `GET /api/plugins/inventory/skus` as `merchant-admin` → 200 `{"items":[],"hasMore":false}` through
    BFF → gateway → core → plugin `:8084` (gateway routes resolve from the registered v1.0.1 manifest).
- **Core fixes while wiring (committed to core):**
  1. `PermissionService.effectiveKeys` — MACHINE actors route to `effectiveKeysForClient` (person
     branch crashed on `personId=null`).
  2. `PluginInstallationService` default gates now include `GL_ACCOUNTS_ENSURED` (optional gate for
     money-moving plugins; KYC has none).
- **Plugin fixes while wiring:** search JPQL null-param bound as bytea on Hibernate 7/Postgres
  (`lower(concat('%',:q,'%'))`) → all 5 repositories now take `:hasQ` boolean + precomputed
  lower-case `:qPattern` (`PluginSupport.searchPattern`).
- **Ops gotchas (dev):** core idempotency filter caches 4xx forever → a transient 404 poisons a
  deterministic key (purge `idempotency_records` `request_key LIKE '%…%' && status 404` to unblock);
  two REGISTERED versions of one code break `findByCodeAndStatus` (install pins `type.version`) —
  keep only the current REGISTERED row; `gradlew --stop` + staggered boots needed: 15Gi host OOM-killed
  core+gateway when all six JVMs booted at once.
- **Remaining:** E2E business happy paths (SKU create/decide, stock batch, voucher authorize/dispense,
  float deposit), `:plugins:inventory:test` unit tests, Dockerfiles, reconciler for crash-before-TX2.
