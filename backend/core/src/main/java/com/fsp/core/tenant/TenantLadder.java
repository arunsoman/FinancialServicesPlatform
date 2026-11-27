package com.fsp.core.tenant;

import java.util.List;
import java.util.Locale;

/**
 * The organization ladder: every org onboards ONLY the next level down.
 *
 * <pre>
 *   PLATFORM → MANUFACTURER (the PSP, depth 1) → DISTRIBUTOR → WHOLESALER → MERCHANT (leaf)
 * </pre>
 *
 * Decided 2026-11-19 (matches the four FSP org portals and SME's org-type ladder, where SME's
 * SUPPLIER ≈ WHOLESALER). DEALER is a recognised sub-type with no rung: it onboards nothing and
 * nothing onboards it. Core is the PDP: the UI shows the next level, core refuses anything else.
 */
public final class TenantLadder {

    public static final List<String> RUNGS = List.of("MANUFACTURER", "DISTRIBUTOR", "WHOLESALER", "MERCHANT");

    private TenantLadder() { }

    /** The only sub-type the given org may onboard, or null when it may onboard none (leaf / off-ladder). */
    public static String childSubTypeOf(Tenant parent) {
        if (parent == null) return null;
        if ("PLATFORM".equals(parent.type)) return RUNGS.get(0);
        if (parent.subType == null) return null;
        int i = RUNGS.indexOf(parent.subType.toUpperCase(Locale.ROOT));
        return i < 0 || i == RUNGS.size() - 1 ? null : RUNGS.get(i + 1);
    }
}
