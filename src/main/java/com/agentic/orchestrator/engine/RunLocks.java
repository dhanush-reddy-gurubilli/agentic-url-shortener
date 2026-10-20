package com.agentic.orchestrator.engine;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-run locks. The run lock guards state decisions (scheduling, transitions, re-planning); the workspace lock
 * serialises applying and committing patches, because parallel stages share one git worktree. Always take the run
 * lock first, then the workspace lock.
 */
@Component
public class RunLocks {

    private final ConcurrentHashMap<UUID, ReentrantLock> runLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReentrantLock> workspaceLocks = new ConcurrentHashMap<>();

    public Held run(UUID runId) {
        return acquire(runLocks, runId);
    }

    public Held workspace(UUID runId) {
        return acquire(workspaceLocks, runId);
    }

    private static Held acquire(ConcurrentHashMap<UUID, ReentrantLock> locks, UUID runId) {
        ReentrantLock lock = locks.computeIfAbsent(runId, id -> new ReentrantLock());
        lock.lock();
        return lock::unlock;
    }

    /** A held lock; closing releases it (use with try-with-resources). */
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }
}
