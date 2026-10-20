package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.agent.AgentRegistry;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageEdit;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.engine.WorkflowTemplate.StageTemplate;
import com.agentic.orchestrator.gate.GateRegistry;
import com.agentic.orchestrator.tool.Workspace;
import com.agentic.orchestrator.tool.WorkspaceManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * The scheduler. One driver per active run repeatedly decides, under the run lock, which stages are ready (all
 * dependencies satisfied and entry gates passing), starts up to {@code parallelism} of them concurrently, and waits for
 * one to finish. Independent stages therefore run in parallel and a stage with several dependencies is a join. When
 * nothing can run it completes, fails or pauses the run (awaiting a human), and honours safe-stop requests.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final String SYSTEM = "orchestrator";

    private final OrchestratorStore store;
    private final OrchestratorProperties props;
    private final WorkflowTemplateLoader templates;
    private final StageRunner stageRunner;
    private final RunLocks locks;
    private final AuditService audit;
    private final WorkspaceManager workspaces;
    private final ReplanService replan;
    private final AgentRegistry agents;
    private final GateRegistry gates;
    private final Json json;
    private final boolean recoverOnStartup;
    private final ExecutorService stageExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<UUID, Driver> drivers = new ConcurrentHashMap<>();

    public WorkflowEngine(OrchestratorStore store, OrchestratorProperties props, WorkflowTemplateLoader templates,
                          StageRunner stageRunner, RunLocks locks, AuditService audit, WorkspaceManager workspaces,
                          ReplanService replan, AgentRegistry agents, GateRegistry gates, Json json,
                          @Value("${orchestrator.recover-on-startup:true}") boolean recoverOnStartup) {
        this.store = store;
        this.props = props;
        this.templates = templates;
        this.stageRunner = stageRunner;
        this.locks = locks;
        this.audit = audit;
        this.workspaces = workspaces;
        this.replan = replan;
        this.agents = agents;
        this.gates = gates;
        this.json = json;
        this.recoverOnStartup = recoverOnStartup;
    }

    /** Fail fast at startup if the workflow references an agent or gate that does not exist. */
    @PostConstruct
    void validateWorkflow() {
        List<StageTemplate> all = new ArrayList<>(templates.template().stages());
        templates.template().dynamicStages().forEach(d -> all.add(templates.template().resolve(d.keyPrefix() + "x")));
        for (StageTemplate s : all) {
            agents.get(s.agent());
            if (s.fallbackAgent() != null) {
                agents.get(s.fallbackAgent());
            }
            s.exitGates().forEach(gates::get);
        }
    }

    @PreDestroy
    void shutdown() {
        stageExecutor.shutdown();
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    public Run start(String requirement, String scenario, Long tokenBudget) {
        Run run = store.insertRun(scenario, requirement, tokenBudget == null ? props.defaultTokenBudget() : tokenBudget);
        UUID id = run.id();
        audit.record(id, null, SYSTEM, "RUN_CREATED", "scenario=" + scenario + " budget=" + run.tokenBudget());
        Workspace workspace;
        try {
            workspace = workspaces.create(id);
        } catch (RuntimeException e) {
            // Do not leave a run stuck in CREATED: record why it never started.
            store.setRunStatus(id, RunStatus.STOPPED, "could not create the workspace: " + e.getMessage());
            audit.record(id, null, SYSTEM, "RUN_STOPPED", "could not create the workspace: " + e.getMessage());
            throw new IllegalStateException("Could not create the run's workspace (is the source a git repository with a "
                    + "commit, and is git installed?): " + e.getMessage(), e);
        }
        store.setWorkspace(id, workspace.root().toString(), workspace.baselineSha());
        for (StageTemplate s : templates.template().stages()) {
            store.insertStage(id, s.key(), s.agent(), s.dependsOn(), false, null);
        }
        store.setRunStatus(id, RunStatus.RUNNING, null);
        audit.record(id, null, SYSTEM, "RUN_STARTED", "workspace " + workspace.root() + " on branch " + workspace.branch());
        store.insertDecision(id, null, SYSTEM, "RUN_STARTED", "Run started from requirement", abbreviate(requirement), null);
        ensureDriving(id);
        return store.getRun(id);
    }

    /** Resume a stopped run (optionally with a larger budget), retry a failed one, or wake a paused one. */
    public Run resume(UUID runId, String actor, Long newBudget) {
        try (var ignored = locks.run(runId)) {
            Run run = store.getRun(runId);
            if (run.status() == RunStatus.RUNNING || run.status() == RunStatus.COMPLETED || run.status() == RunStatus.CREATED) {
                throw new IllegalStateException("Run is " + run.status() + " and cannot be resumed");
            }
            if (newBudget != null) {
                store.setTokenBudget(runId, newBudget);
                store.insertDecision(runId, null, actor, "BUDGET_RAISED", "Token budget set to " + newBudget, null, null);
                run = store.getRun(runId);
            }
            if (run.status() == RunStatus.STOPPED && run.tokensUsed() >= run.tokenBudget()) {
                throw new IllegalStateException("Token budget exhausted (" + run.tokensUsed() + " of " + run.tokenBudget()
                        + "); resume with a higher tokenBudget");
            }
            if (run.status() == RunStatus.FAILED) {
                for (Stage s : store.listStages(runId)) {
                    if (s.status() == StageStatus.FAILED) {
                        replan.reopen(runId, s.stageKey(), "manual resume of a failed run", s.feedback());
                    }
                }
            }
            store.setStopRequested(runId, false);
            store.setRunStatus(runId, RunStatus.RUNNING, null);
            audit.record(runId, null, actor, "RUN_RESUMED", newBudget == null ? "" : "budget " + newBudget);
            store.insertDecision(runId, null, actor, "RUN_RESUMED", "Run resumed", null, null);
        }
        ensureDriving(runId);
        return store.getRun(runId);
    }

    /** After a human action unblocked work: move PAUSED back to RUNNING and make sure a driver is active. */
    public void wake(UUID runId, String actor) {
        try (var ignored = locks.run(runId)) {
            Run run = store.getRun(runId);
            if (run.status() == RunStatus.PAUSED) {
                store.setRunStatus(runId, RunStatus.RUNNING, null);
                audit.record(runId, null, actor, "RUN_RESUMED", "unblocked by a human action");
            }
        }
        ensureDriving(runId);
    }

    /** Stops a paused run immediately; for a running one the driver stops it once in-flight stages finish. */
    public void pokeStop(UUID runId) {
        try (var ignored = locks.run(runId)) {
            Run run = store.getRun(runId);
            if (run.status() == RunStatus.PAUSED) {
                store.setRunStatus(runId, RunStatus.STOPPED, run.statusReason());
                audit.record(runId, null, SYSTEM, "RUN_STOPPED", "stopped while paused");
                return;
            }
        }
        Driver driver = drivers.get(runId);
        if (driver != null) {
            driver.signals.offer("stop");
        }
    }

    /** After a restart: stages that were mid-flight lost their work, so they go back to PENDING and runs continue. */
    @EventListener(ApplicationReadyEvent.class)
    void recoverOnReady() {
        if (recoverOnStartup) {
            recover();
        }
    }

    public void recover() {
        for (Run run : store.runsWithStatus(RunStatus.RUNNING)) {
            try (var ignored = locks.run(run.id())) {
                for (Stage s : store.listStages(run.id())) {
                    if (s.status() == StageStatus.RUNNING) {
                        Stage pending = store.transition(s, StageStatus.PENDING);
                        store.saveStage(StageEdit.of(pending).nextGeneration().attempt(0).build());
                        audit.record(run.id(), s.stageKey(), SYSTEM, "STAGE_INTERRUPTED", "recovered after restart");
                    }
                }
            }
            ensureDriving(run.id());
        }
    }

    public void ensureDriving(UUID runId) {
        drivers.compute(runId, (id, existing) -> {
            if (existing != null && existing.alive) {
                existing.signals.offer("poke");
                return existing;
            }
            Driver driver = new Driver(id);
            Thread.ofVirtual().name("run-" + id).start(driver);
            return driver;
        });
    }

    public boolean isDriving(UUID runId) {
        Driver d = drivers.get(runId);
        return d != null && d.alive;
    }

    // ---- the driver ----------------------------------------------------------------------------------------------

    private enum Kind { EXIT, WAIT }

    private record Step(Kind kind, List<String> toStart) {
        static final Step EXIT = new Step(Kind.EXIT, List.of());
        static final Step WAIT = new Step(Kind.WAIT, List.of());
    }

    private final class Driver implements Runnable {
        final UUID runId;
        final LinkedBlockingQueue<String> signals = new LinkedBlockingQueue<>();
        final Set<String> inFlight = ConcurrentHashMap.newKeySet();
        volatile boolean alive = true;

        Driver(UUID runId) {
            this.runId = runId;
        }

        @Override
        public void run() {
            MDC.put("runId", runId.toString());
            try {
                loop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.error("Driver for run {} crashed", runId, e);
                failRun("engine error: " + e);
            } finally {
                alive = false;
                drivers.remove(runId, this);
                MDC.remove("runId");
                // A human may have resumed the run between our last decision and exiting: don't lose that wake-up.
                try {
                    Run run = store.getRun(runId);
                    if (run.status() == RunStatus.RUNNING && !run.stopRequested() && !drivers.containsKey(runId)) {
                        ensureDriving(runId);
                    }
                } catch (RuntimeException e) {
                    log.warn("Could not re-check run {} after the driver exited: {}", runId, e.toString());
                }
            }
        }

        private void loop() throws InterruptedException {
            while (true) {
                Step step;
                try (var ignored = locks.run(runId)) {
                    step = decide();
                }
                if (step.kind() == Kind.EXIT) {
                    return;
                }
                for (String key : step.toStart()) {
                    stageExecutor.submit(() -> {
                        MDC.put("runId", runId.toString());
                        MDC.put("stageKey", key);
                        try {
                            stageRunner.run(runId, key);
                        } catch (Throwable t) {
                            log.error("Stage {} of run {} crashed", key, runId, t);
                        } finally {
                            MDC.clear();
                            inFlight.remove(key);
                            signals.add("done:" + key);
                        }
                    });
                }
                signals.take();
            }
        }

        /** Must be called under the run lock. */
        private Step decide() {
            Run run = store.getRun(runId);
            if (run.status() != RunStatus.RUNNING) {
                return Step.EXIT;
            }
            if (run.stopRequested()) {
                if (inFlight.isEmpty()) {
                    store.setRunStatus(runId, RunStatus.STOPPED, run.statusReason());
                    audit.record(runId, null, SYSTEM, "RUN_STOPPED", "safe-stop honoured; no stage is in flight");
                    return Step.EXIT;
                }
                return Step.WAIT;
            }
            List<Stage> stages = skipInapplicableStages(runId);
            Map<String, Stage> byKey = stages.stream().collect(Collectors.toMap(Stage::stageKey, s -> s));
            boolean anyFailed = stages.stream().anyMatch(s -> s.status() == StageStatus.FAILED);

            List<String> toStart = new ArrayList<>();
            if (!anyFailed) {
                int capacity = props.parallelism() - inFlight.size();
                for (Stage s : stages) {
                    if (toStart.size() >= capacity) {
                        break;
                    }
                    if (s.status() == StageStatus.PENDING && !inFlight.contains(s.stageKey()) && entryGatesPass(s, byKey, run)) {
                        store.transition(s, StageStatus.RUNNING);
                        inFlight.add(s.stageKey());
                        toStart.add(s.stageKey());
                    }
                }
            }
            if (!toStart.isEmpty()) {
                return new Step(Kind.WAIT, toStart);
            }
            if (!inFlight.isEmpty()) {
                return Step.WAIT;
            }
            return idle(stages);
        }

        /** Nothing is running and nothing can start: finish the run, or pause it for a human. */
        private Step idle(List<Stage> stages) {
            Stage failed = stages.stream().filter(s -> s.status() == StageStatus.FAILED).findFirst().orElse(null);
            if (failed != null) {
                finishRun(RunStatus.FAILED, "stage " + failed.stageKey() + " failed: " + failed.error(), "RUN_FAILED");
                return Step.EXIT;
            }
            if (stages.stream().allMatch(s -> s.status().satisfiesDependents())) {
                finishRun(RunStatus.COMPLETED, null, "RUN_COMPLETED");
                return Step.EXIT;
            }
            List<String> waiting = stages.stream().filter(s -> s.status().isAwaitingHuman())
                    .map(s -> s.stageKey() + " (" + (s.status() == StageStatus.AWAITING_APPROVAL ? "approval" : "clarification") + ")")
                    .toList();
            if (!waiting.isEmpty()) {
                String reason = "awaiting a human: " + String.join(", ", waiting);
                store.setRunStatus(runId, RunStatus.PAUSED, reason);
                audit.record(runId, null, SYSTEM, "RUN_PAUSED", reason);
                return Step.EXIT;
            }
            finishRun(RunStatus.FAILED, "no stage can run: unsatisfiable dependencies", "RUN_FAILED");
            return Step.EXIT;
        }

        private void finishRun(RunStatus status, String reason, String auditType) {
            store.setRunStatus(runId, status, reason);
            audit.record(runId, null, SYSTEM, auditType, reason == null ? "" : reason);
            store.insertDecision(runId, null, SYSTEM, auditType, "Run " + status.name().toLowerCase(), reason, null);
        }

        private void failRun(String reason) {
            try (var ignored = locks.run(runId)) {
                Run run = store.getRun(runId);
                if (run.status().canTransitionTo(RunStatus.FAILED)) {
                    finishRun(RunStatus.FAILED, reason, "RUN_FAILED");
                }
            }
        }
    }

    // ---- readiness -----------------------------------------------------------------------------------------------

    /** Entry gates: every dependency is satisfied and has its accepted output, and the token budget remains. */
    private boolean entryGatesPass(Stage stage, Map<String, Stage> byKey, Run run) {
        if (run.tokensUsed() >= run.tokenBudget()) {
            return false;
        }
        for (String dep : stage.dependsOn()) {
            Stage d = byKey.get(dep);
            if (d == null || !d.status().satisfiesDependents()) {
                return false;
            }
            if (d.status() == StageStatus.SUCCEEDED && d.outputArtifactId() == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Conditional stages (e.g. impact analysis is brownfield only) are SKIPPED once their dependencies are satisfied
     * and the requirement says they don't apply. A skipped stage counts as satisfied for its dependents.
     */
    private List<Stage> skipInapplicableStages(UUID runId) {
        boolean changed;
        List<Stage> stages;
        do {
            changed = false;
            stages = store.listStages(runId);
            Map<String, Stage> byKey = stages.stream().collect(Collectors.toMap(Stage::stageKey, s -> s));
            for (Stage s : stages) {
                if (s.status() != StageStatus.PENDING) {
                    continue;
                }
                var only = templates.template().stage(s.stageKey()).map(StageTemplate::onlyForChangeType).orElse(null);
                if (only == null || !s.dependsOn().stream().allMatch(d -> byKey.get(d).status().satisfiesDependents())) {
                    continue;
                }
                RequirementSpec spec = store.listArtifacts(runId).stream()
                        .filter(a -> a.stageKey().equals("requirements")).reduce((a, b) -> b)
                        .map(a -> json.read(a.content(), RequirementSpec.class)).orElse(null);
                if (spec != null && spec.changeType() != only) {
                    store.transition(s, StageStatus.SKIPPED);
                    audit.record(runId, s.stageKey(), SYSTEM, "STAGE_SKIPPED", "only applies to " + only + " changes; this is " + spec.changeType());
                    changed = true;
                }
            }
        } while (changed);
        return stages;
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
