package com.agentic.orchestrator;

import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.agentic.orchestrator.domain.StageStatus.AWAITING_APPROVAL;
import static com.agentic.orchestrator.domain.StageStatus.AWAITING_CLARIFICATION;
import static com.agentic.orchestrator.domain.StageStatus.FAILED;
import static com.agentic.orchestrator.domain.StageStatus.INVALIDATED;
import static com.agentic.orchestrator.domain.StageStatus.PENDING;
import static com.agentic.orchestrator.domain.StageStatus.RUNNING;
import static com.agentic.orchestrator.domain.StageStatus.SKIPPED;
import static com.agentic.orchestrator.domain.StageStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transition tables are checked pair by pair against an independently written matrix, so a change to either table
 * is a deliberate, visible decision.
 */
class StateMachineTest {

    @Test
    void stageTransitionsMatchTheSpecificationForEveryPair() {
        Map<StageStatus, Set<StageStatus>> allowed = new EnumMap<>(StageStatus.class);
        allowed.put(PENDING, EnumSet.of(RUNNING, SKIPPED));
        allowed.put(RUNNING, EnumSet.of(SUCCEEDED, FAILED, AWAITING_APPROVAL, AWAITING_CLARIFICATION, PENDING, INVALIDATED));
        allowed.put(AWAITING_APPROVAL, EnumSet.of(SUCCEEDED, PENDING, INVALIDATED, FAILED));
        allowed.put(AWAITING_CLARIFICATION, EnumSet.of(PENDING, INVALIDATED));
        allowed.put(SUCCEEDED, EnumSet.of(INVALIDATED));
        allowed.put(FAILED, EnumSet.of(PENDING, INVALIDATED));
        allowed.put(SKIPPED, EnumSet.of(INVALIDATED, PENDING));
        allowed.put(INVALIDATED, EnumSet.of(PENDING, SKIPPED));

        for (StageStatus from : StageStatus.values()) {
            for (StageStatus to : StageStatus.values()) {
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to)
                        .isEqualTo(allowed.get(from).contains(to));
            }
        }
    }

    @Test
    void aStageCannotJumpStraightToSucceededFromPending() {
        assertThat(PENDING.canTransitionTo(SUCCEEDED)).isFalse();
        assertThat(SUCCEEDED.canTransitionTo(RUNNING)).isFalse();
    }

    @Test
    void onlySucceededAndSkippedSatisfyDependents() {
        for (StageStatus s : StageStatus.values()) {
            assertThat(s.satisfiesDependents()).as(s.name()).isEqualTo(s == SUCCEEDED || s == SKIPPED);
        }
    }

    @Test
    void runTransitionsMatchTheSpecificationForEveryPair() {
        Map<RunStatus, Set<RunStatus>> allowed = new EnumMap<>(RunStatus.class);
        allowed.put(RunStatus.CREATED, EnumSet.of(RunStatus.RUNNING, RunStatus.STOPPED));
        allowed.put(RunStatus.RUNNING, EnumSet.of(RunStatus.PAUSED, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.STOPPED));
        allowed.put(RunStatus.PAUSED, EnumSet.of(RunStatus.RUNNING, RunStatus.STOPPED, RunStatus.FAILED));
        allowed.put(RunStatus.STOPPED, EnumSet.of(RunStatus.RUNNING));
        allowed.put(RunStatus.FAILED, EnumSet.of(RunStatus.RUNNING));
        allowed.put(RunStatus.COMPLETED, EnumSet.noneOf(RunStatus.class));

        for (RunStatus from : RunStatus.values()) {
            for (RunStatus to : RunStatus.values()) {
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(allowed.get(from).contains(to));
            }
        }
    }

    @Test
    void aCompletedRunIsFinal() {
        for (RunStatus to : RunStatus.values()) {
            assertThat(RunStatus.COMPLETED.canTransitionTo(to)).isFalse();
        }
    }
}
