import { Outlet } from 'react-router-dom'

export function AppShell() {
  return (
    <div className="grid h-[100dvh] max-h-[100dvh] grid-rows-[auto_1fr_auto] overflow-hidden bg-canvas">
      <header className="border-b border-line bg-panel px-4 py-3">
        <div className="flex items-center justify-between">
          <span className="font-semibold">FSP</span>
          <span className="text-sm text-dim">Phase 0</span>
        </div>
      </header>

      <main className="min-h-0 overflow-hidden">
        <div className="h-full overflow-y-auto p-4" tabIndex={0} aria-label="Main content">
          <Outlet />
        </div>
      </main>

      <footer className="border-t border-line bg-panel px-4 py-2 text-xs text-dim">
        FSP Core Walking Skeleton
      </footer>
    </div>
  )
}
