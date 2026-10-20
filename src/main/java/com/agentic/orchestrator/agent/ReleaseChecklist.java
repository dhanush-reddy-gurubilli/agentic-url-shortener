package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.artifact.Artifacts.ChecklistItem;
import com.agentic.orchestrator.artifact.Artifacts.TestReport;
import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.ApprovalStatus;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.StageStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic release-readiness checklist. The go/no-go decision comes from these facts, never from the
 * model: an LLM may write the narrative but cannot turn a failing check into a GO.
 */
@Component
public class ReleaseChecklist {

    private final OrchestratorStore store;
    private final AuditService audit;
    private final double minCoverage;

    public ReleaseChecklist(OrchestratorStore store, AuditService audit, OrchestratorProperties properties) {
        this.store = store;
        this.audit = audit;
        this.minCoverage = properties.build().minLineCoverage();
    }

    public record Result(List<ChecklistItem> items, String goNoGo) {
        public String asText() {
            StringBuilder sb = new StringBuilder();
            for (ChecklistItem i : items) {
                sb.append(i.ok() ? "[x] " : "[ ] ").append(i.item()).append(": ").append(i.detail()).append('\n');
            }
            return sb.toString();
        }
    }

    public Result evaluate(StageContext ctx) {
        List<ChecklistItem> items = new ArrayList<>();
        TestReport report = ctx.output("validation", TestReport.class).orElse(null);
        items.add(new ChecklistItem("Build compiles and all tests pass",
                report != null && report.compiled() && report.testsFailed() == 0 && report.testsRun() > 0,
                report == null ? "no validation report" : report.testsRun() + " tests run, " + report.testsFailed() + " failed"));
        items.add(new ChecklistItem("Line coverage meets the bar",
                report != null && report.lineCoverage() >= minCoverage,
                report == null ? "no validation report" : String.format("%.0f%% (required %.0f%%)",
                        report.lineCoverage() * 100, minCoverage * 100)));
        items.add(new ChecklistItem("Validation found no policy findings",
                report != null && report.policyFindings().isEmpty(),
                report == null ? "no validation report" : report.policyFindings().size() + " finding(s)"));
        List<Approval> approvals = store.listApprovals(ctx.run().id());
        long pending = approvals.stream().filter(a -> a.status() == ApprovalStatus.PENDING).count();
        items.add(new ChecklistItem("No approval is pending", pending == 0,
                approvals.size() + " approval request(s), " + pending + " pending"));
        List<Stage> stages = store.listStages(ctx.run().id());
        long unfinished = stages.stream().filter(s -> !s.status().satisfiesDependents()
                && !s.stageKey().equals("release_readiness") && !s.stageKey().equals("release")).count();
        items.add(new ChecklistItem("Every earlier stage is complete", unfinished == 0,
                unfinished + " stage(s) not complete"));
        AuditService.VerifyResult chain = audit.verify();
        items.add(new ChecklistItem("Audit chain is intact", chain.valid(), chain.message()));
        boolean allOk = items.stream().allMatch(ChecklistItem::ok);
        return new Result(items, allOk ? "GO" : "NO_GO");
    }

    /** Stage statuses for narratives (released stages excluded so the text matches what the checklist saw). */
    public static String stageSummary(List<Stage> stages) {
        StringBuilder sb = new StringBuilder();
        for (Stage s : stages) {
            sb.append("- ").append(s.stageKey()).append(": ").append(s.status());
            if (s.status() == StageStatus.SUCCEEDED && s.attempt() > 1) {
                sb.append(" (attempt ").append(s.attempt()).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
