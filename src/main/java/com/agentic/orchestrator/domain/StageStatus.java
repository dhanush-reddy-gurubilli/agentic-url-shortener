package com.agentic.orchestrator.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of one stage. READY is not stored: a PENDING stage is ready when its dependencies are satisfied
 * and its entry gates pass.
 */
public enum StageStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, AWAITING_APPROVAL, AWAITING_CLARIFICATION, SKIPPED, INVALIDATED;

    private static final Map<StageStatus, Set<StageStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(RUNNING, SKIPPED),
            // RUNNING -> PENDING covers crash recovery, safe-stop between attempts and rejected approvals
            RUNNING, EnumSet.of(SUCCEEDED, FAILED, AWAITING_APPROVAL, AWAITING_CLARIFICATION, PENDING, INVALIDATED),
            // FAILED: rejected too many times, so the stage gives up instead of being redone forever
            AWAITING_APPROVAL, EnumSet.of(SUCCEEDED, PENDING, INVALIDATED, FAILED),
            AWAITING_CLARIFICATION, EnumSet.of(PENDING, INVALIDATED),
            SUCCEEDED, EnumSet.of(INVALIDATED),
            FAILED, EnumSet.of(PENDING, INVALIDATED),
            SKIPPED, EnumSet.of(INVALIDATED, PENDING),
            INVALIDATED, EnumSet.of(PENDING, SKIPPED));

    public boolean canTransitionTo(StageStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** True when downstream stages may consume this stage. */
    public boolean satisfiesDependents() {
        return this == SUCCEEDED || this == SKIPPED;
    }

    public boolean isAwaitingHuman() {
        return this == AWAITING_APPROVAL || this == AWAITING_CLARIFICATION;
    }
}
