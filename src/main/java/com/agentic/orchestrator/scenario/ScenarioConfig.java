package com.agentic.orchestrator.scenario;

import java.util.List;

/**
 * A scripted scenario: the requirement, injected faults and (for the demo auto-pilot) the human's decisions.
 * Loaded from {@code scenarios/<name>/scenario.yaml}.
 */
public record ScenarioConfig(String name, String type, String description, String requirement, Long tokenBudget,
                             List<Fault> faults, List<HumanAction> humanActions) {

    public ScenarioConfig {
        faults = faults == null ? List.of() : faults;
        humanActions = humanActions == null ? List.of() : humanActions;
    }

    public enum FaultType {
        /** The model call for this stage/attempt times out. */
        TIMEOUT,
        /** The call reports a large token usage, pushing the run over its budget. */
        TOKEN_SURGE
    }

    /** @param attempts attempts the fault applies to; empty means every attempt */
    public record Fault(String stage, FaultType type, List<Integer> attempts, Long tokens) {
        public Fault {
            attempts = attempts == null ? List.of() : attempts;
        }

        public boolean applies(String stageKey, int attempt) {
            return stage.equals(stageKey) && (attempts.isEmpty() || attempts.contains(attempt));
        }
    }

    public enum HumanTrigger { APPROVAL, CLARIFICATION, STOPPED }

    public enum HumanDecision { APPROVE, REJECT, ANSWER_ACCEPTING_ASSUMPTIONS, RAISE_BUDGET }

    /**
     * One scripted decision of the demo auto-pilot.
     *
     * @param stage          stage the trigger concerns (null for STOPPED)
     * @param reasonContains for APPROVAL triggers: only match requests whose reasons contain this text
     * @param times          how many times this action may fire (default 1)
     */
    public record HumanAction(HumanTrigger on, String stage, String reasonContains, HumanDecision decision,
                              String comment, Long tokenBudget, Integer times) {
        public int maxTimes() {
            return times == null ? 1 : times;
        }
    }
}
