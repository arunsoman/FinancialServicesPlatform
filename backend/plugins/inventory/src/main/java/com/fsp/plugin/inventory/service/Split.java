package com.fsp.plugin.inventory.service;

/** Deterministic fee split (plans D7, vision §3.4): minor-unit allocation with the remainder
 *  (merchant margin) computed last so the JE always balances to the penny. */
public final class Split {

    private Split() {}

    public static long telco(long amount, int telcoBp, int distributorBp, int platformBp) {
        return amount * telcoBp / 10_000;
    }

    public static long distributor(long amount, int distributorBp) {
        return amount * distributorBp / 10_000;
    }

    public static long platform(long amount, int platformBp) {
        return amount * platformBp / 10_000;
    }

    public static long margin(long amount, int distributorBp, int platformBp, long telco) {
        return amount - telco - amount * distributorBp / 10_000 - amount * platformBp / 10_000;
    }
}
