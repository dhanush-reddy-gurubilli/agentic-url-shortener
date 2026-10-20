package com.agentic.orchestrator.gate;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import org.springframework.stereotype.Component;

/**
 * Pauses the run for human clarification when the requirement is too ambiguous to design from. Bounded: after
 * {@link #MAX_ROUNDS} rounds the agent's assumptions are accepted and recorded instead of asking again.
 */
@Component
public class AmbiguityGate implements Gate {

    static final int MAX_ROUNDS = 2;

    private final double threshold;

    public AmbiguityGate(OrchestratorProperties properties) {
        this.threshold = properties.ambiguityThreshold();
    }

    @Override
    public String name() {
        return "ambiguity";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        RequirementSpec spec = ctx.json().read(ctx.content(), RequirementSpec.class);
        int round = ctx.stage().generation();
        boolean hasQuestions = spec.ambiguities() != null && !spec.ambiguities().isEmpty();
        if (spec.ambiguityScore() > threshold && hasQuestions && round <= MAX_ROUNDS) {
            return GateResult.clarify("Ambiguity score " + spec.ambiguityScore() + " exceeds " + threshold + "; "
                    + spec.ambiguities().size() + " question(s) need a human answer");
        }
        return GateResult.ok();
    }
}
