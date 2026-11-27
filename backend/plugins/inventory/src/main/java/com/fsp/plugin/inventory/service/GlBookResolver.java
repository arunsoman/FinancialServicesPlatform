package com.fsp.plugin.inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.plugin.inventory.api.ApiProblemException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the tenant's ACTIVE book and its GL accounts BY CODE (plans/inventory-plugin-plan.md D4).
 * The plugin never hardcodes TigerBeetle or GL ids — core owns them; the plugin asks, then posts
 * journal entries that reference those ids.
 */
@Slf4j
@Component
public class GlBookResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record LedgerRefs(UUID bookId, String currency, Map<Integer, UUID> glByCode) {}

    private final CoreClient core;

    public GlBookResolver(CoreClient core) {
        this.core = core;
    }

    public record Resolved(UUID bookId, String currency, UUID glAccountId, int code, String name,
                           String tbAccountId) {}

    /** Resolve ONE GL row by code for the tenant. */
    public Resolved gl(UUID tenantId, int code) {
        return gls(tenantId, code).values().iterator().next();
    }

    /** Resolve several codes in ONE book scan. Missing codes → GL_MISSING problem. */
    public Map<Integer, Resolved> gls(UUID tenantId, int... codes) {
        LedgerRefs refs = refs(tenantId);
        JsonNode accounts = core.listGlAccounts(tenantId, refs.bookId(), null);
        Map<Integer, Resolved> out = new HashMap<>();
        for (JsonNode a : accounts) {
            int code = a.path("code").asInt(-1);
            boolean wanted = false;
            for (int c : codes) if (c == code) wanted = true;
            if (!wanted) continue;
            String tbAccountId = a.hasNonNull("tbAccountId") ? a.path("tbAccountId").asText() : null;
            if (tbAccountId == null || tbAccountId.isBlank()) {
                throw new ApiProblemException("GL_NOT_PROVISIONED",
                    "GL " + code + " has no TigerBeetle account; ensure the CoA was ensured/provisioned", 409);
            }
            out.put(code, new Resolved(refs.bookId(), refs.currency(),
                UUID.fromString(a.path("id").asText()), code, a.path("name").asText(""), tbAccountId));
        }
        for (int c : codes) {
            if (!out.containsKey(c)) {
                throw new ApiProblemException("GL_MISSING",
                    "GL account " + c + " is missing from the tenant's book", 409);
            }
        }
        return out;
    }

    /** The tenant's first ACTIVE book (the one the plugin posts into). */
    public LedgerRefs refs(UUID tenantId) {
        JsonNode books = core.listBooks(tenantId, null);
        JsonNode chosen = null;
        for (JsonNode b : books) {
            if ("ACTIVE".equals(b.path("status").asText())) {
                chosen = b;
                break;
            }
        }
        if (chosen == null) {
            throw new ApiProblemException("INVENTORY_NO_BOOK",
                "The tenant has no ACTIVE book to post into; provision the ledger first", 409);
        }
        UUID bookId = UUID.fromString(chosen.path("id").asText());
        return new LedgerRefs(bookId, chosen.path("currency").asText(), Map.of());
    }
}
