package com.fsp.core.billing;

import java.time.LocalDate;
import java.util.List;

/**
 * Pure invoice arithmetic — the same rules as the widget's preview (model.ts lineAmounts):
 * net = qty × unit price; tax = net × bps / 10 000 rounded half-up to the minor unit.
 * Exact integer arithmetic with overflow checks; never floating point.
 */
public final class InvoiceMath {

    public static final int DEFAULT_CREDIT_DAYS = 30;
    public static final int MAX_LINES = 100;
    /** VAT 15 % or zero-rated (Namibia) — the rates the form offers. */
    public static final List<Integer> TAX_RATES_BPS = List.of(1500, 0);
    public static final List<String> CURRENCIES = List.of("NAD", "ZAR");

    private InvoiceMath() {}

    public record Amounts(long net, long tax, long total) {}

    public static Amounts line(int quantity, long unitPriceMinor, int taxRateBps) {
        long net = Math.multiplyExact((long) quantity, unitPriceMinor);
        // half-up: (net·bps·2 + 10 000) / 20 000 — same integer formula as the widget
        long tax = Math.addExact(Math.multiplyExact(Math.multiplyExact(net, (long) taxRateBps), 2L), 10_000L) / 20_000L;
        return new Amounts(net, tax, Math.addExact(net, tax));
    }

    public static LocalDate dueDate(LocalDate issueDate, int creditDays) {
        return issueDate.plusDays(creditDays);
    }

    /** INV-2026-0007 — seller-scoped, per calendar year of the issue date. */
    public static String number(int year, int seq) {
        return "INV-%d-%04d".formatted(year, seq);
    }

    /** Last two digits of an account number — all a buyer ever sees of it. */
    public static String accountEnding(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 2) return "••";
        return accountNumber.substring(accountNumber.length() - 2);
    }
}
