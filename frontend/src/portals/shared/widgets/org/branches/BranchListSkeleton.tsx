export interface BranchListSkeletonProps {
  rows?: number;
}

/** Placeholder rows for the first load (ui-std §9.5: skeletons, never spinners). */
export function BranchListSkeleton({ rows = 5 }: BranchListSkeletonProps) {
  return (
    <div className="grid gap-2" aria-busy="true" aria-label="Loading branches" data-testid="branch-skeleton">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-3 rounded-xl border border-line p-3">
          <span className="grid flex-1 gap-1.5">
            <span className="h-3 w-44 animate-pulse rounded bg-raised" />
            <span className="h-2.5 w-16 animate-pulse rounded bg-raised" />
          </span>
          <span className="h-5 w-20 animate-pulse rounded-full bg-raised" />
        </div>
      ))}
    </div>
  );
}
