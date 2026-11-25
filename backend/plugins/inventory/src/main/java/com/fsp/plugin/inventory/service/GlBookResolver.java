package com.fsp.plugin.inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.plugin.inventory.api.ApiProblemException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the tenant's ACTIVE book and its GL accounts BY CODE (plans/inventory-plugin-plan.md D4).
 * The plugin never hardcodes TigerBeetle or GL ids — core owns them; the plugin asks, then posts
 * journal entries that reference those ids.
 *
 * GL ON FIRST USE (owner's decision 2026-11-25, option A): the plugin is installed once on the
 * platform root and inherited by every organization (easy install), so its CoA rows are ensured in
 * EACH organization's own ACTIVE book the first time that organization needs them — not at
 * installation time, and never in a book the platform root does not have.
 */
@Slf4j
@Component
public class GlBookResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record LedgerRefs(UUID bookId, String currency, Map<Integer, UUID> glByCode) {}

    private final CoreClient core;
    /** tenant:book pairs whose CoA rows this process has already ensured (core's ensure is idempotent anyway). */
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    public GlBookResolver(CoreClient core) {
        this.core = core;
    }

    public record Resolved(UUID bookId, String currency, UUID glAccountId, int code, String name,
                           String tbAccountId) {}

    /** Resolve ONE GL row by code for the tenant. */
    public Resolved gl(UUID tenantId, int code) {
        return gls(tenantId, code).values().iterator().next();
    }

    /** Resolve several codes in ONE book scan. Missing rows are ensured once in this organization's
     *  book (GL on first use), then looked up again; still missing → GL_MISSING problem. */
    public Map<Integer, Resolved> gls(UUID tenantId, int... codes) {
        LedgerRefs refs = refs(tenantId);
        JsonNode accounts = core.listGlAccounts(tenantId, refs.bookId(), null);
        if (!allPresent(accounts, codes)) {
            ensureCoa(tenantId, refs.bookId(), true);
            accounts = core.listGlAccounts(tenantId, refs.bookId(), null);
        }
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

    /** Ensure the plugin's CoA rows in this organization's book (additive, idempotent in core). */
    public void ensureCoa(UUID tenantId, UUID bookId, boolean force) {
        String key = tenantId + ":" + bookId;
        if (!force && ensured.contains(key)) return;
        List<Map<String, Object>> rows = CoaRows.rows(tenantId).stream()
            .<Map<String, Object>>map(r -> Map.of(
                "code", r.code(), "name", r.name(), "accountType", r.accountType(),
                "normalBalance", r.normalBalance(), "balanceBound", r.balanceBound()))
            .toList();
        core.ensureGlAccounts(tenantId, bookId, rows);
        ensured.add(key);
        log.info("inventory_gl_accounts_ensured tenantId={} book={} rows={}", tenantId, bookId, rows.size());
    }

    /** Every wanted code present WITH its TigerBeetle account. */
    static boolean allPresent(JsonNode accounts, int... codes) {
        for (int c : codes) {
            boolean found = false;
            for (JsonNode a : accounts) {
                if (a.path("code").asInt(-1) == c && a.hasNonNull("tbAccountId") && !a.path("tbAccountId").asText().isBlank()) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** The tenant's ACTIVE book, or empty when it has none (the platform root never does). */
    public java.util.Optional<LedgerRefs> findRefs(UUID tenantId) {
        try {
            return java.util.Optional.of(refs(tenantId));
        } catch (ApiProblemException e) {
            if ("INVENTORY_NO_BOOK".equals(e.getCode())) return java.util.Optional.empty();
            throw e;
        }
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
                "This organization has no active ledger book yet, so inventory cannot post for it. Set up its ledger book first.", 409);
        }
        UUID bookId = UUID.fromString(chosen.path("id").asText());
        return new LedgerRefs(bookId, chosen.path("currency").asText(), Map.of());
    }
}
