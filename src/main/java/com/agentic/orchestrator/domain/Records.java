package com.agentic.orchestrator.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistent records of the orchestrator (tables in V2__orchestrator.sql). */
public final class Records {

    private Records() {
    }

    public record Run(UUID id, String scenario, String requirement, RunStatus status, long tokenBudget, long tokensUsed,
                      int policyViolations, boolean stopRequested, String statusReason, String workspacePath,
                      String baselineSha, Instant createdAt, Instant updatedAt, Instant completedAt) {
    }

    public record Stage(long id, UUID runId, String stageKey, String agent, StageStatus status, List<String> dependsOn,
                        boolean dynamicStage, String taskJson, int generation, int attempt, String inputHashes,
                        Long outputArtifactId, Long pendingArtifactId, String feedback, String error,
                        String checkpointSha, Integer applyOrder, Instant startedAt, Instant finishedAt) {
    }

    public record Artifact(long id, UUID runId, String stageKey, String type, int version, String content,
                           String sha256, String createdBy, Instant createdAt) {
    }

    public record Decision(long id, UUID runId, String stageKey, String actor, String type, String summary,
                           String rationale, String inputHashes, Instant createdAt) {
    }

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED, SUPERSEDED }

    public record Approval(long id, UUID runId, String stageKey, String reasons, String details,
                           ApprovalStatus status, String decidedBy, String comment, Instant createdAt,
                           Instant decidedAt) {
    }

    public record Clarification(long id, UUID runId, String question, String proposedAssumption, String answer,
                                String answeredBy, Instant createdAt, Instant answeredAt) {
    }

    public record AuditEvent(long id, UUID runId, String stageKey, String actor, String eventType, String payload,
                             Instant createdAt, String prevHash, String hash) {
    }
}
