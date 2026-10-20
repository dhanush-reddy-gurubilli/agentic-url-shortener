package com.agentic.orchestrator.gate;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.IntendedFile;
import com.agentic.orchestrator.artifact.Artifacts.Task;
import com.agentic.orchestrator.artifact.Artifacts.TaskGraph;
import com.agentic.orchestrator.policy.ProposedChange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Change control: work may only touch files the approved design lists (tests under the shortener test tree are
 * always allowed). This keeps agents inside the scope a human approved.
 */
@Component
public class DesignScopeGate implements Gate {

    private static final String TEST_PREFIX = "src/test/java/com/agentic/shortener/";

    @Override
    public String name() {
        return "design-scope";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        DesignDoc design = ctx.artifacts().read(ctx.run().id(), "design", DesignDoc.class).orElse(null);
        if (design == null) {
            return GateResult.fail("No accepted design to check the scope against");
        }
        Set<String> allowed = new HashSet<>();
        for (IntendedFile f : design.intendedFiles()) {
            allowed.add(f.path().replace('\\', '/'));
        }
        List<String> touched = new ArrayList<>();
        if (Artifacts.TASK_GRAPH.equals(ctx.artifactType())) {
            for (Task t : ctx.json().read(ctx.content(), TaskGraph.class).tasks()) {
                touched.addAll(t.files());
            }
        } else {
            ctx.changes().stream().map(ProposedChange::path).forEach(touched::add);
        }
        List<String> problems = new ArrayList<>();
        for (String path : touched) {
            String normalized = path.replace('\\', '/');
            if (!allowed.contains(normalized) && !normalized.startsWith(TEST_PREFIX)) {
                problems.add(normalized + " is not in the approved design's file list; keep the change within the "
                        + "design or revise the design");
            }
        }
        return problems.isEmpty() ? GateResult.ok() : GateResult.fail(problems);
    }
}
