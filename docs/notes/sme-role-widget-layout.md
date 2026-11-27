# SME Role Management Widget — ASCII Layout (from role-menu-builder)

## Structure Overview

The SME widget is an **Angular organism** (`mp-role-menu-builder`) that renders:

1. **Top bar** — summary of visible menus/screens + checkbox for "Show planned screens"
2. **LHS sidebar** — collapsible menu tree with:
   - Top-level menu items (Roles, Permissions, Approvals etc.)
   - Each menu has nested submenus (expandable with `▾`/`▸` caret)
   - Submenus contain screens with action chips
3. **Main area** — table of actions:
   - Each screen shows "chips" (action buttons) grouped by **rows**
   - Chips have states: `own` (✓), `off`, `inh` (inherited, dashed)
   - Chips display verb labels: `view`, `create`, `edit`, `delete`, `approve`, etc.
4. **Action rows** — each row (`arow`) has:
   - Description on left (`rdesc`)
   - Flexible chip area on right (`chips`) — flex wrap, gap 4px
   - Chips are buttons with `chip-v` class
5. **Inheritance** — inherited cells show "visible through a role it builds on" tag with dashed border

## ASCII Representation (approximate)

```
+=====================================================================+
|                     +-----------------------+                       |
|                     |  Top bar: "3 of 5 menus  |                       |
|                     |  2 of 8 screens  · [◆]  |                       |
+------------------------------------------------------------------------+
|  LHS SIDEBAR (collapsible)                                              |
|  ┌───────────────┐  ┌───────────────────────┐  ┌───────────────┐     |
|  │ &larrb;  Roles  │  │ &larrb; Permissions   │  │ &larrb; Approvals│     |
|  └───────────────┘  └───────────────────────┘  └───────────────┘     |
|  ┌───────────────┐  ┌───────────────────────┐  ┌───────────────┐     |
|  │ &larrb;  My RBAC│  │ &larrb;  Current RBAC │  │ &larrb;  Pending │     |
|  └───────────────┘  └───────────────────────┘  └───────────────┘     |
|  └─────────────────────────────────────────────────────────────────────|
|                                                                       |
|  ===== MAIN TABLE AREA =====                                            |
|  +------------------------------------------------------------------------+ |
|  |  (when submenu open)                                                    | |
|  |  ---------------------------------------------------------------  | |
|  |  | Screen: "Manage Users"                                                 | | |
|  |  |   Row: user.manage |  [view]  [create]  [edit]  [delete]              | | |
|  |  |  |----------------------------------------------------------------| | | |
|  |  |  | Row: role.manage |  [view]  [create]  [edit]  [delete]              | | | |
|  |  |  |----------------------------------------------------------------| | | |
|  |  |  (Empty state) No chips for this screen.                              | | | |
|  |                                                                       | | |
|  |  (chip states: ✓ = own, = inherited (dashed), = off)                   | | | |
|  +------------------------------------------------------------------------+ |
|  |                                                                       | | |
|  |  ===== SUB-MENU CONTEXT (appears when row/selection changes) =====       | | |
|  |  [Make Active]   [Make Inactive]   [Grant Permission]   [Revoke]       | | |
|  |  [Transfer Ownership]   [Delete]   [View History]                     | | | |
|  +------------------------------------------------------------------------+ | |
|  |                                                                       | | |
|  |  ===== WIDGET QUICK-ACCESS BAR (persistent below table) =====           | | |
|  |  [Refresh]   [Add New Role]   [Import Roles]   [Export Roles]          | | |
|  +------------------------------------------------------------------------+ | |
+=======================================================================+
| FOOTER: "Chips: ✓ = own, = inherited, = off • Select a row to enable ops"|
+=======================================================================+
```

## Key Features from the SME Pattern

| Feature | Description |
|---------|-------------|
| **Menu submenus** | LHS sidebar with expandable menus (`▾`/`▸` caret). Each top-level item reveals nested submenus. |
| **Screen chips** | Actions grouped by **rows** (`rowsOf()`). Each chip is a button with state (`own`/`off`/`inh`). |
| **Row description** | Left column (`rdesc`) describes the row (e.g., `user.manage`, `role.manage`). |
| **Inheritance** | Inherited cells (`inh`) appear with dashed border and "visible through a role it builds on" tag. |
| **Quick-access bar** | Persistent below the table: Refresh, Add New, Import, Export. |
| **Selection model** | User clicks a row → sub-menu context actions apply only to that role. |

## How This Maps to FSP

The FSP role management widget (GAP-2) should follow a **similar pattern**:

1. **LHS: Menu submenus** (Roles, Permissions, Approvals → My Tenants, Current Tenant, Pending)
2. **Table area**: Headers (View, Create, Modify, Approve, Delete) as the **first row**
3. **Role rows**: Each role = one row, with ☐ checkboxes in the 5 action columns
4. **Sub-menu context**: When a row is selected, inline actions for that role only
5. **Quick-access bar**: Below table — Refresh, Add New Role, Import, Export

The SME widget uses Angular + a complex model (`MenuNode`, `ScreenNode`, `CellChip`), but the **visual structure** and **interaction pattern** can be adapted to FSP's React + TypeScript stack.

---

**Does this SME layout match what you had in mind for the FSP role management widget?** If yes, I'll adapt it to the FSP codebase using React components and the new `/v1/roles` API we just built.
