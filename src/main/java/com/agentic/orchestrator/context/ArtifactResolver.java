package com.agentic.orchestrator.context;

import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Artifact;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.StageStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Finds the accepted output of a stage: the cross-stage context that agents and gates read. */
@Component
public class ArtifactResolver {

    private final OrchestratorStore store;
    private final Json json;

    public ArtifactResolver(OrchestratorStore store, Json json) {
        this.store = store;
        this.json = json;
    }

    public Optional<Artifact> outputOf(UUID runId, String stageKey) {
        Stage stage = store.getStage(runId, stageKey);
        if (stage.status() != StageStatus.SUCCEEDED || stage.outputArtifactId() == null) {
            return Optional.empty();
        }
        return Optional.of(store.getArtifact(stage.outputArtifactId()));
    }

    public <T> Optional<T> read(UUID runId, String stageKey, Class<T> type) {
        return outputOf(runId, stageKey).map(a -> json.read(a.content(), type));
    }
}
