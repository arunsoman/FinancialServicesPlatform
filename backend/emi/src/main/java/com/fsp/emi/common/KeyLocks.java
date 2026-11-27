package com.fsp.emi.common;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Striped fair critical-section locks, keyed by "domain:id"
 * (platform §2f/plugin copy — services do not import core classes).
 * Only prevents thundering dispatch races inside one JVM; DB @Version + status
 * preconditions remain the source of truth.
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
