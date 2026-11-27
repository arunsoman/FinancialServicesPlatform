# Portal Builder — UX Redesign Plan

**Date:** 2026-11-19 · **Screen:** `/widget-workbench/builder` · **Code:** `frontend/src/widget-workbench/builder/`

Goal: a clutter-free, intuitive portal builder that an administrator can use without prior knowledge, while keeping the four-eyes publish flow.

---

## 1. Diagnosis (root causes)

- **The screen mirrors the data model, not the job.** Menu tree, palette, inspector, checks and publish are all on screen at once with equal weight, because each maps to a data structure. Nothing says "you are here, do this next".
- **Wrong audience.** The copy is written for the widget developer (ids, `@1.0.0` versions, permission strings, routes, fixtures). The builder's user is an admin assembling a portal. Developer facts belong behind a "Details" disclosure.
- **No visible document lifecycle.** Seed copy → localStorage draft → server draft → waiting for approval → live is never shown. So there is no "new", no "edited since live", no clear primary action, and checks run on the untouched seed as if the user wrote it.
- **No forgiveness.** No undo, ✕ deletes instantly, drag is the only input. People explore timidly, or not at all.

### Problems this plan must solve
1. Too many buttons (~22 equal-weight controls before doing anything; no primary action; Publish/Export duplicated).
2. No way to build a menu from scratch (always loads a seed; Reset returns to the seed; "+ menu" creates an instantly-invalid menu).
3. Too cluttered (three competing navigations, developer jargon everywhere, 10 warnings on first load).
4. On landing the user doesn't know what to do (the workflow only exists in a code comment).
5. Drag-only widget adding (no keyboard path), no undo, destructive actions without confirmation.
6. Laptop/Desktop tiers zoomed to ~0.43 (unreadable); inspector drops below the canvas with a large gap under 1500px; nothing adapts to narrow widths.

---

## 2. Mental model: one workspace that edits the real portal (WYSIWYG)

**The portal is the document.**

- The **left rail is the portal's actual sidebar**, edited in place (Shopify theme editor + Notion-style inline editing). It replaces both the menu tree and the "Sidebar preview" tab.
- The **centre is the selected page** as it will render.
- A **context panel on the right appears only when something is selected.**
- **Publishing is a lifecycle you move through, not a tab.**
- In all copy, **submenus are called "pages"** — that's what users call them.

### Rejected alternatives
- **Stepper wizard (menus → screens → sizes → publish):** real work is iterative; users go back and forth constantly.
- **Tabs / modes:** this is today's problem. "Sidebar preview" disappears because the sidebar *is* the editor.
- **Retool-style everything-docked:** too dense for a once-a-month admin task.

---

## 3. Wireframes

### 3.1 First run (no draft for this portal)
Primary action: choose a starting point.

```
┌ Portal builder · [Admin console ▾] ──────────────────────────────────────┐
│                                                                          │
│   Build the Admin console                                                │
│   Pick a starting point. Nothing goes live until another admin approves. │
│                                                                          │
│   ┌──────────────────────┐ ┌──────────────────────┐ ┌─────────────────┐  │
│   │ ★ Start from the live │ │ Start blank          │ │ Import JSON…    │  │
│   │ portal (recommended)  │ │ One menu, one empty  │ │                 │  │
│   │ 6 menus · 14 pages    │ │ page to fill.        │ │                 │  │
│   └──────────────────────┘ └──────────────────────┘ └─────────────────┘  │
│                                                                          │
│   How it works: 1 Arrange menus & pages  2 Add widgets  3 Submit for     │
│   approval                                                               │
└──────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Editing
Primary action: add or arrange content. The top-right button reads **"Submit for approval"** once there are changes.

```
┌ Admin console ▾  Draft · edited 2 min ago · saved   ↶ ↷   [Preview ▾] [Submit for approval] ⋯ ┐
├─ SIDEBAR (live) ──┬─ General › Overview ──────────── 📱 ▭ 💻 🖥 ─┬─ (panel only when selected) ┤
│ ▾ General         │                                              │ Approvals queue             │
│    Overview   ●   │ ┌─────────────┐ ┌──────────────────────────┐ │ Width  📱 Full ▾  💻 Half ▾ │
│    Settings       │ │ Approvals   │ │ Audit trail              │ │ Shown to: people who can    │
│    + Add page     │ └─────────────┘ └──────────────────────────┘ │ approve KYC, journals…      │
│ ▸ Ledger          │ ┌──────────────── + Add widget ─────────────┐ │ ▸ Details (id, version,     │
│ ▸ Compliance      │ └────────────────────────────────────────────┘ │   permissions, instance)    │
│ + Add menu        │                                              │ [Remove from page]          │
│───────────────────│                                              │                             │
│ ⚠ 2 to fix        │                                              │                             │
└───────────────────┴──────────────────────────────────────────────┴─────────────────────────────┘
```

"+ Add widget" opens a **searchable picker drawer** (grouped by area, one-line description per widget). Click, Enter, or drag from the drawer.

### 3.3 Submit (right-side sheet; replaces the Publish tab)

```
Submit "Admin console" for approval
 ✓ No blocking issues            ⚠ 1 page is empty (Ledger › Reports) [Go to it]
 Changes since live: +1 menu, +3 pages, 5 widgets moved   [View diff]
 Note for the approver: [______________]
 You can't approve your own change. Another administrator will review it.
                                      [Cancel] [Submit for approval]
```

After submit:
- Header pill: **"Waiting for approval · submitted by you 10:42"**; editing is locked. Actions: **Withdraw**, **Copy approval link**.
- On approval: pill shows **"Live · v7"**.
- On rejection: banner with the checker's reason and **"Edit and resubmit"**.

---

## 4. Design decisions

### 4.1 Starting point
- First run always shows the chooser. **"Start from the live portal"** is the default and replaces "Reset to today's menus".
- Reset moves to the ⋯ menu as **"Discard draft…"**, with a `ConfirmDialog` that names what will be lost.
- **"Start blank"** seeds one menu with one empty page, so the first state is never an error.
- **"Import JSON…"** loads an exported layout.

### 4.2 Menus and pages
- **"+ Add menu"** creates a menu *with one page* and puts the menu name into focused inline edit.
- **"+ Add page"** creates a page, inline-edits its name, and opens its empty canvas.
- Rename: double-click or F2. Reorder: drag, or Alt+↑/↓.
- Id and route are **auto-slugged from the label**, shown read-only under the page's Details, and **locked once published** (ids are immutable after publish). An "Edit URL" override sits under Details.
- Icon: a popover on the menu row.
- A menu with no pages **cannot be created**, so that error disappears.

### 4.3 Adding and arranging widgets — three paths, one picker
1. "+ Add widget" on the canvas, or the `/` key.
2. A per-slot "+" between widgets, inserting at that position.
3. Drag from the open picker.

All three go through the same `placeWidget` so behaviour is consistent. Reorder by drag, or by keyboard: select, then Alt+↑/↓.

### 4.4 Device sizes
- Four icon buttons (📱 ▭ 💻 🖥) in the canvas header, in a segmented control visually distinct from anything else; always visible.
- Render each tier at **true width** (390 / 768 / 1024 / 1440) inside a scrollable frame at 100% scale. **Never CSS-zoom** to unreadable sizes. If the frame is wider than the viewport, scroll horizontally inside the frame; offer **"Fit"** as an explicit option.
- Width per tier uses plain choices — **Full / Half / Third / Quarter / Hidden** — mapped to columns and limited to the widget's min–max. Raw column numbers go under Details.
- Fixed-size widgets show their width as read-only text (no dead −/+ controls).
- Banner copy: *"Phone shows widgets top to bottom in this order."*

### 4.5 Validation
- Runs continuously but **reports only on things the user touched**. Seed/untouched issues stay quiet until submit.
- Shown **inline where the problem is**: a ⚠ dot on the sidebar row, and the empty-page message on the canvas.
- Rail footer counter: **"2 to fix"** (danger red) / **"3 suggestions"** (amber) — never green while warnings exist. Opens a list; each item links to the problem.
- Errors block Submit. Warnings are listed in the submit sheet.

### 4.6 Status / lifecycle
One pill in the header:

`Draft (saved locally)` → `Draft (saved to server)` → `Waiting for approval` → `Live vN` / `Changes requested`

"Sign in" appears only at submit: *"Sign in to submit. Your draft stays here."*

### 4.7 Jargon and progressive disclosure
- Hidden by default: ids, versions, instance ids, routes, fixture notes, the connection bar.
- The connection bar collapses to a small header dot with the tooltip *"Using sample data"*.
- Permissions are phrased as people: *"Shown to people who can approve KYC or journals"*. Raw permission strings go under Details.
- Widgets missing sample data show a neutral placeholder instead of an error: *"Sample data isn't available for this widget. It will show live data in the portal."*

### 4.8 Undo and destructive actions
- Undo/redo stack: Ctrl+Z / Ctrl+Shift+Z, plus ↶ ↷ in the header.
- Removing a widget or page is instant and shows a toast: **"Removed Audit trail · Undo"**.
- Deleting a menu that has pages, or discarding the draft, uses `ConfirmDialog` (destructive variant). Never a browser dialog.
- ✕ moves from the widget card header into the panel as **"Remove from page"**. On hover the card shows only ⠿ and the label.

### 4.9 Workbench chrome
- In builder routes, the 22-link workbench sidebar collapses to icons (or hides behind ☰). Two left navigations side by side are the main source of clutter.
- Remove the duplicate "Organizations" link.

### 4.10 Responsive
- Below 1500px: the inspector becomes a right-side drawer (not dropped under the canvas).
- Below 900px: the sidebar rail becomes a drawer and the panel becomes a bottom sheet.

### 4.11 Microcopy

| Moment | Copy |
|---|---|
| Empty page | **This page is empty. Add widgets to show people something here.** [+ Add widget] |
| New menu | **Name this menu, then add the pages it opens.** |
| Empty picker search | No widgets match "xyz". Widgets are added by developers in the Registry. |
| Blocking issue | **Ledger has no pages. Add a page or remove the menu.** |
| Waiting for approval | **Waiting for another administrator to approve. You can't approve your own change.** |
| Signed out at submit | Sign in to submit. Your draft stays here. |
| Missing sample data | Sample data isn't available for this widget. It will show live data in the portal. |

---

## 5. Delivery plan

### Phase 1 — Quick wins (≤1 day each)
1. Remove the duplicate Publish/Export header buttons. Make **"Submit for approval"** the single primary button; Export and Reset go in a ⋯ menu.
2. Give the device switcher its own visually distinct segmented control, always visible on the canvas.
3. Badge colour: red with errors, amber with warnings, neutral otherwise. Suppress warnings on an untouched seed.
4. "+ menu" creates a menu with one page and auto-focuses the label input. "+ sub" becomes "+ Add page".
5. Palette cards show label + one-line description; id, version and permissions go behind a "Details" toggle (palette and inspector).
6. Click or Enter on a palette card appends the widget to the current page (keyboard path).
7. ✕ becomes remove-with-Undo-toast. Rename submenus to "pages", add the empty-state copy, and add a 3-step "How it works" line under the title.
8. Below 1500px, show the inspector as a right drawer instead of dropping it under the canvas.

### Phase 2 — Medium (≈1 week)
- First-run chooser (live / blank / import).
- Undo/redo stack.
- Inline rename in the tree.
- Auto-slugged ids, locked after publish.
- True-width tier frames instead of zoom, with explicit "Fit".
- Lifecycle status pill.
- Submit sheet with change summary and approver note.

### Phase 3 — Full redesign
- WYSIWYG sidebar rail replacing both the menu tree and the Sidebar-preview tab.
- Widget picker drawer with insert-at-slot.
- Inline validation markers on rail rows and pages.
- Diff view of changes since the live version.
- Collapsed workbench nav in builder routes.
- Responsive collapse below 900px (rail → drawer, panel → bottom sheet).
