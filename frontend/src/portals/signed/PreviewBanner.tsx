/**
 * Marks a builder-preview tab (./preview.ts, LOCAL DEV ONLY) so an unsigned, unpublished
 * layout is never mistaken for the real portal — and says how wide the window is, so the
 * developer knows which screen tier they are looking at.
 */
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';

function exitHref(): string {
  return `${window.location.pathname}?preview=off`;
}

export function PreviewBanner(): ReactNode {
  const [width, setWidth] = useState(() => window.innerWidth);
  useEffect(() => {
    const on = () => setWidth(window.innerWidth);
    window.addEventListener('resize', on);
    return () => window.removeEventListener('resize', on);
  }, []);
  return (
    <div
      role="status"
      data-state="builder-preview"
      className="fsp-panel fixed bottom-3 left-1/2 z-50 flex -translate-x-1/2 items-center gap-3 px-3 py-1.5 text-xs shadow-lg"
    >
      <b className="text-[var(--portal-accent)]">PREVIEW</b>
      <span className="text-dim">builder layout, not published · {width}px wide</span>
      <a className="underline" href={exitHref()}>Exit</a>
    </div>
  );
}

export function PreviewEmpty({ label }: { label: string }): ReactNode {
  return (
    <div role="status" className="grid h-[100dvh] place-items-center px-4">
      <div className="fsp-panel max-w-lg p-6" data-state="builder-preview-empty">
        <div className="fsp-eyebrow mb-2">{label} · preview</div>
        <h1 className="mb-3 text-xl font-bold">Nothing to preview yet</h1>
        <p className="text-dim text-sm leading-relaxed">
          Open the {label} portal in the widget workbench’s portal builder in this browser and fix any
          errors it lists — this window redraws as soon as the builder has a layout it can show.{' '}
          <a className="underline" href={exitHref()}>Leave preview</a>
        </p>
      </div>
    </div>
  );
}
