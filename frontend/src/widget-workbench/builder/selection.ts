/** What the builder's inspector is pointed at. */
export type Sel =
  | { kind: 'menu'; m: number }
  | { kind: 'screen'; m: number; s: number }
  | { kind: 'widget'; m: number; s: number; id: string }
  | null;
