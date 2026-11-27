# Checker flag (`persons.is_checker`)

**Status:** implemented 2026-11-18 · **Migrations:** V16 (schema), V17 (system seed), V18 (demo seed)

## Rule

A person may **approve, reject or return** any four-eyes approval only when **all three** hold:

| # | Check | Where | Refusal |
|---|---|---|---|
| 1 | Holds the approval's checker permission (`kyc.approve`, `je.approve`, `ui.layout.manage`, `plugin.manage`, `user.manage`, or the plugin manifest's `approvals[].checkerPermission` for `PLUGIN:<code>:<action>`) | `ApprovalService.requirePermissionFor` | `403 PERMISSION_DENIED` |
| 2 | Is **not** the maker (four-eyes at `person_id`, AGENTS §3.1) | `PermissionService.requireDistinctPerson` | `403 MAKER_CANNOT_APPROVE` |
| 3 | Has `persons.is_checker = TRUE` | `ApprovalService.requireCheckerFlag` | `403 CHECKER_NOT_ENABLED` |

The flag is **additive**: it never grants a permission and never relaxes four-eyes. A role can
make someone *able* to decide a kind of request; the flag says this person is *designated* to act
as a checker at all. Default is `FALSE`, so nobody becomes a checker by accident.

Checks 2 and 3 run inside `ApprovalService.resolveRow`, so every path that resolves an
approval is covered:
- the approval queue;
- `/v1/approvals/{id}/approve|reject`;
- plugin-mediated resolution (DELEGATED actor);
- `resolveByTarget`.

For a plugin-mediated (DELEGATED) resolution, the plugin's machine client must **also** hold the
checker permission as an `ON_BEHALF` grant (`PermissionService.requireClient`).

## Who is a checker

| Environment | Person | Flag |
|---|---|---|
| all (system seed) | `admin-checker` | ✅ |
| all (system seed) | `platform-admin` | ❌ (maker; platform four-eyes always runs admin → admin-checker) |
| `client-demo` only | `psp-ops`, `merchant-checker` | ✅ |
| every new organization | the checker it is **born with** — the PSP checker (`TenantService.createPsp`) and the KYC saga's `checker-<admin>` (`TenantChildrenService`) | ✅ (`PersonProvisioning.designateChecker`) |
| every new organization | its admin (the maker) | ❌ |

Organizations created before V19 are back-filled by `V19__roles_governance.sql`: an
ACTIVE person holding their org's `TENANT_CHECKER` role who was **not** created through the
four-eyes user-admin flow (no `USER_CREATE` request targets them) is flagged. A user an
admin deliberately created with `checker: false` is never flipped.

## Setting the flag

Users → **Add user** / **Edit** has a **Checker** checkbox. Changing it is a normal
`USER_CREATE` / `USER_UPDATE` request:
- it goes through four-eyes, and the flag takes effect only when another checker approves;
- the value travels in the approval payload (`"checker": true|false`);
- `UserAdminService.applyCreate` / `applyUpdate` apply it on approval.

You cannot change your own flag: it is locked together with your own roles.

API: `POST /v1/tenants/{id}/users` and `PATCH /v1/tenants/{id}/users/{personId}` accept
`checker` (boolean; `null` on update = unchanged). `UserView.checker` and
`GET /v1/me/permissions` → `checker` expose it.

## UI

- The KYC decision panel shows an explicit blocked state for `CHECKER_NOT_ENABLED`, as
  ui-std §5.2 requires for `MAKER_CANNOT_APPROVE`. It is never silently hidden.
- The user details panel shows a **Checker** chip.
