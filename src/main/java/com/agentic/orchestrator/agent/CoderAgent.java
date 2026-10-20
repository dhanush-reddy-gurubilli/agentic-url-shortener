package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.Patch;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Artifacts.Task;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.llm.LlmClient;
import org.springframework.stereotype.Component;

/** Implements one task: reads the current content of the task's files and proposes a patch. */
@Component
public class CoderAgent extends LlmAgent {

    private static final String SYSTEM = PromptKit.COMMON_RULES + """
            Task: implement exactly one task as a patch to the repository. Write production-quality, idiomatic \
            Java 25 / Spring Boot code that matches the surrounding style. Parameterised SQL only, no raw client \
            IPs (hash them), no process spawning. Change only the task's files.
            """ + PromptKit.PATCH_FORMAT;

    public CoderAgent(LlmClient llm, Json json) {
        super(llm, json);
    }

    @Override
    public String id() {
        return "CODER";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        Task task = ctx.task();
        DesignDoc design = ctx.requireOutput("design", DesignDoc.class);
        RequirementSpec spec = ctx.requireOutput("requirements", RequirementSpec.class);
        String user = PromptKit.data("task", json.writePretty(task))
                + PromptKit.data("acceptance-criteria", String.join("\n", spec.acceptanceCriteria()))
                + PromptKit.data("design", json.writePretty(design))
                + "Current content of the files this task changes:\n"
                + PromptKit.files(ctx.workspace().fs(), task.files(), 20_000)
                + PromptKit.feedback(ctx.feedback());
        return result(Artifacts.PATCH, Patch.class, ask(ctx, SYSTEM, user));
    }
}
