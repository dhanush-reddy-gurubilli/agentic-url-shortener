package com.agentic.orchestrator;

import com.agentic.IntegrationTest;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.ApprovalStatus;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.engine.RunService;
import com.agentic.orchestrator.engine.WorkflowEngine;
import com.agentic.orchestrator.testing.FakeBuildRunner;
import com.agentic.orchestrator.testing.FixtureRepo;
import com.agentic.orchestrator.testing.ScriptedLlm;
import com.agentic.orchestrator.testing.Scripts;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Base for engine tests: the real engine, gates, policies, store and git workspaces, with a scripted model and a
 * fake build in place of the LLM and Maven.
 */
@Import(EngineTest.Doubles.class)
public abstract class EngineTest extends IntegrationTest {

    @TestConfiguration
    static class Doubles {
        @Bean
        @Primary
        ScriptedLlm scriptedLlm() {
            return new ScriptedLlm();
        }

        @Bean
        @Primary
        FakeBuildRunner fakeBuildRunner() {
            return new FakeBuildRunner();
        }
    }

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("orchestrator.source-root", () -> FixtureRepo.path().toString());
    }

    @Autowired
    protected ScriptedLlm llm;
    @Autowired
    protected FakeBuildRunner build;
    @Autowired
    protected RunService runs;
    @Autowired
    protected OrchestratorStore store;
    @Autowired
    protected AuditService audit;
    @Autowired
    protected WorkflowEngine engine;
    @Autowired
    protected Json json;

    protected Scripts scripts;

    @BeforeEach
    void resetDoubles() {
        llm.reset();
        build.reset();
        scripts = new Scripts(json);
    }

    protected Run awaitRun(UUID id, RunStatus... statuses) {
        Set<RunStatus> wanted = Set.of(statuses);
        until(Duration.ofSeconds(60), () -> wanted.contains(store.getRun(id).status()),
                () -> "run to reach " + wanted + " but it is " + store.getRun(id).status() + " ("
                        + store.getRun(id).statusReason() + ")\n" + describe(id));
        return store.getRun(id);
    }

    protected Stage awaitStage(UUID id, String key, StageStatus... statuses) {
        Set<StageStatus> wanted = Set.of(statuses);
        until(Duration.ofSeconds(60), () -> wanted.contains(store.getStage(id, key).status()),
                () -> "stage " + key + " to reach " + wanted + " but it is " + store.getStage(id, key).status()
                        + "\n" + describe(id));
        return store.getStage(id, key);
    }

    protected Approval pendingApproval(UUID id, String stageKey) {
        until(Duration.ofSeconds(60), () -> pending(id, stageKey) != null,
                () -> "an approval request for " + stageKey + "\n" + describe(id));
        return pending(id, stageKey);
    }

    private Approval pending(UUID id, String stageKey) {
        return store.listApprovals(id).stream()
                .filter(a -> a.status() == ApprovalStatus.PENDING && a.stageKey().equals(stageKey)).findFirst().orElse(null);
    }

    protected Stage stage(UUID id, String key) {
        return store.getStage(id, key);
    }

    /** Status of a stage that may not exist yet (dynamic stages appear when the plan is accepted). */
    protected StageStatus statusOrNull(UUID id, String key) {
        return store.listStages(id).stream().filter(s -> s.stageKey().equals(key)).map(Stage::status).findFirst().orElse(null);
    }

    protected String workspaceFile(UUID id, String relative) throws Exception {
        return Files.readString(Path.of(store.getRun(id).workspacePath()).resolve(relative));
    }

    protected boolean workspaceHas(UUID id, String relative) {
        return Files.exists(Path.of(store.getRun(id).workspacePath()).resolve(relative));
    }

    protected List<String> auditTypes(UUID id) {
        return audit.forRun(id).stream().map(e -> e.eventType()).toList();
    }

    protected long countAudit(UUID id, String type) {
        return auditTypes(id).stream().filter(type::equals).count();
    }

    protected static void until(Duration timeout, BooleanSupplier condition, java.util.function.Supplier<String> description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out waiting for " + description.get());
    }

    /** Readable dump of a run for assertion failures. */
    protected String describe(UUID id) {
        StringBuilder sb = new StringBuilder();
        for (Stage s : store.listStages(id)) {
            sb.append(String.format("  %-18s %-22s attempt=%d gen=%d %s%n", s.stageKey(), s.status(), s.attempt(),
                    s.generation(), s.error() == null ? "" : "error=" + s.error()));
        }
        audit.forRun(id).forEach(e -> sb.append("  [").append(e.eventType()).append("] ").append(e.stageKey())
                .append(" ").append(e.payload()).append('\n'));
        return sb.toString();
    }
}
