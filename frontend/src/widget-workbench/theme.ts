/**
 * Light / dark / system theme for the workbench (dashboard research: respect the user's
 * OS preference but let one control override it; the choice persists across reloads).
 * Applies by setting `data-theme` on <html>, the same contract the portals already render
 * from (index.css `[data-theme="dark"]`), so a widget previewed here is tinted the way it
 * will actually ship.
 */
import { useCallback, useEffect, useState } from 'react';

export type ThemeChoice = 'system' | 'light' | 'dark';

const STORAGE_KEY = 'fsp.wb-theme';

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined' && (window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false);
}

/** Pre-paint bootstrap: called from an inline script in widget-workbench.html (no flash). */
export function bootstrapTheme(): void {
  try {
    const choice = (localStorage.getItem(STORAGE_KEY) as ThemeChoice | null) ?? 'system';
    const dark = choice === 'dark' || (choice === 'system' && systemPrefersDark());
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  } catch {
    /* storage unavailable (private mode) — leave the html default */
  }
}

export function useTheme(): { theme: ThemeChoice; resolved: 'light' | 'dark'; setTheme: (t: ThemeChoice) => void } {
  const [theme, setThemeState] = useState<ThemeChoice>(() => {
    try {
      return (localStorage.getItem(STORAGE_KEY) as ThemeChoice | null) ?? 'system';
    } catch {
      return 'system';
    }
  });

  const apply = useCallback((choice: ThemeChoice) => {
    const resolved = choice === 'dark' || (choice === 'system' && systemPrefersDark()) ? 'dark' : 'light';
    document.documentElement.dataset.theme = resolved;
    return resolved;
  }, []);

  const [resolved, setResolved] = useState<'light' | 'dark'>(() => apply(theme as ThemeChoice));

  // follow OS changes while on "system"
  useEffect(() => {
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)');
    const onChange = () => {
      if (theme === 'system') setResolved(apply('system'));
    };
    mq?.addEventListener?.('change', onChange);
    return () => mq?.removeEventListener?.('change', onChange);
  }, [theme, apply]);

  const setTheme = useCallback(
    (t: ThemeChoice) => {
      setThemeState(t);
      setResolved(apply(t));
      try {
        localStorage.setItem(STORAGE_KEY, t);
      } catch {
        /* ignore */
      }
    },
    [apply],
  );

  return { theme, resolved, setTheme };
}
