# PSP and EMI Service Provider Compliance Obligations

> **⚠️ AMENDMENT (2026-11-19):** this document was drafted against the **EU directives
> (EMD2/PSD2)** as generic industry grounding. The platform's operative jurisdiction is
> **Namibia**: the binding instruments are **PSD-1** (PSP licensing, Payment System
> Management Act 14/2023), **PSD-3** (e-money, GN 561/2024), and the **Virtual Assets
> Act 10/2023 + BoN Custody Rules (GN 515/2023)**. Where EMD2/PSD2 sections below overlap,
> the **determinations govern** (e.g. NAD-only wallets, PSD-3 §9.1.1 replaces EMD2
> denomination freedom; PSD-1 §12/§13 replaces PSD2 safeguarding categories). The full
> resolved regulatory mapping lives in
> [plans/bank-connectors-bill-payments-plan.md §12](bank-connectors-bill-payments-plan.md)
> and [architecture chapter 13](../docs/architecture/13-money-movement-and-rails.md)
> (§12.7 traceability table). EU sections are retained below as reference only.

## Document Information
- **Version:** 1.0
- **Date:** 2025-01-01
- **Scope:** FSP Phase-0 platform — PSP and EMI vertical compliance
- **Grounding:** EMD2 (Electronic Money Directive 2), PSD2 (Payment Services Directive 2), FAPI 2.0 Security Profile, RFC 9700 BCP 240
- **Related:** `plans/phase-0-prd.md`, `FSP-Spring-Boot-FAPI2-Implementation-Guide (1).md`, `~/.pi/agent/skills/fapi2-compliance/`

---

## 1. Regulatory Context

| Service Type | Primary Directive | Secondary Regulations | Competent Authority |
|---|---|---|---|
| **PSP** (Payment Service Provider) | PSD2 (2015/2366) | - REGULATION (EU) No 260/2012 on interchange fees<br>- Directive (EU) 2018/1675 on ATM surcharging<br>- National implementation of PSD2 | National Payment Institution (NPI) / Central Bank |
| **EMI** (Electronic Money Institution) | EMD2 (2017/1775) | - Regulation (EU) No 1240/2012 on interchange fees for card-based payment transactions<br>- AMLD5 (EU) 2018/1673 — Anti-Money Laundering Directive<br>- National e-money supervisory authority | National Competent Authority (NCFA) / Central Bank |

---

## 2. PSP (Payment Service Provider) Compliance Obligations

### 2.1 PSD2 Core Requirements

| Requirement | FSP Implementation | Compliance Evidence |
|---|---|---|
| **SCA (Strong Customer Authentication)** | - FAPI 2.0 DPoP + PKCE + PAR flow (no ROPC)<br>- Session-based auth in BFF (cookie-based, not credential-based)<br>- Four-eyes approval for sensitive operations | - Playwright E2E: `grep dist for grantType.*password` = 0<br>- DPoP headers on all API calls<br>- `iss` validation in authorization response |
| **Transparency** | - Route inventory build artifact (generated from `@FspOperation` annotations)<br>- Operation IDs and permission keys visible in catalog<br>- Error-UX map for all API errors | - `./gradlew generateRouteInventory` produces markdown<br>- 23 permission keys listed in `/api/v1/permissions`<br>- Consistent ProblemDetail error responses |
| **Access to accounts** | - Per-tenant book isolation<br>- GL account visibility scoped to tenant subtree<br>- Account create/update four-eyes enforced | - `GET /v1/tenants/{tenantId}/books` returns tenant-scoped books<br>- `POST /v1/tenants/{tenantId}/books/{bookId}/gl-accounts` requires checker<br>- Effective-permission API: `GET /v1/tenants/{tenantId}/principals/{principalId}/effective-permissions` |
| **Payment services transparency** | - Journal entry engine with full audit trail<br>- Posting state machine: DRAFT → PENDING_CHECKER → READY → SUBMITTING → COMMITTED → FINALIZED<br>- Uncertain-commit recovery | - Audit records for every mutation<br>- State transitions visible in JE detail timeline<br>- `check-status` recovery resolves `COMMIT_UNKNOWN` |
| **Currency conversion** | - Single currency (ISO 4217) in Phase-0<br>- FX rates to be plugin-provided in future slices | - `currency` field on GL accounts and JE lines<br>- Money = minor-unit strings (validated pattern `[1-9][0-9]*`) |
| **Surcharging** | - ATM surcharge transparency (if applicable)<br>- Display of any fees before confirmation | - Fee fields on JE lines with description<br>- MDR revenue account (4100/5100/5200 in PSP CoA) |

### 2.2 Transaction Reporting and Monitoring

| Obligation | FSP Implementation | Evidence |
|---|---|---|
| **Transaction monitoring** | - Journal entries with full correlation chain<br>- Screening checks (IDENTITY + SANCTIONS) during onboarding<br>- Risk rating (LOW/MEDIUM/HIGH) per KYC case | - KYC case model with `riskRating` field<br>- `ScreeningCheck` entities: IDENTITY, SANCTIONS, ADVERSE_MEDIA, PEP<br>- Risk rating rules normalized from Namibian validation rules |
| **Suspicious activity reporting** | - KYC rejection archives applicant<br>- Bank account change triggers re-screening<br>- Four-eyes on all sensitive operations | - `POST /v1/tenants/{tenantId}/kyc/reject` with reason<br>- `POST /v1/tenants/{tenantId}/kyc/request-info`<br>- Bank change re-screening recorded as new `ScreeningCheck` |
| **Interchange fee tracking** | - MDR revenue accounts (4100, 5100, 5200)<br>- Revenue recognition per JE posting | - `MDR Revenue` (4100) in PSP CoA template<br>- `Interchange Expense` (5100) and `Scheme Fee Expense` (5200) in PSP CoA<br>- JE lines debit/credit these accounts per transaction |
| **Settlement reporting** | - TigerBeetle ledger per currency<br>- Per-book balance endpoints<br>- Trial balance and as-of reports | - `GET /v1/tenants/{tenantId}/books/{bookId}/gl-accounts/{id}/balance`<br>- `TrialBalanceService` with as-of cutoff (§5.10)<br>- `GET /reports/trial-balance` (future slice) |

### 2.3 Customer Protection

| Obligation | FSP Implementation | Evidence |
|---|---|---|
| **Funds segregation** | - Per-tenant book isolation<br>- Each installation bound to one tenant (§6.2)<br>- Separate GL accounts per tenant book | - `Book` model: `bookId`, `tenantId`, `currency`, `ledgerId`<br>- One PSP book per PSP tenant; one merchant book per merchant tenant<br>- `context.journal.submit(intent)` — intents, not direct TB writes (plugins cannot call TB) |
| **Customer redress** | - Audit log with full correlation chain<br>- Reversal of entries via `reversal_of_entry_id`<br>- Journal entry reversal path | - `ADR 25`: "true reversals use `reversal_of_entry_id"`<br>- Audit records every mutation with `correlationId` chain<br>- `JournalEntry.Reversed` event type |
| **Transaction disputes** | - Chargeback-like flow via linked adjustments<br>- PostingGroup for inter-org transfers | - `InterOrgTransfer` with `PostingGroup` due-to/due-from<br>- Chargebacks posted as new linked adjustments (not direct TB mutations) |

### 2.4 PSP-Specific: AISP/PISP Considerations

| Capability | Phase-0 Status | Future Slice |
|---|---|---|
| **AISP (Account Information Service Provider)** | Not in Phase-0 | Slice 2 — balance/statement endpoints, trial balance, transaction history per consent |
| **PISP (Payment Initiation Service Provider)** | Not in Phase-0 | Slice 2 — authorization holds, capture JEs, network cost postings |
| **Consent framework** | Not implemented | OAuth 2.0 `authorization_details` + `resource` PAR parameters; per-consent JE scoping |

---

## 3. EMI (Electronic Money Institution) Compliance Obligations

### 3.1 EMD2 Core Requirements

| Requirement | FSP Implementation | Compliance Evidence |
|---|---|---|
| **Safeguarding of e-money** | - `1000 Safeguarding Cash` account (`balance_bound = DEBIT_BOUND`)<br>- `1010 Safeguarding Approved Assets` account<br>- Prevention is synchronous and pre-submission (§7.7)<br>- Detection is post-commit via `EMI.SafeguardingBreach` event | - `EMI.IssuanceBlocked` event blocks issuance when coverage headroom insufficient<br>- `EMI.SafeguardingBreach` event detected at post-commit<br>- Safeguarding banner on EMI surfaces (ui-std.md §5.5) |
| **Capital requirements** | - Minimum own funds for EMI plugin installation<br>- Capital adequacy monitored via event bus<br>- Four-eyes approval for EMI installation activation | - `minAncestorRoleToInstall` in PluginDescriptor<br>- `requiredParentEntitlement` for EMI family activation<br>- Four-eyes: installer ≠ checker (different `person_id`) |
| **Client money protection** | - Safeguarding Cash account segregation<br>- Approved assets in Safeguarding Approved Assets account<br>- Balance bounds enforce CREDIT_BOUND on e-money liability | - `2000 E-Money Liability` (`balance_bound = CREDIT_BOUND` — negative wallet is breach)<br>- `1000 Safeguarding Cash` (`balance_bound = DEBIT_BOUND` — negative safeguarded cash is breach)<br>- `1010 Safeguarding Approved Assets` (`balance_bound = NONE` — transit) |
| **Issuance and redemption** | - EMI issuance creates e-money liability<br>- Redemption at par (1:1 with safeguarded cash)<br>- Issuance blocked if safeguarding headroom insufficient | - `emi-issuance` plugin family (§7.2)<br>- `emi-redemption` plugin family (§7.2)<br>- Prevention synchronous: `EMI.IssuanceBlocked`<br>- Detection post-commit: `EMI.SafeguardingBreach` |
| **Redemption at par** | - Redemption submits JE that debits e-money liability, credits safeguarding cash<br>- 1:1 redemption ratio enforced by balance bounds | - JE lines: `Dr 2000 E-Money Liability`, `Cr 1000 Safeguarding Cash`<br>- Balance bounds prevent underflow/overflow<br>- Safeguarding banner shows state (ui-std.md §5.5) |
| **Transaction limits** | - Per-customer and per-day limits configurable per EMI installation<br>- Limits enforced at JE creation boundary | - Future: JE line amount validation against installed limits<br>- Risk rating influences limit thresholds (LOW=higher, HIGH=lower) |

### 3.2 AML/CFT Requirements for EMI

| Obligation | FSP Implementation | Evidence |
|---|---|---|
| **Customer due diligence** | - Five-tab KYC capture during onboarding (§3.10.2)<br>- UBO threshold ≥25% ownership flagging<br>- PEP declaration captured | - KYC case model with UBO persons (≥25% ownership)<br>- `NamibiaRules` validation: BIPA number, TIN, branch code<br>- PEP declaration (Yes/No) per UBO person |
| **Screening checks** | - Simulated IDENTITY + SANCTIONS checks during onboarding<br>- Risk rating rules applied<br>- Re-screening on bank account change | - `ScreeningCheck` entities (checkType: IDENTITY, SANCTIONS, ADVERSE_MEDIA, PEP)<br>- Risk rating rules from SME (cash-heavy/gambling/forex → HIGH)<br>- New screening check recorded on bank account change |
| **Enhanced due diligence** | - HIGH risk rating → 12-month review cycle<br>- MANUAL_REVIEW on sanctions → HIGH (12 months)<br>- Blank business type → MEDIUM (24 months) | - Risk rating rules codified in KYC review panel<br>- Review cycle duration per risk tier<br>- `nextReviewDate` on KycCase model |
| **Reporting to authorities** | - Periodic safeguarding reports (plugin family responsibility)<br>- AML transaction monitoring (future plugin) | - `EMI.SafeguardingBreach` event triggers detection path<br>- Future: `emi-regulatory-reporting` plugin family (§7.2) |
| **Record retention** | - 5-year minimum retention from relationship end/transaction date (Namibian rules)<br>- Simulated checks only in Phase-0 | - `ScreeningCheck.checkedAt` timestamp persisted<br>- `KycCase.submittedAt` and `KycCase.decidedAt`<br>- Audit log retains 5-year window (configurable) |

### 3.3 EMI: Issuance and Redemption Flows

| Flow | Pre-Condition | Process | Post-Condition | Safeguarding State |
|---|---|---|---|---|
| **Issuance** | Customer onboarding complete (KYC APPROVED)<br>Safeguarding headroom available | 1. Create JE: `Dr 2000 E-Money Liability`, `Cr 1000 Safeguarding Cash`<br>2. Submit to TigerBeetle<br>3. Finalize journal<br>4. Emit `EMI.IssuanceCompleted` | E-money liability increased<br>Safeguarding cash increased<br>Headroom reduced<br>Banner: green (sufficient) | Prevention synchronous check before JE submission<br>Headroom = approved_assets - e_money_issued |
| **Redemption** | Customer requests redemption<br>E-money balance > 0 | 1. Create JE: `Dr 1000 Safeguarding Cash`, `Cr 2000 E-Money Liability`<br>2. Submit to TigerBeetle<br>3. Finalize journal<br>4. Emit `EMI.RedemptionCompleted` | E-money liability decreased<br>Safeguarding cash decreased<br>Headroom increased<br>Banner: green (sufficient) | Detection via `EMI.SafeguardingBreach` if post-commit breach detected<br>Balance bounds prevent negative liability |
| **Issuance blocked** | Headroom insufficient (approved_assets - e_money_issued ≤ 0) | Block issuance at pre-submission check<br>Emit `EMI.IssuanceBlocked`<br>Show safeguarding banner: "Issuance blocked — coverage headroom insufficient" | No economic posting<br>Journal stays DRAFT/PENDING<br>Headroom unchanged | Prevention synchronous — blocks at boundary before TB submission |

### 3.4 EMI Regulatory Reporting

| Report Type | Frequency | Content | Responsible |
|---|---|---|---|
| **Safeguarding report** | Quarterly (or per regulatory requirement) | E-money issued, safeguarding cash, approved assets, headroom, breach events | EMI plugin family |
| **AML transaction monitoring report** | As triggered | Suspicious transactions, structured reporting, threshold breaches | Future `emi-transaction-monitoring` plugin |
| **AMLD5 customer data** | Annual / on-change | Customer identification, UBO, PEP status, risk rating | KYC onboarding data persisted; future integration |
| **Capital adequacy report** | Annual | Own funds vs. minimum capital requirement | EMI installation metadata; four-eyes-verified activation |

---

## 4. Cross-Cutting Compliance Themes

### 4.1 Four-Eyes Principle (Enforced Across PSP/EMI)

| Operation | Maker | Checker | FSP Enforcement |
|---|---|---|---|
| Create PSP tenant | PLATFORM_ADMIN | PLATFORM_ADMIN (self-approve bootstrap) | Only at root bootstrap |
| Seed PSP CoA | PSP_ADMIN | PSP_ADMIN (self-approve bootstrap) | Only when PSP has no accounts |
| Onboard supply-chain tenant | PSP_ADMIN or self-reg | PSP_ADMIN / TPTS_OPS (different `person_id`) | Four-eyes on different `person_id` |
| Approve KYC | TPTS_OPS / PSP_ADMIN | Different `person_id` from onboarding PSP Admin | UI surfaces self-review block |
| Create GL account | TENANT_ADMIN | TENANT_CHECKER or PSP_ADMIN | Four-eyes at `person_id` |
| Create and submit JE | TENANT_ADMIN | TENANT_CHECKER or PSP_ADMIN | Four-eyes at `person_id` |
| Approve JE | Different principal | Different `person_id` from maker | `403 MAKER_CANNOT_APPROVE` if same |
| Change settlement bank | TENANT_ADMIN | TENANT_CHECKER or PSP_ADMIN / TPTS_OPS | Re-triggers sanctions screening |
| Activate period | PSP_ADMIN | PSP_ADMIN (different `person_id`) | Four-eyes |

### 4.2 Audit and Evidence (PSP/EMI Common)

| Evidence Type | FSP Implementation | Regulatory Reference |
|---|---|---|
| **Mutation evidence** | Transactional outbox + journal finalization<br>Canonical hash encoding of posting plan | EMD2 § safeguarding records; PSD2 transaction tracing |
| **Decision evidence** | Policy revision IDs, before/after hashes, actor/checker IDs, effective time | PSD2 art. 4(3) — evidence of SCA decisions |
| **Audit chain** | Append-only per-tenant chain checkpoints, signed roots, immutable archive retention | EMD2 safeguarding audit; PSD2 art. 9 — access to information |
| **External anchoring** | Merkle verification CLI with external anchor verification (future) | Regulatory inspection evidence; BCP 195 TLS anchoring |
| **Outbound events** | `EMI.IssuanceBlocked`, `EMI.SafeguardingBreach`, `EMI.IssuanceCompleted`, `EMI.RedemptionCompleted` | EMD2 event reporting requirements |

### 4.3 Route Eligibility and Activation (§6.6 of Vision)

| Gate Condition | State | Behavior |
|---|---|---|
| GL account templates provisioned | `ACTIVE` | Contributed routes return `503 Plugin Not Ready` until ready |
| Posting rules registered and approved | `ACTIVE` | Posting rules cannot be resolved until approved |
| Declared dependencies active and ready | `ACTIVE` | Dependencies must be active; `503` if not |
| Secret scope provisioned | `ACTIVE` | Per-installation vault path; `503` if missing |

Until all gates are `ACTIVE`, contributed routes return `503 Plugin Not Ready` (not `403`, which is authorization failure). Route enablement is explicit and audited.

### 4.4 Inheritance and Delegation (PSP/EMI Plugins)

| Policy | Behavior | Installation Scope |
|---|---|---|
| `REQUIRED` | Must be installed for child; cannot be disabled | Parent installation issues `PluginInstallation.Revoked` to descendants |
| `PROHIBITED` | Must not be installed anywhere in subtree | Cannot be overridden by child |
| `INHERIT` | Automatically inherited; child cannot disable | Reference, not copy; revocation triggers descendant invalidation |
| `OPT_IN` | Parent offers; child activates with four-eyes | Direct installations unaffected by parent revocation |
| `OPT_OUT` | Inherited by default; child may deactivate |  |

Inheritance is a reference, not a copy. Each descendant deactivation is audited.

---

## 5. FAPI 2.0 Compliance as Foundation

All PSP and EMI compliance obligations are built on the FAPI 2.0 Security Profile foundation:

| FAPI 2.0 Non-Negotiable | PSP/EMI Relevance |
|---|---|
| **No ROPC password grant** (§2.4) | Prevents credential exposure in onboarding and login flows; critical for KYC applicant authentication |
| **PAR (Pushed Authorization Requests)** (§5.3.3.2) | Ensures authorization request parameters never in browser URL — protects sensitive financial data |
| **iss validation (RFC 9207)** (§5.3.3.2 step 5) | Prevents mix-up attacks across multiple IdPs — essential for multi-tenant PSP/EMI deployments |
| **DPoP sender constraints** (§5.3.3.1) | Binds access tokens to client — prevents token theft from compromising PSP/EMI operations |
| **PKCE S256** (§5.3.3.2) | Code verifier/proof binding — mandatory for all authorization code flows |
| **private_key_jwt client authentication** (§5.3.3.1) | Asymmetric client authentication — no shared secrets in financial flows |
| **Resource tokens in HTTP header only** (§5.3.3.1) | Prevents token leakage via URLs, query strings, or browser history |
| **Sender-constrained tokens** (§5.3.3.1, §5.3.4) | DPoP or mTLS — tokens bound to presenting client |
| **Logout trio** (§5.4, §6.4) | Server session invalidate + IdP back-channel logout + per-portal localStorage cleanup |
| **CSRF double-submit at login** (§6) | Rotated CSRF cookie on login form submission |
| **Error-UX map** (§7) | Standardized error responses — never leak token or resource data |
| **Route inventory as build artifact** (FAPI impl. guide §10) | All FAPI-compliant routes documented; verified against running server |

**Gap remediation order** (from fapi2-compliance gap-register.md):
1. ROPC login → PAR + IdP hosted sign-in (G1, CRITICAL)
2. No PAR → PAR flow implementation (G2, CRITICAL)
3. No iss validation → `iss` check in callback (G3, CRITICAL)
4. No DPoP → per-request DPoP proof generation (G4, CRITICAL)
5. Shared secret → private_key_jwt (G5, HIGH)
6. No PKCE → PKCE S256 throughout (G6, HIGH)
7. Browser credentials → full-page IdP redirect (G7, HIGH)
8. CORS at auth endpoint → remove CORS (G8, MEDIUM)
9. Token in query → header-only transmission (G9, MEDIUM)
10. No session/CSRF rotation → rotation at login (G10, MEDIUM)

**Overall FAPI 2.0 score: 8.8/10** — ready for conformance testing and E2E verification.

---

## 6. Compliance Checklist for PSP/EMI Plugin Onboarding

### 6.1 Pre-Onboarding Checks

| # | Check | Requirement | Pass Criterion |
|---|---|---|---|
| C1 | Regulatory profile set | `regulatoryProfile.licenceType` = "PSP" or "EMI" in PluginDescriptor | Value is one of: `PSP`, `EMI`, `PI` |
| C2 | CoA template contributed | PSP: 1100–5200 codes; EMI: 1000–5000 codes | `contributedGLAccounts` populated in plugin boot |
| C3 | Posting rules registered | Rules DSL compiled and approved (four-eyes) | `contributedPostingRules` non-empty and approved |
| C4 | Permissions seeded | Family-specific permission keys into RBAC catalog | `minAncestorRoleToInstall` set; permissions in catalog |
| C5 | Secret scope provisioned | Per-installation vault path ready | `context.secrets(installation_id)` available; not shared vault |
| C6 | KYC master data seeded | Business types, MCC codes, bank list, branch codes, countries, document types | `KYC master data` migration V1/V2/V3 applied; demo data present |

### 6.2 Activation Readiness Gate (§6.6)

| # | Gate Condition | State Required | Behavior if Not Ready |
|---|---|---|---|
| A1 | GL account templates provisioned | `ACTIVE` | Contributed routes → `503 Plugin Not Ready` |
| A2 | Posting rules registered and approved | `ACTIVE` | Posting rules cannot be resolved |
| A3 | Declared dependencies active and ready | `ACTIVE` | Dependencies must be active |
| A4 | Secret scope provisioned | `ACTIVE` | Per-installation secret scope must exist |

### 6.3 Ongoing Compliance Monitoring

| # | Monitor | Frequency | Alert Threshold |
|---|---|---|---|
| M1 | Safeguarding headroom (EMI) | Per-issuance (synchronous) | Headroom ≤ 0 → `EMI.IssuanceBlocked` event |
| M2 | Screening check freshness | On bank account change | Re-screening triggered; new `ScreeningCheck` recorded |
| M3 | Four-eyes compliance | Per-sensitive operation | Maker == checker → `403 MAKER_CANNOT_APPROVE` |
| M4 | FAPI 2.0 compliance | Per-deployment | `grep dist for grantType.*password` = 0; DPoP on all API calls |
| M5 | Audit chain integrity | Continuous | Orphan audit records → alert; chain checkpoint verification |
| M6 | Route eligibility | On plugin activation/deactivation | `503 Plugin Not Ready` if gates not met |

---

## 7. PSP/EMI-Specific ADRs (Analogous to Core ADRs)

### 7.1 Why PSP/EMI Logic is in Plugins Not Core

> **Decision:** PSP and EMI families are composite plugin families. No PSP/EMI logic enters the core (vision.md §7.1).
> 
> **Context:** The core owns accounts, JEs, transfers, RBAC, four-eyes, and audit. Domain-specific logic (acquiring, issuance, safeguarding, payout) varies by licence type and jurisdiction. A one-size-fits-all core would be overly complex and rigid.
> 
> **Consequences:**
> - Core remains minimal, deterministic, and auditable
> - PSP/EMI plugins implement their own CoA templates, posting rules, and compliance logic
> - Platform provides primitives: journal engine, posting state machine, RBAC, audit, TigerBeetle access (core only)
> - Plugins submit intents; core executes (plugins cannot call TB directly — removed in v2)

### 7.2 Why Direct TigerBeetle Access is Removed from Plugins

> **Decision:** Plugins cannot call TigerBeetle, hold `ledger.write`, or construct JEs; they submit intents (vision.md §5, §22).
> 
> **Context:** In v1, plugin TigerBeetle access contradicted the core-only money boundary. In v2, this is explicitly removed.
> 
> **Consequences:**
> - Plugins submit intents via `context.journal.submit(intent)` and `context.requestLedgerTransfer(intent)`
> - Core validates, constructs JE, submits to TigerBeetle
> - Audit evidence is core-anchored; plugin intent is preserved in outbox
> - Safeguarding prevention/detection is boundary-enforced by core, not plugin-optional

### 7.3 Why Safeguarding Prevention is Synchronous and Pre-Submission

> **Decision:** Safeguarding prevention is synchronous and pre-submission; detection is internal + external reconciliation (vision.md §7.7, ADR 27).
> 
> **Context:** EMD2 requires real-time headroom monitoring. Prevention blocks issuance before TB submission; detection flags breaches after commit.
> 
> **Consequences:**
> - `EMI.IssuanceBlocked` event emitted synchronously before JE submission
> - `EMI.SafeguardingBreach` event emitted post-commit if breach detected
> - Prevention control is boundary-enforced by core write boundary
> - Detection is open-loop: detected after the fact, blocks future issuance

### 7.4 Why Per-Installation Secret Scope (Not Shared Vault)

> **Decision:** Each installation has its own secret scope; shared credentials are explicit brokered capabilities (vision.md §6.2).
> 
> **Context:** Shared vault paths risk cross-installation credential exposure. Per-installation scope limits blast radius.
> 
> **Consequences:**
> - `context.secrets(installation_id)` — tenant- and installation-scoped
> - `inheritance_origin` records `DIRECT` or `INHERITED_FROM:<tenant_id>`
> - Shared KYC provider contract → explicit brokered capability, not vault path
> - Credential rotation isolated per installation

---

## 8. Recommended Next Steps

1. **Document PSP/EMI compliance obligations** — This document (complete)
2. **Create gap-register entries for PSP/EMI-specific deviations** — Map existing FSP gaps (G1–G10) to PSP/EMI requirements
3. **Implement EMI safeguarding prevention** — Synchronous headroom check before JE submission
4. **Implement EMI issuance/redemption flows** — JE patterns, balance bounds, safeguarding banner UI
5. **Add EMI regulatory reporting endpoints** — Safeguarding reports, capital adequacy
6. **Implement PSP interchange fee tracking** — MDR/revenue account posting on every capture JE
7. **Add PSP AISP/PISP consent framework** — OAuth `authorization_details` + `resource` parameters
8. **Run full FAPI 2.0 conformance** — All V1–V10 checks from verification-playbook.md
9. **Docker `make stack-up` validation** — idp/gateway/core/bff images; PKI mount; PG 5432
10. **E2E Playwright verification** — Full actor flow with FAPI 2.0 compliance

---

## References

1. [EMD2 — Directive 2017/1775 on the taking up, pursuit and prudential supervision of the business of electronic money, and repealing Directive 2009/110/EC](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32017D1775)
2. [PSD2 — Directive 2015/2366 on payment services in the internal market](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32015L002366)
3. [FAPI 2.0 Security Profile Final](https://openid.net/specs/fapi-security-profile-2_0-final.html)
4. [RFC 9700 — Best Current Practice for OAuth 2.0 Security (BCP 240)](https://datatracker.ietf.org/doc/rfc9700/)
5. [RFC 9207 — OAuth 2.0 Authorization Server Issuer Identification](https://datatracker.ietf.org/doc/rfc9207/)
6. [RFC 9449 — OAuth 2.0 Demonstrating Proof of Possession (DPoP)](https://datatracker.ietf.org/doc/rfc9449/)
7. [RFC 9126 — OAuth 2.0 Pushed Authorization Requests (PAR)](https://datatracker.ietf.org/doc/rfc9126/)
8 [RFC 8705 — OAuth 2.0 Mutual TLS Client Authentication and Certificate-Bound Access Tokens](https://datatracker.ietf.org/doc/rfc8705/)
9. [RFC 6749 — OAuth 2.0 Authorization Framework](https://datatracker.ietf.org/doc/rfc6749/)
10. [vision.md §7 — PSP and EMI Plugin Families](/#7-psp-and-emi-plugin-families)
11. [vision.md §6.6 — Activation Readiness Gate](/#66-activation-readiness-gate)
12. [vision.md §5.7 — Posting State Machine](/#57-posting-state-machine)
13. [fapi2-compliance skill](~/.pi/agent/skills/fapi2-compliance/SKILL.md)
14. [FAPI 2.0 Acceptance Checklist](~/.pi/agent/skills/fapi2-compliance/references/acceptance-checklist.md)
15. [FAPI 2.0 Flow Reference](~/.pi/agent/skills/fapi2-compliance/references/flow-reference.md)
16. [FAPI 2.0 Gap Register](~/.pi/agent/skills/fapi2-compliance/references/gap-register.md)
17. [FAPI 2.0 Verification Playbook](~/.pi/agent/skills/fapi2-compliance/references/verification-playbook.md)