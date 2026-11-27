package com.fsp.psp.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controller operation declaration — the PSP mirror of core's {@code @FspOperation} (FAPI2
 * guide §3). Services do NOT import core classes (services/AGENTS rule), so psp carries a
 * local declaration with identical semantics; {@link PspOperations} inventories these into
 * the service descriptor that the gateway fetches at {@code /internal/ops/catalog} and
 * routes by — operation ids stay stable across URL/API changes.
 *
 * Authorization model for this surface: all current ops are SELF-SERVICE and therefore
 * carry NO permission key — the gateway authenticates the person (DPoP-constrained token)
 * and psp enforces resource ownership in-domain (a wallet/saga may only be read or mutated
 * by its owner person; tenant isolation falls out of that identity equality). Ops that
 * later need an RBAC permission (e.g. staff force-void) declare a permission key; the
 * gateway then asks core — the sole PDP — for the decision before forwarding.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PspOperation {

    /** Stable operation id (e.g. psp.bill.pay) — never changes when URLs change. */
    String id();

    /** Permission key required; empty string = owner-scoped (no RBAC permission). */
    String permission() default "";

    /** Owning component (always "psp"; declared for descriptor symmetry with core). */
    String component() default "psp";

    Risk risk() default Risk.READ;

    /** Human title for the generated descriptor/role editor. */
    String title();

    enum Risk { READ, WRITE, FOUR_EYES }
}
