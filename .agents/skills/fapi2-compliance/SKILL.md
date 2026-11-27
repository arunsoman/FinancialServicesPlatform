---
name: fapi2-compliance
description: FAPI 2.0 Security Profile rules for FSP (PSP/EMI) — tokens never in the browser, BFF owns the session (PAR + PKCE + DPoP), gateway validates JWT + DPoP, core is the sole PDP, four-eyes, secrets and crypto. Load before any change to auth, sessions, BFF/gateway/core endpoints, permissions, or anything a browser calls; use references/acceptance-checklist.md to sign a change off.
---

<!-- Vendored into the repo (was ~/.pi/agent/skills/fapi2-compliance) so every agent and machine has it.
     Edit here; this copy is the source of truth for the FSP repository. -->

# FAPI 2.0 Compliance Skill for PSP and EMI Service Providers

A developer skill for building FAPI 2.0 compliant financial services platform applications. This skill provides the knowledge and implementation patterns required to meet the OpenID Foundation FAPI 2.0 Security Profile requirements, specifically tailored for **Payment Service Providers (PSPs)** and **Electronic Money Institutions (EMIs)** under PSD2 and EMD2 regulatory frameworks.

## Skill Overview

This skill covers the FAPI 2.0 Security Profile ([FAPI-2-SECURITY-PROFILE](https://openid.net/specs/fapi-security-profile-2_0-final.html)), grounded in the normative references:

- [RFC 6749] OAuth 2.0 Authorization Framework
- [RFC 6750] OAuth 2.0 Bearer Token Usage
- [RFC 7636] Proof Key for Code Exchange by OAuth Public Clients
- [RFC 8414] OAuth 2.0 Authorization Server Metadata
- [RFC 8705] OAuth 2.0 Mutual-TLS Client Authentication and Certificate-Bound Access Tokens
- [RFC 9126] OAuth 2.0 Pushed Authorization Requests
- [RFC 9207] OAuth 2.0 Authorization Server Issuer Identification
- [RFC 9449] OAuth 2.0 Demonstrating Proof of Possession (DPoP)
- [RFC 9700] Best Current Practice for OAuth 2.0 Security (BCP 240)
- [PSD2] Directive (EU) 2015/2366 — Payment Services Directive 2
- [EMD2] Directive (EU) 2017/1775 — Electronic Money Directive 2

---

## Regulatory Context: PSP vs EMI

| Aspect | PSP (Payment Service Provider) | EMI (Electronic Money Institution) |
|--------|--------------------------------|-------------------------------------|
| **Primary Directive** | PSD2 (2015/2366) | EMD2 (2017/1775) |
| **Core Activity** | Execute payment transactions (direct debits, credit transfers, card payments, money remittance, PIS, AIS) | Issue electronic money (stored value), provide payment services |
| **Capital Requirements** | €20,000–€125,000 depending on services (PSD2 Annex I) | €350,000 minimum (EMD2 Art. 4) |
| **Ongoing Own Funds** | Linked to payment volume (Methods A/B/C) | Higher of €350,000 or 2% of avg outstanding e-money (Method D) |
| **Safeguarding** | Required for funds held beyond T+1; segregation from own funds | **Required 100% at all times**; segregation or insurance/guarantee (EMD2 Art. 7) |
| **Redemption** | Not applicable (no stored value) | **Mandatory at par**; e-money holders can redeem any time at face value |
| **Interest on e-money** | N/A | **Prohibited** (EMD2 Art. 11) |
| **SCA Requirements** | Yes, for all electronic remote payment transactions (PSD2 Art. 97) | Yes, for payment services under PSD2 |
| **DORA (since Jan 2025)** | Yes — payment institutions in scope | Yes — EMIs explicitly in scope |

---

## Core Non-Negotiables (Mandatory FAPI 2.0 Requirements)

### 5.3.3 General Client Requirements (FAPI 2.0 Security Profile)

1. **Sender-constrained access tokens** — Clients shall support sender-constrained access tokens using one or both of:
   - Mutual TLS (RFC 8705) — transport-layer binding via mTLS
   - DPoP (RFC 9449) — application-layer binding via signed JWT in DPoP header
   - *Note: Bearer tokens alone are explicitly forbidden for resource access*

2. **Client authentication** — Clients shall authenticate using one or both of:
   - mTLS (Section 2 of RFC 8705)
   - `private_key_jwt` (Section 9 of OIDC) — JWT signed with client's private key, `aud` claim MUST be the authorization server's issuer identifier as a string (not array)

3. **Access tokens in HTTP header only** — shall send access tokens in the HTTP header as in Section 2.1 of RFC 6750 or Section 7.1 of DPoP [RFC9449]; **never in query parameters**

4. **PKCE with S256** — shall use PKCE with S256 code challenge method for all authorization code flows

5. **No ROPC ever** — the resource owner password credentials grant MUST NOT be used (RFC 9700 §2.4)

### 5.3.3.2 Authorization Code Flow Requirements

1. **Authorization code grant only** — shall use the authorization code grant (RFC 6749)
2. **Pushed Authorization Requests (PAR)** — shall use pushed authorization requests according to RFC 9126
3. **PKCE mandatory** — shall use PKCE with S256 code challenge method
4. **iss validation** — shall check the `iss` parameter in the authorization response according to RFC 9207 to prevent mix-up attacks
5. **Only client_id + request_uri** — shall only send `client_id` and `request_uri` request parameters to the authorization endpoint; all other parameters sent via PAR (RFC 9126)

### 5.3.2 Authorization Server Requirements

1. **Reject ROPC** — shall reject requests using the resource owner password credentials grant
2. **Confidential clients only** — shall only support confidential clients as defined in RFC 6749
3. **Sender-constrained tokens only** — shall only issue sender-constrained access tokens (MTLS or DPoP)
4. **Client authentication** — shall authenticate clients using MTLS (Section 2 of RFC 8705) or `private_key_jwt` (Section 9 of OIDC)
5. **iss enforcement** — shall only accept its issuer identifier value as a string in the `aud` claim received in client authentication assertions
6. **PKCE requirement** — shall require PKCE with S256
7. **Pushed Authorization Requests** — shall support CIBA-style PAR; shall reject authorization requests sent without PAR
8. **request_uri one-time use** — shall enforce one-time use of `request_uri` values; enforcement at point of authorization (after user consent), not at loading
9. **Authorization codes single-use** — shall reject an authorization code if it has been previously used
10. **No refresh token rotation** — except for infrastructure migration/extraordinary circumstances, with option to retry with old refresh token
11. **Authorization codes max lifetime 60 seconds**
12. **If using DPoP, shall support "Authorization Code Binding to DPoP Key"** (Section 10.1 of RFC 9449)

### 5.3.4 Resource Server Requirements

1. **Accept tokens in HTTP header only** — shall accept access tokens in the HTTP header as in RFC 6750 §2.1 or DPoP §7.1; **shall NOT accept in query parameters**
2. **Token validity** — shall verify validity, integrity, expiration and revocation status
3. **Scope/permission verification** — shall verify that the authorization represented by the access token is sufficient for the requested resource access
4. **Sender-constrained token verification** — shall support and verify sender-constrained access tokens using MTLS or DPoP
5. **Audience restriction** — tokens should be restricted to minimum required privileges

### 5.4 Cryptography and Secrets

1. **JWT algorithms** — PS256, ES256, or EdDSA (Ed25519); **never the `none` algorithm**
2. **RSA keys minimum 2048 bits**; **EC keys minimum 224 bits**
3. **128 bits of entropy** for credentials not intended for end-users
4. **JWKS over TLS only** — JWKS URI endpoints shall only be served over TLS

---

## PSP-Specific FAPI 2.0 Compliance Requirements

### PSD2 Article 97 — Strong Customer Authentication (SCA)

| Requirement | FAPI 2.0 Mapping | Implementation |
|-------------|------------------|----------------|
| SCA for online account access | FAPI 2.0 login flow (PAR + DPoP + PKCE) | Full-page redirect to IdP hosted sign-in; no SPA credentials form |
| SCA for electronic payment initiation | Transaction signing via DPoP proof per request | Each payment API call includes fresh DPoP proof bound to amount + payee |
| Dynamic linking (amount + payee) | DPoP `htu` + `htm` + `jti` + `cnf.jwk` per request | Payment amount and payee ID encoded in DPoP proof claims |
| Exemptions (contactless, low-value, trusted beneficiaries, recurring) | Handled at authorization server policy level | AS evaluates exemption criteria; returns token with appropriate scope |

### PSD2 Articles 65-66 — Access to Payment Accounts (AIS/PIS)

| Requirement | FAPI 2.0 Mapping | Implementation |
|-------------|------------------|----------------|
| ASPSP must provide access via dedicated interface | FAPI 2.0 resource server with DPoP verification | `/api/v1/accounts`, `/api/v1/transactions` endpoints require DPoP |
| Identification via qualified certificates | mTLS or `private_key_jwt` client auth | TPPs authenticate with registered JWKS; `client_assertion` at token endpoint |
| Secure communication (SCA-RTS Art 30-36) | DPoP + TLS 1.2+ with BCP195 cipher suites | All API calls over TLS; DPoP proof per request |
| No contractual preconditions for access | FAPI 2.0 interceptor enforces catalog routes | Gateway validates token + catalog before forwarding to core |

### PSD2 Article 95 — Operational & Security Risk Management

| Requirement | FAPI 2.0 Mapping | Implementation |
|-------------|------------------|----------------|
| Incident reporting | Audit service + outbox events | `REP018` operational risk reporting; major incident notification within 4 hours |
| Security measures | DPoP + mTLS + PKCE + PAR | All FAPI 2.0 mechanisms implemented as defense-in-depth |
| ICT risk management | DORA compliance (since Jan 2025) | Third-party ICT register, resilience testing, incident reporting |

### PSP-Specific Compliance Evidence Mapping

| PSP Obligation | FSP Implementation | Compliance Evidence |
|----------------|--------------------|---------------------|
| **Transaction monitoring** | Journal entries with full correlation chain; Screening checks (IDENTITY + SANCTIONS) during onboarding | KYC case model with `riskRating`; `ScreeningCheck` entities |
| **Interchange fee tracking** | MDR revenue accounts (4100, 5100, 5200) in PSP CoA template | JE lines debit/credit these accounts per transaction |
| **Settlement reporting** | TigerBeetle ledger per currency; Per-book balance endpoints | `GET /v1/tenants/{tenantId}/books/{bookId}/gl-accounts/{id}/balance` |
| **Funds segregation** | Per-tenant book isolation; Each installation bound to one tenant | `Book` model: `bookId`, `tenantId`, `currency`, `ledgerId` |
| **Customer redress** | Audit log with correlation chain; Reversal via `reversal_of_entry_id` | `JournalEntry.Reversed` event type; ADR 25 for true reversals |

---

## EMI-Specific FAPI 2.0 Compliance Requirements

### EMD2 Core Requirements

| Requirement | FAPI 2.0 Mapping | Implementation |
|-------------|------------------|----------------|
| **Safeguarding 100% of e-money funds** | Journal entry engine with balance bounds | `1000 Safeguarding Cash` (`DEBIT_BOUND`), `1010 Safeguarding Approved Assets` (`NONE`), `2000 E-Money Liability` (`CREDIT_BOUND`) |
| **Capital requirements** | EMI plugin installation four-eyes + capital check | `minAncestorRoleToInstall` in PluginDescriptor; `requiredParentEntitlement` |
| **Issuance at par** | Issuance JE: `Dr 2000 E-Money Liability`, `Cr 1000 Safeguarding Cash` | Synchronous headroom check before submission (`EMI.IssuanceBlocked` event) |
| **Redemption at par** | Redemption JE: `Dr 1000 Safeguarding Cash`, `Cr 2000 E-Money Liability` | 1:1 ratio enforced by balance bounds; `EMI.RedemptionCompleted` event |
| **No interest on e-money** | Balance bounds prevent interest accrual on liability account | `CREDIT_BOUND` on e-money liability prevents negative balance (interest) |

### EMI Safeguarding — Prevention vs Detection (Vision §7.7, ADR 27)

| Layer | Mechanism | FAPI 2.0 Integration |
|-------|-----------|----------------------|
| **Prevention (synchronous, pre-submission)** | Headroom check: `approved_assets - e_money_issued > 0` before JE submission | Blocks JE at `TbService` boundary; emits `EMI.IssuanceBlocked` |
| **Detection (post-commit)** | Post-commit reconciliation; `EMI.SafeguardingBreach` event | Detected after TB commit; blocks future issuance; alerts operators |

### EMI Regulatory Reporting (Quarterly/Annual)

| Report Type | Frequency | FAPI 2.0 Integration |
|-------------|-----------|----------------------|
| Safeguarding report | Quarterly | `EMI.SafeguardingBreach` events + headroom metrics via audit outbox |
| AML transaction monitoring | As triggered | Screening check freshness on bank account change; `ScreeningCheck` entities |
| Capital adequacy | Annual | EMI installation metadata; four-eyes-verified activation |
| AMLD5 customer data | Annual / on-change | KYC onboarding data (UBO ≥25%, PEP declaration, risk rating) |

### EMI-Specific FAPI 2.0 Flow: Issuance

```
EMI Plugin Intent
    │
    ▼  POST /api/v1/emi/issuance (with Idempotency-Key)
    │   {amount, currency, customerId, walletId}
    │
    ▼  Core validates:
    │   - KYC APPROVED
    │   - Safeguarding headroom > 0 (synchronous check)
    │   - Balance bounds on 2000 (CREDIT_BOUND) and 1000 (DEBIT_BOUND)
    │
    ▼  If headroom insufficient → EMI.IssuanceBlocked event → 409 response
    │
    ▼  Creates Journal Entry:
    │   Dr 2000 E-Money Liability (amount)
    │   Cr 1000 Safeguarding Cash (amount)
    │   Four-eyes approval required (maker ≠ checker)
    │
    ▼  Submits to TigerBeetle via TbService (deterministic transfer IDs)
    │
    ▼  On COMMITTED → EMI.IssuanceCompleted event + audit record
    │
    ▼  Safeguarding banner UI updated (green/amber/red per ui-std.md §5.5)
```

### EMI-Specific FAPI 2.0 Flow: Redemption

```
EMI Plugin Intent
    │
    ▼  POST /api/v1/emi/redemption (with Idempotency-Key)
    │   {amount, currency, customerId, walletId, payoutAccount}
    │
    ▼  Core validates:
    │   - E-money balance ≥ amount
    │   - KYC APPROVED
    │   - Balance bounds on 2000 (CREDIT_BOUND) and 1000 (DEBIT_BOUND)
    │
    ▼  Creates Journal Entry:
    │   Dr 1000 Safeguarding Cash (amount)
    │   Cr 2000 E-Money Liability (amount)
    │   Four-eyes approval required
    │
    ▼  Submits to TigerBeetle via TbService
    │
    ▼  On COMMITTED → EMI.RedemptionCompleted event + audit record
    │
    ▼  Post-commit detection: EMI.SafeguardingBreach if breach detected
    │
    ▼  Safeguarding banner UI updated
```

---

## Compliance Flow (Full FAPI 2.0 Authorization Code Flow with PAR and DPoP)

### Overview Diagram

```
Client Backend (BFF)
    │
    ▼  POST /par (RFC 9126, authenticated via private_key_jwt or mTLS)
    │   {grant_type, code_challenge, code_challenge_method=S256,
    │    redirect_uris, response_type=code, scope, authorization_details, resource}
    │
    ▼  ← 201 {request_uri, expires_in}
    │
    ▼  User Agent: GET /authorize?client_id&request_uri=<opaque ticket>
    │
    ▼  ← 302 Redirect to IdP hosted sign-in page
    │
    ▼  User authenticates at IdP (hosted page, NOT SPA credentials form)
    │
    ▼  ← 302 Back to /authorize with code + state + iss
    │
    ▼  POST /token (DPoP-wrapped)
    │   Authorization: DPoP <proof-jwt>
    │   DPoP: {htu, htm, jti, iat, cnf.jwk, sig}
    │   grant_type=authorization_code
    │   code=<authz_code>
    │   client_assertion=<private_key_jwt>
    │   client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
    │
    ▼  ← 200 {access_token, token_type, expires_in, cnf.{jkt or x5t#S256}}
    │
    ▼  API calls: Authorization: Bearer <access_token>, DPoP <new-proof-jwt>
```

### Step-by-Step Flow

#### 1. Pushed Authorization Request (PAR)
- Client's backend makes a POST to the authorization server's `/par` endpoint
- All authorization request parameters are included in the POST body
- Client authenticates using `private_key_jwt` or mTLS (NO client secret)
- Authorization server validates, stores parameters, returns opaque `request_uri`
- `expires_in` typically 30 seconds

#### 2. Minimal Front-Channel Redirect
- Client redirects user agent to `/authorize` endpoint
- URL contains only: `client_id` and `request_uri`
- **No scopes, no PKCE challenge, no state, no nonce in the URL**
- The `request_uri` is an opaque ticket referencing pre-validated parameters

#### 3. IdP Hosted Sign-In
- User reaches the IdP's hosted login page (not an SPA form)
- This is a full page redirect — the browser leaves the client context
- User enters credentials at the IdP (not the application)
- **This is the critical separation: SPA never the OAuth client**

#### 4. Authorization Response with `iss`
- After authentication, the authorization server returns a response at `/authorize`
- The response includes the `iss` parameter per RFC 9207
- Client MUST check `iss` against the expected authorization server issuer identifier (`https://idp.fsp.local`). Abort flow on mismatch.
- Authorization code is issued

#### 5. Token Exchange with DPoP
- Client's backend POSTs to the token endpoint
- Includes:
  - `grant_type=authorization_code`
  - `code=<authorization_code>` 
  - `client_assertion=<private_key_jwt>` signed with client's private key
  - `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`
  - `DPoP` header containing a new proof JWT for the token endpoint
- The DPoP proof JWT contains:
  - `htu`: URL of the token endpoint
  - `htm`: HTTP method (POST)
  - `jti`: unique JWT ID to prevent replay
  - `iat`: issued-at timestamp
  - `cnf.jwk`: client's public key
- Authorization server validates DPoP proof, extracts `jkt` (thumbprint)
- Authorization server issues access token with `cnf` claim binding to the public key

#### 6. API Calls with DPoP
- All subsequent API calls include:
  - `Authorization: Bearer <access_token>`
  - `DPoP: <new proof JWT for this specific request>`
- Each DPoP proof is freshly generated per request
- Proof contains:
  - `htu`: the API endpoint URL
  - `htm`: the HTTP method
  - `jti`: new unique ID (replay prevention via server-side cache)
  - `iat`: current timestamp
  - `cnf.jwk`: the client's public key
- Resource server validates:
  1. DPoP proof signature using `jwk` from header
  2. `cnf.jkt` in access token matches SHA-256 thumbprint of DPoP proof's `jwk`
  3. `htm` and `htu` match the current request
  4. `jti` not previously seen (replay prevention)
  5. `iat` is recent

#### 7. CSRF Protection at Login
- CSRF double-submit pattern at login form rotation
- Rotated at login (per FAPI UI std §6)
- Form POST with double-submit cookie pattern

#### 8. Logout Trio
- End session endpoint with `id_token_hint`
- `post_logout_redirect_uri` validation
- Client-side localStorage session cleanup with per-portal `storageKey` isolation

#### 9. Error-UX Map
- Standardized error responses for FAPI compliance violations
- Mapping of error codes to UX patterns
- RFC 9207 `iss` validation errors handled gracefully

#### 10. Route Inventory as Build Artifact
- All FAPI-compliant routes documented as build artifact
- Includes: `/auth/login`, `/auth/callback`, `/auth/logout`, `/bff/session`, `/bff/csrf`, `/api/me/permissions`
- Generated from code annotations and verified against running server

---

## PSP/EMI Plugin Architecture Compliance (Vision §7)

### Plugin Compliance Gates (Vision §6.6 — Activation Readiness Gate)

| Gate | Condition | State Required | Behavior if Not Ready |
|------|-----------|----------------|----------------------|
| A1 | GL account templates provisioned | `ACTIVE` | Contributed routes → `503 Plugin Not Ready` |
| A2 | Posting rules registered and approved | `ACTIVE` | Posting rules cannot be resolved |
| A3 | Declared dependencies active and ready | `ACTIVE` | Dependencies must be active; `503` if not |
| A4 | Secret scope provisioned | `ACTIVE` | Per-installation vault path; `503` if missing |

Until all gates are `ACTIVE`, contributed routes return `503 Plugin Not Ready` (not `403`).

### Plugin Secret Scoping (Vision §6.2)

- Each installation has its own secret scope: `context.secrets(installation_id)`
- Shared credentials are explicit brokered capabilities, not shared vault paths
- `inheritance_origin` records `DIRECT` or `INHERITED_FROM:<tenant_id>`
- Credential rotation isolated per installation

### Four-Eyes for Plugin Operations (Invariant §1)

| Operation | Maker | Checker | FSP Enforcement |
|-----------|-------|---------|-----------------|
| Install PSP/EMI plugin | PSP_ADMIN / TENANT_ADMIN | Different `person_id` (PSP_ADMIN / TPTS_OPS) | UI surfaces self-review block |
| Activate EMI safeguarding | EMI_ADMIN | EMI_CHECKER (different `person_id`) | `403 MAKER_CANNOT_APPROVE` if same |
| Provision CoA templates | PSP_ADMIN | PSP_ADMIN (bootstrap only) | Only when PSP has no accounts |
| Configure posting rules | TENANT_ADMIN | TENANT_CHECKER or PSP_ADMIN | Four-eyes at `person_id` |

---

## References

- [FAPI-2-SECURITY-PROFILE] https://openid.net/specs/fapi-security-profile-2_0-final.html
- [FAPI-2-ATTACKER-MODEL] https://openid.net/specs/fapi-attacker-model-2_0-final.html
- [RFC 9700] https://datatracker.ietf.org/doc/rfc9700/
- [RFC 9126] https://datatracker.ietf.org/doc/rfc9126/
- [RFC 9207] https://datatracker.ietf.org/doc/rfc9207/
- [RFC 9449] https://datatracker.ietf.org/doc/rfc9449/
- [RFC 8705] https://datatracker.ietf.org/doc/rfc8705/
- [RFC 7636] https://datatracker.ietf.org/doc/rfc7636/
- [PSD2] https://eur-lex.europa.eu/eli/dir/2015/2366/2025-01-17/eng
- [EMD2] https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32017D1775
- [FCA Approach Document] https://www.fca.org.uk/publication/finalised-guidance/fca-approach-payment-services-electronic-money-2017-november-2024.pdf
- [BCP 195] https://www.rfc-editor.org/info/bcp195
- Project: `plans/psp-emi-compliance.md` — PSP and EMI Service Provider Compliance Obligations
- Project: `vision.md` §7 — PSP and EMI Plugin Families
- Project: `FSP-Spring-Boot-FAPI2-Implementation-Guide (1).md` — Implementation Guide