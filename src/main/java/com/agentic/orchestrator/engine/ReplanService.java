package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.artifact.Artifacts.ChangeType;
import com.agentic.orchestrator.artifact.Artifacts.Patch;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Artifacts.Task;
import com.agentic.orchestrator.artifact.Artifacts.TaskGraph;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.StageEdit;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.engine.WorkflowTemplate.DynamicStageTemplate;
import com.agentic.orchestrator.engine.WorkflowTemplate.StageTemplate;
import com.agentic.orchestrator.tool.ChangeFailed;
import com.agentic.orchestrator.tool.SandboxViolation;
import com.agentic.orchestrator.tool.Workspace;
import com.agentic.orchestrator.tool.WorkspaceManager;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Dynamic re-planning. When an upstream output changes, only the stages whose recorded input hashes no longer match
 * are re-opened (minimal recompute); a stage that already committed code takes every later commit with it, because
 * those commits are discarded when the workspace is rolled back to the checkpoint before it.
 */
@Service
public class ReplanService {

    private static final String SYSTEM = "orchestrator";

    private final OrchestratorStore store;
    private final AuditService audit;
    private final RunLocks locks;
    private final WorkspaceManager workspaces;
    private final WorkflowTemplateLoader templates;
    private final Json json;

    public ReplanService(OrchestratorStore store, AuditService audit, RunLocks locks, WorkspaceManager workspaces,
                         WorkflowTemplateLoader templates, Json json) {
        this.store = store;
        this.audit = audit;
        this.locks = locks;
        this.workspaces = workspaces;
        this.templates = templates;
        this.json = json;
    }

    // ---- input hashes (the basis of "did my inputs change?") -------------------------------------------------

    /**
     * Hash of each dependency's accepted output. A dynamic stage hashes its own task instead of the whole task graph
     * it was expanded from, so editing one task leaves the others untouched.
     */
    public Map<String, String> inputHashes(Stage stage, Map<String, Stage> byKey) {
        Map<String, String> hashes = new TreeMap<>();
        for (String dep : stage.dependsOn()) {
            Stage d = byKey.get(dep);
            if (d == null) {
                continue;
            }
            boolean expandedFromThis = stage.dynamicStage() && templates.template().dynamicFor(stage.stageKey())
                    .map(t -> t.expandFrom().equals(dep)).orElse(false);
            if (expandedFromThis) {
                hashes.put(dep, Json.sha256(stage.taskJson() == null ? "" : stage.taskJson()));
            } else if (d.status() == StageStatus.SKIPPED) {
                hashes.put(dep, "skipped");
            } else if (d.outputArtifactId() != null) {
                hashes.put(dep, store.getArtifact(d.outputArtifactId()).sha256());
            }
        }
        return hashes;
    }

    // ---- reopening ----------------------------------------------------------------------------------------------

    /** Re-opens one stage: rolls the workspace back first if it had committed code. */
    public void reopen(UUID runId, String stageKey, String reason, String feedback) {
        try (var ignored = locks.run(runId)) {
            Stage stage = store.getStage(runId, stageKey);
            if (stage.applyOrder() != null) {
                rollbackFrom(runId, Set.of(stageKey), reason, Map.of(stageKey, feedback == null ? "" : feedback));
            } else {
                resetInPlace(stage, reason, feedback);
            }
        }
    }

    /**
     * Re-opens the given stages and everything downstream of them. If any of them had committed code, the workspace is
     * rebuilt from the baseline by replaying only the patches of the stages that are staying, so unaffected stages are
     * neither undone nor re-run, whatever order they happened to commit in. A retained patch that no longer applies
     * (because it relied on code that is going away) pulls its stage into the affected set as well.
     */
    public void rollbackFrom(UUID runId, Set<String> keys, String reason, Map<String, String> feedbackByKey) {
        try (var ignored = locks.run(runId); var wsLock = locks.workspace(runId)) {
            Run run = store.getRun(runId);
            List<Stage> stages = store.listStages(runId);
            Set<String> initial = downstream(stages, new LinkedHashSet<>(keys));
            boolean touchesCode = stages.stream().anyMatch(s -> initial.contains(s.stageKey()) && s.applyOrder() != null);
            Set<String> affected = initial;
            if (touchesCode && run.workspacePath() != null) {
                affected = rebuildWorkspace(run, stages, initial);
                audit.record(runId, null, SYSTEM, "ROLLBACK", "workspace rebuilt without " + affected + ": " + reason);
            }
            for (String key : affected) {
                resetInPlace(store.getStage(runId, key), reason, feedbackByKey.get(key));
            }
            store.insertDecision(runId, null, SYSTEM, "REPLANNED",
                    "Re-opened " + affected + ": " + reason, reason, null);
        }
    }

    /** @return the affected set, possibly grown by retained patches that could not be re-applied */
    private Set<String> rebuildWorkspace(Run run, List<Stage> stages, Set<String> affectedIn) {
        Set<String> affected = new LinkedHashSet<>(affectedIn);
        Workspace workspace = workspaces.open(run.id(), run.workspacePath(), run.baselineSha());
        List<Stage> applied = stages.stream().filter(s -> s.applyOrder() != null)
                .sorted(java.util.Comparator.comparingInt(Stage::applyOrder)).toList();
        boolean restart;
        do {
            restart = false;
            workspace.git().resetTo(run.baselineSha());
            Map<String, String> newCheckpoints = new HashMap<>();
            for (Stage s : applied) {
                if (affected.contains(s.stageKey())) {
                    continue;
                }
                Patch patch = json.read(store.getArtifact(s.outputArtifactId()).content(), Patch.class);
                try {
                    patch.changes().forEach(workspace.fs()::apply);
                } catch (ChangeFailed | SandboxViolation e) {
                    audit.record(run.id(), s.stageKey(), SYSTEM, "STAGE_INVALIDATED",
                            "its patch no longer applies once the rolled-back code is gone: " + e.getMessage());
                    Set<String> seeds = new LinkedHashSet<>(affected);
                    seeds.add(s.stageKey());
                    affected = downstream(stages, seeds);
                    restart = true;
                    break;
                }
                newCheckpoints.put(s.stageKey(), workspace.git().commitAll("[" + s.stageKey() + "] " + patch.summary() + " (replayed)"));
            }
            if (!restart) {
                newCheckpoints.forEach((key, sha) -> {
                    Stage retained = store.getStage(run.id(), key);
                    store.saveStage(StageEdit.of(retained).checkpoint(sha, retained.applyOrder()).build());
                });
            }
        } while (restart);
        return affected;
    }

    /** Manual rollback: re-opens a stage and everything downstream of it. */
    public void rollbackTo(UUID runId, String stageKey, String reason) {
        try (var ignored = locks.run(runId)) {
            List<Stage> stages = store.listStages(runId);
            Set<String> closure = downstream(stages, new LinkedHashSet<>(Set.of(stageKey)));
            rollbackFrom(runId, closure, reason, Map.of());
        }
    }

    /** Moves the stage back to PENDING with a fresh generation so any in-flight work for it is discarded. */
    private void resetInPlace(Stage stage, String reason, String feedback) {
        Stage current = store.getStage(stage.runId(), stage.stageKey());
        Stage base = current;
        if (current.status() != StageStatus.PENDING && current.status() != StageStatus.INVALIDATED) {
            base = store.transition(current, StageStatus.INVALIDATED);
        }
        store.saveStage(StageEdit.of(base).status(StageStatus.PENDING).nextGeneration().attempt(0)
                .inputHashes(null).output(null).pending(null).feedback(feedback == null || feedback.isBlank() ? null : feedback)
                .error(null).checkpoint(null, null).startedAt(null).finishedAt(null).build());
        store.supersedePending(stage.runId(), stage.stageKey());
        audit.record(stage.runId(), stage.stageKey(), SYSTEM, "STAGE_INVALIDATED", reason);
    }

    static Set<String> downstream(List<Stage> stages, Set<String> startKeys) {
        Set<String> result = new LinkedHashSet<>(startKeys);
        Deque<String> queue = new ArrayDeque<>(startKeys);
        while (!queue.isEmpty()) {
            String key = queue.poll();
            for (Stage s : stages) {
                if (s.dependsOn().contains(key) && result.add(s.stageKey())) {
                    queue.add(s.stageKey());
                }
            }
        }
        return result;
    }

    // ---- reacting to a completed stage ---------------------------------------------------------------------------

    /**
     * Called after a stage's new output is accepted. Dependents whose recorded inputs no longer match are re-opened;
     * conditional stages are skipped or revived when the requirement's change type changed.
     */
    public void onStageCompleted(UUID runId, String stageKey) {
        try (var ignored = locks.run(runId)) {
            List<Stage> stages = store.listStages(runId);
            Map<String, Stage> byKey = stages.stream().collect(Collectors.toMap(Stage::stageKey, s -> s));
            for (Stage dependent : stages) {
                if (!dependent.dependsOn().contains(stageKey)) {
                    continue;
                }
                Stage fresh = store.getStage(runId, dependent.stageKey());
                if (fresh.inputHashes() != null && fresh.status() != StageStatus.PENDING
                        && fresh.status() != StageStatus.SKIPPED) {
                    Map<String, String> recorded = json.readStringMap(fresh.inputHashes());
                    Map<String, String> current = inputHashes(fresh, byKey);
                    if (!Objects.equals(new TreeMap<>(recorded), current)) {
                        reopen(runId, fresh.stageKey(), "its input from '" + stageKey + "' changed", null);
                    }
                }
            }
            if (stageKey.equals("requirements")) {
                reconcileConditionalStages(runId);
            }
        }
    }

    private void reconcileConditionalStages(UUID runId) {
        RequirementSpec spec = store.listArtifacts(runId).stream()
                .filter(a -> a.stageKey().equals("requirements")).reduce((a, b) -> b)
                .map(a -> json.read(a.content(), RequirementSpec.class)).orElse(null);
        if (spec == null) {
            return;
        }
        for (Stage stage : store.listStages(runId)) {
            ChangeType only = templates.template().stage(stage.stageKey()).map(StageTemplate::onlyForChangeType).orElse(null);
            if (only == null) {
                continue;
            }
            boolean needed = spec.changeType() == only;
            if (stage.status() == StageStatus.SKIPPED && needed) {
                resetInPlace(stage, "requirement is now " + spec.changeType(), null);
            } else if (stage.status() != StageStatus.SKIPPED && stage.status() != StageStatus.PENDING && !needed) {
                resetInPlace(stage, "requirement is now " + spec.changeType() + "; stage no longer applies", null);
            }
        }
    }

    // ---- dynamic expansion ---------------------------------------------------------------------------------------

    /**
     * Turns the planner's task graph into stages, diffing against any earlier expansion: new tasks are added, changed
     * tasks re-opened, removed tasks retired, and the join stages are pointed at the current set.
     */
    public void expand(UUID runId, String plannerKey, TaskGraph graph) {
        DynamicStageTemplate dyn = templates.template().expandedFrom(plannerKey).orElse(null);
        if (dyn == null) {
            return;
        }
        try (var ignored = locks.run(runId)) {
            Map<String, Stage> existing = new HashMap<>();
            for (Stage s : store.listStages(runId)) {
                if (s.stageKey().startsWith(dyn.keyPrefix())) {
                    existing.put(s.stageKey(), s);
                }
            }
            Set<String> desiredKeys = graph.tasks().stream().map(t -> dyn.keyPrefix() + t.id())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            for (Stage s : existing.values()) {
                if (!desiredKeys.contains(s.stageKey()) && !isRetired(s)) {
                    retire(s);
                }
            }
            for (Task task : graph.tasks()) {
                String key = dyn.keyPrefix() + task.id();
                List<String> deps = new ArrayList<>(List.of(plannerKey));
                if (task.dependsOn() != null) {
                    task.dependsOn().forEach(d -> deps.add(dyn.keyPrefix() + d));
                }
                String taskJson = json.write(task);
                Stage current = existing.get(key);
                if (current == null) {
                    store.insertStage(runId, key, dyn.agent(), deps, true, taskJson);
                    audit.record(runId, key, SYSTEM, "TASK_ADDED", task.title());
                } else if (isRetired(current)) {
                    store.saveStage(StageEdit.of(current).status(StageStatus.PENDING).dependsOn(deps).taskJson(taskJson)
                            .nextGeneration().attempt(0).error(null).inputHashes(null).build());
                    audit.record(runId, key, SYSTEM, "TASK_ADDED", task.title() + " (re-added)");
                } else if (!taskJson.equals(current.taskJson()) || !deps.equals(current.dependsOn())) {
                    store.saveStage(StageEdit.of(current).dependsOn(deps).taskJson(taskJson).build());
                    reopen(runId, key, "its task was changed by the new plan", null);
                    audit.record(runId, key, SYSTEM, "TASK_MODIFIED", task.title());
                }
            }
            joinDynamicStages(runId, dyn);
        }
    }

    private static boolean isRetired(Stage s) {
        return s.status() == StageStatus.SKIPPED && s.error() != null && s.error().startsWith("removed by re-plan");
    }

    private void retire(Stage stage) {
        reopen(stage.runId(), stage.stageKey(), "its task was removed by the new plan", null);
        Stage reset = store.getStage(stage.runId(), stage.stageKey());
        store.saveStage(StageEdit.of(store.transition(reset, StageStatus.SKIPPED)).error("removed by re-plan").build());
        audit.record(stage.runId(), stage.stageKey(), SYSTEM, "TASK_REMOVED", "removed by re-plan");
    }

    /** Join stages wait for every active stage of the group, however many tasks the plan has. */
    private void joinDynamicStages(UUID runId, DynamicStageTemplate dyn) {
        List<Stage> stages = store.listStages(runId);
        List<String> active = stages.stream().filter(s -> s.stageKey().startsWith(dyn.keyPrefix()) && !isRetired(s))
                .map(Stage::stageKey).toList();
        for (String joiner : dyn.joinedBy()) {
            Stage stage = store.getStage(runId, joiner);
            List<String> deps = new ArrayList<>(templates.template().stage(joiner).orElseThrow().dependsOn());
            deps.addAll(active);
            if (!deps.equals(stage.dependsOn())) {
                store.saveStage(StageEdit.of(stage).dependsOn(deps).build());
                if (stage.status() != StageStatus.PENDING) {
                    reopen(runId, joiner, "the set of implementation stages changed", null);
                }
            }
        }
    }
}
