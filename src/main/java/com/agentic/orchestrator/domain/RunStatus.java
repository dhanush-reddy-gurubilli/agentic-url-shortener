package com.agentic.orchestrator.domain;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle of a workflow run. */
public enum RunStatus {
    CREATED, RUNNING, PAUSED, COMPLETED, FAILED, STOPPED;

    private static final java.util.Map<RunStatus, Set<RunStatus>> ALLOWED = java.util.Map.of(
            CREATED, EnumSet.of(RUNNING, STOPPED),
            RUNNING, EnumSet.of(PAUSED, COMPLETED, FAILED, STOPPED),
            PAUSED, EnumSet.of(RUNNING, STOPPED, FAILED),
            STOPPED, EnumSet.of(RUNNING),
            FAILED, EnumSet.of(RUNNING),
            COMPLETED, EnumSet.noneOf(RunStatus.class));

    public boolean canTransitionTo(RunStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == STOPPED;
    }
}
