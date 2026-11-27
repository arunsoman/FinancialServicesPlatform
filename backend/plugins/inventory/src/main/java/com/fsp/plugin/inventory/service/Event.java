package com.fsp.plugin.inventory.service;

import com.fsp.plugin.inventory.domain.InventoryEvent;
import com.fsp.plugin.inventory.repository.InventoryEventRepository;

import java.util.Map;
import java.util.UUID;

/** Domain-event trail helper — every record carries correlation_id (AGENTS §2f). */
public final class Event {

    private Event() {}

    public static void record(InventoryEventRepository events, UUID tenantId, String type,
                              String subjectType, UUID subjectId, Map<String, ?> payload) {
        InventoryEvent e = new InventoryEvent();
        e.id = UUID.randomUUID();
        e.ownerTenantId = tenantId;
        e.eventType = type;
        e.subjectType = subjectType;
        e.subjectId = subjectId;
        e.payload = PluginSupport.json(payload);
        e.correlationId = PluginSupport.correlation();
        events.save(e);
    }
}
