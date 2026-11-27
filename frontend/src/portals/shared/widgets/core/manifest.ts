/**
 * The widget manifest — ported from SME widgets/core/manifest.ts, adapted to
 * FSP reality (plans/ui-portals-plan.md §6.2):
 *  - `requires` / action `cells` are OUR flat permission keys (the permissions
 *    table / @FspOperation) — no `row:verb` grid;
 *  - the role-screen vocabulary stays the 4 canonical operations
 *    view | create | modify | approve, resolved from `commandOperations`;
 *  - every size is a compile-checked `size` record (grid.ts).
 */
import type { ReactNode } from 'react';
import type { SizeSpan } from './grid';
import { TIERS } from './grid';

/** The four canonical operations a role may be given on a widget. */
export const OPERATIONS = ['view', 'create', 'modify', 'approve'] as const;
export type Operation = (typeof OPERATIONS)[number];
export const OPERATION_LABEL: Record<Operation, string> = {
  view: 'View', create: 'Create', modify: 'Modify', approve: 'Approve',
};

/** A named, gated action inside a widget (RBAC screen: "<label>: allow"). */
export interface WidgetAction {
  /** stable id; the element carries data-action="<id>" */
  id: string;
  label: string;
  /** the FSP permission key that shows this action ("kyc.approve"). */
  cell: string;
  /** the logical command it sends, when it sends one. */
  command?: string;
  kind?: 'primary' | 'ghost' | 'danger';
}

/** config field descriptors for the builder/inspector form. */
export interface FieldBase { label?: string; help?: string }
export interface NumberField extends FieldBase { type: 'number'; default: number; min?: number; max?: number }
export interface BooleanField extends FieldBase { type: 'boolean'; default: boolean }
export interface StringField extends FieldBase { type: 'string'; default: string }
export interface EnumField<T extends string> extends FieldBase { type: 'enum'; options: readonly T[]; default: T }
export type ConfigField = NumberField | BooleanField | StringField | EnumField<string>;

// typed so a config type's keys map to field descriptors (SME FieldFor)
type FieldFor<T> =
  [T] extends [boolean] ? BooleanField
  : [T] extends [number] ? NumberField
  : [T] extends [string] ? (string extends T ? StringField : EnumField<T>)
  : never;

export type ConfigSchema<C> = {
  readonly [K in keyof C]-?: FieldFor<C[K]>;
};

export interface WidgetDef<C = Record<string, unknown>> {
  /** "<area>.<name>", lower case — stable forever (layouts + RBAC refer to it). */
  id: string;
  version: string;
  label: string;
  description?: string;
  category?: string;
  /** portals whose widgets registry carries it. */
  contexts: Array<'PLATFORM' | 'MANUFACTURER' | 'DISTRIBUTOR' | 'WHOLESALER' | 'MERCHANT' | 'ANY'>;
  /** permission keys — any held one lets the widget render (OR); or {all:[…]} to require several. */
  requires: string[] | { all: string[] };
  /** optional second gate: render only when this is ALSO met (e.g. a target selection). */
  requiresAll?: { all: string[] };
  actions?: WidgetAction[];
  /** logical reads (never URLs); resolved per-context in sources.ts. */
  sources?: string[];
  commands?: string[];
  sources2?: never;
  /** command → canonical operation (drives the role screen); defaults from actions[]. */
  commandOperations?: Record<string, Operation>;
  size: Record<TierName, SizeSpan>;
  minHeight?: string;
  hideOn?: Array<'forbidden' | 'empty'>;
  /** config schema for the inspector; typed per widget. */
  config?: ConfigSchema<C>;
  /** lazy component for the portal lane; the workbench may render fixtures inline. */
  component?: () => Promise<{ default: (props: WidgetRuntimeProps<C>) => ReactNode }>;
  /** pure-data widgets (e.g. tree/table rows) may ship a sync renderer for tests. */
  render?: (props: WidgetRuntimeProps<C>) => ReactNode;
}

export type TierName = (typeof TIERS)[number];
export type { SizeSpan };

/** What the runtime host hands a widget instance. */
export interface WidgetRuntimeProps<C = unknown> {
  mode: 'live' | 'fixture';
  config: Partial<C>;
  /** resolved page/context state from the host (selection + route). */
  context: WidgetHostContextLike;
  port: WidgetDataPortLike;
  state: WidgetState;
  problem?: { title: string; detail: string; code?: string };
}

export interface WidgetHostContextLike {
  portal: string;
  orgId: string | null;
  screenKey: string;
  params: Record<string, string>;
  selection: Record<string, string>;
  select: (key: string, value: string) => void;
  /**
   * The permission keys the host gated on (session, or the workbench's fixed cells). Widgets
   * use it ONLY to choose which affordances to show; core / the plugin re-decide every call.
   */
  permissions: readonly string[];
}

export interface WidgetDataPortLike {
  request: <T>(source: string, params?: Record<string, string>) => Promise<T>;
  command: <T>(command: string, body?: unknown, params?: Record<string, string>) => Promise<T>;
}

export type WidgetState = 'loading' | 'ready' | 'empty' | 'error' | 'forbidden';

/* ----------------------------------------------------------- validations */
const problems: string[] = [];

export function validateWidget(def: WidgetDef, label = def.id): string[] {
  const errs: string[] = [];
  if (!def.id || !/^[a-z][a-z0-9.]*$/.test(def.id)) errs.push(`${label}: bad id "${def.id}"`);
  if (!def.version) errs.push(`${label}: missing version`);
  if (!def.size) errs.push(`${label}: missing size`);
  else for (const t of TIERS) if (def.size[t] === undefined) errs.push(`${label}: size missing tier "${t}"`);
  const cellOp = def.commandOperations ?? {};
  for (const a of def.actions ?? []) {
    if (!a.cell) errs.push(`${label}: action ${a.id} missing cell`);
    if (a.command && !cellOp[a.command]) errs.push(`${label}: action ${a.id} command ${a.command} not in commandOperations`);
  }
  for (const c of Object.keys(cellOp)) if (!OPERATIONS.includes(cellOp[c])) errs.push(`${label}: commandOperations[${c}] = unknown op`);
  if (!def.component && !def.render) errs.push(`${label}: no component or render`);
  return [...errs, ...problems];
}

/**
 * The only way to make a widget. Runs the structural checks eagerly (dev) so
 * a malformed manifest fails the build/tests, exactly like SME.
 */
export function defineWidget<C>(def: WidgetDef<C>): WidgetDef<C> {
  if (import.meta.env.DEV) {
    const errs = validateWidget(def as WidgetDef);
    if (errs.length) throw new Error(`widget=${def.id}: ${errs.join('; ')}`);
  }
  return def;
}

/**
 * Does a holder of `held` see this widget? `requires: [a, b]` = any one of them (OR);
 * `requires: {all: [a, b]}` = every one (AND); `requiresAll` is a second AND gate.
 */
export function requiresMet(def: WidgetDef, held: readonly string[]): boolean {
  const r = def.requires;
  const first = Array.isArray(r)
    ? (r.length === 0 || r.some((k) => held.includes(k))) // empty wants = visible to everyone
    : (r.all.length === 0 || r.all.every((k) => held.includes(k)));
  return first && (def.requiresAll?.all ?? []).every((k) => held.includes(k));
}

/** Every permission key a widget names: what shows it + what each action needs. */
export function cellsOf(def: WidgetDef): string[] {
  const out = new Set<string>([...requiresRows(def), ...(def.requiresAll?.all ?? [])]);
  for (const a of def.actions ?? []) out.add(a.cell);
  return [...out];
}

/** Effective requires rows (flat list). */
export function requiresRows(def: WidgetDef): string[] {
  return Array.isArray(def.requires) ? def.requires : def.requires.all;
}

/** The four-operation checklist for the RBAC screen (plans §6.2). */
export function can(def: WidgetDef, op: Operation): boolean {
  if (op === 'view') return true;
  const ops = def.commandOperations ?? {};
  return Object.values(ops).includes(op);
}

/** Which cells make up one operation (op ⇒ View requires View anyway). */
export function cellsFor(def: WidgetDef, op: Operation): string[] {
  if (op === 'view') return requiresRows(def);
  const out: string[] = [];
  for (const a of def.actions ?? []) {
    const opOf = (def.commandOperations ?? {})[a.command ?? ''];
    if (opOf === op) out.push(a.cell);
  }
  return out;
}
