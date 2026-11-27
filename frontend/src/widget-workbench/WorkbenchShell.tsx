import { NavLink, Outlet } from 'react-router-dom';
import { WIDGETS } from '../portals/shared/widgets/registry';
import { ConnectionBar } from './ConnectionBar';
import { groupByCategory } from './model';

const navClass = ({ isActive }: { isActive: boolean }) => (isActive ? 'nav on' : 'nav');

/** The workbench shell (SME wb-root): widgets by category on the left, the page on the right. */
export function WorkbenchShell() {
  const groups = groupByCategory(WIDGETS);
  return (
    <div className="wb">
      <a className="skip" href="#wb-main">Skip to content</a>
      <aside className="side">
        <div className="brand">
          FSP <b>widget workbench</b>
        </div>
        <NavLink to="/builder" className={navClass}>Portal builder</NavLink>
        <NavLink to="/registry" className={navClass}>Registry</NavLink>
        <NavLink to="/new" className={navClass}>New widget (LLM)</NavLink>
        {groups.map((g) => (
          <div key={g.category} className="grid gap-1">
            <div className="grp">{g.category}</div>
            {g.items.map((w) => (
              <NavLink key={w.id} to={`/w/${w.id}`} className={navClass}>{w.label}</NavLink>
            ))}
          </div>
        ))}
        <p className="foot dim">
          New widget: <b>New widget (LLM)</b> with an LLM, or by hand in{' '}
          <span className="mono">shared/widgets/&lt;area&gt;/&lt;name&gt;/</span> (index.tsx + widget.tsx + data.ts).
          <br />
          Theme: the toggle in the bar above (light · dark · follows the OS) — the same
          <code>data-theme</code> on &lt;html&gt; the portals render from.
        </p>
      </aside>
      <main className="main" id="wb-main" tabIndex={-1}>
        <ConnectionBar />
        <Outlet />
      </main>
    </div>
  );
}
