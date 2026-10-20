package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.Patch;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.llm.LlmClient;
import org.springframework.stereotype.Component;

import java.util.List;

/** Updates documentation (README, API notes, changelog) to match the change. */
@Component
public class DocAgent extends LlmAgent {

    private static final String SYSTEM = PromptKit.COMMON_RULES + """
            Task: update the documentation for this change. You may only change files under docs/ and README.md, \
            and every document you write or change must contain '## ' section headings. Record the change in \
            docs/CHANGELOG.md (create it if missing).
            """ + PromptKit.PATCH_FORMAT;

    public DocAgent(LlmClient llm, Json json) {
        super(llm, json);
    }

    @Override
    public String id() {
        return "DOC_WRITER";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        RequirementSpec spec = ctx.requireOutput("requirements", RequirementSpec.class);
        DesignDoc design = ctx.requireOutput("design", DesignDoc.class);
        String user = PromptKit.data("requirement-spec", json.writePretty(spec))
                + PromptKit.data("design", json.writePretty(design))
                + PromptKit.files(ctx.workspace().fs(), List.of("README.md", "docs/CHANGELOG.md"), 8_000)
                + PromptKit.feedback(ctx.feedback());
        return result(Artifacts.PATCH, Patch.class, ask(ctx, SYSTEM, user));
    }
}
