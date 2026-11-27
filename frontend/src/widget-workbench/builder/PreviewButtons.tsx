import { SCREEN_TYPES, openPreview } from './preview-window';

/** Preview ▸ Desktop · Tablet · Mobile — each opens the real portal in a window of that size. */
export function PreviewButtons({ portalKey }: { portalKey: string }) {
  return (
    <span className="row-b" role="group" aria-label="Preview the portal">
      <span className="dim small">Preview</span>
      {SCREEN_TYPES.map((t) => (
        <button
          key={t.id}
          type="button"
          className="btn"
          aria-label={`Preview on ${t.label.toLowerCase()}`}
          title={`Open the ${portalKey} portal at ${t.width}×${t.height} with this layout (updates as you edit)`}
          onClick={() => openPreview(portalKey, t)}
        >
          {t.label} ↗
        </button>
      ))}
    </span>
  );
}
