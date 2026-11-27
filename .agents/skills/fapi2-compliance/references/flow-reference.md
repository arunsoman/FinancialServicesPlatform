# FAPI 2.0 Compliant Flow Reference — Financial Services Platform

## Flow Overview

This document defines the complete FAPI 2.0 compliant authorization code flow with Pushed Authorization Requests (PAR), DPoP, and private_key_jwt client authentication. This flow replaces the existing ROPC-based login and implements all FAPI 2.0 non-negotiables.

## Actors

| Actor | Description |
|-------|-------------|
| **Resource Owner (RO)** | End user whose identity and attributes are being authorized |
| **User Agent (UA)** | Browser controlled by the RO |
| **Client** | Confidential application backend (FSP BFF) — never a public SPA OAuth client |
| **Authorization Server (AS)** | IdP hosting sign-in, issuing tokens per FAPI 2.0 |
| **Resource Server (RS)** | FSP core API — validates sender-constrained tokens |

## Flow Sequence Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                      CLIENT BACKEND                              │
│ 1. POST /par ────────────────────────────────────────────→        │
│    {grant_type, code_challenge, code_challenge_method=S256,     │
│     redirect_uris, response_type=code, scope, authorization_details,│
│     resource, client_assertion, client_assertion_type}         │
│    Auth: private_key_jwt or mTLS                                │
│                                                                 │
│ 2. ← 201 {request_uri, expires_in}                               │
│                                                                 │
│ 3. GET /authorize?client_id=&request_uri=<opaque>────────────→ │
│    (only client_id + request_uri — NO other params)             │
│                                                                 │
│ 4. ← 302 Redirect to IdP hosted sign-in page                     │
│    (full page UA redirect — SPA never the OAuth client)         │
│                                                                 │
│ 5. User authenticates at IdP (hosted page, credentials at IdP)   │
│                                                                 │
│ 6. ← 302 Back to /authorize?code=<authz_code>&state=&iss=...     │
│                                                                 │
│ 7. POST /token ───────────────────────────────────────────────→ │
│    grant_type=authorization_code&code=<authz_code>              │
│    client_assertion=<jwt-signed-with-private-key>               │
│    client_assertion_type=urn:ietf:params:oauth:client-assertion-  │
│    type:jwt-bearer& DPoP: <proof-JWT-for-token-endpoint>         │
│                                                                 │
│ 8. ← 200 {access_token, token_type=Bearer, expires_in,           │
│    cnf: {jkt: <thumbprint-of-client-public-key>}                │
│                                                                 │
│ 9. API Calls: Authorization: Bearer <access_token>              │
│    DPoP: <new proof-JWT-per-request>                            │
│                                                                 │
│10. ← 401 if DPoP invalid, jti replay, cnf mismatch              │
└─────────────────────────────────────────────────────────────────┘
```

## Step-by-Step Implementation

### Step 1: Pushed Authorization Request (PAR)

**Endpoint**: `POST /par` at the authorization server

**Request Body** (all parameters that used to be in `/authorize` GET query):

```http
POST /par HTTP/1.1
Host: idp.fsp.local
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code_challenge=<E38...S256>
&code_challenge_method=S256
&redirect_uris=https%3A%2F%2Fspa.fsp.local%2Fcallback
&response_type=code
&scope=kyc.review
&authorization_details=...  (optional, per RFC 9396)
&resource=https://core.fsp.local/v1/accounts  (optional, per RFC 8707)
&client_assertion=<JWT-signed-with-private-key>
&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
```

**Client Authentication** at `/par`:
- **private_key_jwt**: JWT signed with client's private key, `aud` = AS issuer identifier as string
  ```json
  {
    "iss": "client-fsp-spa",       // client ID — MUST match registered value
    "sub": "client-fsp-spa",       // subject — MUST equal client ID
    "aud": "https://idp.fsp.local", // AS issuer — string, NOT array
    "jti": "<unique-nonce>",
    "exp": <timestamp+60s>,
    "iat": <now>
  }
  ```
- **mTLS**: Client certificate presented at TLS handshake; AS extracts client identity

**Response**:
```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "request_uri": "urn:ietf:params:oauth:request_uri:jU-2...s4-E",
  "expires_in": 30
}
```

**Key Security Properties**:
- Parameters in POST body, not URL — no browser history leakage
- Client authenticated via asymmetric key (no shared secret)
- `expires_in` limits window for request_uri reuse
- AS validates and stores all parameters before user involvement

---

### Step 2: Minimal Front-Channel Redirect

**Endpoint**: `GET /authorize`

**Query Parameters** (only what the browser needs):

```http
GET /authorize HTTP/1.1
Host: idp.fsp.local

client_id=client-fsp-spa
&request_uri=urn:ietf:params:oauth:request_uri:jU-2...s4-E
```

**Critical**: Only `client_id` and `request_uri` are sent. No scopes, no PKCE challenge, no state, no nonce. All pre-validated by PAR.

**Security Properties**:
- URL bar contains no sensitive data
- Browser history only records the minimal URL
- No referer leakage of scopes/parameters
- URL length limits no longer constrain the flow

---

### Step 3: IdP Hosted Sign-In Page

**This is the critical FAPI 2.0 separation**:

- The user's browser is redirected to the IdP's **hosted sign-in page**
- **NOT** an SPA form collecting credentials
- The IdP page is served from `https://idp.fsp.local` (or configured IdP domain)
- User enters credentials **at the IdP**, not at the application
- This is a full-page HTTP redirect — the UA leaves the client context

**What this achieves**:
- SPA never acts as OAuth client (FAPI 2.0 §5.3.1: "public clients are not within scope")
- Credentials never traverse the application's browser context
- IdP handles authentication, MFA, session management
- Application receives only an authorization code back

**Redirect pattern**:
```http
HTTP/1.1 302 Found
Location: https://idp.fsp.local/authorize?...(IdP-specific params...)
```

---

### Step 4: Authorization Response with iss

**After user authenticates at IdP**, the browser is redirected back:

```http
HTTP/1.1 302 Found
Location: https://spa.fsp.local/callback?
    code=Abc123Xyz999
    &state=xyz789
    &iss=https://idp.fsp.local
```

**Critical checks**:
1. **`iss` parameter validation** — Client MUST verify `iss` in authorization response matches expected issuer identifier (`https://idp.fsp.local`). This prevents mix-up attacks where a malicious AS tricks the client into accepting code from another AS.

2. **Code single-use** — Authorization code can only be redeemed once at the token endpoint.

3. **State validation** — If `state` parameter is present, it must match the value initiated in the flow (or be validated via PKCE/nonce).

---

### Step 5: Token Exchange with DPoP and private_key_jwt

**Endpoint**: `POST /token` at the authorization server

**Request Body**:

```http
POST /token HTTP/1.1
Host: idp.fsp.local
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=<authorization_code_from_step_4>
&client_assertion=<JWT-signed-with-client-private-key>
&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
&DPoP=<proof-JWT-for-token-endpoint>
```

**client_assertion JWT** (signed with client's private key):

```json
{
  "iss": "client-fsp-spa",     // client ID — MUST match registered value
  "sub": "client-fsp-spa",     // subject — MUST equal client ID
  "aud": "https://idp.fsp.local", // AS issuer — string, NOT array (FAPI 2.0 mandate)
  "jti": "<unique-nonce-for-replay-prevention>",
  "exp": <timestamp+60s>,      // short-lived, per RFC 9700
  "iat": <unix-timestamp>
}
```

**DPoP proof JWT** for the token endpoint:

```json
{
  "typ": "dpop+jwt",
  "alg": "ES256",
  "htu": "https://idp.fsp.local/token",   // URL this proof is bound to
  "htm": "POST",                            // HTTP method
  "jti": "<fresh-nonce>",                   // must be unique per token exchange
  "iat": <now>,
  "cnf": {
    "jwk": {
      "kty": "EC",
      "crv": "P-256",
      "x": "<public-x>",
      "y": "<public-y>"
    }
  }
}
```

**Authorization Server Validation** at token exchange:

1. Validate `client_assertion` signature using registered public key
2. Validate `client_assertion.aud` = AS issuer identifier (string, not array)
3. Validate DPoP proof: signature, `htu`/`htm` match current request
4. Extract `cnf.jkt` (JWK thumbprint) from DPoP proof
5. Issue access token with `cnf` claim:
   ```json
   {
     "iss": "https://idp.fsp.local",
     "sub": "resource-owner-id",
     "aud": "https://core.fsp.local",
     "exp": <expiry>,
     "iat": <issue-time>,
     "cnf": {
       "jkt": "<SHA-256-thumbprint-of-client-public-key>"
     }
   }
   ```

**Key Properties**:
- Client authenticated via private_key_jwt (no shared secret)
- Token bound to client public key via `cnf` claim
- DPoP proof binds token to specific token endpoint call
- `jti` prevents replay of token exchange

---

### Step 6: API Calls with Per-Request DPoP

**All subsequent API calls** to the resource server include:

```http
GET /api/v1/accounts HTTP/1.1
Host: core.fsp.local
Authorization: Bearer <access_token_from_step_5>
DPoP: <new proof-JWT-for-this-specific-request>
```

**Each DPoP proof is freshly generated** per request:

```json
{
  "typ": "dpop+jwt",
  "alg": "ES256",
  "htu": "https://core.fsp.local/api/v1/accounts",  // exact request URL
  "htm": "GET",                                      // request method
  "jti": "<unique-nonce>",                           // never reused
  "iat": <current-timestamp>,
  "cnf": {
    "jwk": { ...client public key... }
  }
}
```

**Resource Server Validation** per RFC 9449 & FAPI 2.0 §5.3.4:

1. **Parse Authorization header** — extract Bearer access token
2. **Validate token signature** — verify JWT signature against AS public key
3. **Extract `cnf.jkt`** from access token — expected thumbprint
4. **Validate DPoP proof signature** — verify using `jwk` from DPoP header
5. **Compute SHA-256 thumbprint** of DPoP proof's `jwk`; compare with `cnf.jkt` from token
   - *Match proves: same key that signed the DPoP proof is the one the AS bound this token to*
6. **Validate `htm`** matches the HTTP method of the current request
7. **Validate `htu`** matches the full URL of the current request (scheme + host + path + query)
8. **Check `jti`** against server-side replay cache — reject if previously seen
9. **Validate `iat`** is recent (within acceptable clock skew, max 10-30 seconds future per FAPI 2.0 §5.3.2.1.13)

**If all checks pass**: Grant access to the requested resource.
**If any check fails**: Return `401 Unauthorized`.

**Key Security Properties**:
- Stolen token cannot be used without valid per-request DPoP proof
- DPoP proof cannot be reused (jti replay prevention)
- Proof binds to specific URL and method (htm/htu validation)
- Active cryptographic proof per request (defense-in-depth)

---

### Step 7: CSRF Double-Submit at Login

**Login form pattern** (per FAPI UI std §6):

```html
<form id="login-form" action="/bff/auth/login" method="POST">
  <input type="hidden" name="_csrf" value="<!-- CSRF token from cookie -->">
  <input type="text" name="username" required>
  <input type="password" name="password" required>
  <button type="submit">Sign In</button>
</form>
```

**CSRF protection pattern**:

1. **On login page GET**: Server sets `csrf_token` cookie (HttpOnly + Secure + SameSite=Strict)
2. **Form renders** with CSRF token in hidden field (read from cookie via JavaScript, not hardcoded)
3. **On form POST**: Server validates that:
   - `csrf_token` header value matches `csrf_token` form field value
   - OR double-submit: cookie value matches form field value
4. **On successful login**: Rotate/regenerate CSRF cookie; rotate session attributes

**Why this matters**: ROPC and insecure login flows are eliminated (§2.4, §7.5), but CSRF protection remains required for the remaining front-channel steps. The double-submit pattern is the recommended FAPI approach (RFC 9700 §4.7.1).

---

### Step 8: Logout Trio

**FAPI 2.0 mandates** a logout flow that cleanly terminates sessions across all domains:

```http
GET /bff/logout?post_logout_redirect_uri=https://spa.fsp.local/ended HTTP/1.1
```

**Logout flow steps**:

1. **Client initiates logout** at `/bff/logout?post_logout_redirect_uri=<uri>`
2. **Server invalidates session** — clears `FSP_SESSION_USER` and `FSP_SESSION_TOKEN` attrs
3. **IdP back-channel logout** — send `end_session` request to IdP (if supported)
4. **Client-side cleanup**:
   - `localStorage.removeItem('FSP_STORAGE_KEY')` (per-portal storageKey isolation)
   - Remove auth cookies
5. **Redirect to `post_logout_redirect_uri`** — user sees logout confirmation page

**Per-portal localStorage isolation**: Each portal (admin, manufacturer, distributor, wholesaler) has a distinct `storageKey` (e.g., `admin-portal`, `manufacturer-portal`). This ensures that logging out of one portal does not clear sessions in another, and that eligibility checks (`portalEligible`) use the correct key.

**Acceptance criteria**:
- `localStorage[storageKey]` contains only portal-specific session data
- Logout from admin portal does not affect manufacturer portal session
- `portalEligible` check uses correct `storageKey`

---

### Step 9: Error-UX Map

**Standardized error responses** for FAPI compliance violations:

| Error Scenario | HTTP Status | Response Format | UX Action |
|----------------|-------------|-----------------|-----------|
| Missing/invalid DPoP proof | 401 | `{error: "invalid_dpop", error_description: "DPoP proof validation failed"}` | Show technical error; do not leak token details |
| `iss` mismatch in auth response | 302 redirect back to /authorize with error | Redirect with `error=iss_mismatch` | Return user to login; do not persist partial auth state |
| Expired `request_uri` | 400 from /par | `{error: "request_uri_expired", expires_in: 30}` | Retry PAR flow; user refreshes |
| PKCE mismatch at token exchange | 400 | `{error: "invalid_code_verifier", error_description: "code_verifier does not match code_challenge"}` | Retry auth flow from Step 1 |
| Token revoked/invalid | 401 | `{error: "invalid_token"}` | Force re-authentication |
| CSRF failure at login | 400 / redirect back | `{error: "csrf_failure"}` | Clear form; show "please retry" |

**Error-UX design principles**:
- Never expose internal token details in user-facing messages
- Always offer a retry path, never a dead end
- Log technical details server-side only
- Map to consistent UI patterns per portal theme

---

### Step 10: Route Inventory as Build Artifact

**All FAPI 2.0 compliant routes** must be documented as a build artifact:

```
/bff/auth/login          → GET (presents login form, sets CSRF cookie)
/bff/auth/login          → POST (validates credentials, initiates auth flow)
/bff/auth/callback       → GET (receives code+iss from IdP, validates iss)
/bff/par                 → POST (Pushed Authorization Request)
/bff/session             → GET (returns current user session attrs)
/bff/csrf                → GET (returns CSRF token cookie)
/bff/logout              → GET (initiates logout trio)
/api/v1/permissions      → GET (returns 23 permission keys for current user)
/api/v1/me/permissions   → GET (same as above, per FAPI route inventory)
/api/v1/health           → GET (health check, no auth required)
```

**Generation**: Routes annotated with `@FspOperation` in core controllers; generated during Gradle build; verified against running server via Playwright E2E.

**Build artifact format** (Markdown, committed to repo):

```markdown
# FSP FAPI 2.0 Route Inventory

## Authorization Endpoints
- `GET /bff/auth/login` — presents login form, sets CSRF cookie
- `POST /bff/auth/login` — validates credentials, redirects to BFF /auth/login?return_to=
- `GET /bff/auth/callback` — receives code+iss from IdP, validates iss

## Token Endpoints
- `POST /bff/par` — Pushed Authorization Request (RFC 9126)
- `POST /bff/token` — token exchange with DPoP + private_key_jwt

## Resource Endpoints
- `GET /api/v1/permissions` — returns 23 permission keys
- `GET /api/v1/me/permissions` — same, per-user

## Logout
- `GET /bff/logout` — logout trio (session invalidate + IdP logout + localStorage cleanup)
```

---

## Compliance Verification Checklist

All items must pass before a build is FAPI 2.0 compliant:

### Authorization Flow
- [ ] `grep -rn "grantType.*password\|grant_type.*password" dist/ returns 0
- [ ] `grep -rn "client_secret" dist/ returns 0 (token endpoint requests)
- [ ] `grep -rn "request_uri" dist/ present and used correctly
- [ ] `code_challenge` and `code_challenge_method=S256` present in all auth flows
- [ ] `iss` validation present and tested (mismatch aborts flow)
- [ ] Full-page redirect to IdP hosted sign-in observed (no SPA credential collection)
- [ ] Only `client_id` + `request_uri` sent to /authorize beyond Step 3

### Token & API Security
- [ ] DPoP proof generated per API request (not shared/reused)
- [ ] `htu`/`htm` in DPoP proof match actual request URL/method
- [ ] `cnf.jkt` in access token matches SHA-256 of DPoP proof's `jwk`
- [ ] `jti` replay prevention present (server-side cache, reject on duplicate)
- [ ] Access tokens sent via `Authorization: Bearer` header only
- [ ] No access tokens in query strings, fragments, or POST bodies

### Client Authentication
- [ ] `private_key_jwt` used at /par and /token exchanges
- [ ] `aud` in client_assertion = AS issuer as string (not array)
- [ ] Client key pair generated and registered (JWKS or static config)
- [ ] No shared secrets in codebase or configuration

### Session & CSRF
- [ ] CSRF double-submit rotated at login
- [ ] Session attrs `FSP_SESSION_USER`/`FSP_SESSION_TOKEN` rotated on login
- [ ] Per-portal `storageKey` isolation for localStorage
- [ ] Logout trio: session invalidate + IdP logout + localStorage cleanup
- [ ] `post_logout_redirect_uri` validated against allowed origins

### Infrastructure
- [ ] Authorization endpoint: `Access-Control-Allow-Origin` absent (no CORS)
- [ ] All endpoints served over TLS (TLS 1.2+ per BCP 195)
- [ ] JWKS endpoints served over TLS only
- [ ] Clock skew tolerance: JWTs accepted with `iat`/`nbf` between 0 and 10 seconds in the future, rejected >60 seconds in the future
- [ ] Route inventory build artifact generated and committed

### E2E Playwright Verification (from verification-playbook.md)
- [ ] Browser never contains username/password fields for login
- [ ] Full-page redirect to IdP observed in trace
- [ ] `grep dist for grantType.*password` = 0
- [ ] No `client_secret` in token exchange requests
- [ ] DPoP headers present on all API calls in trace
- [ ] `iss` validation in code and runtime
- [ ] Route inventory matches running server endpoints

*This flow reference is grounded in the OpenID Foundation FAPI 2.0 Security Profile (final, 22 Feb 2025) and RFC 9700 (BCP 240, January 2025).*