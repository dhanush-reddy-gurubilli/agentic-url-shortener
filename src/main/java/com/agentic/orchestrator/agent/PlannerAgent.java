package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Artifacts.TaskGraph;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.llm.LlmClient;
import org.springframework.stereotype.Component;

/** Decomposes the approved design into implementation tasks with dependencies; each becomes a workflow stage. */
@Component
public class PlannerAgent extends LlmAgent {

    private static final String SYSTEM = PromptKit.COMMON_RULES + """
            Task: split the design into small implementation tasks. Each task changes a disjoint set of files taken \
            from the design's intendedFiles (test files are written by a separate testing stage; do not plan them \
            here). Tasks that do not depend on each other run in parallel, so only add dependsOn where one task \
            needs another's output.
            JSON shape:
            {"tasks": [{"id": "T1", "title": "...", "files": ["src/main/..."], "dependsOn": [],
                        "acceptance": ["testable statement"]}]}
            """;

    public PlannerAgent(LlmClient llm, Json json) {
        super(llm, json);
    }

    @Override
    public String id() {
        return "PLANNER";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        DesignDoc design = ctx.requireOutput("design", DesignDoc.class);
        RequirementSpec spec = ctx.requireOutput("requirements", RequirementSpec.class);
        String user = PromptKit.data("requirement-spec", json.writePretty(spec))
                + PromptKit.data("design", json.writePretty(design))
                + PromptKit.feedback(ctx.feedback());
        return result(Artifacts.TASK_GRAPH, TaskGraph.class, ask(ctx, SYSTEM, user));
    }
}
