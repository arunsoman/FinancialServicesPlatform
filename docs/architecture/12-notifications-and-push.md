# Architecture Chapter 12 — Notifications: In-App, Push, Email & SMS

**Parent Document:** [docs/architecture.md](../architecture.md)

---

> **Status:** push/in-app slice **live** (F-12, `V23__notifications_and_push.sql`); **email +
> SMS slice built** (V29): per-channel queues and consent prefs, AND notify schema
> isolation (`fsp_notifications`); **auth plane built** (V30): password reset + MFA OTP
> ride the same transports without ever entering the inbox (§12.9). **Normative:**
> AGENTS.md §2c (privacy), §2.5 (outbox), Chapter 06 (outbox), Chapter 08 (REST).

## 12.1 Why a queue-shaped design, not a fire-and-forget sender

A notification — on any channel — is a *pointer*, never a record of truth. FSP therefore
splits the problem into three independent, crash-safe pieces:

```mermaid
flowchart LR
    A["Domain moment<br/>(ApprovalService opens<br/>a four-eyes request)"] -->|same tx| B["notifications table<br/>(rows per checkee, deduped<br/>+ Notification.PushRequired outbox row)"]
    B -->|@Scheduled sweep<br/>*_at IS NULL per channel| C["DispatchDispatcher"]
    C -->|per device| D["Web/APNs/FCM<br/>PushPublishers"]
    C -->|per address| E["EmailPublisher (SMTP)"]
    C -->|per MSISDN| F["SmsPublisher (provider REST)"]
    D & E & F --> G["Device / Mailbox / Handset"]
    A -->|same SPA| H["In-app inbox + badge<br/>(GET /v1/notifications)"]
```

1. **The `notifications` table IS the delivery queue — for every channel.** The domain
   transaction writes the rows; nothing memory-held, nothing lost on restart, catches up
   after days of downtime and (with all `fsp.push.*`/`fsp.email.*`/`fsp.sms.*`
   credentials absent, the default) can run *fully dormant* — the inbox/badge works with
   zero outbound credentials. Each channel has its own claim cursor
   (`pushed_at`, `email_sent_at`, `sms_sent_at`) and bounded attempt counter
   (`*_attempts`); `*_via` is delivery evidence, claimed atomically per channel.
2. **One outbox row per batch** (`Notification.PushRequired` in `outbox_events`) gives the
   audit-evidence trail "the platform intended to notify these persons" under Chapter 06
   rules — inside the same DB transaction, so a rollback never sends anything for a thing
   that never happened. The channels used are a *delivery detail*; the audit fact is the
   recipient list. (⛏ hardening: rename event type to `Notification.DispatchRequired`.)
3. **Dispatch is a scheduled sweep** (`fsp.push.dispatchFixedDelay`, default 2 s, settle
   window 2 s so a just-written row isn't read mid-transaction; all outbound provider I/O
   happens **outside** any DB transaction — short per-row claim transactions only).
   Single-instance Phase-0; the guarded- UPDATE claim pattern already supports the
   multi-replica hardening slice.

**The channel rule (normative):** *a channel with no credentials is simply ABSENT — no
bean, no dispatch, no error.* Adding a channel later is a pure config change. Every
channel is also independently retryable and independently claimable: an SMTP outage never
delays a push, a dead device never blocks an email.

## 12.2 Common outbound architecture (one contract, five channels)

FSP designs for **browsers (Web Push), iOS (APNs), Android (FCM), email (SMTP) and SMS
(provider gateway) against one uniform contract**. A channel acquires its *target* in one
of two ways and everything else — payload shape, claim, retry, retirement — is identical:

```
Target acquisition (per channel):
  PUSH   → device self-registers: POST /v1/notifications/push/subscriptions
           { platform: WEBPUSH|APNS|FCM, endpoint | token, p256dh?, auth? }
           → push_subscriptions (one row per device, unique among ACTIVE)
  EMAIL  → derived from the person profile (persons.email)  — never self-registered
  SMS    → derived from the person profile (persons.phone) — MSISDN, E.164 normalised

Write-side (identical for all channels):
  Domain tx → notifications row (deduped per person+resource) + outbox row

Dispatch-side (per channel, independent cursors):
  Dispatcher sweep → for each pending row:
      push  : person's ACTIVE subscriptions → matching PushPublisher bean
      email : person's channel pref ON + verified address → EmailPublisher
      sms   : person's channel pref ON + valid MSISDN      → SmsPublisher
      Delivered        → per-channel claim (email_via = "SMTP", sms_via = provider)
      definitive fail  → channel target retired (device / address marked dead)
      transient fail   → attempts++ (bounded, give up at 3 per channel)
      no target/creds  → claimed with via = NONE (that channel only)
```

| Channel | Adapter | Credential gate | Security posture | Notes |
|---|---|---|---|---|
| **In-app inbox** | (none — the table itself) | always on | PDP-gated reads (`notification.view`) | Source of truth; every other channel is best-effort. |
| **Web Push** | `WebPushPublisher` | `fsp.push.webpush.enabled` + VAPID P-256 keypair | RFC 8291 `aes128gcm` E2E + RFC 8292 VAPID JWT | **Default push channel.** The push service never sees plaintext. JDK-only crypto. |
| **Apple (APNs)** | `ApnsPublisher` | `fsp.push.apns.enabled` + team-id + key-id + topic + `.p8` key (mounted like the IdP key) | HTTP/2 provider API, ES256 provider JWT (≤ 60 min) | 403/404/410 retires the device token (definitive). |
| **Google (FCM)** | `FcmPublisher` | `fsp.push.fcm.enabled` + project-id + service-account key JSON | OAuth2 JWT-bearer (RS256), FCM v1 `messages:send` | `UNREGISTERED`/404 retires the token. |
| **Email** | `EmailPublisher` (V29, built) | `fsp.email.enabled` + SMTP host/port + `FSP_EMAIL_*` env credentials | TLS (STARTTLS or implicit), no E2E — strict payload minimisation (§12.5) | Jakarta Mail (`spring-boot-starter-mail`) — the one accepted F-12 dependency. Bounce handling §12.3. |
| **SMS** | `SmsPublisher` (V29, built) | `fsp.sms.enabled` + `fsp.sms.provider` + `FSP_SMS_*` env credentials | **Plaintext end-to-end at the telco** — strictest payloads (§12.5), sender-id branded | Adapter seam: one Phase-0 HTTP provider adapter; SMPP/other providers = new bean, no schema change. |

Generating the Web Push VAPID keypair (openssl, no node tooling needed):

```bash
openssl ecparam -name prime256v1 -genkey -noout -out vapid.pem
openssl ec -in vapid.pem -pubout -outform DER 2>/dev/null | tail -c 65 \
  | base64 | tr '+/' '-_' | tr -d '='   # → FSP_PUSH_WEBPUSH_PUBLIC_KEY
# private key → FSP_PUSH_WEBPUSH_PRIVATE_KEY (base64 of the DER PKCS#8)
```

Credentials arrive **only via env** (`FSP_PUSH_*`, `FSP_EMAIL_*`, `FSP_SMS_*`), mounted at
runtime like the IdP signing key (`idp-signing.pem` — Chapter 01 §3), never baked into
images, never logged (backend/AGENTS.md §2c).

## 12.3 Email channel (V29 — built)

**Targets.** A person's email address comes from `persons.email` — the same field used at
login/password reset. It is **never** a self-registered value: notifications can only be
mailed to the address a person already authenticates with (or updates through the verified
profile flow). One address per person = one email target; no per-address subscription
tables (push is per-*device*, email is per-*person*).

**Consent & preferences.** Channels that write to a mailbox/MSISDN are **opt-in**
(per-person, per-channel): `notification_channel_prefs(person_id, channel, enabled)`
with `EMAIL`/`SMS` default `false`, surfaced as
`GET/PUT /v1/notifications/channels` (§12.6). This is the quiet-hours/unsubscribe story
for transactional mail, and it gives every person a kill switch without touching the
inbox. (⛏ hardening: per-type mute matrix, e.g. "email me only four-eyes requests".)

**Message shape.** The email is the same pointer, in a minimal branded text+HTML
template: subject = generic title ("Awaiting your approval"), body = generic body + a
portal link (navigates to an *authorised* route through the normal gateway/BFF login —
the link grants nothing). No amounts, no account numbers, no maker names (§12.5).

**Delivery semantics (grounded in the push pattern):**

| Outcome | Handling |
|---|---|
| SMTP 2xx accepted | claim `email_sent_at`, `email_via = 'SMTP'` |
| transient 4xx / timeout / TLS error | `email_attempts++` (bounded at 3) |
| **definitive** (5.1.x invalid mailbox/domain, hard bounce) | `email_attempts = 3` effectively — never retried; the address is *not* deleted (it may still be a login) but the channel pref is flagged dead |
| no address / pref off / channel disabled | claim `email_via = 'NONE'` |
| provider exception mid-sweep | logged (reason + status class only, no PII), the row stays pending for the next sweep |

Bounces reported asynchronously by the provider (NDR/HTTP webhook) are not needed in
Phase-0: synchronous SMTP codes cover the definitive cases. (⛏ hardening: outbox-driven
bounce webhook ingestion, suppression list.)

## 12.4 SMS channel (V29 — built)

**Targets.** `persons.phone`, E.164-normalised (`+<countrycode><nsn>`); a phone that does
not normalise is simply not an SMS target. One MSISDN per person, like email — no device
table.

**Compliance posture (regulatory, not stylistic).**

1. **Transactional classification only.** SMS carries operational four-eyes/queue
   pointers — never marketing. Still, the per-person pref (§12.3) is the audit trail for
   consent, and opt-out must work: the `PUT /v1/notifications/channels` off-switch is the
   FSP-side equivalent of "STOP".
2. **Quiet hours.** Transactional operational SMS may send at any hour; (⛏ hardening:
   per-tenant quiet-hour window in prefs for jurisdictions that require it).
3. **Sender-ID branding.** `fsp.sms.sender-id` (alnum sender ID, ≤ 11 chars) so the
   message is recognisably from the platform; reduces phishing confusion.

**Message shape — the strictest payload of all channels.** An SMS is ≤ 160 chars of
**plaintext at the telco**, unecrypted and uncontrolled end-to-end:

- generic title + one line; **no link by default** (a bare portal origin is acceptable
  where tenancy requires it — never a URL shortener, which leaks the resource path and
  creates a phishing surface);
- **never** amounts, identifiers or any data. Notification-plane SMS carries no auth
  material; when the message NEEDS a secret (MFA OTP), it is the auth plane's dispatch
  (§12.9) rendered by the IdP — and reset links still never travel over SMS, only 8-digit
  OTP codes do.

**Delivery semantics.** Same table discipline as email: provider 2xx → `sms_sent_at` +
`sms_via = provider name`; transient (rate limit, gateway 5xx) → `sms_attempts++`
(bounded 3); definitive (invalid MSISDN, blocked number) → channel flagged dead in prefs;
no phone / pref off / disabled → `sms_via = 'NONE'`.

## 12.5 FAPI 2.0 & data minimization implications

FAPI 2.0 governs the OAuth machinery; notifications add a *second, quieter* outbound path,
so the slice applies FAPI's spirit deliberately — with a **channel sensitivity ladder**,
because the channels differ radically in how much protection the transport itself gives:

| Channel | Who can read the payload in flight/at rest | Payload budget |
|---|---|---|
| Web Push | OS + push service see only RFC 8291 ciphertext; content is E2E to the browser | generic copy |
| APNs / FCM | Apple/Google deliver plaintext to the OS (provider-encryption at rest) | generic copy |
| Email | recipient's mailbox provider stores plaintext indefinitely; forwarding copies it anywhere | generic copy + link; assume it will be screenshotted |
| SMS | telcos, HLR, and *anyone holding the handset* see plaintext | ≤ 160 chars, no link, no structure |

Therefore, uniformly (AGENTS §2c):

1. **Data minimization is the payload contract.** `{type, title, body, tenantId, link}`
   with **generic words** ("A settlement bank account filing is waiting for a checker
   decision."). No amounts, no account numbers, no names, no KYC payloads — on *any*
   channel. The link is a route, not evidence; the device re-fetches the *authorised*
   resource through the normal gateway/boundary. Email/SMS have **no E2E equivalent of
   Web Push's RFC 8291**, which is exactly why their payloads are equally generic: the
   minimisation is the privacy control, not the transport.
2. **The notification plane is transport-only, never a decision or money path.**
   Notifications never carry commands; clicking/navigating only routes. A replayed or
   forged notification cannot move states — the four-eyes/idempotency machinery is
   untouched. Conversely the dispatcher runs inside core's authenticated DB context and
   never accepts inbound traffic.
3. **Push targets are person-plane bound; email/SMS targets are the person profile.**
   Only PERSON actors register devices (machine/DELEGATED refused); the row binds to the
   person's **home tenant** (not the act-as target), so "sudo into tenant" cannot re-base
   devices. Email/SMS reuse the login/profile contact data, so the same property holds by
   construction. Reads are `notification.view`-gated at the PDP like every core call;
   channel prefs are the person's own rows (self-service only, never act-as-writable).
4. **No PII in logs.** Dispatch logs record notification ids, channels/platforms and
   person ids — never push tokens, mailbox addresses phone numbers, VAPID/SMTP/API keys
   or payload text. Failures log reason + status codes.
5. **FAPI note — untrusted notification centres.** The OS/mailbox/lockscreen is *outside*
   every trust boundary; the design assumes contents are shoulder-surfable. That is why
   the pointer pattern (generic copy + opaque link + server-side re-authorization on
   click-through) is mandated rather than "put the number in the notification".
6. **Phishing framing (email/SMS specific).** Because email/SMS messages are spoofable,
   platform copy **never** asks for credentials, codes, or confirmations inside the
   message; every message routes to the portal where authentication re-happens. Sender-ID
   and From domains are fixed and documented so checkers can recognise genuine FSP mail.

## 12.6 HTTP surface (`/v1/notifications`, permission `notification.view`)

| Method & path | Purpose | Semantics |
|---|---|---|
| `GET /v1/notifications` | Inbox page (§2f-compliant) | `{items, page, size, totalElements, totalPages, hasNext, unread}` — bounded `page`/`size` (cap 100), `sort` over a safelist (`createdAt`/`readAt`/`type`/`opType`, default `createdAt,desc`), server-side `q` (title/body/opType ILIKE) and `unread` filter in SQL. |
| `GET /v1/notifications/unread-count` | **Badge poll target** (`{unread}`) — no rows over the wire. | The SPA polls this every 30 s; cheapest possible check. |
| `POST /v1/notifications/read-all` | Mark whole inbox read (204). | One click; the queue keeps its own truth. |
| `GET /v1/notifications/push/config` | `{webpushEnabled, vapidPublicKey}` | Public key exposure for the SPA service worker; `webpushEnabled=false` when the channel is off. |
| `POST /v1/notifications/push/subscriptions` | Register device (`PushSubscribeRequest`) | Per-platform validation (WEBPUSH needs an `https://` endpoint; APNs/FCM need a token); re-subscription of a known device refreshes key material in place (one row per device). |
| `DELETE /v1/notifications/push/subscriptions/{endpoint}` | Browser forgot the device | Retires the row (definitive) after the owner check. |
| `GET /v1/notifications/channels` (V29) | `{email: {enabled, hasAddress}, sms: {enabled, hasPhone}}` | The person's outbound-channel state (never the raw address). |
| `PUT /v1/notifications/channels` (V29) | Opt in/out per channel (`{email?: bool, sms?: bool}`) | Writes the person's pref rows; email/SMS require a profile address/phone to exist or the toggle 400s. |

SPA plumbing: `public/fsp-push-sw.js` (push + `notificationclick` handlers),
`web-core/push-core.ts` (unread polling + Web Push enable, both fail-soft — the badge is
cosmetic), wired at first authed hydration (`SessionProvider`) and surfaced as the
`approvals-badge` on the Approvals nav item (`AppShell`). (⛏: a channels widget in the
person's profile surfaces the GET/PUT surface.)

## 12.7 Data model (schema `fsp_notifications` — V23 · V29)

As of V29 the whole notify subdomain lives in its own PostgreSQL schema,
**`fsp_notifications`** — data-plane isolation of the module from core's `public` tables
(the notify module stays in the core process; this is schema isolation, not plugin
process isolation, AGENTS §4). Rules V29 established (normative):

1. Every notify DDL change is FULLY QUALIFIED as `fsp_notifications.<table>`.
2. Cross-schema FKs from notify tables to `persons`/`tenants` (public) are intentional.
3. The coupling is ONE-WAY: nothing in `public/` may take a dependency on
   `fsp_notifications` tables; core code touches them only through the JPA entities in
   `com.fsp.core.notify` (`@Table(schema = "fsp_notifications")`).

* **`fsp_notifications.notifications`** (V23, relocated by V29) — `id, person_id,
  tenant_id, type, op_type, title, body, resource_type, resource_id, link, read_at,
  pushed_at, push_attempts, push_via, created_at` **+ per-channel claim columns (V29):**
  `email_sent_at, email_attempts, email_via, sms_sent_at, sms_attempts, sms_via` — the
  identical claiming pattern, learned once. Indexes: inbox list (`person_id, created_at
  DESC`), unread count partial, one push / email / sms queue-scan partial each
  (`*_at IS NULL`), one-per-person-per-resource unique dedupe, per-person-per-resource
  read index. (⛏ generalisation if a fifth outbound channel ever arrives: a
  `notification_deliveries(notification_id, channel, …)` side table instead of a new
  column family.)
* **`fsp_notifications.push_subscriptions`** (V23, relocated by V29; V31 adds
  `version BIGINT NOT NULL DEFAULT 0`) — per-device registrations with a
  `@Version` optimistic lock (AGENTS §2.8): the re-subscribe write path is a
  compare-and-swap, so a concurrent retire/refresh fails loudly instead of lost-updating.
* **`fsp_notifications.notification_channel_prefs`** (V29) — `person_id, channel
  ('EMAIL'|'SMS'), enabled BOOL, dead BOOL, created_at, updated_at`; PK `(person_id,
  channel)`; cross-schema FK to `public.persons`; self-service writes only
  (person-plane). `dead` = definitive bounce verdict on the contact; the person
  re-enables (clearing the flag) after fixing their profile contact.
* **`fsp_notifications.auth_dispatches`** (V30, §12.9) — the AUTH-PLANE dispatch queue:
  `id, purpose ('PASSWORD_RESET'|'MFA_LOGIN'|'MFA_ENROLMENT'), person_id, channel,
  subject, text, correlation_id, attempts, via, sent_at, created_at`. Written by the
  IdP (JDBC, inside its mutating transaction + one outbox evidence row); delivered by
  core's `AuthDispatchDispatcher` through the SAME `OutboundChannel` transports as F-12 —
  but NO consent gating (an auth message is never opt-out), NO inbox row, and the
  secret-bearing `text` is purged on claim. Contact is NEVER stored — resolved from
  `persons` at send time. Same partial pending-index / claim / bounded-bump pattern.
* **Permission** — `notification.view` (READ, component `notify`), granted to every admin
  and checker role (idempotent inserts, `ON CONFLICT DO NOTHING` — §2.5). (⛏:
  `notification.prefs` self-service is owner-gated, not a new permission.)

## 12.8 Recipient resolution & the hardening ledger

Recipients of an approval-open notification = **ACTIVE persons of the approval row's
tenant holding the checker permission for that op type** (`checkerPermissionOf`, the same
mapping approvals use), minus the maker (four-eyes §3.1). Same-tenant scope in Phase-0;
subtree recipients (PSP notified about tenant queues they supervise) = ⛏ hardening slice.
KYC plugin approvals surface through the same hook (permission from the registered plugin
manifest); plugin types that don't resolve simply notify nobody.

The per-channel split is resolved *at dispatch*: every recipient gets the inbox row
(always), and email/SMS/push fan-out is then gated by that person's own devices/prefs —
so the audit list (outbox) is the person list, and channel reachability never distorts
"who was notified".

Also ⛏: multi-instance dispatcher claim-before-send (per channel); retention/purge job
for `notifications` + DEAD `push_subscriptions`; native-app delivery (APNs/FCM
credentials wiring in prod environments); email/SMS provider prod wiring + bounce webhook
ingestion; per-type/per-tenant mute matrix and quiet hours; verified-contact marking
(bounce/infallibility feedback into `persons`);
`Notification.PushRequired → Notification.DispatchRequired` outbox event rename.

## 12.9 The auth plane: password reset + MFA ride the same transports (V30 — built)

Authentication-adjacent messages — password-reset links, MFA login/enrolment OTPs — are
NOT notification-inbox items: they fire pre-auth or pre-inbox, must not be opt-out, and
must not be deduped per resource. But they absolutely reuse the V29 OUTBOUND TRANSPORTS
(SMTP email + generic JSON SMS gateway, the same credential-gated `OutboundChannel`
beans, the same claiming pattern). Ownership split (normative):

| Plane | Owns | Tables |
|---|---|---|
| **IdP** (auth logic, secrets) | reset tokens, OTP challenges, enrolment state; secrets ALWAYS hashed (SHA-256), single-use, TTL (15 min reset / 5 min OTP), ≤3 OTP attempts, 60 s issuance cooldown, 204-always reset requests (no enumeration, RFC 9700) | `public.password_resets` · `public.mfa_challenges` · `public.user_mfa` (core-migrated like V6) |
| **Notify core** (transports) | delivery only: sweep `auth_dispatches`, resolve contact from the person profile, send, purge `text` on claim | `fsp_notifications.auth_dispatches` (one-way: IdP writes dispatch rows, never reads them back) |

Flow seams:

* **Password reset** — `POST /idp/password/reset-request {username}` (204 ALWAYS →
  `204` even for unknown/inactive/no-email persons) parks a hashed 256-bit token; the
  reset link travels through EMAIL only (never SMS — no links over SMS per §12.4);
  `POST /idp/password/reset-confirm {token, newPassword, confirmPassword}` enforces the
  F-8 policy (10–128 chars ≠ current), flips `password_must_change = false`, consumes
  the token (≤5 confirm attempts). One live reset per person (partial unique, V30).
* **MFA on the token grant** — a person with an enrolled `user_mfa` row gets
  `401 MFA_REQUIRED` from the native password grant after a correct password (the
  challenge is dispatched right there; re-requests inside the 60 s cooldown REUSE the
  live challenge — a wrong-party login cannot pump mail/SMS), and must add `otp` to the
  grant body. Wrong/expired codes: `400 MFA_CODE_INVALID / MFA_CODE_EXPIRED /
  MFA_ATTEMPTS_EXHAUSTED`.
* **Hosted authorize flow** — the MFA step is parked in an OTP-sized ticket
  (`ParService.MfaTicket`, mirrors the F-8 password-change ticket): a code-entry page
  renders at the IdP, `POST /idp/mfa/verify` resumes the PAR. Identity is already
  proven when the ticket is issued; only possession is pending.
* **Enrolment (self-service, bearer-authenticated)** — `GET /idp/mfa` (state, never raw
  contacts), `PUT /idp/mfa {channel}` sends a confirmation code TO the chosen channel
  (possession proof), `POST /idp/mfa/confirm {code}` writes `user_mfa`,
  `DELETE /idp/mfa {password}` requires the current password (knowledge proof for
  factor removal). Requires a usable profile contact per channel, else `400
  MFA_NO_CONTACT`.

Security posture (FAPI/SCA): OTP + token secrets exist only as SHA-256 hashes in the IdP
plane; the rendered message (with the plaintext code) exists momentarily in the dispatch
row and in the log-free sweep (`purpose/person/outcome` only — zero token/PII logging,
AGENTS §2c); everything is single-use with bounded attempts + cooldowns; the SPA/BFF
wiring for the ROPC `MFA_REQUIRED` step and the `/#/reset` route is ⛏ (the IdP surfaces
are live; BFF passes problem bodies through unchanged).
