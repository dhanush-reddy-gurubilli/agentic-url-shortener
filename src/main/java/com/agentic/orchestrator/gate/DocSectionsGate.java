package com.agentic.orchestrator.gate;

import com.agentic.orchestrator.policy.ProposedChange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Documentation output must stay in docs (or the README) and contain at least one section heading. */
@Component
public class DocSectionsGate implements Gate {

    @Override
    public String name() {
        return "doc-sections";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        List<String> problems = new ArrayList<>();
        boolean hasHeading = false;
        for (ProposedChange c : ctx.changes()) {
            boolean docPath = c.path().startsWith("docs/") || c.path().equals("README.md");
            if (!docPath) {
                problems.add(c.path() + " is not documentation; the doc writer may only change docs/ and README.md");
            } else if (c.newContent() != null && c.newContent().lines().anyMatch(l -> l.startsWith("## "))) {
                hasHeading = true;
            }
        }
        if (!hasHeading) {
            problems.add("documentation must contain at least one '## ' section heading");
        }
        return problems.isEmpty() ? GateResult.ok() : GateResult.fail(problems);
    }
}
