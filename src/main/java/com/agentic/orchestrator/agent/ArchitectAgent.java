package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.ImpactReport;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.llm.LlmClient;
import com.agentic.orchestrator.tool.CodeIndex;
import org.springframework.stereotype.Component;

/** Produces the design: decisions, and exactly which files the change will create, modify or delete. */
@Component
public class ArchitectAgent extends LlmAgent {

    private static final String SYSTEM = PromptKit.COMMON_RULES + """
            Task: design the change. Be minimal and consistent with the existing code. The intendedFiles list is a \
            contract: later stages may only touch these files (plus tests under src/test/java/com/agentic/shortener/).
            Never plan changes under src/main/java/com/agentic/orchestrator, policies, workflows or scenarios.
            List a pom.xml entry and newDependencies when a new library is needed; add a new Flyway migration file \
            (never edit an existing one) when the schema changes.
            JSON shape:
            {"summary": "...", "decisions": [{"title": "...", "rationale": "..."}],
             "intendedFiles": [{"path": "src/main/java/...", "action": "CREATE|MODIFY|DELETE", "purpose": "..."}],
             "newDependencies": ["group:artifact"], "risks": ["..."]}
            """;

    public ArchitectAgent(LlmClient llm, Json json) {
        super(llm, json);
    }

    @Override
    public String id() {
        return "ARCHITECT";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        RequirementSpec spec = ctx.requireOutput("requirements", RequirementSpec.class);
        StringBuilder user = new StringBuilder();
        user.append(PromptKit.data("requirement-spec", json.writePretty(spec)));
        ctx.output("impact_analysis", ImpactReport.class)
                .ifPresent(r -> user.append(PromptKit.data("impact-report", json.writePretty(r))));
        user.append(PromptKit.data("code-index", json.writePretty(CodeIndex.build(ctx.workspace().fs()))));
        user.append(PromptKit.feedback(ctx.feedback()));
        return result(Artifacts.DESIGN_DOC, DesignDoc.class, ask(ctx, SYSTEM, user.toString()));
    }
}
