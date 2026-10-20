package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.Patch;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Artifacts.TaskGraph;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.llm.LlmClient;
import org.springframework.stereotype.Component;

/** Writes unit and integration tests from the acceptance criteria and the design, in parallel with the code. */
@Component
public class TestAgent extends LlmAgent {

    private static final String SYSTEM = PromptKit.COMMON_RULES + """
            Task: write JUnit 5 tests (AssertJ, Spring MockMvc / @SpringBootTest where an HTTP or database \
            boundary is involved) that prove every acceptance criterion. The implementation is written \
            at the same time by another stage: rely only on the file names, class names and signatures in the \
            design and the task graph. Put tests under src/test/java/com/agentic/shortener/. Every changed \
            production class must be exercised by at least one test that names it.
            """ + PromptKit.PATCH_FORMAT;

    public TestAgent(LlmClient llm, Json json) {
        super(llm, json);
    }

    @Override
    public String id() {
        return "TESTER";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        RequirementSpec spec = ctx.requireOutput("requirements", RequirementSpec.class);
        DesignDoc design = ctx.requireOutput("design", DesignDoc.class);
        TaskGraph tasks = ctx.requireOutput("planning", TaskGraph.class);
        var fs = ctx.workspace().fs();
        String user = PromptKit.data("acceptance-criteria", String.join("\n", spec.acceptanceCriteria()))
                + PromptKit.data("design", json.writePretty(design))
                + PromptKit.data("task-graph", json.writePretty(tasks))
                + PromptKit.data("existing-tests", String.join("\n", fs.listFiles("src/test/java/com/agentic/shortener")))
                + PromptKit.feedback(ctx.feedback());
        return result(Artifacts.PATCH, Patch.class, ask(ctx, SYSTEM, user));
    }
}
