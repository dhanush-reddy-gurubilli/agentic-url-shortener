package com.agentic.orchestrator.gate;

import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.context.ArtifactResolver;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.policy.ProposedChange;
import com.agentic.orchestrator.policy.Violation;

import java.util.List;

/** An exit check on a stage's output. Gates are small, deterministic and individually testable. */
public interface Gate {

    String name();

    GateResult evaluate(GateContext context);

    /**
     * @param changes proposed file changes when the output is a Patch, otherwise empty
     * @param content the stage output as JSON
     */
    record GateContext(Run run, Stage stage, String artifactType, String content, List<ProposedChange> changes,
                       ArtifactResolver artifacts, Json json) {
    }

    /**
     * @param needsClarification the output is fine but depends on answers only a human can give; this is not a failure
     */
    record GateResult(boolean passed, boolean needsClarification, List<String> messages, List<Violation> violations) {

        public static GateResult ok() {
            return new GateResult(true, false, List.of(), List.of());
        }

        public static GateResult fail(String... messages) {
            return new GateResult(false, false, List.of(messages), List.of());
        }

        public static GateResult fail(List<String> messages) {
            return new GateResult(false, false, messages, List.of());
        }

        public static GateResult clarify(String message) {
            return new GateResult(true, true, List.of(message), List.of());
        }

        public static GateResult violations(List<Violation> violations) {
            return new GateResult(violations.isEmpty(), false,
                    violations.stream().map(Violation::toString).toList(), violations);
        }
    }
}
