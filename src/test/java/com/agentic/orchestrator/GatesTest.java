package com.agentic.orchestrator;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.Ambiguity;
import com.agentic.orchestrator.artifact.Artifacts.ChangeOp;
import com.agentic.orchestrator.artifact.Artifacts.ChangeType;
import com.agentic.orchestrator.artifact.Artifacts.FileChange;
import com.agentic.orchestrator.artifact.Artifacts.TestReport;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.gate.AmbiguityGate;
import com.agentic.orchestrator.gate.BuildGate;
import com.agentic.orchestrator.gate.DocSectionsGate;
import com.agentic.orchestrator.gate.Gate.GateContext;
import com.agentic.orchestrator.gate.Gate.GateResult;
import com.agentic.orchestrator.gate.SchemaGate;
import com.agentic.orchestrator.policy.ProposedChange;
import com.agentic.orchestrator.testing.Scripts;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GatesTest {

    private final Json json = new Json();
    private final Scripts scripts = new Scripts(json);
    private final OrchestratorProperties props = new OrchestratorProperties(Path.of("."), Path.of("."), null, null,
            null, 4, 0.5, 5, 1000, new OrchestratorProperties.Build("mvn", java.time.Duration.ofMinutes(1), 0.80), "k");

    private GateContext ctx(String type, String content, int generation, List<ProposedChange> changes) {
        UUID id = UUID.randomUUID();
        Run run = new Run(id, null, "req", RunStatus.RUNNING, 1000, 0, 0, false, null, null, null, Instant.now(), Instant.now(), null);
        Stage stage = new Stage(1, id, "s", "A", StageStatus.RUNNING, List.of(), false, null, generation, 1, null, null,
                null, null, null, null, null, null, null);
        return new GateContext(run, stage, type, content, changes, null, json);
    }

    private GateResult schema(String type, String content) {
        return new SchemaGate().evaluate(ctx(type, content, 1, List.of()));
    }

    // ---- schema --------------------------------------------------------------------------------------------------

    @Test
    void aValidSpecPassesAndAnIncompleteOneListsEveryProblem() {
        assertThat(schema(Artifacts.REQUIREMENT_SPEC, scripts.spec(ChangeType.GREENFIELD, 0.2, List.of())).passed()).isTrue();

        GateResult bad = schema(Artifacts.REQUIREMENT_SPEC,
                "{\"title\":\"\",\"acceptanceCriteria\":[],\"functionalRequirements\":[],\"ambiguityScore\":2}");
        assertThat(bad.passed()).isFalse();
        assertThat(String.join("|", bad.messages())).contains("title is required").contains("changeType")
                .contains("acceptanceCriteria").contains("functionalRequirements").contains("ambiguityScore");
    }

    @Test
    void notJsonIsReportedAsNotValidJson() {
        GateResult r = schema(Artifacts.DESIGN_DOC, "Sure! Here is the design: ...");
        assertThat(r.passed()).isFalse();
        assertThat(r.messages().getFirst()).contains("not valid DesignDoc JSON");
    }

    @Test
    void markdownFencesAroundJsonAreTolerated() {
        String fenced = "```json\n" + scripts.design(Scripts.create(Scripts.SERVICE)) + "\n```";
        assertThat(schema(Artifacts.DESIGN_DOC, fenced).passed()).isTrue();
    }

    @Test
    void aDesignNeedsIntendedFilesWithActions() {
        assertThat(schema(Artifacts.DESIGN_DOC, "{\"summary\":\"s\",\"intendedFiles\":[]}").passed()).isFalse();
        assertThat(schema(Artifacts.DESIGN_DOC, "{\"summary\":\"s\",\"intendedFiles\":[{\"path\":\"a\"}]}").passed()).isFalse();
    }

    @Test
    void aTaskGraphMustBeAnAcyclicSetOfTasksWithFilesAndAcceptance() {
        var ok = scripts.tasks(Scripts.task("A", List.of("f1"), List.of()), Scripts.task("B", List.of("f2"), List.of("A")));
        assertThat(schema(Artifacts.TASK_GRAPH, ok).passed()).isTrue();

        var cyclic = scripts.tasks(Scripts.task("A", List.of("f1"), List.of("B")), Scripts.task("B", List.of("f2"), List.of("A")));
        assertThat(String.join("|", schema(Artifacts.TASK_GRAPH, cyclic).messages())).contains("cycle");

        var unknown = scripts.tasks(Scripts.task("A", List.of("f1"), List.of("ghost")));
        assertThat(String.join("|", schema(Artifacts.TASK_GRAPH, unknown).messages())).contains("unknown task ghost");

        var duplicate = scripts.tasks(Scripts.task("A", List.of("f1"), List.of()), Scripts.task("A", List.of("f2"), List.of()));
        assertThat(String.join("|", schema(Artifacts.TASK_GRAPH, duplicate).messages())).contains("duplicate");

        var noFiles = scripts.tasks(Scripts.task("A", List.of(), List.of()));
        assertThat(String.join("|", schema(Artifacts.TASK_GRAPH, noFiles).messages())).contains("files");

        assertThat(schema(Artifacts.TASK_GRAPH, "{\"tasks\":[]}").passed()).isFalse();
        assertThat(schema(Artifacts.TASK_GRAPH, scripts.tasks(Scripts.task("bad id!", List.of("f"), List.of()))).passed()).isFalse();
    }

    @Test
    void aPatchMustHaveWellFormedChanges() {
        assertThat(schema(Artifacts.PATCH, scripts.patch("ok", Scripts.write("a", "x"))).passed()).isTrue();
        assertThat(schema(Artifacts.PATCH, "{\"summary\":\"s\",\"changes\":[]}").passed()).isFalse();
        assertThat(schema(Artifacts.PATCH, "{\"summary\":\"s\",\"changes\":[{\"path\":\"a\",\"op\":\"WRITE\"}]}").passed()).isFalse();
        assertThat(schema(Artifacts.PATCH, scripts.patch("r", new FileChange("a", ChangeOp.REPLACE, null, "", "y"))).passed()).isFalse();
    }

    @Test
    void aReleaseSummaryNeedsAVerdict() {
        assertThat(schema(Artifacts.RELEASE_SUMMARY, "{\"markdown\":\"m\",\"goNoGo\":\"GO\",\"risks\":[],\"checklist\":[]}").passed()).isTrue();
        assertThat(schema(Artifacts.RELEASE_SUMMARY, "{\"markdown\":\"m\",\"goNoGo\":\"MAYBE\",\"risks\":[],\"checklist\":[]}").passed()).isFalse();
    }

    // ---- ambiguity -----------------------------------------------------------------------------------------------

    @Test
    void anAmbiguousSpecAsksAHumanButOnlyForABoundedNumberOfRounds() {
        AmbiguityGate gate = new AmbiguityGate(props);
        String ambiguous = scripts.spec(ChangeType.GREENFIELD, 0.9, List.of(new Ambiguity("A1", "Which?", "This one")));

        assertThat(gate.evaluate(ctx(Artifacts.REQUIREMENT_SPEC, ambiguous, 1, List.of())).needsClarification()).isTrue();
        assertThat(gate.evaluate(ctx(Artifacts.REQUIREMENT_SPEC, ambiguous, 2, List.of())).needsClarification()).isTrue();
        assertThat(gate.evaluate(ctx(Artifacts.REQUIREMENT_SPEC, ambiguous, 3, List.of())).needsClarification())
                .as("after two rounds the assumptions are accepted").isFalse();
    }

    @Test
    void aClearSpecOrOneWithoutQuestionsNeverPauses() {
        AmbiguityGate gate = new AmbiguityGate(props);
        assertThat(gate.evaluate(ctx(Artifacts.REQUIREMENT_SPEC, scripts.spec(ChangeType.GREENFIELD, 0.1,
                List.of(new Ambiguity("A1", "q", "a"))), 1, List.of())).needsClarification()).isFalse();
        assertThat(gate.evaluate(ctx(Artifacts.REQUIREMENT_SPEC, scripts.spec(ChangeType.GREENFIELD, 0.9, List.of()), 1,
                List.of())).needsClarification()).as("nothing to ask").isFalse();
    }

    // ---- build ---------------------------------------------------------------------------------------------------

    private GateResult build(TestReport report) {
        return new BuildGate(props).evaluate(ctx(Artifacts.TEST_REPORT, json.write(report), 1, List.of()));
    }

    @Test
    void theBuildGateRequiresCompilationPassingTestsCoverageAndNoPolicyFindings() {
        assertThat(build(new TestReport(true, 10, 0, 0.85, 1, List.of(), "", List.of())).passed()).isTrue();
        assertThat(build(new TestReport(false, 0, 0, 0, 1, List.of(), "COMPILATION ERROR", List.of())).passed()).isFalse();
        assertThat(String.join("|", build(new TestReport(true, 10, 2, 0.9, 1, List.of("A.t1", "A.t2"), "", List.of())).messages()))
                .contains("2 test(s) failed").contains("A.t1");
        assertThat(String.join("|", build(new TestReport(true, 0, 0, 0.9, 1, List.of(), "", List.of())).messages())).contains("No tests ran");
        assertThat(String.join("|", build(new TestReport(true, 10, 0, 0.5, 1, List.of(), "", List.of())).messages())).contains("coverage 50%");
        assertThat(build(new TestReport(true, 10, 0, 0.9, 1, List.of(), "", List.of("tests missing"))).passed()).isFalse();
    }

    // ---- docs ----------------------------------------------------------------------------------------------------

    private static ProposedChange doc(String path, String content) {
        return new ProposedChange(new FileChange(path, ChangeOp.WRITE, content, null, null), null, content);
    }

    @Test
    void documentationMustStayInDocsAndHaveSections() {
        DocSectionsGate gate = new DocSectionsGate();
        assertThat(gate.evaluate(ctx(Artifacts.PATCH, "", 1, List.of(doc("docs/CHANGELOG.md", "# C\n\n## New\n")))).passed()).isTrue();
        assertThat(gate.evaluate(ctx(Artifacts.PATCH, "", 1, List.of(doc("README.md", "## Usage")))).passed()).isTrue();
        assertThat(gate.evaluate(ctx(Artifacts.PATCH, "", 1, List.of(doc("docs/x.md", "no headings")))).passed()).isFalse();
        assertThat(gate.evaluate(ctx(Artifacts.PATCH, "", 1, List.of(doc("src/main/java/A.java", "## fake")))).passed()).isFalse();
    }
}
