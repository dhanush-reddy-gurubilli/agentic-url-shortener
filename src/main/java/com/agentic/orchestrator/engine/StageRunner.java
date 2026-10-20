package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.agent.Agent.AgentResult;
import com.agentic.orchestrator.agent.AgentRegistry;
import com.agentic.orchestrator.agent.StageContext;
import com.agentic.orchestrator.approval.HighImpactClassifier;
import com.agentic.orchestrator.approval.ImpactReason;
import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.Ambiguity;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.Patch;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Artifacts.Task;
import com.agentic.orchestrator.artifact.Artifacts.TaskGraph;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.context.ArtifactResolver;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.Artifact;
import com.agentic.orchestrator.domain.Records.Clarification;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.StageEdit;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.engine.WorkflowTemplate.Autonomy;
import com.agentic.orchestrator.engine.WorkflowTemplate.FailureAction;
import com.agentic.orchestrator.engine.WorkflowTemplate.StageTemplate;
import com.agentic.orchestrator.gate.Gate;
import com.agentic.orchestrator.gate.Gate.GateContext;
import com.agentic.orchestrator.gate.Gate.GateResult;
import com.agentic.orchestrator.gate.GateRegistry;
import com.agentic.orchestrator.llm.LlmException;
import com.agentic.orchestrator.policy.ProposedChange;
import com.agentic.orchestrator.policy.Violation;
import com.agentic.orchestrator.tool.ChangeFailed;
import com.agentic.orchestrator.tool.SandboxViolation;
import com.agentic.orchestrator.tool.Workspace;
import com.agentic.orchestrator.tool.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Executes one stage to completion or to a human checkpoint: agent call, exit gates, approval, then applying and
 * committing the output. Failures are retried with the gate feedback (bounded), then handed to the fallback agent,
 * then the stage fails. All state changes are persisted and audited as they happen.
 */
@Service
public class StageRunner {

    private static final Logger log = LoggerFactory.getLogger(StageRunner.class);
    private static final String SYSTEM = "orchestrator";
    private static final int MAX_REJECTIONS = 3;
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    private final OrchestratorStore store;
    private final OrchestratorProperties props;
    private final WorkflowTemplateLoader templates;
    private final AgentRegistry agents;
    private final GateRegistry gates;
    private final HighImpactClassifier classifier;
    private final ArtifactResolver artifacts;
    private final Json json;
    private final AuditService audit;
    private final RunLocks locks;
    private final WorkspaceManager workspaces;
    private final ReplanService replan;
    private final RunControl control;
    private final int maxConsecutiveLlmFailures;
    private final ConcurrentHashMap<UUID, AtomicInteger> llmFailureStreak = new ConcurrentHashMap<>();

    public StageRunner(OrchestratorStore store, OrchestratorProperties props, WorkflowTemplateLoader templates,
                       AgentRegistry agents, GateRegistry gates, HighImpactClassifier classifier,
                       ArtifactResolver artifacts, Json json, AuditService audit, RunLocks locks,
                       WorkspaceManager workspaces, ReplanService replan, RunControl control,
                       @Value("${orchestrator.max-consecutive-llm-failures:5}") int maxConsecutiveLlmFailures) {
        this.maxConsecutiveLlmFailures = maxConsecutiveLlmFailures;
        this.store = store;
        this.props = props;
        this.templates = templates;
        this.agents = agents;
        this.gates = gates;
        this.classifier = classifier;
        this.artifacts = artifacts;
        this.json = json;
        this.audit = audit;
        this.locks = locks;
        this.workspaces = workspaces;
        this.replan = replan;
        this.control = control;
    }

    /** How one attempt ended. */
    private enum Kind { DONE, PARKED, STALE, FAILED }

    private record Outcome(Kind kind, List<String> messages, boolean fromGate, boolean llmFailure) {
        static final Outcome DONE = new Outcome(Kind.DONE, List.of(), false, false);
        static final Outcome PARKED = new Outcome(Kind.PARKED, List.of(), false, false);
        static final Outcome STALE = new Outcome(Kind.STALE, List.of(), false, false);

        static Outcome failed(List<String> messages, boolean fromGate, boolean llmFailure) {
            return new Outcome(Kind.FAILED, messages, fromGate, llmFailure);
        }
    }

    // ---- entry point ---------------------------------------------------------------------------------------------

    /** Runs a stage the scheduler has already moved to RUNNING. */
    public void run(UUID runId, String stageKey) {
        long generation = store.getStage(runId, stageKey).generation();
        try {
            execute(runId, stageKey, generation);
        } catch (RuntimeException e) {
            log.error("Unexpected error in stage {} of run {}", stageKey, runId, e);
            failStage(runId, stageKey, generation, "Unexpected error: " + e);
        }
    }

    private void execute(UUID runId, String stageKey, long generation) {
        StageTemplate tpl = templates.template().resolve(stageKey);
        while (true) {
            Run run = store.getRun(runId);
            Stage stage = store.getStage(runId, stageKey);
            if (stage.generation() != generation || stage.status() != StageStatus.RUNNING) {
                return;
            }
            if (run.stopRequested()) {
                returnToPending(stage, "safe-stop requested");
                return;
            }
            int attempt = stage.attempt() + 1;
            // How many times this stage has been started in this run, across re-openings. Recorded and scripted model
            // responses are keyed by it, so a stage redone after a re-plan gets a fresh response, not the old one.
            int sequence = (int) audit.count(runId, stageKey, "STAGE_STARTED") + 1;
            Map<String, Stage> byKey = store.listStages(runId).stream().collect(Collectors.toMap(Stage::stageKey, s -> s));
            stage = save(StageEdit.of(stage).attempt(attempt)
                    .inputHashes(json.write(replan.inputHashes(stage, byKey)))
                    .startedAt(stage.startedAt() == null ? store.now() : stage.startedAt()).build());
            audit.record(runId, stageKey, stage.agent(), "STAGE_STARTED", "attempt " + attempt + " (start #" + sequence + ")");

            Outcome outcome;
            try {
                StageContext ctx = context(run, stage, sequence);
                AgentResult result = agents.get(stage.agent()).execute(ctx);
                llmFailureStreak.computeIfAbsent(runId, k -> new AtomicInteger()).set(0);
                outcome = process(run, stage, tpl, result);
            } catch (SandboxViolation e) {
                int violations = store.addPolicyViolations(runId, 1);
                audit.record(runId, stageKey, SYSTEM, "POLICY_VIOLATION", "[sandbox] " + e.getMessage());
                if (violations >= props.maxPolicyViolations()) {
                    control.requestStop(runId, SYSTEM, violations + " policy violations (limit " + props.maxPolicyViolations() + ")");
                }
                outcome = Outcome.failed(List.of(e.getMessage()), true, false);
            } catch (ChangeFailed e) {
                outcome = Outcome.failed(List.of(e.getMessage()), true, false);
            } catch (LlmException e) {
                outcome = Outcome.failed(List.of("Model call failed: " + e.getMessage()), false, true);
            } catch (RuntimeException e) {
                log.warn("Agent {} failed in stage {}: {}", stage.agent(), stageKey, e.toString());
                outcome = Outcome.failed(List.of("Agent error: " + e), false, false);
            }

            if (outcome.kind() != Kind.FAILED) {
                return;
            }
            if (!handleFailure(runId, stageKey, generation, tpl, attempt, outcome)) {
                return;
            }
        }
    }

    /** @return true to run another attempt, false when the stage is finished for now */
    private boolean handleFailure(UUID runId, String stageKey, long generation, StageTemplate tpl, int attempt,
                                  Outcome outcome) {
        String feedback = String.join("\n", outcome.messages());
        audit.record(runId, stageKey, SYSTEM, "STAGE_ATTEMPT_FAILED", "attempt " + attempt + ": " + abbreviate(feedback, 1500));
        if (outcome.llmFailure()) {
            int streak = llmFailureStreak.computeIfAbsent(runId, k -> new AtomicInteger()).incrementAndGet();
            if (streak >= maxConsecutiveLlmFailures) {
                control.requestStop(runId, SYSTEM, streak + " consecutive model failures");
            }
        }
        try (var ignored = locks.run(runId)) {
            Stage stage = store.getStage(runId, stageKey);
            if (stage.generation() != generation || stage.status() != StageStatus.RUNNING) {
                return false;
            }
            if (tpl.failureAction() == FailureAction.REOPEN_CODE_STAGES && outcome.fromGate()) {
                reopenCodeStages(stage, tpl, feedback);
                return false;
            }
            if (attempt >= tpl.retry().maxAttempts()) {
                String fallback = tpl.fallbackAgent();
                if (fallback != null && !stage.agent().equals(fallback)) {
                    save(StageEdit.of(stage).agent(fallback).attempt(0).feedback(feedback).build());
                    audit.record(runId, stageKey, SYSTEM, "STAGE_FALLBACK",
                            stage.agent() + " -> " + fallback + " after " + attempt + " failed attempt(s)");
                    store.insertDecision(runId, stageKey, SYSTEM, "FALLBACK",
                            "Switched stage " + stageKey + " from " + stage.agent() + " to " + fallback,
                            abbreviate(feedback, 500), stage.inputHashes());
                    return true;
                }
                failStageLocked(stage, feedback);
                return false;
            }
            save(StageEdit.of(stage).feedback(feedback).build());
            audit.record(runId, stageKey, SYSTEM, "STAGE_RETRY", "retrying with feedback (attempt " + (attempt + 1) + ")");
        }
        sleepBackoff(tpl.retry().backoffMillis(), attempt);
        return true;
    }

    private static void sleepBackoff(long baseMillis, int attempt) {
        if (baseMillis <= 0) {
            return;
        }
        long delay = Math.min(MAX_BACKOFF_MILLIS, baseMillis << Math.min(attempt - 1, 10));
        delay += ThreadLocalRandom.current().nextLong(delay / 4 + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- one attempt ---------------------------------------------------------------------------------------------

    private Outcome process(Run run, Stage stage, StageTemplate tpl, AgentResult result) {
        UUID runId = run.id();
        long total = store.addTokens(runId, result.tokensUsed());
        if (result.tokensUsed() > 0) {
            audit.record(runId, stage.stageKey(), stage.agent(), "TOKEN_USAGE", result.tokensUsed() + " tokens (total " + total + ")");
        }
        if (total > run.tokenBudget()) {
            control.requestStop(runId, SYSTEM, "token budget exceeded (" + total + " of " + run.tokenBudget() + ")");
        }

        Workspace workspace = workspaces.open(runId, run.workspacePath(), run.baselineSha());
        List<ProposedChange> changes = List.of();
        if (Artifacts.PATCH.equals(result.artifactType())) {
            changes = proposedChanges(workspace, result.content());
        }

        GateContext gateCtx = new GateContext(run, stage, result.artifactType(), result.content(), changes, artifacts, json);
        List<String> failures = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();
        String clarification = null;
        for (String name : tpl.exitGates()) {
            GateResult gate = gates.get(name).evaluate(gateCtx);
            violations.addAll(gate.violations());
            if (!gate.passed()) {
                failures.addAll(gate.messages());
                if (name.equals("schema")) {
                    break;
                }
            } else if (gate.needsClarification()) {
                clarification = gate.messages().getFirst();
            }
        }
        if (!violations.isEmpty()) {
            int total2 = store.addPolicyViolations(runId, violations.size());
            violations.forEach(v -> audit.record(runId, stage.stageKey(), SYSTEM, "POLICY_VIOLATION", v.toString()));
            if (total2 >= props.maxPolicyViolations()) {
                control.requestStop(runId, SYSTEM, total2 + " policy violations (limit " + props.maxPolicyViolations() + ")");
            }
        }
        if (!failures.isEmpty()) {
            audit.record(runId, stage.stageKey(), SYSTEM, "GATE_FAILED", abbreviate(String.join(" | ", failures), 1500));
            return Outcome.failed(failures, true, false);
        }
        if (clarification != null) {
            return parkForClarification(stage, result, clarification);
        }

        Set<ImpactReason> reasons = approvalReasons(stage, tpl, result, changes);
        if (!reasons.isEmpty()) {
            return parkForApproval(stage, result, changes, reasons);
        }
        return accept(runId, stage.stageKey(), stage.generation(), result.artifactType(), result.content(),
                stage.agent(), null);
    }

    private List<ProposedChange> proposedChanges(Workspace workspace, String content) {
        Patch patch;
        try {
            patch = json.read(content, Patch.class);
        } catch (RuntimeException e) {
            return List.of();   // not a valid patch: the schema gate reports it
        }
        if (patch.changes() == null) {
            return List.of();
        }
        List<ProposedChange> changes = new ArrayList<>();
        for (var change : patch.changes()) {
            if (change.path() == null || change.op() == null) {
                return List.of();   // malformed: the schema gate reports it
            }
            changes.add(ProposedChange.of(workspace.fs(), change));
        }
        return changes;
    }

    private Set<ImpactReason> approvalReasons(Stage stage, StageTemplate tpl, AgentResult result,
                                              List<ProposedChange> changes) {
        if (tpl.autonomy() == Autonomy.ALWAYS_APPROVE) {
            return EnumSet.of(stage.stageKey().equals("release") ? ImpactReason.RELEASE : ImpactReason.ALWAYS_REVIEW);
        }
        if (tpl.autonomy() == Autonomy.APPROVE_ON_HIGH_IMPACT) {
            if (Artifacts.PATCH.equals(result.artifactType())) {
                return classifier.classify(changes);
            }
            if (Artifacts.DESIGN_DOC.equals(result.artifactType())) {
                return classifier.classify(json.read(result.content(), DesignDoc.class));
            }
        }
        return Set.of();
    }

    // ---- human checkpoints ---------------------------------------------------------------------------------------

    private Outcome parkForApproval(Stage stage, AgentResult result, List<ProposedChange> changes,
                                    Set<ImpactReason> reasons) {
        UUID runId = stage.runId();
        try (var ignored = locks.run(runId)) {
            Stage current = store.getStage(runId, stage.stageKey());
            if (current.generation() != stage.generation() || current.status() != StageStatus.RUNNING) {
                return Outcome.STALE;
            }
            Artifact pending = store.insertArtifact(runId, stage.stageKey(), result.artifactType(), result.content(),
                    Json.sha256(result.content()), stage.agent());
            Approval approval = store.insertApproval(runId, stage.stageKey(),
                    reasons.stream().map(Enum::name).collect(Collectors.joining(",")),
                    approvalDetails(stage, result, changes, reasons));
            save(StageEdit.of(current).status(StageStatus.AWAITING_APPROVAL).pending(pending.id()).build());
            audit.record(runId, stage.stageKey(), SYSTEM, "APPROVAL_REQUESTED",
                    "approval " + approval.id() + ": " + approval.reasons());
            store.insertDecision(runId, stage.stageKey(), SYSTEM, "APPROVAL_REQUIRED",
                    "Human approval needed for " + stage.stageKey() + ": " + approval.reasons(), null, current.inputHashes());
            return Outcome.PARKED;
        }
    }

    private String approvalDetails(Stage stage, AgentResult result, List<ProposedChange> changes, Set<ImpactReason> reasons) {
        StringBuilder sb = new StringBuilder();
        reasons.forEach(r -> sb.append("- ").append(r.name()).append(": this output ").append(r.description()).append('\n'));
        if (Artifacts.PATCH.equals(result.artifactType())) {
            sb.append("\nFiles:\n");
            for (ProposedChange c : changes) {
                sb.append("- ").append(c.path()).append(" (").append(c.change().op()).append(", +")
                        .append(c.addedLines().size()).append("/-").append(c.removedLines().size()).append(")\n");
            }
        } else {
            sb.append("\nOutput:\n").append(abbreviate(result.content(), 3000));
        }
        return sb.toString();
    }

    private Outcome parkForClarification(Stage stage, AgentResult result, String reason) {
        UUID runId = stage.runId();
        try (var ignored = locks.run(runId)) {
            Stage current = store.getStage(runId, stage.stageKey());
            if (current.generation() != stage.generation() || current.status() != StageStatus.RUNNING) {
                return Outcome.STALE;
            }
            RequirementSpec spec = json.read(result.content(), RequirementSpec.class);
            Set<String> asked = store.listClarifications(runId).stream().map(Clarification::question).collect(Collectors.toSet());
            for (Ambiguity a : spec.ambiguities()) {
                if (!asked.contains(a.question())) {
                    store.insertClarification(runId, a.question(), a.proposedAssumption());
                }
            }
            save(StageEdit.of(current).status(StageStatus.AWAITING_CLARIFICATION).build());
            audit.record(runId, stage.stageKey(), SYSTEM, "CLARIFICATION_REQUESTED", reason);
            store.insertDecision(runId, stage.stageKey(), SYSTEM, "CLARIFICATION_REQUIRED", reason, null, current.inputHashes());
            return Outcome.PARKED;
        }
    }

    // ---- accepting output ----------------------------------------------------------------------------------------

    /**
     * Accepts a stage's output: stores the artifact, applies and commits a patch to the workspace (checkpoint), marks
     * the stage SUCCEEDED and triggers downstream re-planning. Discards the result if the stage was re-opened while
     * the agent was working (stale generation).
     */
    private Outcome accept(UUID runId, String stageKey, long generation, String type, String content, String createdBy,
                           Long existingArtifactId) {
        try (var ignored = locks.run(runId)) {
            Stage stage = store.getStage(runId, stageKey);
            boolean live = stage.status() == StageStatus.RUNNING || stage.status() == StageStatus.AWAITING_APPROVAL;
            if (stage.generation() != generation || !live) {
                return Outcome.STALE;
            }
            Run run = store.getRun(runId);
            String checkpoint = null;
            Integer order = null;
            if (Artifacts.PATCH.equals(type)) {
                Patch patch = json.read(content, Patch.class);
                Workspace workspace = workspaces.open(runId, run.workspacePath(), run.baselineSha());
                try (var wsLock = locks.workspace(runId)) {
                    try {
                        patch.changes().forEach(workspace.fs()::apply);
                        checkpoint = workspace.git().commitAll("[" + stageKey + "] " + patch.summary());
                    } catch (ChangeFailed | SandboxViolation e) {
                        workspace.git().resetTo(workspace.git().head());
                        return Outcome.failed(List.of("The patch no longer applies to the current workspace ("
                                + e.getMessage() + "). Regenerate it against the current file contents."), true, false);
                    }
                }
                order = store.nextApplyOrder(runId);
            }
            Artifact artifact = existingArtifactId != null ? store.getArtifact(existingArtifactId)
                    : store.insertArtifact(runId, stageKey, type, content, Json.sha256(content), createdBy);
            Stage done = save(StageEdit.of(stage).status(StageStatus.SUCCEEDED).output(artifact.id()).pending(null)
                    .checkpoint(checkpoint, order).error(null).feedback(null).finishedAt(store.now()).build());
            audit.record(runId, stageKey, SYSTEM, "STAGE_SUCCEEDED", "artifact " + artifact.type() + " v" + artifact.version()
                    + " sha " + artifact.sha256().substring(0, 12) + (checkpoint == null ? "" : ", checkpoint " + checkpoint.substring(0, 8)));
            store.insertDecision(runId, stageKey, createdBy, "OUTPUT_ACCEPTED",
                    stageKey + " produced " + artifact.type() + " v" + artifact.version(), null, done.inputHashes());

            if (Artifacts.TASK_GRAPH.equals(type)) {
                replan.expand(runId, stageKey, json.read(content, TaskGraph.class));
            }
            replan.onStageCompleted(runId, stageKey);
            return Outcome.DONE;
        }
    }

    /** Called when a human approved a parked stage: applies the stored output. */
    public void acceptApproved(UUID runId, String stageKey) {
        Stage stage = store.getStage(runId, stageKey);
        Artifact pending = store.getArtifact(stage.pendingArtifactId());
        Outcome outcome = accept(runId, stageKey, stage.generation(), pending.type(), pending.content(),
                pending.createdBy(), pending.id());
        if (outcome.kind() == Kind.FAILED) {
            try (var ignored = locks.run(runId)) {
                Stage current = store.getStage(runId, stageKey);
                save(StageEdit.of(store.transition(current, StageStatus.PENDING)).attempt(0).pending(null)
                        .feedback(String.join("\n", outcome.messages())).nextGeneration().build());
                audit.record(runId, stageKey, SYSTEM, "STAGE_REOPENED", "approved output no longer applies: " + outcome.messages());
            }
        }
    }

    /** Called when a human rejected a parked stage: the comment becomes a constraint and the stage is redone. */
    public void rejectParked(UUID runId, String stageKey, String comment, long rejectionsSoFar) {
        try (var ignored = locks.run(runId)) {
            Stage stage = store.getStage(runId, stageKey);
            if (rejectionsSoFar >= MAX_REJECTIONS) {
                failStageLocked(stage, "Rejected " + rejectionsSoFar + " times; last comment: " + comment);
                return;
            }
            replan.reopen(runId, stageKey, "rejected by a human reviewer",
                    "A human reviewer rejected the previous output with this comment. Treat it as a requirement and "
                            + "revise accordingly: " + comment);
        }
    }

    // ---- validation loop -----------------------------------------------------------------------------------------

    /**
     * Validation failed: re-open the code and test stages (all of them, or those whose files the failure mentions)
     * with the build output as feedback. Bounded by the stage's max attempts, after which the stage fails.
     */
    private void reopenCodeStages(Stage validation, StageTemplate tpl, String feedback) {
        UUID runId = validation.runId();
        long rounds = audit.forRun(runId).stream()
                .filter(e -> "STAGE_REOPENED_FOR_FIX".equals(e.eventType()) && validation.stageKey().equals(e.stageKey())).count();
        List<Stage> producers = store.listStages(runId).stream()
                .filter(s -> s.applyOrder() != null && (s.agent().equals("CODER") || s.agent().equals("TESTER")))
                .toList();
        if (rounds >= tpl.retry().maxAttempts() - 1 || producers.isEmpty()) {
            failStageLocked(validation, feedback);
            return;
        }
        List<Stage> mentioned = producers.stream().filter(s -> mentionsChangedFile(s, feedback)).toList();
        boolean implementationNamed = mentioned.stream().anyMatch(s -> s.agent().equals("CODER"));
        List<Stage> targets;
        if (mentioned.isEmpty()) {
            targets = producers;                 // cannot tell where the fault is: redo everything that wrote code
        } else if (implementationNamed) {
            targets = mentioned;                 // the failure points at specific code
        } else {
            // Only tests are named. The fault could still be in any implementation stage, so those are re-opened too;
            // otherwise the loop would keep blaming the tests while the faulty code stays untouched.
            targets = producers.stream().filter(s -> mentioned.contains(s) || s.agent().equals("CODER")).toList();
        }
        Map<String, String> feedbackByKey = targets.stream().collect(Collectors.toMap(Stage::stageKey,
                s -> "Validation (build and tests of the whole branch) failed after your change. Fix the cause:\n" + feedback));
        audit.record(runId, validation.stageKey(), SYSTEM, "STAGE_REOPENED_FOR_FIX",
                "validation failed; re-opening " + feedbackByKey.keySet());
        replan.rollbackFrom(runId, feedbackByKey.keySet(), "validation failed: " + abbreviate(feedback, 300), feedbackByKey);
    }

    private boolean mentionsChangedFile(Stage stage, String feedback) {
        if (stage.outputArtifactId() == null) {
            return false;
        }
        try {
            Patch patch = json.read(store.getArtifact(stage.outputArtifactId()).content(), Patch.class);
            return patch.changes().stream().anyMatch(c -> {
                String path = c.path().replace('\\', '/');
                String simple = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.java$", "");
                return feedback.contains(path) || feedback.contains(simple);
            });
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- small helpers -------------------------------------------------------------------------------------------

    private StageContext context(Run run, Stage stage, int attempt) {
        Task task = stage.taskJson() == null ? null : json.read(stage.taskJson(), Task.class);
        return new StageContext(run, stage, attempt, stage.feedback(), task, store.listClarifications(run.id()),
                workspaces.open(run.id(), run.workspacePath(), run.baselineSha()), artifacts, json);
    }

    private Stage save(Stage stage) {
        store.saveStage(stage);
        return stage;
    }

    private void returnToPending(Stage stage, String why) {
        try (var ignored = locks.run(stage.runId())) {
            Stage current = store.getStage(stage.runId(), stage.stageKey());
            if (current.generation() == stage.generation() && current.status() == StageStatus.RUNNING) {
                save(StageEdit.of(store.transition(current, StageStatus.PENDING)).attempt(0).build());
                audit.record(stage.runId(), stage.stageKey(), SYSTEM, "STAGE_INTERRUPTED", why);
            }
        }
    }

    private void failStage(UUID runId, String stageKey, long generation, String message) {
        try (var ignored = locks.run(runId)) {
            Stage stage = store.getStage(runId, stageKey);
            if (stage.generation() == generation && stage.status() == StageStatus.RUNNING) {
                failStageLocked(stage, message);
            }
        }
    }

    private void failStageLocked(Stage stage, String message) {
        Stage failed = store.transition(stage, StageStatus.FAILED);
        save(StageEdit.of(failed).error(abbreviate(message, 4000)).finishedAt(store.now()).build());
        audit.record(stage.runId(), stage.stageKey(), SYSTEM, "STAGE_FAILED", abbreviate(message, 1500));
        store.insertDecision(stage.runId(), stage.stageKey(), SYSTEM, "STAGE_FAILED",
                "Stage " + stage.stageKey() + " failed after " + stage.attempt() + " attempt(s)", abbreviate(message, 1000),
                stage.inputHashes());
    }

    static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
