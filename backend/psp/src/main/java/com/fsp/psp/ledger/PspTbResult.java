package com.fsp.psp.ledger;

/** Sealed result for every PSP TigerBeetle write/read (mirror of EmiTbResult). */
public sealed interface PspTbResult {

    record Ok() implements PspTbResult {}

    /** TigerBeetle returned a definitive per-record rejection (accounts/transfers result batch). */
    record DefinitiveRejection(String detail) implements PspTbResult {}

    /** Transport died mid-call with unknown server-side outcome — replay with the same ids. */
    record UnknownTransport(String detail) implements PspTbResult {}
}
