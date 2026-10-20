package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.TestReport;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.policy.PolicyEngine;
import com.agentic.orchestrator.policy.Violation;
import com.agentic.orchestrator.tool.BuildRunner;
import com.agentic.orchestrator.tool.Workspace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic validation, no model involved: build and test the workspace, then re-check the whole branch
 * against change-control policy (protected paths untouched, every changed class has a test).
 */
@Component
public class ValidationAgent implements Agent {

    private final BuildRunner build;
    private final PolicyEngine policy;
    private final Json json;

    public ValidationAgent(BuildRunner build, PolicyEngine policy, Json json) {
        this.build = build;
        this.policy = policy;
        this.json = json;
    }

    @Override
    public String id() {
        return "VALIDATOR";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        Workspace ws = ctx.workspace();
        TestReport built = build.verify(ws);
        List<String> nameStatus = ws.git().changedFilesSince(ws.baselineSha());
        List<String> findings = new ArrayList<>();
        for (String line : nameStatus) {
            String path = line.split("\\s+", 2)[1].trim().replace('\\', '/');
            policy.config().protectedPaths().stream()
                    .filter(p -> path.startsWith(p) || path.equals(p)).findFirst()
                    .ifPresent(p -> findings.add("protected path changed on the branch: " + path));
        }
        for (Violation v : policy.missingTests(ws.fs(), nameStatus)) {
            findings.add(v.toString());
        }
        TestReport report = new TestReport(built.compiled(), built.testsRun(), built.testsFailed(),
                built.lineCoverage(), built.durationMillis(), built.failures(), built.outputTail(), findings);
        return new AgentResult(Artifacts.TEST_REPORT, json.write(report), 0);
    }
}
