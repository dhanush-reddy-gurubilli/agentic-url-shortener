package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.artifact.Artifacts.ChangeType;
import com.agentic.orchestrator.graph.Dag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The SDLC as an explicit dependency graph: stages, what each depends on, its entry and exit gates, retry policy,
 * fallback and autonomy level. Loaded from {@code workflows/sdlc.yaml} and validated (unique keys, known
 * dependencies, no cycles) before any run starts.
 */
public record WorkflowTemplate(String name, List<StageTemplate> stages, List<DynamicStageTemplate> dynamicStages) {

    public WorkflowTemplate {
        dynamicStages = dynamicStages == null ? List.of() : dynamicStages;
    }

    /** How much human involvement a stage's output needs. */
    public enum Autonomy {
        /** Accepted automatically once its gates pass. */
        AUTONOMOUS,
        /** Accepted automatically unless the high-impact classifier flags it. */
        APPROVE_ON_HIGH_IMPACT,
        /** Always needs a human decision. */
        ALWAYS_APPROVE
    }

    /** What happens when a stage's exit gates fail after its retries (or immediately, for loop-back stages). */
    public enum FailureAction {
        /** Retry the same stage with feedback, then fall back, then fail. */
        RETRY,
        /** Re-open the stages that produced code, with the failure as feedback (validation loop). */
        REOPEN_CODE_STAGES
    }

    public record RetryPolicy(int maxAttempts, long backoffMillis) {
        public RetryPolicy {
            maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
            backoffMillis = backoffMillis < 0 ? 200 : backoffMillis;
        }
    }

    public record StageTemplate(String key, String agent, List<String> dependsOn, ChangeType onlyForChangeType,
                                List<String> exitGates, RetryPolicy retry, String fallbackAgent, Autonomy autonomy,
                                FailureAction failureAction) {
        public StageTemplate {
            dependsOn = dependsOn == null ? List.of() : dependsOn;
            exitGates = exitGates == null ? List.of() : exitGates;
            retry = retry == null ? new RetryPolicy(3, 200) : retry;
            autonomy = autonomy == null ? Autonomy.AUTONOMOUS : autonomy;
            failureAction = failureAction == null ? FailureAction.RETRY : failureAction;
        }
    }

    /**
     * A group of stages created at run time from another stage's output (the planner's task graph becomes one
     * implementation stage per task). {@code joinedBy} stages automatically wait for every stage of the group.
     */
    public record DynamicStageTemplate(String name, String keyPrefix, String agent, String expandFrom,
                                       List<String> joinedBy, List<String> exitGates, RetryPolicy retry,
                                       String fallbackAgent, Autonomy autonomy) {
        public DynamicStageTemplate {
            joinedBy = joinedBy == null ? List.of() : joinedBy;
            exitGates = exitGates == null ? List.of() : exitGates;
            retry = retry == null ? new RetryPolicy(3, 200) : retry;
            autonomy = autonomy == null ? Autonomy.AUTONOMOUS : autonomy;
        }
    }

    public Optional<StageTemplate> stage(String key) {
        return stages.stream().filter(s -> s.key().equals(key)).findFirst();
    }

    public Optional<DynamicStageTemplate> dynamicFor(String stageKey) {
        return dynamicStages.stream().filter(d -> stageKey.startsWith(d.keyPrefix())).findFirst();
    }

    public Optional<DynamicStageTemplate> expandedFrom(String stageKey) {
        return dynamicStages.stream().filter(d -> d.expandFrom().equals(stageKey)).findFirst();
    }

    /** Effective stage settings for a stage key, whether static or dynamic. */
    public StageTemplate resolve(String stageKey) {
        Optional<StageTemplate> fixed = stage(stageKey);
        if (fixed.isPresent()) {
            return fixed.get();
        }
        DynamicStageTemplate dyn = dynamicFor(stageKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown stage " + stageKey));
        return new StageTemplate(stageKey, dyn.agent(), List.of(dyn.expandFrom()), null, dyn.exitGates(), dyn.retry(),
                dyn.fallbackAgent(), dyn.autonomy(), FailureAction.RETRY);
    }

    /** Throws {@link IllegalStateException} if the graph is malformed. */
    public WorkflowTemplate validate() {
        Set<String> keys = new LinkedHashSet<>();
        for (StageTemplate s : stages) {
            if (!keys.add(s.key())) {
                throw new IllegalStateException("Duplicate stage key " + s.key());
            }
        }
        for (StageTemplate s : stages) {
            for (String dep : s.dependsOn()) {
                if (!keys.contains(dep)) {
                    throw new IllegalStateException("Stage " + s.key() + " depends on unknown stage " + dep);
                }
            }
        }
        for (DynamicStageTemplate d : dynamicStages) {
            if (!keys.contains(d.expandFrom())) {
                throw new IllegalStateException("Dynamic stages " + d.name() + " expand from unknown stage " + d.expandFrom());
            }
            for (String joiner : d.joinedBy()) {
                if (!keys.contains(joiner)) {
                    throw new IllegalStateException("Dynamic stages " + d.name() + " are joined by unknown stage " + joiner);
                }
            }
        }
        Dag.assertAcyclic(effectiveEdges());
        return this;
    }

    /** Dependencies including the implicit edges from dynamic groups (expandFrom -> group -> joinedBy). */
    private Map<String, List<String>> effectiveEdges() {
        Map<String, List<String>> deps = new HashMap<>();
        for (StageTemplate s : stages) {
            deps.put(s.key(), new ArrayList<>(s.dependsOn()));
        }
        for (DynamicStageTemplate d : dynamicStages) {
            String node = "<" + d.name() + ">";
            deps.put(node, new ArrayList<>(List.of(d.expandFrom())));
            for (String joiner : d.joinedBy()) {
                deps.get(joiner).add(node);
            }
        }
        return deps;
    }

}
