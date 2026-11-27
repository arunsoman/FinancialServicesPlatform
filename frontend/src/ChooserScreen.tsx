/** The dev portal chooser (index.html → src/chooser.tsx mounts it). */
const PORTALS = [
  { path: '/admin/', label: 'Admin Console', accent: '#f0a10e', who: 'Platform organization staff' },
  { path: '/manufacturer/', label: 'Manufacturer', accent: '#f0a35e', who: 'subType MANUFACTURER tenants' },
  { path: '/distributor/', label: 'Distributor', accent: '#a78bfa', who: 'subType DISTRIBUTOR tenants' },
  { path: '/wholesaler/', label: 'Wholesaler', accent: '#7cecff', who: 'subType WHOLESALER tenants' },
  { path: '/merchant/', label: 'Merchant', accent: '#f2c94c', who: 'subType MERCHANT tenants' },
]

export function ChooserScreen() {
  return (
    <div className="grid min-h-[100dvh] place-items-center">
      <div className="w-full max-w-2xl">
        <div className="fsp-eyebrow mb-2 text-center">FSP Financial Services Platform</div>
        <h1 className="mb-8 text-center text-2xl">Pick a portal</h1>
        <div className="grid gap-4 sm:grid-cols-2">
          {PORTALS.map((p) => (
            <a key={p.path} href={p.path} className="fsp-panel block p-5 transition-transform hover:-translate-y-0.5">
              <div className="mb-1 flex items-center gap-2">
                <span className="h-3 w-3 rounded-full" style={{ background: p.accent }} />
                <span className="font-extrabold" style={{ color: p.accent }}>{p.label}</span>
              </div>
              <div className="text-dim text-xs">{p.who}</div>
            </a>
          ))}
        </div>
        <p className="text-dim mt-8 text-center text-xs">
          One shared codebase · four build-time identities · menus from the widget registry
        </p>
      </div>
    </div>
  )
}
