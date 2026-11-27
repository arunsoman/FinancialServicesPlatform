package com.fsp.shared;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Launcher-cwd-independent PKI key lookup — RELATIVE ONLY (no absolute paths in code).
 *
 * Gradle bootRun / IDE runs / container launches start from different working directories
 * (module dir, repo root, …), so a single literal relative path is never enough. Instead of
 * hard-coding machine-specific absolute fallbacks, this resolver anchors on the repo itself:
 * it walks up from the working directory and probes <ancestor>/infrastructure/pki/dev —
 * wherever the repo is cloned, on any machine, at any module depth.
 *
 * Resolution order (first existing wins):
 *  1. the configured path as given (correct for the launcher's cwd, or absolute deployment path)
 *  2. the repo-relative default infrastructure/pki/dev/<fileName> at the cwd or any ancestor
 *  3. the configured path anchored at the cwd or any ancestor (module-relative configs like ../infrastructure/…)
 *
 * If nothing exists, the repo-relative default is returned so callers fail closed with their
 * own "run 'make pki'" message.
 */
public final class PkiKeyResolver {

    /** Dev PKI directory, relative to the repository root. */
    public static final String PKI_DEV_DIR = "infrastructure/pki/dev";

    /** Ancestor walk bound: repo modules live at most ~3 levels deep; keep a margin. */
    private static final int MAX_ANCESTORS = 5;

    private PkiKeyResolver() {
    }

    /**
     * Resolve a PKI file: the configured path (if it exists as given), else the named file
     * under the repo's infrastructure/pki/dev found by walking up from the working directory.
     */
    public static Path resolve(String configured, String fileName) {
        Path cfg = Path.of(configured);
        if (Files.exists(cfg)) return cfg;

        Path rel = Path.of(PKI_DEV_DIR, fileName);
        Path found = probeAncestors(rel);
        if (found != null) return found;

        found = probeAncestors(cfg);
        return found != null ? found : rel;
    }

    /** First ancestor (cwd itself first, then parents up to {@link #MAX_ANCESTORS}) holding {@code relative}. */
    private static Path probeAncestors(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i <= MAX_ANCESTORS && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }
}
