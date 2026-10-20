package com.agentic.orchestrator.approval;

import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.ApprovalStatus;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.engine.RunLocks;
import com.agentic.orchestrator.engine.StageRunner;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Human approval checkpoints. Approving applies the parked output; rejecting turns the comment into a constraint and
 * re-opens the stage. A comment is mandatory either way, and every decision is audited as a human decision.
 */
@Service
public class ApprovalService {

    private final OrchestratorStore store;
    private final AuditService audit;
    private final StageRunner stageRunner;
    private final RunLocks locks;

    public ApprovalService(OrchestratorStore store, AuditService audit, StageRunner stageRunner, RunLocks locks) {
        this.store = store;
        this.audit = audit;
        this.stageRunner = stageRunner;
        this.locks = locks;
    }

    public List<Approval> pending() {
        return store.listApprovals(ApprovalStatus.PENDING);
    }

    public Approval approve(long id, String actor, String comment) {
        requireComment(comment);
        Approval approval = store.getApproval(id);
        try (var ignored = locks.run(approval.runId())) {
            Stage stage = requireAwaiting(approval);
            store.decideApproval(id, ApprovalStatus.APPROVED, actor, comment);
            audit.record(approval.runId(), approval.stageKey(), actor, "APPROVAL_DECIDED", "approved " + id + ": " + comment);
            store.insertDecision(approval.runId(), approval.stageKey(), actor, "APPROVED",
                    "Approved " + approval.stageKey() + " (" + approval.reasons() + ")", comment, stage.inputHashes());
            stageRunner.acceptApproved(approval.runId(), approval.stageKey());
        }
        return store.getApproval(id);
    }

    public Approval reject(long id, String actor, String comment) {
        requireComment(comment);
        Approval approval = store.getApproval(id);
        try (var ignored = locks.run(approval.runId())) {
            Stage stage = requireAwaiting(approval);
            store.decideApproval(id, ApprovalStatus.REJECTED, actor, comment);
            audit.record(approval.runId(), approval.stageKey(), actor, "APPROVAL_DECIDED", "rejected " + id + ": " + comment);
            store.insertDecision(approval.runId(), approval.stageKey(), actor, "REJECTED",
                    "Rejected " + approval.stageKey() + " (" + approval.reasons() + ")", comment, stage.inputHashes());
            long rejections = store.listApprovals(approval.runId()).stream()
                    .filter(a -> a.stageKey().equals(approval.stageKey()) && a.status() == ApprovalStatus.REJECTED).count();
            stageRunner.rejectParked(approval.runId(), approval.stageKey(), comment, rejections);
        }
        return store.getApproval(id);
    }

    private Stage requireAwaiting(Approval approval) {
        if (approval.status() != ApprovalStatus.PENDING) {
            throw new IllegalStateException("Approval " + approval.id() + " was already " + approval.status());
        }
        Stage stage = store.getStage(approval.runId(), approval.stageKey());
        if (stage.status() != StageStatus.AWAITING_APPROVAL) {
            store.decideApproval(approval.id(), ApprovalStatus.SUPERSEDED, "orchestrator", "stage is no longer waiting");
            throw new IllegalStateException("Approval " + approval.id() + " is stale: stage " + approval.stageKey()
                    + " is " + stage.status());
        }
        return stage;
    }

    private static void requireComment(String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("A comment is required for every approval decision");
        }
    }
}
