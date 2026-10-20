package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts.Task;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.context.ArtifactResolver;
import com.agentic.orchestrator.domain.Records.Clarification;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.tool.Workspace;

import java.util.List;
import java.util.Optional;

/**
 * Everything an agent may read: its stage and attempt, feedback from the previous attempt, the human's
 * clarifications, the workspace, and the accepted outputs of upstream stages (cross-stage context).
 *
 * @param attempt how many times this stage has been started in this run (1-based, counted across re-openings)
 */
public record StageContext(Run run, Stage stage, int attempt, String feedback, Task task,
                           List<Clarification> clarifications, Workspace workspace, ArtifactResolver artifacts,
                           Json json) {

    public <T> Optional<T> output(String stageKey, Class<T> type) {
        return artifacts.read(run.id(), stageKey, type);
    }

    public <T> T requireOutput(String stageKey, Class<T> type) {
        return output(stageKey, type).orElseThrow(() ->
                new IllegalStateException("Upstream stage " + stageKey + " has no accepted output"));
    }
}
