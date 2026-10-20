package com.agentic.orchestrator.api;

import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.audit.ReliabilityMetrics;
import com.agentic.orchestrator.domain.Records.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Audit and metrics")
public class AuditController {

    private final AuditService audit;
    private final ReliabilityMetrics metrics;

    public AuditController(AuditService audit, ReliabilityMetrics metrics) {
        this.audit = audit;
        this.metrics = metrics;
    }

    @GetMapping("/audit")
    public List<AuditEvent> events(@RequestParam(required = false) UUID runId) {
        return runId == null ? audit.all() : audit.forRun(runId);
    }

    @GetMapping("/audit/verify")
    @Operation(summary = "Recompute the audit hash chain; reports the first broken event if the trail was tampered with")
    public AuditService.VerifyResult verify() {
        return audit.verify();
    }

    @GetMapping("/metrics/reliability")
    @Operation(summary = "Success rate, retry/fallback/rollback frequency, MTTR and latency across runs")
    public ReliabilityMetrics.Report reliability() {
        return metrics.compute();
    }
}
