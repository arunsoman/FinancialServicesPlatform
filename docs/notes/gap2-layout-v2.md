# GAP-2: Role Management Widget — Revised Layout (menu submenus at table start)

## ASCII Layout — Menu Submenus at the START of the Table

```
+=======================================================================+
|                           TOP BAR / HEADER                             |
|  (Title: "Role Management" + breadcrumb: Home > RBAC > Roles)         |
+------------------------------------------------------------------------+
|                                                       |
|  ===== LHS SIDEBAR: MENU SUBMENUS (fixed width 180px) =====           |
|  └─────────────────────────────────────────────────────────────────┘   |
|  ┌───────────────┐  ┌───────────────────────┐  ┌───────────────┐   |
|  │  &larrb;  Roles   │  │  &larrb; Permissions   │  │ &larrb; Approvals │   |
|  └───────────────┘  └───────────────────────┘  └───────────────┘   |
|  ┌───────────────┐  ┌───────────────────────┐  ┌───────────────┐   |
|  │  &larrb;  My Tenants   │  │  &larrb;  Current Tenant  │  │ &larrb;  Pending  │   |
|  └───────────────┘  └───────────────────────┘  └───────────────┘   |
|  └─────────────────────────────────────────────────────────────────┘   |
|                                                       |
|  ===== MAIN TABLE AREA (starts right after the submenu section) =====   |
|  +------------------------------------------------------------------------+ |
|  |                                                                       | |
|  |  ===== ROLE MATRIX TABLE =====                                         | |
|  |  |                                                                      | | |
|  |  |  Headers across the top: View   Create   Modify   Approve   Delete | | |
|  |  |  |----------------------------------------------------------------| | | |
|  |  |  | Row: role1 | ☐   ☐     ☐         ☐        ☐                      | | | |
|  |  |  | Row: role2 | ☐   ☐     ☐         ☐        ☐                      | | | |
|  |  |  | Row: role3 | ☐   ☐     ☐         ☐        ☐                      | | | |
|  |  |  |                                                                      | | | |
|  |  |  (Empty state) No roles. "Create new role" button below.            | | | |
|  |  |                                                                      | | | |
|  +------------------------------------------------------------------------+ | |
|  |                                                                       | | |
|  |  ===== SUB-MENU CONTEXT =====                                           | | |
|  |  (When a row is hovered/selected, these actions apply to THAT row)       | | |
|  |  [Make Active]   [Make Inactive]   [Grant Permission]   [Revoke]     | | |
|  |  [Transfer Ownership]   [Delete]   [View History]                     | | |
|  |                                                                       | | |
|  +------------------------------------------------------------------------+ | |
|  |                                                                       | | |
|  |  ===== WIDGET QUICK-ACCESS BAR =====                                    | | |
|  |  (Persistent below the table, spans full width)                         | | |
|  |  [Refresh]   [Add New Role]   [Import Roles]   [Export Roles]         | | |
|  |                                                                       | | |
|  +------------------------------------------------------------------------+ | |
+=======================================================================+
| FOOTER / STATUS BAR                                                    |
|  (e.g. "2 roles • 5 permissions • Select a row to enable actions        ")|
+=======================================================================+
```

## How This Structure Works

1. **LHS Sidebar = Menu Submenus** (fixed, always visible)
   - Three top-level items: Roles, Permissions, Approvals
   - Each has nested options (My Tenants, Current Tenant, Pending)
   - Clicking one filters the main table

2. **Main Table = Starts IMMEDIATELY after the submenu section**
   - The table headers (View, Create, Modify, Approve, Delete) are the **first row** of the table
   - No separate widget "level" — the table IS the widget
   - Each row represents a role
   - Checkboxes appear in the 5 action columns

3. **Sub-Menu Context** (appears when a row is selected)
   - Inline actions for the selected role only
   - Make Active/Inactive, Grant/Revoke Permission, etc.

4. **Widget Quick-Access Bar** (persistent, full-width)
   - Always visible below the table
   - Global actions: Add New Role, Import/Export, Refresh

## Key Differences from Previous Layout

| Aspect | Old Layout | New Layout (this request) |
|--------|-----------|---------------------------|
| **Menu position** | Separate LHS sidebar | At the START of the table area |
| **Table headers** | Above the table grid | **First row of the table itself** |
| **Widgets** | Separate component instances | **Embedded within the table structure** |
| **Submenus** | Fixed LHS only | Both LHS AND contextual (row-level) |
| **Quick-access** | Optional | Fixed bar below table |

---

## Frontend Implementation Map

1. **Menu submenus** → `src/portals/shared/widgets/rbac/navigation/`
   - `RolesDropdown.tsx`, `PermissionsDropdown.tsx`, `ApprovalsDropdown.tsx`
   - Each passes `tenantId` + `roleFilter` to the table

2. **Table component** → `src/portals/shared/widgets/rbac/role-matrix/Table.tsx`
   - Receives `menuSelection` from the submenus
   - Renders headers as the **first TR** (table row)
   - Renders role rows with checkboxes
   - Handles row-click → shows sub-menu context

3. **Widget quick-access** → `src/portals/shared/widgets/rbac/toolbar/Toolbar.tsx`
   - Always rendered below the table
   - Independent of row selection

4. **Menu → Table data flow**
   - User clicks `&larrb; Roles` in LHS
   - `GET /v1/roles?tenantId=X` fetches roles
   - Table renders with headers + role rows
   - Selecting a row → sub-menu context actions apply only to that role

---

**Does this layout — with menu submenus at the start of the table, then the table headers/actions, then widget quick-access below — match your requirement?** If yes, I'll proceed to code the three frontend pieces (navigation, table, toolbar) and wire them to the new `/v1/roles` API.
