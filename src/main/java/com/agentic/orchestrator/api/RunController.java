package com.agentic.orchestrator.api;

import com.agentic.orchestrator.api.ApiDtos.Answer;
import com.agentic.orchestrator.api.ApiDtos.ClarificationRequest;
import com.agentic.orchestrator.api.ApiDtos.RequirementRequest;
import com.agentic.orchestrator.api.ApiDtos.ResumeRequest;
import com.agentic.orchestrator.api.ApiDtos.ReviseRequest;
import com.agentic.orchestrator.api.ApiDtos.RunDetail;
import com.agentic.orchestrator.api.ApiDtos.RunView;
import com.agentic.orchestrator.api.ApiDtos.StageView;
import com.agentic.orchestrator.api.ApiDtos.StartRunRequest;
import com.agentic.orchestrator.api.ApiDtos.StopRequest;
import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.domain.NotFoundException;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Artifact;
import com.agentic.orchestrator.domain.Records.AuditEvent;
import com.agentic.orchestrator.domain.Records.Decision;
import com.agentic.orchestrator.engine.RunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Runs", description = "Start and steer governed SDLC runs; humans decide through the endpoints that need X-Approval-Key")
public class RunController {

    private final RunService runs;
    private final OrchestratorStore store;
    private final AuditService audit;
    private final HumanGuard guard;

    public RunController(RunService runs, OrchestratorStore store, AuditService audit, HumanGuard guard) {
        this.runs = runs;
        this.store = store;
        this.audit = audit;
        this.guard = guard;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start a run from a requirement; it executes asynchronously (needs X-Approval-Key unless the model mode is replay)")
    public RunView start(@RequestBody StartRunRequest request,
                         @RequestHeader(value = "X-Approval-Key", required = false) String key) {
        guard.requireIfRunsCostMoney(key);
        return RunView.of(runs.start(request.requirement(), request.scenario(), request.tokenBudget()));
    }

    @GetMapping
    public List<RunView> list() {
        return store.listRuns().stream().map(RunView::of).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Run status with the dependency graph: every stage's state, attempts and checkpoint")
    public RunDetail get(@PathVariable UUID id) {
        return new RunDetail(RunView.of(store.getRun(id)),
                store.listStages(id).stream().map(StageView::of).toList(),
                store.listApprovals(id), store.listClarifications(id));
    }

    @GetMapping("/{id}/artifacts")
    public List<Artifact> artifacts(@PathVariable UUID id) {
        store.getRun(id);
        return store.listArtifacts(id).stream()
                .map(a -> new Artifact(a.id(), a.runId(), a.stageKey(), a.type(), a.version(), null, a.sha256(),
                        a.createdBy(), a.createdAt())).toList();
    }

    @GetMapping("/{id}/artifacts/{artifactId}")
    public Artifact artifact(@PathVariable UUID id, @PathVariable long artifactId) {
        return store.listArtifacts(id).stream().filter(a -> a.id() == artifactId).findFirst()
                .orElseThrow(() -> new NotFoundException("Unknown artifact " + artifactId + " in run " + id));
    }

    @GetMapping("/{id}/decisions")
    @Operation(summary = "Decision log / lineage: who decided what, and why, in order")
    public List<Decision> decisions(@PathVariable UUID id) {
        store.getRun(id);
        return store.listDecisions(id);
    }

    @GetMapping("/{id}/audit")
    public List<AuditEvent> audit(@PathVariable UUID id) {
        store.getRun(id);
        return audit.forRun(id);
    }

    @PostMapping("/{id}/clarifications")
    @Operation(summary = "Answer the clarification questions (or accept the proposed assumptions); triggers a re-plan")
    public RunDetail clarify(@PathVariable UUID id, @RequestBody ClarificationRequest request,
                             @RequestHeader(value = "X-Approval-Key", required = false) String key,
                             @RequestHeader(value = "X-Actor", required = false) String actor) {
        guard.require(key);
        Map<Long, String> answers = new HashMap<>();
        if (request.answers() != null) {
            for (Answer a : request.answers()) {
                answers.put(a.id(), a.answer());
            }
        }
        runs.answerClarifications(id, HumanGuard.actor(actor), answers, request.acceptAssumptions());
        return get(id);
    }

    @PostMapping("/{id}/stop")
    @Operation(summary = "Safe-stop: nothing new is scheduled, in-flight stages finish, state stays resumable")
    public RunView stop(@PathVariable UUID id, @RequestBody(required = false) StopRequest request,
                        @RequestHeader(value = "X-Actor", required = false) String actor) {
        return RunView.of(runs.stop(id, HumanGuard.actor(actor), request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume a stopped or failed run, optionally with a higher token budget")
    public RunView resume(@PathVariable UUID id, @RequestBody(required = false) ResumeRequest request,
                          @RequestHeader(value = "X-Approval-Key", required = false) String key,
                          @RequestHeader(value = "X-Actor", required = false) String actor) {
        guard.require(key);
        return RunView.of(runs.resume(id, HumanGuard.actor(actor), request == null ? null : request.tokenBudget()));
    }

    @PostMapping("/{id}/rollback")
    @Operation(summary = "Roll the workspace back to before a stage and re-run it and everything downstream")
    public RunDetail rollback(@PathVariable UUID id, @RequestParam String toStage,
                              @RequestParam(required = false) String reason,
                              @RequestHeader(value = "X-Approval-Key", required = false) String key,
                              @RequestHeader(value = "X-Actor", required = false) String actor) {
        guard.require(key);
        runs.rollback(id, toStage, HumanGuard.actor(actor), reason);
        return get(id);
    }

    @PostMapping("/{id}/stages/{stageKey}/revise")
    @Operation(summary = "Ask for a stage to be redone with feedback; downstream stages re-run only if their inputs change")
    public RunDetail revise(@PathVariable UUID id, @PathVariable String stageKey, @RequestBody ReviseRequest request,
                            @RequestHeader(value = "X-Approval-Key", required = false) String key,
                            @RequestHeader(value = "X-Actor", required = false) String actor) {
        guard.require(key);
        runs.revise(id, stageKey, HumanGuard.actor(actor), request.feedback());
        return get(id);
    }

    @PutMapping("/{id}/requirement")
    @Operation(summary = "Edit the requirement text mid-run; the plan is re-derived from it")
    public RunDetail updateRequirement(@PathVariable UUID id, @RequestBody RequirementRequest request,
                                       @RequestHeader(value = "X-Approval-Key", required = false) String key,
                                       @RequestHeader(value = "X-Actor", required = false) String actor) {
        guard.require(key);
        runs.updateRequirement(id, HumanGuard.actor(actor), request.requirement());
        return get(id);
    }
}
