package com.agentic.orchestrator.domain;

import com.agentic.orchestrator.domain.Records.Stage;

import java.time.Instant;
import java.util.List;

/** Fluent copy-on-write editing of the immutable {@link Stage} record. */
public final class StageEdit {

    private final Stage original;
    private String agent;
    private StageStatus status;
    private List<String> dependsOn;
    private String taskJson;
    private int generation;
    private int attempt;
    private String inputHashes;
    private Long outputArtifactId;
    private Long pendingArtifactId;
    private String feedback;
    private String error;
    private String checkpointSha;
    private Integer applyOrder;
    private Instant startedAt;
    private Instant finishedAt;

    private StageEdit(Stage s) {
        this.original = s;
        this.agent = s.agent();
        this.status = s.status();
        this.dependsOn = s.dependsOn();
        this.taskJson = s.taskJson();
        this.generation = s.generation();
        this.attempt = s.attempt();
        this.inputHashes = s.inputHashes();
        this.outputArtifactId = s.outputArtifactId();
        this.pendingArtifactId = s.pendingArtifactId();
        this.feedback = s.feedback();
        this.error = s.error();
        this.checkpointSha = s.checkpointSha();
        this.applyOrder = s.applyOrder();
        this.startedAt = s.startedAt();
        this.finishedAt = s.finishedAt();
    }

    public static StageEdit of(Stage stage) {
        return new StageEdit(stage);
    }

    public StageEdit agent(String v) {
        agent = v;
        return this;
    }

    public StageEdit status(StageStatus v) {
        status = v;
        return this;
    }

    public StageEdit dependsOn(List<String> v) {
        dependsOn = v;
        return this;
    }

    public StageEdit taskJson(String v) {
        taskJson = v;
        return this;
    }

    public StageEdit nextGeneration() {
        generation++;
        return this;
    }

    public StageEdit attempt(int v) {
        attempt = v;
        return this;
    }

    public StageEdit inputHashes(String v) {
        inputHashes = v;
        return this;
    }

    public StageEdit output(Long v) {
        outputArtifactId = v;
        return this;
    }

    public StageEdit pending(Long v) {
        pendingArtifactId = v;
        return this;
    }

    public StageEdit feedback(String v) {
        feedback = v;
        return this;
    }

    public StageEdit error(String v) {
        error = v;
        return this;
    }

    public StageEdit checkpoint(String sha, Integer order) {
        checkpointSha = sha;
        applyOrder = order;
        return this;
    }

    public StageEdit startedAt(Instant v) {
        startedAt = v;
        return this;
    }

    public StageEdit finishedAt(Instant v) {
        finishedAt = v;
        return this;
    }

    public Stage build() {
        return new Stage(original.id(), original.runId(), original.stageKey(), agent, status, dependsOn,
                original.dynamicStage(), taskJson, generation, attempt, inputHashes, outputArtifactId,
                pendingArtifactId, feedback, error, checkpointSha, applyOrder, startedAt, finishedAt);
    }
}
