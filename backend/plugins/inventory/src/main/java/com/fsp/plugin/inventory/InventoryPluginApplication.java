package com.fsp.plugin.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * fsp-plugin-inventory — SKU + digital-value inventory plugin (:8084, plans/inventory-plugin-plan.md).
 *
 * Contract (AGENTS.md plugin-platform-plan §10):
 *  - Serves ONLY manifest operations under /v1/plugins/inventory/*
 *  - Verifies the gateway actor assertion on every inbound request
 *  - Outbound only via the gateway (machine tokens + DPoP + actor assertions)
 *  - Owns its own DB (fsp_plugin_inventory); never touches core's DB or TigerBeetle —
 *    money moves exclusively through core journal entries (four-eyes or posting intents)
 *  - Data rows carry owner_tenant_id; queries always scoped
 */
@SpringBootApplication
@EnableScheduling
public class InventoryPluginApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryPluginApplication.class, args);
    }
}
