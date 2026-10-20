package com.agentic.orchestrator.gate;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.artifact.Artifacts.TestReport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Exit gate of validation: it compiles, every test passes, coverage meets the bar and no policy finding remains. */
@Component
public class BuildGate implements Gate {

    private final double minCoverage;

    public BuildGate(OrchestratorProperties properties) {
        this.minCoverage = properties.build().minLineCoverage();
    }

    @Override
    public String name() {
        return "build";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        TestReport report = ctx.json().read(ctx.content(), TestReport.class);
        List<String> problems = new ArrayList<>();
        if (!report.compiled()) {
            problems.add("The build does not compile or could not run:\n" + report.outputTail());
        } else {
            if (report.testsFailed() > 0) {
                problems.add(report.testsFailed() + " test(s) failed: " + String.join(", ", report.failures())
                        + "\n" + report.outputTail());
            }
            if (report.testsRun() == 0) {
                problems.add("No tests ran");
            }
            if (report.lineCoverage() < minCoverage) {
                problems.add(String.format("Line coverage %.0f%% is below the required %.0f%%",
                        report.lineCoverage() * 100, minCoverage * 100));
            }
        }
        problems.addAll(report.policyFindings());
        return problems.isEmpty() ? GateResult.ok() : GateResult.fail(problems);
    }
}
