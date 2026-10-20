package com.agentic.orchestrator.api;

import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.Clarification;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record StartRunRequest(String requirement, String scenario, Long tokenBudget) {
    }

    public record DecisionRequest(String comment) {
    }

    public record StopRequest(String reason) {
    }

    public record ResumeRequest(Long tokenBudget) {
    }

    public record ReviseRequest(String feedback) {
    }

    public record RequirementRequest(String requirement) {
    }

    public record Answer(long id, String answer) {
    }

    public record ClarificationRequest(List<Answer> answers, boolean acceptAssumptions) {
    }

    public record RunView(UUID id, String scenario, String requirement, RunStatus status, String statusReason,
                          long tokenBudget, long tokensUsed, int policyViolations, boolean stopRequested,
                          String branch, String workspacePath, Instant createdAt, Instant updatedAt,
                          Instant completedAt) {
        static RunView of(Run r) {
            return new RunView(r.id(), r.scenario(), r.requirement(), r.status(), r.statusReason(), r.tokenBudget(),
                    r.tokensUsed(), r.policyViolations(), r.stopRequested(), "agent/" + r.id(), r.workspacePath(),
                    r.createdAt(), r.updatedAt(), r.completedAt());
        }
    }

    public record StageView(String key, String agent, StageStatus status, List<String> dependsOn, boolean dynamic,
                            int generation, int attempt, String feedback, String error, String checkpoint,
                            Integer applyOrder, Long outputArtifactId, Instant startedAt, Instant finishedAt) {
        static StageView of(Stage s) {
            return new StageView(s.stageKey(), s.agent(), s.status(), s.dependsOn(), s.dynamicStage(), s.generation(),
                    s.attempt(), s.feedback(), s.error(), s.checkpointSha(), s.applyOrder(), s.outputArtifactId(),
                    s.startedAt(), s.finishedAt());
        }
    }

    /** The run and its dependency graph with every stage's state: what the dashboard of a reviewer needs. */
    public record RunDetail(RunView run, List<StageView> stages, List<Approval> approvals,
                            List<Clarification> clarifications) {
    }
}
