package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.ImpactReport;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.llm.LlmClient;
import com.agentic.orchestrator.tool.CodeIndex;
import com.agentic.orchestrator.tool.CodeIndex.ClassInfo;
import com.agentic.orchestrator.tool.CodeIndex.Index;
import com.agentic.orchestrator.tool.WorkspaceFs;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Brownfield codebase reasoning. A deterministic index (classes, endpoints, tables, dependencies) is built first;
 * the model then reasons over that fact base and the most relevant sources instead of guessing.
 */
@Component
public class ImpactAnalysisAgent extends LlmAgent {

    private static final String SYSTEM = PromptKit.COMMON_RULES + """
            Task: impact analysis of a change to an existing codebase. Using the code index and the relevant \
            sources, identify the impacted classes, endpoints, tables and data flows, any risks, and, for a bug \
            report, the most likely root cause (cite the class and method). Use only names that appear in the index.
            JSON shape:
            {"summary": "...", "affectedClasses": ["..."], "endpoints": ["GET /..."], "tables": ["..."],
             "dataFlows": ["A -> B -> C"], "risks": ["..."], "suspectedRootCause": "... or null"}
            """;

    private static final Set<String> STOPWORDS = Set.of("should", "would", "could", "which", "their", "there",
            "these", "those", "about", "after", "before", "being", "other", "every", "using", "users", "user");

    public ImpactAnalysisAgent(LlmClient llm, Json json) {
        super(llm, json);
    }

    @Override
    public String id() {
        return "IMPACT_ANALYSIS";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        RequirementSpec spec = ctx.requireOutput("requirements", RequirementSpec.class);
        WorkspaceFs fs = ctx.workspace().fs();
        Index index = CodeIndex.build(fs);
        List<ClassInfo> relevant = CodeIndex.relevant(fs, index, keywords(spec)).stream().limit(4).toList();
        StringBuilder user = new StringBuilder();
        user.append(PromptKit.data("requirement-spec", json.writePretty(spec)));
        user.append(PromptKit.data("code-index", json.writePretty(index)));
        user.append("Most relevant sources:\n");
        user.append(PromptKit.files(fs, relevant.stream().map(ClassInfo::path).toList(), 8_000));
        user.append(PromptKit.feedback(ctx.feedback()));
        return result(Artifacts.IMPACT_REPORT, ImpactReport.class, ask(ctx, SYSTEM, user.toString()));
    }

    /** Distinctive words from the spec, used to rank classes by relevance. */
    static List<String> keywords(RequirementSpec spec) {
        Set<String> words = new LinkedHashSet<>();
        List<String> texts = new ArrayList<>();
        texts.add(spec.title());
        texts.addAll(spec.goals() == null ? List.of() : spec.goals());
        texts.addAll(spec.functionalRequirements() == null ? List.of() : spec.functionalRequirements());
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (word.length() >= 5 && !STOPWORDS.contains(word)) {
                    words.add(word);
                }
            }
        }
        return words.stream().limit(12).toList();
    }
}
