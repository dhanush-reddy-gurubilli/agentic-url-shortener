package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.approval.ApprovalService;
import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.Clarification;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.scenario.ScenarioCatalog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What humans and the API can do to a run. Every decision that unblocks work wakes the scheduler; every decision is
 * recorded with its actor in the audit trail and the decision log.
 */
@Service
public class RunService {

    private static final int MAX_REQUIREMENT_CHARS = 10_000;

    private final WorkflowEngine engine;
    private final OrchestratorStore store;
    private final ApprovalService approvals;
    private final ReplanService replan;
    private final RunControl control;
    private final RunLocks locks;
    private final AuditService audit;
    private final ScenarioCatalog scenarios;

    public RunService(WorkflowEngine engine, OrchestratorStore store, ApprovalService approvals, ReplanService replan,
                      RunControl control, RunLocks locks, AuditService audit, ScenarioCatalog scenarios) {
        this.engine = engine;
        this.store = store;
        this.approvals = approvals;
        this.replan = replan;
        this.control = control;
        this.locks = locks;
        this.audit = audit;
        this.scenarios = scenarios;
    }

    public Run start(String requirement, String scenario, Long tokenBudget) {
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("requirement is required");
        }
        if (requirement.length() > MAX_REQUIREMENT_CHARS) {
            throw new IllegalArgumentException("requirement must be at most " + MAX_REQUIREMENT_CHARS + " characters");
        }
        if (tokenBudget != null && tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
        if (scenario != null && scenarios.find(scenario).isEmpty()) {
            throw new IllegalArgumentException("Unknown scenario '" + scenario + "'");
        }
        return engine.start(requirement.strip(), scenario, tokenBudget);
    }

    public Run stop(UUID runId, String actor, String reason) {
        Run run = store.getRun(runId);
        if (run.status().isTerminal() || run.status() == RunStatus.CREATED) {
            throw new IllegalStateException("Run is " + run.status() + " and cannot be stopped");
        }
        control.requestStop(runId, actor, reason == null || reason.isBlank() ? "stop requested" : reason);
        engine.pokeStop(runId);
        return store.getRun(runId);
    }

    public Run resume(UUID runId, String actor, Long tokenBudget) {
        return engine.resume(runId, actor, tokenBudget);
    }

    public Approval approve(long approvalId, String actor, String comment) {
        Approval decided = approvals.approve(approvalId, actor, comment);
        engine.wake(decided.runId(), actor);
        return decided;
    }

    public Approval reject(long approvalId, String actor, String comment) {
        Approval decided = approvals.reject(approvalId, actor, comment);
        engine.wake(decided.runId(), actor);
        return decided;
    }

    /**
     * @param answers            clarification id to answer
     * @param acceptAssumptions  accept the agent's proposed assumption for every question left unanswered
     */
    public void answerClarifications(UUID runId, String actor, Map<Long, String> answers, boolean acceptAssumptions) {
        try (var ignored = locks.run(runId)) {
            Stage requirements = store.listStages(runId).stream()
                    .filter(s -> s.status() == StageStatus.AWAITING_CLARIFICATION).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No stage of this run is awaiting clarification"));
            List<Clarification> open = store.listClarifications(runId).stream().filter(c -> c.answer() == null).toList();
            for (Clarification c : open) {
                String answer = answers.get(c.id());
                if (answer != null && !answer.isBlank()) {
                    store.answerClarification(c.id(), answer.strip(), actor);
                } else if (acceptAssumptions && c.proposedAssumption() != null) {
                    store.answerClarification(c.id(), c.proposedAssumption(), actor + " (accepted proposed assumption)");
                } else {
                    throw new IllegalArgumentException("Question " + c.id() + " needs an answer, or set acceptAssumptions");
                }
            }
            audit.record(runId, requirements.stageKey(), actor, "CLARIFICATION_ANSWERED", open.size() + " question(s) answered");
            store.insertDecision(runId, requirements.stageKey(), actor, "CLARIFIED",
                    "Answered " + open.size() + " clarification question(s)"
                            + (acceptAssumptions ? " (accepting proposed assumptions where not answered)" : ""), null, null);
            replan.reopen(runId, requirements.stageKey(), "human answered clarification questions", null);
        }
        engine.wake(runId, actor);
    }

    /** Re-opens a stage and everything downstream of it, then resumes. */
    public void rollback(UUID runId, String stageKey, String actor, String reason) {
        requireInactive(runId);
        store.getStage(runId, stageKey);
        replan.rollbackTo(runId, stageKey, reason == null || reason.isBlank() ? "manual rollback" : reason);
        audit.record(runId, stageKey, actor, "ROLLBACK_REQUESTED", reason);
        engine.resume(runId, actor, null);
    }

    /** A human asks for a stage to be redone with feedback; downstream stages follow only if their inputs change. */
    public void revise(UUID runId, String stageKey, String actor, String feedback) {
        if (feedback == null || feedback.isBlank()) {
            throw new IllegalArgumentException("feedback is required");
        }
        store.getStage(runId, stageKey);
        replan.reopen(runId, stageKey, "revision requested by " + actor, "A human reviewer asked for changes: " + feedback);
        store.insertDecision(runId, stageKey, actor, "REVISION_REQUESTED", "Revision requested for " + stageKey, feedback, null);
        resumeOrWake(runId, actor);
    }

    /** Edits the requirement text mid-run; the requirements stage is redone and downstream follows by input hash. */
    public void updateRequirement(UUID runId, String actor, String requirement) {
        if (requirement == null || requirement.isBlank() || requirement.length() > MAX_REQUIREMENT_CHARS) {
            throw new IllegalArgumentException("requirement is required (at most " + MAX_REQUIREMENT_CHARS + " characters)");
        }
        store.setRequirement(runId, requirement.strip());
        store.insertDecision(runId, "requirements", actor, "REQUIREMENT_EDITED", "Requirement text edited", requirement, null);
        replan.reopen(runId, "requirements", "requirement edited by " + actor,
                "The requirement text was edited by a human. Re-derive the specification from the new text.");
        resumeOrWake(runId, actor);
    }

    private void resumeOrWake(UUID runId, String actor) {
        Run run = store.getRun(runId);
        if (run.status() == RunStatus.STOPPED || run.status() == RunStatus.FAILED) {
            engine.resume(runId, actor, null);
        } else if (!run.status().isTerminal()) {
            engine.wake(runId, actor);
        }
    }

    private void requireInactive(UUID runId) {
        Run run = store.getRun(runId);
        if (run.status() == RunStatus.RUNNING || run.status() == RunStatus.COMPLETED) {
            throw new IllegalStateException("Run is " + run.status() + "; stop it before rolling back");
        }
    }
}
