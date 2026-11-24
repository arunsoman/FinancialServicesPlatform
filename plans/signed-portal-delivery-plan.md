> **Superseded (2026-11-24): signed portal delivery was removed.** Portals load their published menus
> from core (`GET /v1/ui/menus/{portal}`); `ui_bundles` is dropped (V42). Kept for history.

# Signed Portal Delivery & Tamper Detection — Plan

**Status:** S1–S4 delivered (SPA slices S2/S3 verified by gate + tamper tests; S4 live-smoked) · **Companion to:** `plans/widget-harness-prd.md`
**Implements:** FAPI2 guide §3 signed operation-catalog publication (compliance ledger row
"CI-signed publication = hardening ⛏"), extended to portal layouts + widget delivery.

## 1. Goal & threat model

Every portal (admin/org) draws its menus, submenus, screens and widget placements from
`ui_layouts` and `ui_widgets`. Today that content travels as plain JSON from core — a
compromised origin, rogue middlebox, or in-process tamper silently rewrites what users see
(added menus, re-routed commands, malicious widget placement).

**This design makes UI metadata tamper-evident and fail-closed:**

| Threat | Coverage |
|---|---|
| Compromised backend process rewrites layout JSON | payload is signed at publish; origin can't re-sign without the private key |
| Rogue proxy / gateway mutation in flight | SPA re-verifies signature + canonical hash against a **build-pinned** trust store |
| XSS/mutation of cached layout in the browser | verification happens at boot before any widget is mounted; verify-then-render |
| Key compromise | keys never enter the browser or images; rotation via `kid` with overlap window |
| Replay of an old (legitimately signed) layout | `updatedAt` + version inside the covered payload, displayed to the user |

Transport confidentiality remains TLS (the FAPI2 guide's job). The signature is
**defense in depth against corrupted authority** — it detects tampering even when TLS
terminates at an attacker-controlled proxy.

## 2. Signed delivery design

### 2.1 What is signed

One **delivery bundle per portal**, produced by core at PUBLISH time (and re-signed when a
widget register event alters the catalog):

```jsonc
{
  "kid": "ui-builder-2026-11",              // signing key id
  "iat": 1763000000,
  "payload": {
    "portal": "admin",
    "schemaVersion": 1,
    "layoutVersion": "<uuid>",              // ui_layouts.id of the PUBLISHED row
    "publishedAt": "2026-11-18T03:44:38Z",
    "menus": [ ... canonical layout tree: menus → screens → placedWidgets ... ],
    "widgets": [ { "id": "kyc.orgs", "version": "1.0.0", "bundle": "widgets/kyc-orgs",
                   "bundleHash": "sha256:…",   // hash of the BUILT asset chunk
                   "requires": ["kyc.review"], "span": {...} } ]
  }
}
```

- **`bundleHash` covers the widget's generated asset** — the delivery path protects menus AND
  the code that runs; a swapped widget chunk fails its hash check at import time (SRI-style,
  computed from the digest core registers alongside the widget).

### 2.2 Signature envelope

**JWS Compact Serialization (ES256, ECDSA P-256)** over the canonical payload:
`BASE64URL(UTF8(JOSE header)).BASE64URL(canonicalPayloadHashDoc).BASE64URL(signature)`

- Canonicalization: stable field order (sorted keys, no whitespace) hashed SHA-256 — same
  canonical-hash discipline the operation catalog already uses (`OperationCatalog.digest`).
- ES256 chosen over Ed25519 for universal **WebCrypto** support in the verifying browser;
  Ed25519 stays an optional upgrade behind the same `kid` mechanism.
- `alg` allow-list on the verifier (`{"ES256"}` only) — algorithm-confusion attacks are
  refused without parsing the payload.

### 2.3 Key management

| Rule | Detail |
|---|---|
| Private key lives ONLY in core | mounted volume, mirroring the settled IdP pattern (`FSP_IDP_SIGNING_KEY=/opt/fsp/pki/…`); never in images, never logged, never sent to the browser |
| Dev PKI | `make pki` gains `ui-builder.p12` + JWKS export (same script, same `${FSP_TLS_PASSWORD}` regime) |
| Trust anchor = **build-pinned JWKS in the SPA** | the portal shell bundles the trust store at build time; the verifier resolves `kid` ONLY from this pinned store — the running browser accepts no keys discovered at runtime. This is what makes origin-compromise detectable |
| Rotation | new `kid` added to trust store, signer emits both `kid`s for an overlap window, old `kid` removed in the next SPA release |
| prod | managed CA / HSM-backed keyceremony (same slice as guide §7 audit checkpoints; out of Phase-0 scope) |

## 3. Delivery & verification flow

```
Portal SPA boot ─→ GET /v1/ui/bundles/{portal}            (authenticated-only, RFC-7807 errors)
core            ─→ reads PUBLISHED layout + registry rows
                ─→ canonicalize → sha256 digest → ES256 sign (kid) → JWS envelope
                ─→ returns { payload, jws, kid, digest }  (+ Cache-Control: no-store)
SPA              1. `kid` ∈ pinned JWKS?  else TAMPERED
                 2. alg ∈ {ES256}?        else TAMPERED
                 3. verify JWS over digest; recompute digest from payload  else TAMPERED
                 4. widget chunk integrity: sha256(bundle) == bundleHash before dynamic import
                 5. render menus/widgets
```

Verification runs **before anything from the bundle is mounted** — verify-then-render, the
same discipline as the gateway edge (verify before forward).

### 3.1 Fail-closed tampered UI (binds `ui-std.md`)

On ANY failed check the portal renders a full-screen **danger boundary** — not a degraded
menu, not the last cached layout:

> ```
> ✋ UI metadata tampered — this portal will not load.
> Signed content failed verification (kid mismatch / signature invalid / digest drift).
> Do not retry, do not refresh in expectation of recovery.
> Get in touch: redteam@mindlabsolutions.com   ·  evidence id: <correlationId>
> ```

- `danger` role only (reserved: destructive/error — ui-std §2); full-screen, no window scroll.
- Includes `correlationId` (already produced by every request) so the security team can match
  server-side evidence.
- The evidence is ALSO recorded server-side — but note the signature check runs client-side;
  the SPA `POST`s nothing on failure (the tampered client's report can't be trusted).
  Server-side detection is a *separate* control: `AuditService` records every delivery with
  the digest, and the daily audit-checkpoint job (guide §7) re-signs and cross-verifies —
  drift between checkpoints = server-side tamper alarm.

## 4. Endpoints & permissions

| Op | Endpoint | Protection |
|---|---|---|
| signed bundle | `GET /v1/ui/bundles/{portal}` | authenticated-only (same as published read); `@FspOperation` catalog row |
| trust store | `GET /v1/ui/trust-store` (kid list + JWKS, PUBLIC keys only) | authenticated-only; NOT an authority — pinned build store wins |
| (existing) publish | `POST /v1/ui/layouts/{portal}/submit` + `/v1/approvals` | unchanged; publish handler additionally computes `bundleHash`es and stamps the signed bundle row |

New table (V10): `ui_bundles` — `(portal, kid, payload, digest, jws, layout_id, created_at)`,
unique `(portal)` for current; append-only history retained.

## 5. CI alignment (guide §3)

The same signing utility that stamps the bundle also signs the **CI-generated operation
catalog** (existing `digest`, previously unsigned) — closing the "CI-signed publication"
hardening row in the ledger §5c. Both artifacts verify with the same pinned trust store.

## 6. Acceptance criteria

1. Signed bundle verifies in-browser (ES256 WebCrypto, pinned kid) before rendering.
2. Bit-flip in any payload byte → tampered boundary, redteam contact, correlation id shown.
3. Widget bundle hash mismatch → module not imported, same boundary.
4. Unknown/rotated-out `kid` → tampered boundary.
5. alg ≠ ES256 → tampered boundary (before any JSON parsing).
6. Publish flow unchanged: four-eyes publish produces a verifiable bundle in the same tx.
7. ✅ Audit checkpoint detects digest drift between deliveries (`UiBundleCheckpoint`, daily cron `${fsp.ui.checkpointCron}`, manual `run()`; alarms as ERROR logs + audit evidence).
8. Bundle endpoints covered by catalog + idempotency conventions; no secrets/PII logged.

## 7. Build order

| Slice | Deliverable |
|---|---|
| S1 | Signer in core: canonicalization + ES256 (JCE), `ui_bundles` (V10), sign-on-publish handler extension, `GET /v1/ui/bundles/{portal}` + trust-store endpoint |
| S2 | SPA verifier module: pinned JWKS, verify-then-render boot pipeline, chunk-hash import guard |
| S3 | Tampered-UI boundary (ui-std danger boundary + evidence display) |
| S4 | ✅ Delivered — `CatalogDescriptorSigner` (boot catalog ES256-signed via `UiSigner`, `/internal/ops/catalog` carries kid+jws); `UiBundleCheckpoint` daily job re-assembles each portal's world and cross-verifies stored bundles (UI_CHECKPOINT_DRIFT / _SIGFAIL alarms + append-only audit evidence); `UiSigner.verify()` (payload-identity + P-256). Live: `ui_checkpoint_ok portals=1 catalog=ok`; Node/WebCrypto verifies catalog JWS against the pinned store |
| S5 | ✅ Delivered — layout renderer (builder phase B): `SignedMenuGate` zod-parses the VERIFIED payload, refuses a bundle signed for another portal (`portal_mismatch`), and provides it on `SignedLayoutCtx`; `AppShell` draws the sidebar from it (menus with ≥1 visible screen), `/p/:menuId/:screenId` (`LayoutScreen`) renders placed widgets in the container-tier `LayoutGrid` at their signed spans; a widget renders only if this build has it AND the signed `widgets` list approves it (same major) AND the user meets `requires`; home = first visible screen. No published layout → coded menus (unenrolled portals). Tests: `signed/signed-layout.test.ts` |
