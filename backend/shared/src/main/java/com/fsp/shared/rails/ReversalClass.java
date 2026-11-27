package com.fsp.shared.rails;

/** Per-biller reversal classification (plan §12.3, PSD-1 §16.2 outer 15-business-day bound). */
public enum ReversalClass {
    /** Reversible anytime before commit (platform fees, own-service charges). */
    A,
    /** Reversible only inside the contractual window, auto only with a recon proof. */
    B,
    /** Non-reversible (airtime/data vouchers, prepaid value already applied).
     *  <p>Default for contract-silent billers — conservative.</p> */
    C
}
