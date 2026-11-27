package com.fsp.emi.ledger;

/** Sealed result for every EMI TigerBeetle write/read (mirror of core TbResult). */
public sealed interface EmiTbResult {

    record Ok() implements EmiTbResult {}

    /** TigerBeetle returned a definitive per-record rejection (accounts/transfers result batch). */
    record DefinitiveRejection(String detail) implements EmiTbResult {}

    /** Transport died mid-call with unknown server-side outcome — replay with the same ids. */
    record UnknownTransport(String detail) implements EmiTbResult {}
}
