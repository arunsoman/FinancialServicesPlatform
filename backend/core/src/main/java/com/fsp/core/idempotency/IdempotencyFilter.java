package com.fsp.core.idempotency;

import com.fsp.core.common.Actor;
import com.fsp.core.common.HashUtil;
import com.fsp.core.common.RequestCorrelation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Transport-level idempotency for every mutating /v1 request (AGENTS.md §2e).
 *
 * Semantics for a given (actor, Idempotency-Key) pair:
 *  - identical retry (same method+path+body hash), original DONE  → replay the stored response,
 *    header Idempotency-Replayed: true;
 *  - identical retry while original PENDING (concurrent duplicate) → 409 IDEMPOTENCY_IN_PROGRESS
 *    with Retry-After;
 *  - same key, different payload                                  → 409 IDEMPOTENCY_KEY_REUSED;
 *  - 5xx originals are NOT cached (record cleared) so the client can retry with the same key.
 *
 * NOT a substitute for domain invariants: four-eyes, state-machine preconditions and the
 * deterministic TigerBeetle transfer ids stay authoritative (§3). This layer only prevents
 * accidental duplicate HTTP calls from doing duplicate work.
 */
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String KEY_HEADER = "Idempotency-Key";

    private final IdempotencyRecordRepository repository;

    public IdempotencyFilter(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!MUTATING.contains(request.getMethod()) || !request.getRequestURI().startsWith("/v1/")) {
            chain.doFilter(request, response);
            return;
        }

        try {
            handle(request, response, chain);
        } catch (Exception e) {
            if (e instanceof ServletException s) throw s;
            if (e instanceof IOException io) throw io;
            log.error("idempotency_filter_failure path={}", request.getRequestURI(), e);
            writeJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "{\"code\":\"IDEMPOTENCY_FILTER_ERROR\",\"detail\":\"internal error\"}");
        }
    }

    private void handle(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Actor actor = RequestCorrelation.currentActor();
        if (actor == null) {
            log.warn("idempotency_actor_missing path={} remote={}", request.getRequestURI(), request.getRemoteAddr());
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST,
                "{\"code\":\"ACTOR_MISSING\",\"detail\":\"Mutating requests require authenticated identity headers\"}");
            return;
        }

        // Keys are scoped per actor (§2e): a person's own key space, a machine client's own key
        // space, and a plugin-on-behalf-of-person space — so a plugin can never replay or collide
        // with a key the person used directly, and machine calls (no person) are still recorded.
        UUID scope = scopeOf(actor);

        String key = request.getHeader(KEY_HEADER);
        if (key == null || key.isBlank() || key.length() > 128 || !key.chars().allMatch(c ->
                Character.isLetterOrDigit(c) || c == '-' || c == '.' || c == '_')) {
            log.warn("idempotency_key_missing path={} actor={}", request.getRequestURI(), scope);
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST,
                "{\"code\":\"IDEMPOTENCY_KEY_REQUIRED\","
                    + "\"detail\":\"Mutating requests require header Idempotency-Key (1-128 chars of A-Za-z0-9._-)}\"}");
            return;
        }

        // Reading the body drains the servlet stream: from here on ONLY the cached wrapper may travel
        // down the chain, otherwise the controller sees an empty body (400 "body missing").
        CachedBodyRequest cachedRequest = new CachedBodyRequest(request);
        String body = cachedRequest.bodyAsString();
        String hash = HashUtil.sha256Hex(request.getMethod() + "|" + request.getRequestURI() + "|" + body);

        IdempotencyRecord existing = repository.findByUserIdAndRequestKey(scope, key).orElse(null);
        if (existing != null) {
            if (IdempotencyRecord.State.PENDING.equals(stateOf(existing))) {
                log.warn("idempotency_in_progress userId={} key={} path={}",
                    scope, key, request.getRequestURI());
                response.setHeader("Retry-After", "1");
                writeJson(response, HttpServletResponse.SC_CONFLICT,
                    "{\"code\":\"IDEMPOTENCY_IN_PROGRESS\",\"detail\":\"A request with this Idempotency-Key is still executing\"}");
                return;
            }
            if (!existing.requestHash.equals(hash)) {
                log.warn("idempotency_key_reused userId={} key={} originalPath={} thisPath={}",
                    scope, key, existing.path, request.getRequestURI());
                writeJson(response, HttpServletResponse.SC_CONFLICT,
                    "{\"code\":\"IDEMPOTENCY_KEY_REUSED\",\"detail\":\"Idempotency-Key was used with a different request payload\"}");
                return;
            }
            log.info("idempotency_replayed userId={} key={} path={} storedStatus={}",
                scope, key, request.getRequestURI(), existing.statusCode);
            response.setHeader("Idempotency-Replayed", "true");
            response.setStatus(existing.statusCode);
            response.setContentType(existing.contentType == null ? "application/json" : existing.contentType);
            response.getOutputStream().write(existing.responseBody == null ? new byte[0] : existing.responseBody);
            return;
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.id = UUID.randomUUID();
        record.userId = scope;
        record.requestKey = key;
        record.method = request.getMethod();
        record.path = request.getRequestURI();
        record.requestHash = hash;
        record.state = IdempotencyRecord.State.PENDING;
        try {
            repository.save(record);
        } catch (Exception e) {
            // lost a race with a concurrent same-key request inserting PENDING — treat as in-progress
            log.warn("idempotency_insert_race userId={} key={} detail={}", scope, key, e.getMessage());
            response.setHeader("Retry-After", "1");
            writeJson(response, HttpServletResponse.SC_CONFLICT,
                "{\"code\":\"IDEMPOTENCY_IN_PROGRESS\",\"detail\":\"A concurrent request with this Idempotency-Key is executing\"}");
            return;
        }

        ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedRequest, cached);
        } catch (Exception e) {
            repository.delete(record); // 5xx-class failures must stay retryable with the same key
            log.warn("idempotency_request_failed_uncached userId={} key={} path={} detail={}",
                scope, key, record.path, e.getMessage());
            throw e;
        }

        int status = cached.getStatus();
        if (status >= 500) {
            repository.delete(record);
            log.warn("idempotency_response_uncached userId={} key={} status={} path={}",
                scope, key, status, record.path);
        } else {
            record.state = IdempotencyRecord.State.DONE;
            record.statusCode = status;
            record.contentType = cached.getContentType();
            record.responseBody = cached.getContentAsByteArray();
            record.completedAt = java.time.Instant.now();
            repository.save(record);
            log.info("idempotency_recorded userId={} key={} status={} path={}",
                scope, key, status, record.path);
        }
        cached.copyBodyToResponse();
    }

    private static String stateOf(IdempotencyRecord r) {
        return r.state == null ? IdempotencyRecord.State.PENDING : r.state;
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        String corr = RequestCorrelation.currentCorrelation();
        if (corr != null) response.setHeader("X-Correlation-Id", corr.split(":", 2)[0]);
        response.getWriter().write(body);
    }

    static UUID scopeOf(Actor actor) {
        String name = switch (actor.type()) {
            case PERSON -> null;
            case MACHINE -> "client:" + actor.clientId();
            case DELEGATED -> "delegated:" + actor.clientId() + ":" + actor.personId();
            // acting keys never collide with the same person's own (non-acting) keys
            case ACTING -> "acting:" + actor.personId() + ":" + actor.activeTenantId();
        };
        if (name == null) return actor.personId();
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
