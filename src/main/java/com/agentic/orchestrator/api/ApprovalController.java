package com.agentic.orchestrator.api;

import com.agentic.orchestrator.api.ApiDtos.DecisionRequest;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.ApprovalStatus;
import com.agentic.orchestrator.engine.RunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Approvals", description = "Human checkpoints for high-impact changes")
public class ApprovalController {

    private final RunService runs;
    private final OrchestratorStore store;
    private final HumanGuard guard;

    public ApprovalController(RunService runs, OrchestratorStore store, HumanGuard guard) {
        this.runs = runs;
        this.store = store;
        this.guard = guard;
    }

    @GetMapping
    @Operation(summary = "List approval requests (default: pending)")
    public List<Approval> list(@RequestParam(defaultValue = "PENDING") ApprovalStatus status) {
        return store.listApprovals(status);
    }

    @GetMapping("/{id}")
    public Approval get(@PathVariable long id) {
        return store.getApproval(id);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve: the parked output is applied. A comment is mandatory")
    public Approval approve(@PathVariable long id, @RequestBody DecisionRequest request,
                            @RequestHeader(value = "X-Approval-Key", required = false) String key,
                            @RequestHeader(value = "X-Actor", required = false) String actor) {
        guard.require(key);
        return runs.approve(id, HumanGuard.actor(actor), request.comment());
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject: the comment becomes a constraint and the stage is redone. A comment is mandatory")
    public Approval reject(@PathVariable long id, @RequestBody DecisionRequest request,
                           @RequestHeader(value = "X-Approval-Key", required = false) String key,
                           @RequestHeader(value = "X-Actor", required = false) String actor) {
        guard.require(key);
        return runs.reject(id, HumanGuard.actor(actor), request.comment());
    }
}
