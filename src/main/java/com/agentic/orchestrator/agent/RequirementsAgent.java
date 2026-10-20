package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.domain.Records.Clarification;
import com.agentic.orchestrator.llm.LlmClient;
import org.springframework.stereotype.Component;

/** Turns a free-text requirement into a structured spec, and says what is ambiguous instead of guessing silently. */
@Component
public class RequirementsAgent extends LlmAgent {

    private static final String SYSTEM = PromptKit.COMMON_RULES + """
            Task: interpret the requirement and normalise it into an engineering problem.
            Decide changeType: GREENFIELD (new capability) or BROWNFIELD (fix or change to existing behaviour).
            List every genuine ambiguity as a question WITH a proposedAssumption you would make if nobody answers.
            ambiguityScore is 0 (fully specified) to 1 (cannot design from this); use above 0.5 only when the \
            answers would materially change the design.
            JSON shape:
            {"title": "...", "changeType": "GREENFIELD|BROWNFIELD", "goals": ["..."],
             "functionalRequirements": ["..."], "nonFunctionalRequirements": ["..."],
             "acceptanceCriteria": ["testable statement", ...],
             "ambiguities": [{"id": "A1", "question": "...", "proposedAssumption": "..."}],
             "assumptions": ["..."], "ambiguityScore": 0.0}
            """;

    public RequirementsAgent(LlmClient llm, Json json) {
        super(llm, json);
    }

    @Override
    public String id() {
        return "REQUIREMENTS";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        StringBuilder user = new StringBuilder();
        user.append(PromptKit.data("requirement", ctx.run().requirement()));
        if (!ctx.clarifications().isEmpty()) {
            StringBuilder answers = new StringBuilder();
            for (Clarification c : ctx.clarifications()) {
                if (c.answer() != null) {
                    answers.append("Q: ").append(c.question()).append("\nA (").append(c.answeredBy()).append("): ")
                            .append(c.answer()).append("\n\n");
                }
            }
            user.append("The human reviewer answered your earlier questions. Treat these answers as requirements and "
                    + "do not ask them again:\n").append(PromptKit.data("clarifications", answers.toString()));
        }
        user.append(PromptKit.feedback(ctx.feedback()));
        return result(Artifacts.REQUIREMENT_SPEC, RequirementSpec.class, ask(ctx, SYSTEM, user.toString()));
    }
}
