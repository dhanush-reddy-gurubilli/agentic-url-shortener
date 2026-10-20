package com.agentic.orchestrator.gate;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.ChangeOp;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.FileChange;
import com.agentic.orchestrator.artifact.Artifacts.ImpactReport;
import com.agentic.orchestrator.artifact.Artifacts.Patch;
import com.agentic.orchestrator.artifact.Artifacts.ReleaseSummary;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Artifacts.Task;
import com.agentic.orchestrator.artifact.Artifacts.TaskGraph;
import com.agentic.orchestrator.artifact.Artifacts.TestReport;
import com.agentic.orchestrator.graph.Dag;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structured-output check: the artifact parses into its typed record and satisfies the required-field rules. */
@Component
public class SchemaGate implements Gate {

    @Override
    public String name() {
        return "schema";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        List<String> problems = new ArrayList<>();
        try {
            switch (ctx.artifactType()) {
                case Artifacts.REQUIREMENT_SPEC -> checkSpec(ctx.json().read(ctx.content(), RequirementSpec.class), problems);
                case Artifacts.IMPACT_REPORT -> checkImpact(ctx.json().read(ctx.content(), ImpactReport.class), problems);
                case Artifacts.DESIGN_DOC -> checkDesign(ctx.json().read(ctx.content(), DesignDoc.class), problems);
                case Artifacts.TASK_GRAPH -> checkTasks(ctx.json().read(ctx.content(), TaskGraph.class), problems);
                case Artifacts.PATCH -> checkPatch(ctx.json().read(ctx.content(), Patch.class), problems);
                case Artifacts.TEST_REPORT -> ctx.json().read(ctx.content(), TestReport.class);
                case Artifacts.RELEASE_SUMMARY -> checkSummary(ctx.json().read(ctx.content(), ReleaseSummary.class), problems);
                default -> problems.add("Unknown artifact type " + ctx.artifactType());
            }
        } catch (RuntimeException e) {
            problems.add("Output is not valid " + ctx.artifactType() + " JSON: " + e.getMessage());
        }
        return problems.isEmpty() ? GateResult.ok() : GateResult.fail(problems);
    }

    static void checkSpec(RequirementSpec spec, List<String> problems) {
        require(spec.title(), "title", problems);
        if (spec.changeType() == null) {
            problems.add("changeType must be GREENFIELD or BROWNFIELD");
        }
        if (isEmpty(spec.acceptanceCriteria())) {
            problems.add("acceptanceCriteria must not be empty");
        }
        if (isEmpty(spec.functionalRequirements())) {
            problems.add("functionalRequirements must not be empty");
        }
        if (spec.ambiguityScore() < 0 || spec.ambiguityScore() > 1) {
            problems.add("ambiguityScore must be between 0 and 1");
        }
        if (spec.ambiguities() != null) {
            for (var a : spec.ambiguities()) {
                if (a.question() == null || a.question().isBlank()) {
                    problems.add("every ambiguity needs a question");
                }
            }
        }
    }

    private static void checkImpact(ImpactReport report, List<String> problems) {
        require(report.summary(), "summary", problems);
    }

    private static void checkDesign(DesignDoc design, List<String> problems) {
        require(design.summary(), "summary", problems);
        if (isEmpty(design.intendedFiles())) {
            problems.add("intendedFiles must list the files the change will touch");
            return;
        }
        for (var file : design.intendedFiles()) {
            if (file.path() == null || file.path().isBlank() || file.action() == null) {
                problems.add("every intended file needs a path and an action (CREATE, MODIFY, DELETE)");
            }
        }
    }

    static void checkTasks(TaskGraph graph, List<String> problems) {
        if (graph.tasks() == null || graph.tasks().isEmpty()) {
            problems.add("the task graph needs at least one task");
            return;
        }
        Set<String> ids = new HashSet<>();
        for (Task t : graph.tasks()) {
            if (t.id() == null || !t.id().matches("[A-Za-z0-9_-]+")) {
                problems.add("task ids must be letters, digits, '-' or '_': " + t.id());
            } else if (!ids.add(t.id())) {
                problems.add("duplicate task id " + t.id());
            }
            if (isEmpty(t.files())) {
                problems.add("task " + t.id() + " must list the files it changes");
            }
            if (isEmpty(t.acceptance())) {
                problems.add("task " + t.id() + " must have acceptance criteria");
            }
        }
        Map<String, List<String>> deps = new HashMap<>();
        for (Task t : graph.tasks()) {
            List<String> taskDeps = t.dependsOn() == null ? List.of() : t.dependsOn();
            for (String dep : taskDeps) {
                if (!ids.contains(dep)) {
                    problems.add("task " + t.id() + " depends on unknown task " + dep);
                }
            }
            deps.put(t.id(), taskDeps);
        }
        if (problems.isEmpty()) {
            try {
                Dag.assertAcyclic(deps);
            } catch (IllegalStateException e) {
                problems.add(e.getMessage());
            }
        }
    }

    private static void checkPatch(Patch patch, List<String> problems) {
        if (isEmpty(patch.changes())) {
            problems.add("the patch must contain at least one change");
            return;
        }
        for (FileChange c : patch.changes()) {
            if (c.path() == null || c.path().isBlank() || c.op() == null) {
                problems.add("every change needs a path and an op (WRITE, REPLACE, DELETE)");
                continue;
            }
            if (c.op() == ChangeOp.WRITE && c.content() == null) {
                problems.add(c.path() + ": WRITE needs content");
            }
            if (c.op() == ChangeOp.REPLACE && (c.find() == null || c.find().isEmpty() || c.replacement() == null)) {
                problems.add(c.path() + ": REPLACE needs find and replacement");
            }
        }
    }

    private static void checkSummary(ReleaseSummary summary, List<String> problems) {
        require(summary.markdown(), "markdown", problems);
        if (!"GO".equals(summary.goNoGo()) && !"NO_GO".equals(summary.goNoGo())) {
            problems.add("goNoGo must be GO or NO_GO");
        }
    }

    private static void require(String value, String field, List<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(field + " is required");
        }
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }
}
