package com.fsp.core.ledger;

/** CoA templates by tenant sub-type (Phase-0 subsets from PRD §3.5). */
import java.util.List;

public final class CoaTemplates {

    public record Row(int code, String name, String accountType, String normalBalance, String balanceBound) {}

    private CoaTemplates() {}

    public static java.util.List<Row> forTenant(String subType) {
        return switch (subType == null ? "" : subType) {
            case "MANUFACTURER", "DISTRIBUTOR", "DEALER", "WHOLESALER" -> supplyChain();
            default -> merchant();
        };
    }

    public static java.util.List<Row> psp() {
        return List.of(
            new Row(1000, "Cash at Bank", "ASSET", "DEBIT", "DEBIT_BOUND"),
            new Row(1150, "Merchant Reserve", "ASSET", "DEBIT", "DEBIT_BOUND"),
            new Row(1300, "Acquirer Clearing", "CLEARING", "DEBIT", "NONE"),
            new Row(1350, "Network Fees Payable", "LIABILITY", "CREDIT", "NONE"),
            new Row(2110, "Payouts in Transit", "ASSET", "DEBIT", "DEBIT_BOUND"),
            new Row(2100, "Merchant Payable", "LIABILITY", "CREDIT", "CREDIT_BOUND"),
            new Row(2200, "Chargeback Payable", "LIABILITY", "CREDIT", "NONE"),
            new Row(4100, "MDR Revenue", "INCOME", "CREDIT", "NONE"),
            new Row(4200, "Gateway Fee Revenue", "INCOME", "CREDIT", "NONE"),
            new Row(5100, "Interchange Expense", "EXPENSE", "DEBIT", "NONE"),
            new Row(5200, "Scheme Fee Expense", "EXPENSE", "DEBIT", "NONE"));
    }

    /** Inventory & digital-value plugin rows (plans/inventory-plugin-plan.md D4).
     *  Codes chosen so they never collide with PSP rows (PSP uses 2110 for Payouts in Transit —
     *  here 2110 is free only on non-PSP books; a collision is respected and used as-is). */
    public static java.util.List<Row> inventory() {
        return List.of(
            new Row(1200, "Inventory On Hand", "ASSET", "DEBIT", "DEBIT_BOUND"),
            new Row(2010, "Goods Received Clearing", "CLEARING", "CREDIT", "NONE"),
            new Row(2050, "Merchant Digital Float", "LIABILITY", "CREDIT", "CREDIT_BOUND"),
            new Row(2100, "Telco Payable", "LIABILITY", "CREDIT", "NONE"),
            new Row(2110, "Distributor Commission Payable", "LIABILITY", "CREDIT", "NONE"),
            new Row(4200, "Platform Fee Revenue", "INCOME", "CREDIT", "NONE"));
    }

    public static java.util.List<Row> merchant() {
        return List.of(
            new Row(1000, "Cash at Bank", "ASSET", "DEBIT", "DEBIT_BOUND"),
            new Row(2000, "Customer Deposits", "LIABILITY", "CREDIT", "CREDIT_BOUND"),
            new Row(4000, "Fee Revenue", "INCOME", "CREDIT", "NONE"),
            new Row(5100, "Payment Fees Expense", "EXPENSE", "DEBIT", "NONE"));
    }

    public static java.util.List<Row> supplyChain() {
        return List.of(
            new Row(1000, "Cash at Bank", "ASSET", "DEBIT", "DEBIT_BOUND"),
            new Row(3000, "Trade Receivables", "ASSET", "DEBIT", "DEBIT_BOUND"),
            new Row(3100, "Trade Payables", "LIABILITY", "CREDIT", "NONE"),
            new Row(4100, "Sales Revenue", "INCOME", "CREDIT", "NONE"),
            new Row(5100, "Payment Fees Expense", "EXPENSE", "DEBIT", "NONE"),
            new Row(5200, "Cost of Goods Sold", "EXPENSE", "DEBIT", "NONE"));
    }
}
