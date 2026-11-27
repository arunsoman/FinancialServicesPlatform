package com.fsp.shared.rails;

/** Rail families — one adapter implementation per family (plan §3.4.2). */
public enum RailFamily {
    ISO8583,
    ISO20022,
    REST_OPEN_BANKING,
    NACHA_BATCH,
    BILLER_REST,
    WALLET_RAIL,
    CUSTODIAN_REST
}
