export interface SectionHeadingProps {
  id: string;
  title: string;
  subtitle: string;
}

/** Shield-marked section title with a rule under it (Role Details / Permissions Matrix). */
export function SectionHeading({ id, title, subtitle }: SectionHeadingProps) {
  return (
    <div className="mb-4 flex items-start gap-3 border-b border-line pb-3">
      <svg aria-hidden="true" viewBox="0 0 24 24" className="mt-0.5 h-5 w-5 shrink-0" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z" />
        <path d="M9 12l2 2 4-4" />
      </svg>
      <div className="min-w-0">
        <h2 id={id} className="text-[16px] font-bold leading-tight">{title}</h2>
        <p className="text-dim mt-0.5 text-[12px]">{subtitle}</p>
      </div>
    </div>
  );
}
