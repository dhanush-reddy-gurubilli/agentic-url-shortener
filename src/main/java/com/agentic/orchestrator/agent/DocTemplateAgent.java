package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.ChangeOp;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.FileChange;
import com.agentic.orchestrator.artifact.Artifacts.IntendedFile;
import com.agentic.orchestrator.artifact.Artifacts.Patch;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.tool.WorkspaceFs;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** Fallback for the doc writer when the model is unavailable: a changelog entry generated from the artifacts. */
@Component
public class DocTemplateAgent implements Agent {

    static final String CHANGELOG = "docs/CHANGELOG.md";

    private final Json json;

    public DocTemplateAgent(Json json) {
        this.json = json;
    }

    @Override
    public String id() {
        return "DOC_TEMPLATE";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        RequirementSpec spec = ctx.requireOutput("requirements", RequirementSpec.class);
        DesignDoc design = ctx.requireOutput("design", DesignDoc.class);
        WorkspaceFs fs = ctx.workspace().fs();
        String entry = "## " + spec.title() + " (run " + ctx.run().id().toString().substring(0, 8) + ")\n\n"
                + "### Requirements\n"
                + spec.functionalRequirements().stream().map(r -> "- " + r).collect(Collectors.joining("\n")) + "\n\n"
                + "### Files\n"
                + design.intendedFiles().stream().map(DocTemplateAgent::describe).collect(Collectors.joining("\n"))
                + "\n";
        String existing = fs.exists(CHANGELOG) ? fs.read(CHANGELOG) : "# Changelog\n";
        String body = existing.startsWith("# Changelog") ? existing.substring("# Changelog".length()).stripLeading() : existing;
        String content = "# Changelog\n\n" + entry + (body.isBlank() ? "" : "\n" + body);
        Patch patch = new Patch("Changelog entry generated from the requirement and design (template fallback)",
                List.of(new FileChange(CHANGELOG, ChangeOp.WRITE, content, null, null)));
        return new AgentResult(Artifacts.PATCH, json.write(patch), 0);
    }

    private static String describe(IntendedFile f) {
        return "- `" + f.path() + "` (" + f.action().name().toLowerCase() + "): " + f.purpose();
    }
}
