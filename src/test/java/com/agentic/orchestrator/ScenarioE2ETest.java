package com.agentic.orchestrator;

import com.agentic.IntegrationTest;
import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.TestReport;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.ApprovalStatus;
import com.agentic.orchestrator.domain.Records.Artifact;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.engine.RunService;
import com.agentic.orchestrator.scenario.ScenarioAutopilot;
import com.agentic.orchestrator.scenario.ScenarioCatalog;
import com.agentic.orchestrator.scenario.ScenarioConfig;
import com.agentic.orchestrator.tool.GitTool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three scenarios end to end: the real engine, the real Maven build and test run inside each run's workspace, the
 * recorded model responses from scenarios/&lt;name&gt;/responses, and the scripted human. Slow (a few minutes each), so
 * excluded from the default build: run with {@code mvn verify -Pscenarios}.
 *
 * The workspace source is a copy of the current working tree (so uncommitted work is exercised too).
 */
@Tag("scenario")
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class ScenarioE2ETest extends IntegrationTest {

    private static Path sourceCopy;
    private static Path workspaces;

    @DynamicPropertySource
    static void scenarioProperties(DynamicPropertyRegistry registry) throws IOException {
        sourceCopy = Files.createTempDirectory("agentic-scenario-source");
        workspaces = Files.createTempDirectory("agentic-scenario-workspaces");
        copyWorkingTree(Path.of("").toAbsolutePath(), sourceCopy);
        registry.add("orchestrator.source-root", () -> sourceCopy.toString());
        registry.add("orchestrator.workspace-root", () -> workspaces.toString());
    }

    @Autowired
    RunService runs;
    @Autowired
    ScenarioCatalog catalog;
    @Autowired
    ScenarioAutopilot autopilot;
    @Autowired
    OrchestratorStore store;
    @Autowired
    AuditService audit;
    @Autowired
    Json json;

    private UUID play(String name) {
        ScenarioConfig scenario = catalog.find(name).orElseThrow();
        Run run = runs.start(scenario.requirement(), scenario.name(), scenario.tokenBudget());
        autopilot.play(run.id(), scenario);
        return run.id();
    }

    private TestReport lastTestReport(UUID id) {
        return store.listArtifacts(id).stream().filter(a -> a.type().equals(Artifacts.TEST_REPORT))
                .reduce((a, b) -> b).map(a -> json.read(a.content(), TestReport.class)).orElseThrow();
    }

    private String workspaceFile(UUID id, String relative) throws IOException {
        return Files.readString(Path.of(store.getRun(id).workspacePath()).resolve(relative));
    }

    private boolean workspaceHas(UUID id, String relative) {
        return Files.exists(Path.of(store.getRun(id).workspacePath()).resolve(relative));
    }

    private long count(UUID id, String eventType) {
        return audit.forRun(id).stream().filter(e -> e.eventType().equals(eventType)).count();
    }

    private void assertCommonOutcome(UUID id) {
        Run run = store.getRun(id);
        assertThat(run.status()).as("run status (%s)", run.statusReason()).isEqualTo(RunStatus.COMPLETED);
        TestReport report = lastTestReport(id);
        assertThat(report.compiled()).isTrue();
        assertThat(report.testsFailed()).isZero();
        assertThat(report.testsRun()).isPositive();
        assertThat(report.lineCoverage()).as("line coverage of the shortener").isGreaterThanOrEqualTo(0.80);
        assertThat(report.policyFindings()).isEmpty();
        assertThat(audit.verify().valid()).as("audit chain").isTrue();
        assertThat(workspaceHas(id, "docs/runs/" + id + "/ENGINEERING_SUMMARY.md")).isTrue();
        List<Approval> approvals = store.listApprovals(id);
        assertThat(approvals).as("every checkpoint was decided").noneMatch(a -> a.status() == ApprovalStatus.PENDING);
        assertThat(approvals).filteredOn(a -> a.status() == ApprovalStatus.APPROVED)
                .allMatch(a -> a.decidedBy().equals(ScenarioAutopilot.ACTOR));
        assertThat(store.listStages(id)).allMatch(s -> s.status().satisfiesDependents());
        String log = new GitTool(Path.of(run.workspacePath())).run("log", "--oneline");
        assertThat(log.lines().count()).as("one commit per accepted stage plus the baseline").isGreaterThan(4);
    }

    @Test
    void greenfield() throws Exception {
        UUID id = play("greenfield");

        assertCommonOutcome(id);
        assertThat(store.getStage(id, "impact_analysis").status()).as("greenfield skips impact analysis").isEqualTo(StageStatus.SKIPPED);
        assertThat(count(id, "STAGE_FALLBACK")).as("the documentation timeout ends in the template fallback").isEqualTo(1);
        assertThat(store.getStage(id, "documentation").agent()).isEqualTo("DOC_TEMPLATE");
        assertThat(workspaceFile(id, "pom.xml")).contains("<groupId>com.google.zxing</groupId>");
        assertThat(workspaceHas(id, "src/main/java/com/agentic/shortener/service/QrCodeService.java")).isTrue();
        assertThat(workspaceHas(id, "src/main/java/com/agentic/shortener/web/QrController.java")).isTrue();
        assertThat(workspaceHas(id, "docs/CHANGELOG.md")).isTrue();
        assertThat(store.listApprovals(id)).extracting(Approval::stageKey).contains("design", "impl:T1", "impl:T3", "release");
        assertThat(store.listApprovals(id).stream().filter(a -> a.stageKey().equals("impl:T1")).findFirst().orElseThrow().reasons())
                .contains("DEPENDENCY_CHANGE");
    }

    @Test
    void brownfield() throws Exception {
        UUID id = play("brownfield");

        assertCommonOutcome(id);
        // Codebase reasoning: impact analysis ran (it is skipped for greenfield) and located the defect.
        assertThat(store.getStage(id, "impact_analysis").status()).isEqualTo(StageStatus.SUCCEEDED);
        String impact = store.listArtifacts(id).stream().filter(a -> a.type().equals(Artifacts.IMPACT_REPORT))
                .findFirst().orElseThrow().content();
        assertThat(impact).contains("ShortenerService").contains("suspectedRootCause");

        // The fix and the feature are in the branch; the defect note and the test that asserted the defect are gone.
        String service = workspaceFile(id, "src/main/java/com/agentic/shortener/service/ShortenerService.java");
        assertThat(service).contains("link.expiresAt() != null").contains("createBatch").doesNotContain("KNOWN DEFECT");
        assertThat(workspaceFile(id, "src/test/java/com/agentic/shortener/ExpiryDefectTest.java"))
                .contains("aLinkExpiringExactlyNowIsGone").doesNotContain("knownDefect");
        assertThat(workspaceFile(id, "src/main/java/com/agentic/shortener/web/CreateRateLimitFilter.java")).contains("/api/v1/urls/bulk");

        // Change control: the attempt to edit the applied V1 migration was blocked, and V3 was added instead.
        assertThat(audit.forRun(id)).anyMatch(e -> e.eventType().equals("POLICY_VIOLATION")
                && e.payload().contains("migrations-append-only"));
        assertThat(workspaceFile(id, "src/main/resources/db/migration/V1__shortener.sql"))
                .isEqualTo(Files.readString(sourceCopy.resolve("src/main/resources/db/migration/V1__shortener.sql")));
        assertThat(workspaceHas(id, "src/main/resources/db/migration/V3__urls_expiry_index.sql")).isTrue();

        // Validation caught the first version of the fix (it broke links without an expiry): roll back, redo, pass.
        assertThat(count(id, "STAGE_REOPENED_FOR_FIX")).isGreaterThanOrEqualTo(1);
        assertThat(count(id, "ROLLBACK")).isGreaterThanOrEqualTo(1);
        assertThat(store.getStage(id, "impl:T1").attempt()).isGreaterThanOrEqualTo(1);

        // Each kind of high-impact change was put to a human.
        assertThat(store.listApprovals(id).stream().map(Approval::reasons).reduce("", (a, b) -> a + " " + b))
                .contains("SCHEMA_CHANGE").contains("API_CHANGE").contains("SECURITY_SENSITIVE_FILE");
        assertThat(store.listApprovals(id)).extracting(Approval::stageKey).contains("design", "impl:T2", "impl:T3", "impl:T4", "release");
    }

    @Test
    void ambiguous() throws Exception {
        UUID id = play("ambiguous");

        assertCommonOutcome(id);
        // Requirement understanding: it asked instead of guessing, and the human's answers re-derived the spec.
        var clarifications = store.listClarifications(id);
        assertThat(clarifications).hasSize(3).allMatch(c -> c.answer() != null)
                .allMatch(c -> c.answeredBy().contains("accepted proposed assumption"));
        assertThat(store.getStage(id, "requirements").generation()).isGreaterThanOrEqualTo(2);

        // Feedback loop: the first design was rejected with a reason and redone narrowly.
        List<Approval> designApprovals = store.listApprovals(id).stream().filter(a -> a.stageKey().equals("design")).toList();
        assertThat(designApprovals).extracting(Approval::status).containsExactly(ApprovalStatus.REJECTED, ApprovalStatus.APPROVED);
        assertThat(designApprovals.getFirst().comment()).contains("Too broad");
        assertThat(workspaceFile(id, "pom.xml")).doesNotContain("guava");
        assertThat(workspaceFile(id, "src/main/java/com/agentic/shortener/service/CreateRateLimiter.java"))
                .isEqualTo(Files.readString(sourceCopy.resolve("src/main/java/com/agentic/shortener/service/CreateRateLimiter.java")));
        assertThat(workspaceFile(id, "src/main/java/com/agentic/shortener/service/UrlValidator.java")).contains("must not contain credentials");

        // Safe-stop and resume: the token surge stopped the run, and the human raised the budget.
        assertThat(count(id, "SAFE_STOP_REQUESTED")).isEqualTo(1);
        assertThat(count(id, "RUN_STOPPED")).isEqualTo(1);
        assertThat(store.listDecisions(id)).anyMatch(d -> d.type().equals("BUDGET_RAISED")
                && d.actor().equals(ScenarioAutopilot.ACTOR));
        assertThat(store.getRun(id).tokenBudget()).isEqualTo(2_000_000L);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static final Set<String> SKIPPED = Set.of(".git", "target", "workspaces", ".idea", ".vscode");

    private static void copyWorkingTree(Path from, Path to) {
        try {
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(from) && SKIPPED.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
