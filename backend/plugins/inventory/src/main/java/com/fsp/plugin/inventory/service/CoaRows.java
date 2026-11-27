package com.fsp.plugin.inventory.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Plugin-local copy of core's {@code CoaTemplates.inventory()} (plugins never import core). */
public final class CoaRows {

    private CoaRows() {}

    public record Row(int code, String name, String accountType, String normalBalance, String balanceBound) {}

    public static List<CoaRows.Row> rows(UUID tenantId) {
        return List.of(
            row(1200, "Inventory On Hand", "ASSET", "DEBIT", "DEBIT_BOUND"),
            row(2010, "Goods Received Clearing", "CLEARING", "CREDIT", "NONE"),
            row(2050, "Merchant Digital Float", "LIABILITY", "CREDIT", "CREDIT_BOUND"),
            row(2100, "Telco Payable", "LIABILITY", "CREDIT", "NONE"),
            row(2110, "Distributor Commission Payable", "LIABILITY", "CREDIT", "NONE"),
            row(4200, "Platform Fee Revenue", "INCOME", "CREDIT", "NONE"));
    }

    private static CoaRows.Row row(int code, String name, String type, String normal, String bound) {
        return new CoaRows.Row(code, name, type, normal, bound);
    }
}
