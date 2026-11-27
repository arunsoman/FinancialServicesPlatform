# FAPI 2.0 Compliance Gap Register — Phase-0 Financial Services Platform

## Overview

This document registers all known Phase-0 deviations from the FAPI 2.0 Security Profile, along with remediation order and acceptance criteria. Each gap is categorized by severity and linked to the specific FAPI 2.0 normative requirement it violates.

## Gap Register

### G1: ROPC Login Flow (CRITICAL)

**Current Behavior**: `BffSessionController.login` → `IdpClient.login` POST `/idp/token` with `grantType: "password"` = ROPC. SPA form collects credentials and hands them to token machinery.

**FAPI 2.0 Violation**: §2.4 — "The resource owner password credentials grant [RFC6749] MUST NOT be used." RFC 9700 §2.4 prohibits the grant entirely because it "increases the credential attack surface, trains users to enter credentials outside the authorization server, and cannot properly support many multi-step or cryptographic authentication methods."

**Remediation**: Replace with full-page redirect to BFF `/auth/login?return_to=` → authenticated PAR (S256 PKCE + `authorization_details` + `resource`) → 302 (only `request_uri`) → IdP hosted sign-in page → callback `code+iss` → token exchange (verifier + client auth + DPoP) → session rotate → CSRF rotate.

**Acceptance Criteria** (from verification-playbook.md):
- `grep dist for grantType.*password` returns 0 matches
- Browser never sends credentials to token endpoint
- Full-page redirect to IdP hosted sign-in page observed in E2E

**Remediation Order**: 1st — highest severity, login is the entry point for all subsequent auth

---

### G2: No Pushed Authorization Requests (PAR) (CRITICAL)

**Current Behavior**: Authorization request parameters sent via GET /authorize with full query string (scopes, redirect_uris, etc. in URL).

**FAPI 2.0 Violation**: §5.3.3.2 — "shall use pushed authorization requests according to [RFC9126]" and "shall reject authorization requests sent without [RFC9126]". RFC 9700 §2.1.1 requires exact redirect URI matching and prohibits pattern/prefix matching that JAR/GET-based flows enable.

**Remediation**: Implement PAR flow: client backend POSTs authorization request to `/par` endpoint first, receives opaque `request_uri`, then only `client_id` + `request_uri` are sent to `/authorize` in the front channel.

**Acceptance Criteria**:
- `grep -r "grant_type" dist/` shows only `authorization_code`
- No authorization request parameters in browser URL bar beyond `client_id` and `request_uri`
- PAR `expires_in` enforced (typically 30 seconds)

**Remediation Order**: 2nd — login flow cannot be compliant without PAR

---

### G3: No iss (RFC 9207) Validation (CRITICAL)

**Current Behavior**: No check of `iss` parameter in authorization response; no mix-up attack prevention.

**FAPI 2.0 Violation**: §5.3.3.2 step 5 — "shall check the `iss` parameter in the authorization response according to [RFC9207] to prevent mix-up attacks." RFC 9700 §4.4.2.1 requires clients to compare received `iss` to stored issuer identifier.

**Remediation**: After authorization response, always verify `iss` matches the expected authorization server issuer identifier (`https://idp.fsp.local`). Abort flow on mismatch.

**Acceptance Criteria**:
- Code includes `iss` validation check
- E2E test confirms mismatch aborts flow
- No mix-up possible across multiple authorization servers

**Remediation Order**: 3rd — foundational validation, required before token exchange

---

### G4: No DPoP / Sender-Constrained Tokens (CRITICAL)

**Current Behavior**: Access tokens issued and used as plain Bearer tokens; no DPoP proof JWTs in API call headers.

**FAPI 2.0 Violation**: §5.3.3.1 — "shall support sender-constrained access tokens using one or both of: MTLS or DPoP." §5.3.4 — resource servers "shall support and verify sender-constrained access tokens using one or both of: MTLS or DPoP." RFC 9700 §2.2.1 recommends sender-constraining access tokens.

**Remediation**: 
- Generate DPoP proof JWT per API request (not just token exchange)
- Include `DPoP` header on all API calls: `Authorization: Bearer <access_token>`, `DPoP: <proof-jwt>`
- Resource server validates: DPoP signature, `cnf.jkt` matches access token thumbprint, `htm`/`htu` match request, `jti` replay prevention
- Access token includes `cnf` claim with JWK thumbprint

**Acceptance Criteria**:
- `grep -rn "DPoP" src/` shows proof generation on every API call
- Resource server validates DPoP on protected endpoints
- Stolen token cannot be used without valid DPoP proof

**Remediation Order**: 4th — token binding required after issuance

---

### G5: Client Authentication via Shared Secret (HIGH)

**Current Behavior**: Client authentication at token endpoint uses `client_secret` parameter.

**FAPI 2.0 Violation**: §5.3.3.1 — "shall authenticate clients using one or both of: MTLS (Section 2 of RFC 8705), `private_key_jwt` (Section 9 of OIDC)." RFC 9700 §2.5 recommends asymmetric client authentication; client secrets are discouraged for confidential clients.

**Remediation**: Replace `client_secret` with `private_key_jwt` assertion:
- Client generates private/public key pair
- Public key registered with authorization server (via JWKS or static config)
- At token exchange, client sends `client_assertion` JWT signed with private key
- `client_assertion_type = urn:ietf:params:oauth:client-assertion-type:jwt-bearer`
- `aud` in assertion = authorization server's issuer identifier as string

**Acceptance Criteria**:
- No `client_secret` in token endpoint requests
- `client_assertion` JWT present in all token exchanges
- `aud` claim in assertion = expected issuer string

**Remediation Order**: 5th — client identity must be secured before token issuance

---

### G6: No PKCE (HIGH)

**Current Behavior**: No `code_challenge` / `code_verifier` in authorization flow.

**FAPI 2.0 Violation**: §5.3.3.2 — "shall use PKCE [RFC7636] with `S256` as the code challenge method." RFC 9700 §2.1.1 makes PKCE mandatory for all authorization code flows.

**Remediation**: 
- Generate `code_verifier` (high-entropy random string, 43-128 ASCII characters)
- Compute `code_challenge = SHA256(code_verifier)` base64url-encoded
- Send `code_challenge` and `code_challenge_method=S256` in PAR or authorization request
- At token exchange, send `code_verifier`
- Server validates `SHA256(code_verifier) == code_challenge`

**Acceptance Criteria**:
- `code_challenge` present in all authorization requests
- `code_verifier` sent at token exchange
- No authorization flow without PKCE

**Remediation Order**: 6th — code binding required before token exchange

---

### G7: Browser Credentials in Front Channel (HIGH)

**Current Behavior**: SPA form collects username/password and sends them to token machinery; credentials traverse browser.

**FAPI 2.0 Violation**: §5.3.3 — clients "shall not expose open redirectors" and "shall initiate an authorization process only with the end-user's explicit or implicit consent and protect initiation... against cross-site request forgery." RFC 9700 §2.4 prohibits ROPC which inherently sends credentials through browser.

**Remediation**: 
- SPA never collects or transmits credentials
- Full-page redirect to BFF `/auth/login?return_to=`
- BFF performs redirect to IdP hosted sign-in page
- User authenticates at IdP (not application)
- Callback receives `code` only (no credentials)
- Code exchanged for token at back-channel with DPoP + private_key_jwt

**Acceptance Criteria**:
- Browser never contains username/password fields for login
- `grep -rn "password" src/portals/` shows 0 credential-collecting forms
- Full-page redirect observed in Playwright E2E

**Remediation Order**: 7th — user interface pattern, enables all other remediations

---

### G8: CORS at Authorization Endpoint (MEDIUM)

**Current Behavior**: CORS headers may be present at authorization endpoint.

**FAPI 2.0 Violation**: §5.3.2.3 — "Servers shall not support CORS [CORS.Protocol] for the authorization endpoint, as clients must perform an HTTP redirect rather than access this endpoint directly."

**Remediation**: Remove CORS support at authorization endpoint; ensure endpoint only responds to redirects, not direct fetch/AXIOS calls.

**Acceptance Criteria**:
- `Access-Control-Allow-Origin` header absent from `/authorize`
- `Access-Control-Allow-Origin` header absent from `/par`
- Authorization endpoint only serves HTML redirects

**Remediation Order**: 8th — infrastructure, does not block core flow

---

### G9: Token in Query Parameters (MEDIUM)

**Current Behavior**: Access tokens transmitted in URL query parameters or fragments.

**FAPI 2.0 Violation**: §5.3.3.1 — "shall send access tokens in the HTTP header as in Section 2.1 of OAuth 2.0 Bearer Token Usage [RFC6750] or Section 7.1 of DPoP [RFC9449]; shall NOT accept access tokens in the query parameters stated in Section 2.3 of RFC 6750." RFC 9700 §3 explicitly rejects token-in-URL patterns.

**Remediation**: 
- All access tokens sent via HTTP `Authorization: Bearer` header
- Never in query strings, fragments, or POST bodies
- DPoP proof in dedicated `DPoP` header, not bundled in token

**Acceptance Criteria**:
- `grep -rn "access_token=" src/portals/` returns 0 results
- All API calls use `Authorization: Bearer <token>` header pattern
- Tokens never appear in browser URL bar

**Remediation Order**: 9th — token transmission, critical for credential leakage prevention

---

### G10: No Session/CSRF Rotation at Login (MEDIUM)

**Current Behavior**: Session established; no CSRF token rotation; no session invalidation on re-auth.

**FAPI 2.0 Violation**: §6.2 — DPoP proof replay considerations; §6.4 — authorization request leaks can lead to CSRF. FAPI UI std §6 requires CSRF double-submit rotated at login.

**Remediation**: 
- On login, rotate/regenerate CSRF tokens
- Session invalidate/recreate pattern on re-authentication
- Double-submit cookie pattern: set CSRF cookie on login form POST, read from header on submission
- Logout trio implementation (end session + post_logout_redirect_uri + localStorage cleanup)

**Acceptance Criteria**:
- CSRF cookie set on login form POST
- CSRF header validated on form submission
- Logout clears server session + client localStorage `storageKey`
- Session attrs `FSP_SESSION_USER`/`FSP_SESSION_TOKEN` rotated

**Remediation Order**: 10th — session hygiene, supports all other flows

---

## Remediation Priority Summary

| Order | Gap | Severity | FAPI Section |
|-------|-----|----------|--------------|
| 1 | ROPC Login | CRITICAL | §2.4, §5.3.3 |
| 2 | No PAR | CRITICAL | §5.3.3.2 |
| 3 | No iss Validation | CRITICAL | §5.3.3.2 step 5 |
| 4 | No DPoP | CRITICAL | §5.3.3.1, §5.3.4 |
| 5 | Shared Secret Auth | HIGH | §5.3.3.1 |
| 6 | No PKCE | HIGH | §5.3.3.2 |
| 7 | Browser Credentials | HIGH | §5.3.3, RFC 9700 §2.4 |
| 8 | CORS at Authz Endpoint | MEDIUM | §5.3.2.3 |
| 9 | Token in Query | MEDIUM | §5.3.3.1, RFC 9700 §3 |
| 10 | No Session/CSRF Rotation | MEDIUM | §6.2, §6.4 |

## PSP/EMI-Specific Gaps (Beyond Core FAPI 2.0)

### G11: No Safeguarding Headroom Check for EMI Issuance (CRITICAL — EMI Only)

**Current Behavior**: EMI issuance journal entries submitted without pre-submission check of safeguarding headroom (`approved_assets - e_money_issued > 0`).

**Regulatory Violation**: EMD2 Art. 7 — EMI must safeguard 100% of funds received in exchange for e-money at all times. FSA safeguarding guidance requires synchronous prevention.

**Remediation**: 
- Add synchronous headroom check in `TbService` before JE submission for EMI issuance
- If headroom ≤ 0, emit `EMI.IssuanceBlocked` event and return `409` with `ISSUANCE_BLOCKED_HEADROOM`
- Prevention is synchronous and pre-submission; detection (`EMI.SafeguardingBreach`) is post-commit

**Acceptance Criteria**:
- Unit test: issuance with zero headroom returns `409` and emits `EMI.IssuanceBlocked`
- Integration test: issuance with sufficient headroom succeeds; balance bounds enforced
- UI: safeguarding banner shows correct state (ui-std.md §5.5)

**Remediation Order**: 11th (after core FAPI 2.0 gaps) — EMI-specific, blocks non-compliant issuance

---

### G12: EMI Redemption Not at Par (CRITICAL — EMI Only)

**Current Behavior**: Redemption journal entries allow non-1:1 ratios or bypass balance bounds.

**Regulatory Violation**: EMD2 Art. 10 — e-money holders have right to redeem at par (face value) at any time.

**Remediation**:
- Enforce 1:1 redemption ratio via balance bounds on `2000 E-Money Liability` (`CREDIT_BOUND`) and `1000 Safeguarding Cash` (`DEBIT_BOUND`)
- JE lines: `Dr 1000 Safeguarding Cash`, `Cr 2000 E-Money Liability` for exact same amount
- Post-commit detection: `EMI.SafeguardingBreach` if breach detected

**Acceptance Criteria**:
- Unit test: redemption with amount > balance rejected by `CREDIT_BOUND`
- Unit test: redemption amount mismatch rejected by JE validation
- Integration test: successful redemption emits `EMI.RedemptionCompleted`

**Remediation Order**: 12th

---

### G13: PSP Interchange Fee Tracking Missing (HIGH — PSP Only)

**Current Behavior**: Payment capture journal entries do not automatically post to MDR revenue accounts (4100, 5100, 5200).

**Regulatory Violation**: PSD2 Art. 94 + IFR (Regulation 2015/751) — interchange fee transparency and tracking required.

**Remediation**:
- Add posting rule for payment capture JEs to debit/credit MDR Revenue (4100), Interchange Expense (5100), Scheme Fee Expense (5200)
- Posting rule registered and approved via four-eyes
- Automatic posting on every capture JE

**Acceptance Criteria**:
- Capture JE includes lines for MDR/Interchange/Scheme Fee accounts
- Revenue recognized per transaction
- Reportable via trial balance

**Remediation Order**: 13th

---

### G14: No Plugin Activation Readiness Gates (HIGH — PSP/EMI Plugins)

**Current Behavior**: Plugin routes accessible without verifying GL provisioning, posting rules approval, dependencies, secret scope.

**Regulatory Violation**: Vision §6.6 — routes must return `503 Plugin Not Ready` until all gates `ACTIVE`.

**Remediation**:
- Implement `PluginReadinessInterceptor` checking 4 gates (A1–A4)
- Return `503 Plugin Not Ready` with `PluginNotReadyProblemDetail` if any gate not `ACTIVE`
- Gate status visible in `/internal/ops/catalog` and plugin management UI

**Acceptance Criteria**:
- Plugin routes return `503` before gates met
- All 4 gates verified in integration test
- UI shows readiness status per gate

**Remediation Order**: 14th

---

### G15: Per-Installation Secret Scope Not Enforced (HIGH — PSP/EMI Plugins)

**Current Behavior**: Plugins share vault paths or use global secrets.

**Regulatory Violation**: Vision §6.2 — each installation has its own secret scope; shared credentials are explicit brokered capabilities.

**Remediation**:
- `context.secrets(installation_id)` returns tenant- and installation-scoped secrets
- `inheritance_origin` field tracks `DIRECT` or `INHERITED_FROM:<tenant_id>`
- Shared KYC provider → explicit brokered capability, not vault path
- Credential rotation isolated per installation

**Acceptance Criteria**:
- Secret retrieval scoped to installation_id
- Inheritance origin tracked and auditable
- Rotation of one installation's secrets doesn't affect others

**Remediation Order**: 15th

---

### G16: EMI Regulatory Reporting Endpoints Missing (MEDIUM — EMI Only)

**Current Behavior**: No endpoints for safeguarding reports, capital adequacy, AMLD5 customer data.

**Regulatory Violation**: EMD2 safeguarding reporting; AMLD5 customer data requirements.

**Remediation**:
- `GET /api/v1/emi/reports/safeguarding` — quarterly safeguarding report
- `GET /api/v1/emi/reports/capital-adequacy` — annual capital adequacy
- `GET /api/v1/emi/reports/aml-customer-data` — annual/on-change customer data
- Reports sourced from audit outbox + KYC data + EMI installation metadata

**Acceptance Criteria**:
- All 3 endpoints return structured data
- Safeguarding report includes headroom, breaches, issuance/redemption volumes
- Capital adequacy includes own funds vs minimum requirement
- AML data includes UBO ≥25%, PEP status, risk rating, screening freshness

**Remediation Order**: 16th

---

## Acceptance Checklist (from verification-playbook.md)

- [ ] `grep dist for grantType.*password` = 0
- [ ] `grep -rn "client_secret" src/` = 0 (token endpoint requests)
- [ ] `grep -rn "DPoP" src/` present on all API calls
- [ ] `grep -rn "code_challenge" src/` present in auth flow
- [ ] Full-page redirect to IdP hosted sign-in observed in E2E
- [ ] `iss` validation code present and tested
- [ ] No access tokens in browser URL bar
- [ ] Logout trio (end session + redirect + localStorage cleanup) works
- [ ] CSRF double-submit rotated at login
- [ ] Route inventory build artifact generated and verified

## PSP/EMI-Specific Acceptance Checklist

- [ ] EMI issuance blocked when safeguarding headroom ≤ 0 (G11)
- [ ] EMI redemption at par enforced via balance bounds (G12)
- [ ] PSP capture JEs post to MDR/Interchange/Scheme Fee accounts (G13)
- [ ] Plugin routes return 503 until all 4 readiness gates ACTIVE (G14)
- [ ] Per-installation secret scope enforced with inheritance tracking (G15)
- [ ] EMI regulatory reporting endpoints return structured data (G16)

---

*This gap register is living document. Gaps remediated in order 1–16 above. Verification playbook at references/verification-playbook.md governs E2E acceptance.*