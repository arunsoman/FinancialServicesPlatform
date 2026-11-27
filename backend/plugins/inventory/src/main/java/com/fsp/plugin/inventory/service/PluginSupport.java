package com.fsp.plugin.inventory.service;

import com.fsp.plugin.inventory.api.ApiProblemException;
import com.fsp.plugin.inventory.domain.IdempotencyRecord;
import com.fsp.plugin.inventory.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

/** Shared plugin-side helpers: money parsing (minor-unit strings), currency, idempotency, JSON. */
public final class PluginSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private PluginSupport() {}

    /** Minor-unit string → long (AGENTS §2b: string on the wire, long in Java). */
    public static long money(String s, String field) {
        if (s == null || s.isBlank()) throw ApiProblemException.badRequest("FIELD", field + " is required");
        try {
            long v = Long.parseLong(s.trim());
            if (v < 0) throw ApiProblemException.badRequest("FIELD", field + " must be >= 0");
            return v;
        } catch (NumberFormatException e) {
            throw ApiProblemException.badRequest("FIELD", field + " must be an integer minor-unit string");
        }
    }

    public static String currency(String c) {
        if (c == null || !c.matches("(?i)[A-Z]{3}")) {
            throw ApiProblemException.badRequest("FIELD", "currency must be a 3-letter ISO-4217 alpha-3 code");
        }
        return c.toUpperCase();
    }

    public static String nonBlank(String v, String field) {
        if (v == null || v.isBlank()) throw ApiProblemException.badRequest("FIELD", field + " is required");
        return v.trim();
    }

    public static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Reject a replay BEFORE side effects (AGENTS §9.1: unique on (person_id, request_key)). */
    public static void requireFresh(IdempotencyRecordRepository repo, UUID personId, String key) {
        if (repo.findByPersonIdAndRequestKey(personId, key).isPresent()) {
            throw ApiProblemException.conflict("IDEMPOTENT_REPLAY",
                "This Idempotency-Key was already used; the original state has not changed");
        }
    }

    public static void recordIdempotent(IdempotencyRecordRepository repo, UUID personId, String key, int status) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.id = UUID.randomUUID();
        r.personId = personId;
        r.requestKey = key;
        r.status = "DONE";
        r.responseStatus = status;
        repo.save(r);
    }

    /** correlation_id for the plugin event trail (MDC traceId, set by the actor-assertion filter). */
    public static String correlation() {
        String t = MDC.get("traceId");
        return t == null || t.isBlank() ? "unset" : t;
    }

    public static String json(Map<String, ?> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Lower-cased LIKE pattern for server-side search; must not break param-type inference
     *  (a raw null String param binds as bytea on Hibernate 7/Postgres and lower(bytea) fails). */
    public static String searchPattern(String q) {
        return q == null ? null : "%" + q.trim().toLowerCase() + "%";
    }
}
