package com.fsp.plugin.inventory.service;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Striped fair critical-section locks, keyed by "domain:id" (AGENTS §2d (plugin copy — plugins do not import core classes)).
 *
 * Purpose: stop non-idempotent dispatch sections from interleaving inside one JVM — e.g. two
 * checkers approving the SAME journal entry concurrently must not both reach TigerBeetle.
 * The DB (@Version + status preconditions) remains the source of truth; these locks only
 * prevent the thundering dispatch race before any DB write lands.
 *
 * Fair=true so approval-critical sections don't starve under contention.
 * withLock always unlocks in finally — callers can never leak a lock.
 */
@Component
public class KeyLocks {

    private final ConcurrentHashMap<String, ReentrantLock> byKey = new ConcurrentHashMap<>();

    /** Run {@code action} holding the exclusive stripe lock for domain+id. */
    public <T> T withLock(String domain, UUID id, java.util.function.Supplier<T> action) {
        ReentrantLock lock = byKey.computeIfAbsent(domain + ":" + id, k -> new ReentrantLock(true));
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Void-returning convenience. */
    public void withLock(String domain, UUID id, Runnable action) {
        withLock(domain, id, () -> {
            action.run();
            return null;
        });
    }
}
