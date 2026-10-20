package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.ImpactReport;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.tool.CodeIndex;
import com.agentic.orchestrator.tool.CodeIndex.ClassInfo;
import com.agentic.orchestrator.tool.CodeIndex.Endpoint;
import com.agentic.orchestrator.tool.CodeIndex.Index;
import com.agentic.orchestrator.tool.WorkspaceFs;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fallback for impact analysis when the model is unavailable: the code index alone, no LLM involved. */
@Component
public class ImpactStaticAgent implements Agent {

    private final Json json;

    public ImpactStaticAgent(Json json) {
        this.json = json;
    }

    @Override
    public String id() {
        return "IMPACT_STATIC";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        RequirementSpec spec = ctx.requireOutput("requirements", RequirementSpec.class);
        WorkspaceFs fs = ctx.workspace().fs();
        Index index = CodeIndex.build(fs);
        List<String> keywords = ImpactAnalysisAgent.keywords(spec);
        List<ClassInfo> relevant = CodeIndex.relevant(fs, index, keywords).stream().limit(8).toList();
        List<String> names = relevant.stream().map(ClassInfo::name).toList();
        List<Endpoint> matching = index.endpoints().stream()
                .filter(e -> keywords.stream().anyMatch(k -> e.path().toLowerCase(Locale.ROOT).contains(k)
                        || e.handler().toLowerCase(Locale.ROOT).contains(k)))
                .toList();
        // With no keyword match, every endpoint is potentially affected.
        List<String> endpoints = (matching.isEmpty() ? index.endpoints() : matching).stream()
                .map(e -> e.verb() + " " + e.path()).toList();
        List<String> flows = new ArrayList<>();
        for (ClassInfo c : relevant) {
            for (String dep : c.dependsOn()) {
                flows.add(c.name() + " -> " + dep);
            }
        }
        ImpactReport report = new ImpactReport(
                "Static analysis only (the model was unavailable): classes ranked by keyword matches against the "
                        + "requirement. Review them manually.",
                names, endpoints, index.tables(), flows,
                List.of("No model reasoning was applied; the listed classes are keyword matches, not a verified impact set"),
                null);
        return new AgentResult(Artifacts.IMPACT_REPORT, json.write(report), 0);
    }
}
