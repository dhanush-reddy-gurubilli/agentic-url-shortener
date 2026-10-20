package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.ApprovalStatus;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.engine.RunService;
import com.agentic.orchestrator.scenario.ScenarioConfig.HumanAction;
import com.agentic.orchestrator.scenario.ScenarioConfig.HumanDecision;
import com.agentic.orchestrator.scenario.ScenarioConfig.HumanTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DEMO ONLY. Plays the human of a scenario by following the scripted {@code human-actions} in scenario.yaml, through
 * the very same {@link RunService} calls the REST API uses. Every decision it makes carries the actor name
 * {@value #ACTOR}, so the audit trail always shows which decisions were scripted. In real use the approval endpoints
 * are called by people and this class is never started.
 */
@Component
public class ScenarioAutopilot {

    public static final String ACTOR = "autopilot:scripted-human";
    private static final Logger log = LoggerFactory.getLogger(ScenarioAutopilot.class);
    private static final Duration MAX_DURATION = Duration.ofMinutes(45);

    private final RunService runs;
    private final OrchestratorStore store;

    public ScenarioAutopilot(RunService runs, OrchestratorStore store) {
        this.runs = runs;
        this.store = store;
    }

    public void attach(UUID runId, ScenarioConfig scenario) {
        Thread.ofVirtual().name("autopilot-" + runId).start(() -> play(runId, scenario));
    }

    /** Blocking; returns when the run completes or fails, or the time limit passes. */
    public void play(UUID runId, ScenarioConfig scenario) {
        Map<HumanAction, Integer> fired = new HashMap<>();
        long deadline = System.nanoTime() + MAX_DURATION.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                Run run = store.getRun(runId);
                if (run.status() == RunStatus.COMPLETED || run.status() == RunStatus.FAILED) {
                    return;
                }
                if (!actOnce(run, scenario, fired)) {
                    Thread.sleep(150);
                }
            }
            log.warn("Autopilot for run {} gave up after {}", runId, MAX_DURATION);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Autopilot for run {} failed", runId, e);
        }
    }

    /** @return true if it made a decision */
    private boolean actOnce(Run run, ScenarioConfig scenario, Map<HumanAction, Integer> fired) {
        for (Approval approval : store.listApprovals(run.id())) {
            if (approval.status() != ApprovalStatus.PENDING) {
                continue;
            }
            HumanAction action = find(scenario, fired, HumanTrigger.APPROVAL, approval.stageKey(), approval.reasons());
            if (action != null) {
                fired.merge(action, 1, Integer::sum);
                String comment = action.comment() == null ? "scripted decision" : action.comment();
                if (action.decision() == HumanDecision.REJECT) {
                    runs.reject(approval.id(), ACTOR, comment);
                } else {
                    runs.approve(approval.id(), ACTOR, comment);
                }
                return true;
            }
        }
        boolean awaitingClarification = store.listStages(run.id()).stream()
                .anyMatch(s -> s.status() == StageStatus.AWAITING_CLARIFICATION);
        if (awaitingClarification) {
            HumanAction action = find(scenario, fired, HumanTrigger.CLARIFICATION, null, null);
            if (action != null) {
                fired.merge(action, 1, Integer::sum);
                runs.answerClarifications(run.id(), ACTOR, Map.of(), true);
                return true;
            }
        }
        if (run.status() == RunStatus.STOPPED) {
            HumanAction action = find(scenario, fired, HumanTrigger.STOPPED, null, null);
            if (action != null) {
                fired.merge(action, 1, Integer::sum);
                runs.resume(run.id(), ACTOR, action.tokenBudget());
                return true;
            }
        }
        return false;
    }

    private static HumanAction find(ScenarioConfig scenario, Map<HumanAction, Integer> fired, HumanTrigger trigger,
                                    String stage, String reasons) {
        for (HumanAction a : scenario.humanActions()) {
            if (a.on() != trigger || fired.getOrDefault(a, 0) >= a.maxTimes()) {
                continue;
            }
            if (a.stage() != null && !a.stage().equals(stage)) {
                continue;
            }
            if (a.reasonContains() != null && (reasons == null || !reasons.contains(a.reasonContains()))) {
                continue;
            }
            return a;
        }
        return null;
    }
}
