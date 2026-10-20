package com.agentic.orchestrator;

import com.agentic.orchestrator.artifact.Artifacts.Ambiguity;
import com.agentic.orchestrator.artifact.Artifacts.ChangeType;
import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.Clarification;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.llm.LlmException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.agentic.orchestrator.testing.Scripts.HELPER;
import static com.agentic.orchestrator.testing.Scripts.SERVICE;
import static com.agentic.orchestrator.testing.Scripts.create;
import static com.agentic.orchestrator.testing.Scripts.javaClass;
import static com.agentic.orchestrator.testing.Scripts.modify;
import static com.agentic.orchestrator.testing.Scripts.task;
import static com.agentic.orchestrator.testing.Scripts.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retries, fallback, policy blocks, approvals, clarification, safe-stop, rollback and recovery. */
class ControlPathsTest extends EngineTest {

    private UUID startHappyRun() {
        scripts.happyPath(llm);
        return startScriptedRun();
    }

    private UUID startScriptedRun() {
        return runs.start("Add QR code support for short URLs", null, null).id();
    }

    private void approveRelease(UUID id) {
        Approval release = pendingApproval(id, "release");
        runs.approve(release.id(), "alice", "ship it");
        awaitRun(id, RunStatus.COMPLETED);
    }

    // ---- retries, fallback ---------------------------------------------------------------------------------------

    @Test
    void invalidModelOutputIsRetriedWithTheGateFeedback() {
        scripts.happyPath(llm);
        llm.on("design", 1, "this is not json at all");
        llm.on("design", 2, scripts.design(create(SERVICE), create(HELPER)));

        UUID id = startScriptedRun();
        awaitRun(id, RunStatus.PAUSED);

        assertThat(llm.callsFor("design")).hasSize(2);
        assertThat(llm.callsFor("design").get(1).user()).contains("previous attempt was not accepted").contains("not valid DesignDoc JSON");
        assertThat(stage(id, "design").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(countAudit(id, "STAGE_RETRY")).isEqualTo(1);
        assertThat(countAudit(id, "GATE_FAILED")).isEqualTo(1);
    }

    @Test
    void whenRetriesAreExhaustedTheFallbackAgentTakesOver() {
        scripts.happyPath(llm);
        llm.failAlways("documentation", new LlmException.Timeout("model timed out"));

        UUID id = startScriptedRun();
        awaitRun(id, RunStatus.PAUSED);

        assertThat(countAudit(id, "STAGE_FALLBACK")).isEqualTo(1);
        assertThat(stage(id, "documentation").agent()).isEqualTo("DOC_TEMPLATE");
        assertThat(stage(id, "documentation").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(llm.callsFor("documentation")).hasSize(2);   // max attempts of the primary agent
    }

    @Test
    void aStageThatKeepsFailingFailsTheRun() {
        scripts.happyPath(llm);
        llm.failAlways("requirements", new LlmException("always down"));

        UUID id = startScriptedRun();
        Run failed = awaitRun(id, RunStatus.FAILED);

        assertThat(failed.statusReason()).contains("requirements");
        assertThat(stage(id, "requirements").status()).isEqualTo(StageStatus.FAILED);
        assertThat(llm.callsFor("requirements")).hasSize(3);
        assertThat(stage(id, "design").status()).as("nothing downstream ran").isEqualTo(StageStatus.PENDING);
    }

    // ---- policy --------------------------------------------------------------------------------------------------

    @Test
    void editingAnExistingMigrationIsBlockedAndTheNextAttemptSucceeds() throws Exception {
        scripts.happyPath(llm);
        String migration = "src/main/resources/db/migration/V1__init.sql";
        llm.on("design", scripts.design(create(SERVICE), create(HELPER), modify(migration)));
        llm.on("impl:T1", 1, scripts.patch("bad", write(migration, "drop table urls;"), write(SERVICE, javaClass("QrCodeService"))));
        llm.on("impl:T1", 2, scripts.patch("good", write(SERVICE, javaClass("QrCodeService"))));

        UUID id = startScriptedRun();
        awaitRun(id, RunStatus.PAUSED);

        assertThat(stage(id, "impl:T1").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(stage(id, "impl:T1").attempt()).isEqualTo(2);
        assertThat(workspaceFile(id, migration)).as("migration untouched").isEqualTo("create table urls (id bigint primary key);\n");
        assertThat(store.getRun(id).policyViolations()).isGreaterThanOrEqualTo(1);
        assertThat(audit.forRun(id)).anyMatch(e -> e.eventType().equals("POLICY_VIOLATION")
                && e.payload().contains("migrations-append-only"));
    }

    @Test
    void agentsCannotChangeTheOrchestratorItself() {
        scripts.happyPath(llm);
        String engine = "src/main/java/com/agentic/orchestrator/Engine.java";
        llm.on("design", scripts.design(create(SERVICE), create(HELPER), modify(engine)));
        llm.on("impl:T1", 1, scripts.patch("sneaky", write(engine, "class Engine { /* owned */ }")));
        llm.on("impl:T1", 2, scripts.patch("fine", write(SERVICE, javaClass("QrCodeService"))));

        UUID id = startScriptedRun();
        awaitRun(id, RunStatus.PAUSED);

        assertThat(audit.forRun(id)).anyMatch(e -> e.eventType().equals("POLICY_VIOLATION") && e.payload().contains("protected-paths"));
        assertThat(stage(id, "impl:T1").attempt()).isEqualTo(2);
    }

    @Test
    void aSecretInAPatchIsBlocked() {
        scripts.happyPath(llm);
        llm.on("impl:T1", 1, scripts.patch("leak", write(SERVICE,
                "package x;\nclass QrCodeService { String apiKey = \"AKIAABCDEFGHIJKLMNOP\"; }\n")));
        llm.on("impl:T1", 2, scripts.patch("clean", write(SERVICE, javaClass("QrCodeService"))));

        UUID id = startScriptedRun();
        awaitRun(id, RunStatus.PAUSED);

        assertThat(audit.forRun(id)).anyMatch(e -> e.eventType().equals("POLICY_VIOLATION") && e.payload().contains("no-secrets"));
    }

    @Test
    void aPatchOutsideTheApprovedDesignIsRejectedByTheScopeGate() {
        scripts.happyPath(llm);
        llm.on("impl:T1", 1, scripts.patch("scope creep", write("src/main/java/com/agentic/shortener/service/Extra.java", javaClass("Extra"))));
        llm.on("impl:T1", 2, scripts.patch("in scope", write(SERVICE, javaClass("QrCodeService"))));

        UUID id = startScriptedRun();
        awaitRun(id, RunStatus.PAUSED);

        assertThat(llm.callsFor("impl:T1").get(1).user()).contains("not in the approved design");
    }

    // ---- approvals -----------------------------------------------------------------------------------------------

    @Test
    void rejectingADesignFeedsTheCommentBackAndTheStageIsRedone() {
        scripts.happyPath(llm);
        llm.on("design", 1, scripts.design(create(SERVICE), create(HELPER), modify("pom.xml")));
        llm.on("design", 2, scripts.design(create(SERVICE), create(HELPER)));

        UUID id = startScriptedRun();
        Approval first = pendingApproval(id, "design");
        assertThat(first.reasons()).isEqualTo("DEPENDENCY_CHANGE");
        runs.reject(first.id(), "bob", "We do not need a new dependency for this");

        awaitRun(id, RunStatus.PAUSED);
        assertThat(llm.callsFor("design")).hasSize(2);
        assertThat(llm.callsFor("design").get(1).user()).contains("We do not need a new dependency for this");
        assertThat(store.getApproval(first.id()).status().name()).isEqualTo("REJECTED");
        assertThat(stage(id, "design").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(store.listDecisions(id)).anyMatch(d -> d.actor().equals("bob") && d.type().equals("REJECTED"));
    }

    @Test
    void aDesignRejectedThreeTimesFailsTheRunInsteadOfLoopingForever() {
        scripts.happyPath(llm);
        llm.on("design", scripts.design(create(SERVICE), create(HELPER), modify("pom.xml")));

        UUID id = startScriptedRun();
        long previous = -1;
        for (int round = 1; round <= 3; round++) {
            final long seen = previous;
            final int current = round;
            until(java.time.Duration.ofSeconds(60), () -> store.listApprovals(id).stream()
                            .anyMatch(a -> a.id() > seen && a.status().name().equals("PENDING")),
                    () -> "approval request #" + current + "\n" + describe(id));
            Approval pending = store.listApprovals(id).stream().filter(a -> a.status().name().equals("PENDING")).findFirst().orElseThrow();
            previous = pending.id();
            runs.reject(pending.id(), "bob", "Still no: round " + round);
        }

        Run failed = awaitRun(id, RunStatus.FAILED);
        assertThat(failed.statusReason()).contains("design").contains("failed");
        assertThat(stage(id, "design").error()).contains("Rejected 3 times").contains("round 3");
        assertThat(llm.callsFor("design")).hasSize(3);
    }

    @Test
    void aSchemaChangeNeedsTwoCheckpointsAndNothingIsAppliedBeforeApproval() throws Exception {
        scripts.happyPath(llm);
        String migration = "src/main/resources/db/migration/V2__qr.sql";
        llm.on("design", scripts.design(create(SERVICE), create(HELPER), create(migration)));
        llm.on("planning", scripts.tasks(task("T1", List.of(SERVICE, migration), List.of()), task("T2", List.of(HELPER), List.of())));
        llm.on("impl:T1", scripts.patch("service+migration", write(SERVICE, javaClass("QrCodeService")),
                write(migration, "create table qr_cache (id bigint primary key);\n")));

        UUID id = startScriptedRun();
        Approval design = pendingApproval(id, "design");
        assertThat(design.reasons()).contains("SCHEMA_CHANGE");
        runs.approve(design.id(), "alice", "Schema change is justified");

        Approval impl = pendingApproval(id, "impl:T1");
        assertThat(impl.reasons()).contains("SCHEMA_CHANGE");
        assertThat(impl.details()).contains(migration);
        assertThat(workspaceHas(id, migration)).as("parked, not applied").isFalse();
        runs.approve(impl.id(), "alice", "Migration looks right");

        awaitRun(id, RunStatus.PAUSED);
        assertThat(workspaceFile(id, migration)).contains("qr_cache");
    }

    @Test
    void approvalDecisionsNeedACommentAndCannotBeRepeated() {
        scripts.happyPath(llm);
        UUID id = startScriptedRun();
        Approval release = pendingApproval(id, "release");

        assertThatThrownBy(() -> runs.approve(release.id(), "alice", "  ")).isInstanceOf(IllegalArgumentException.class);
        runs.approve(release.id(), "alice", "ok");
        assertThatThrownBy(() -> runs.approve(release.id(), "alice", "again")).isInstanceOf(IllegalStateException.class);
    }

    // ---- clarification -------------------------------------------------------------------------------------------

    @Test
    void anAmbiguousRequirementPausesForClarificationAndTheAnswersTriggerAReplan() {
        scripts.happyPath(llm);
        List<Ambiguity> questions = List.of(
                new Ambiguity("A1", "Rate limit per IP or per API key?", "Per IP"),
                new Ambiguity("A2", "Which threshold?", "60 requests per minute"));
        llm.on("requirements", 1, scripts.spec(ChangeType.GREENFIELD, 0.8, questions));
        llm.on("requirements", 2, scripts.spec(ChangeType.GREENFIELD, 0.1, List.of()));

        UUID id = startScriptedRun();
        awaitStage(id, "requirements", StageStatus.AWAITING_CLARIFICATION);
        awaitRun(id, RunStatus.PAUSED);
        List<Clarification> open = store.listClarifications(id);
        assertThat(open).hasSize(2).allMatch(c -> c.answer() == null);
        assertThat(stage(id, "design").status()).as("nothing runs on an ambiguous spec").isEqualTo(StageStatus.PENDING);

        runs.answerClarifications(id, "alice", Map.of(open.get(0).id(), "Per API key"), true);

        awaitRun(id, RunStatus.PAUSED);   // now waiting at the release approval
        assertThat(stage(id, "requirements").generation()).isEqualTo(2);
        assertThat(llm.callsFor("requirements").get(1).user()).contains("Per API key").contains("60 requests per minute");
        assertThat(store.listClarifications(id)).allMatch(c -> c.answer() != null);
        assertThat(store.listClarifications(id).get(1).answeredBy()).contains("accepted proposed assumption");
        approveRelease(id);
    }

    // ---- safe-stop and budget ------------------------------------------------------------------------------------

    @Test
    void exceedingTheTokenBudgetSafeStopsAndResumingNeedsMoreBudget() {
        scripts.happyPath(llm);
        UUID id = runs.start("Add QR code support for short URLs", null, 300L).id();

        Run stopped = awaitRun(id, RunStatus.STOPPED);
        assertThat(stopped.statusReason()).contains("token budget exceeded");
        assertThat(stage(id, "requirements").status()).as("in-flight work finished").isEqualTo(StageStatus.SUCCEEDED);
        assertThat(countAudit(id, "SAFE_STOP_REQUESTED")).isEqualTo(1);

        assertThatThrownBy(() -> runs.resume(id, "alice", null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget");

        runs.resume(id, "alice", 500_000L);
        awaitRun(id, RunStatus.PAUSED);
        approveRelease(id);
        assertThat(store.listDecisions(id)).anyMatch(d -> d.type().equals("BUDGET_RAISED") && d.actor().equals("alice"));
    }

    @Test
    void aSafeStopRequestedByAHumanHaltsSchedulingAndKeepsStateResumable() {
        scripts.happyPath(llm);
        llm.on("design", r -> {
            runs.stop(r.runId(), "alice", "hold on");
            return scripts.design(create(SERVICE), create(HELPER));
        });

        UUID id = startScriptedRun();
        awaitRun(id, RunStatus.STOPPED);

        assertThat(stage(id, "design").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(stage(id, "planning").status()).as("nothing new was scheduled").isEqualTo(StageStatus.PENDING);

        runs.resume(id, "alice", null);
        awaitRun(id, RunStatus.PAUSED);
        approveRelease(id);
    }

    // ---- validation loop and rollback ----------------------------------------------------------------------------

    @Test
    void aFailingBuildReopensTheCodeStagesWithTheBuildOutputAsFeedback() throws Exception {
        scripts.happyPath(llm);
        build.behave(ws -> build.calls() == 1
                ? com.agentic.orchestrator.testing.FakeBuildRunner.failing("QrCodeServiceTest.rendersPng expected 200 but was 500")
                : com.agentic.orchestrator.testing.FakeBuildRunner.PASSING);
        llm.on("impl:T1", r -> scripts.patch(r.attempt() == 1 ? "first" : "fixed",
                write(SERVICE, javaClass("QrCodeService") + (r.attempt() == 1 ? "// buggy\n" : "// fixed\n"))));

        UUID id = startScriptedRun();
        awaitRun(id, RunStatus.PAUSED);

        assertThat(build.calls()).isEqualTo(2);
        assertThat(countAudit(id, "STAGE_REOPENED_FOR_FIX")).isEqualTo(1);
        assertThat(countAudit(id, "ROLLBACK")).isGreaterThanOrEqualTo(1);
        assertThat(llm.callsFor("impl:T1")).hasSize(2);
        assertThat(llm.callsFor("impl:T1").get(1).user()).contains("Validation").contains("expected 200 but was 500");
        assertThat(workspaceFile(id, SERVICE)).contains("// fixed").doesNotContain("// buggy");
        assertThat(stage(id, "validation").status()).isEqualTo(StageStatus.SUCCEEDED);
        approveRelease(id);
    }

    @Test
    void validationThatNeverPassesFailsTheRunAfterBoundedAttempts() {
        scripts.happyPath(llm);
        build.behave(ws -> com.agentic.orchestrator.testing.FakeBuildRunner.failing("QrCodeServiceTest always fails"));

        UUID id = startScriptedRun();
        Run failed = awaitRun(id, RunStatus.FAILED);

        assertThat(failed.statusReason()).contains("validation");
        assertThat(build.calls()).isEqualTo(3);
        assertThat(countAudit(id, "STAGE_REOPENED_FOR_FIX")).isEqualTo(2);
    }

    @Test
    void rollingBackToAStageUndoesItsCommitsAndRerunsEverythingDownstream() {
        UUID id = startHappyRun();
        awaitRun(id, RunStatus.PAUSED);
        assertThat(workspaceHas(id, SERVICE)).isTrue();

        runs.rollback(id, "planning", "alice", "plan again");

        awaitRun(id, RunStatus.PAUSED);
        assertThat(llm.callsFor("planning")).hasSize(2);
        assertThat(llm.callsFor("impl:T1")).hasSize(2);
        assertThat(llm.callsFor("impl:T2")).hasSize(2);
        assertThat(workspaceHas(id, SERVICE)).as("re-applied after the rollback").isTrue();
        assertThat(countAudit(id, "ROLLBACK")).isGreaterThanOrEqualTo(1);
        approveRelease(id);
    }

    // ---- dynamic re-planning -------------------------------------------------------------------------------------

    @Test
    void aChangedPlanReopensOnlyTheAffectedTasks() throws Exception {
        String extra = "src/main/java/com/agentic/shortener/service/QrExtra.java";
        scripts.happyPath(llm);
        llm.on("design", scripts.design(create(SERVICE), create(HELPER), create(extra)));
        llm.on("test_generation", scripts.patch("tests", write(com.agentic.orchestrator.testing.Scripts.TEST,
                com.agentic.orchestrator.testing.Scripts.testFor("QrCodeService", "QrSizes", "QrExtra"))));
        llm.on("planning", 1, scripts.tasks(task("T1", List.of(SERVICE), List.of()), task("T2", List.of(HELPER), List.of("T1"))));
        llm.on("planning", 2, scripts.tasks(task("T1", List.of(SERVICE), List.of()), task("T3", List.of(extra), List.of("T1"))));
        llm.on("impl:T3", scripts.patch("extra", write(extra, javaClass("QrExtra"))));

        UUID id = startScriptedRun();
        pendingApproval(id, "release");
        assertThat(workspaceHas(id, HELPER)).isTrue();

        runs.revise(id, "planning", "alice", "Drop T2 and add T3 instead");
        until(java.time.Duration.ofSeconds(60), () -> statusOrNull(id, "impl:T3") == StageStatus.SUCCEEDED
                && stage(id, "validation").status() == StageStatus.SUCCEEDED && stage(id, "planning").generation() == 2,
                () -> "re-plan to settle\n" + describe(id));

        assertThat(llm.callsFor("impl:T1")).as("unchanged task is not redone").hasSize(1);
        assertThat(stage(id, "impl:T2").status()).isEqualTo(StageStatus.SKIPPED);
        assertThat(stage(id, "impl:T2").error()).contains("removed by re-plan");
        assertThat(stage(id, "validation").dependsOn()).contains("impl:T1", "impl:T3").doesNotContain("impl:T2");
        assertThat(workspaceHas(id, HELPER)).as("the removed task's commit was rolled back").isFalse();
        assertThat(workspaceHas(id, extra)).isTrue();
        assertThat(workspaceHas(id, SERVICE)).isTrue();
        assertThat(audit.forRun(id)).anyMatch(e -> e.eventType().equals("TASK_REMOVED"))
                .anyMatch(e -> e.eventType().equals("TASK_ADDED") && e.stageKey().equals("impl:T3"));
        assertThat(store.listDecisions(id)).anyMatch(d -> d.type().equals("REPLANNED"));
        approveRelease(id);
    }

    @Test
    void anEditedRequirementIsReDerivedAndDownstreamFollowsOnlyIfItsInputsChanged() {
        UUID id = startHappyRun();
        pendingApproval(id, "release");

        // Same spec comes back: nothing downstream should be recomputed.
        runs.updateRequirement(id, "alice", "Add QR code support for short URLs, PNG only");
        until(java.time.Duration.ofSeconds(60), () -> stage(id, "requirements").generation() == 2
                && stage(id, "requirements").status() == StageStatus.SUCCEEDED, () -> "requirements rerun\n" + describe(id));

        assertThat(llm.callsFor("requirements")).hasSize(2);
        assertThat(llm.callsFor("requirements").get(1).user()).contains("PNG only");
        assertThat(llm.callsFor("design")).as("identical spec hash: design is kept").hasSize(1);
        assertThat(llm.callsFor("planning")).hasSize(1);
        approveRelease(id);
    }

    // ---- crash recovery ------------------------------------------------------------------------------------------

    @Test
    void stagesInterruptedByACrashAreRetriedWhenTheEngineRecovers() {
        scripts.happyPath(llm);
        // Build the state a crash leaves behind: a RUNNING run whose requirements stage was mid-flight.
        UUID id = engine.start("Add QR code support for short URLs", null, 1_000_000L).id();
        awaitRun(id, RunStatus.PAUSED);   // let it run to the release approval, then simulate the crash state
        var running = store.getStage(id, "documentation");
        store.saveStage(com.agentic.orchestrator.domain.StageEdit.of(running).status(StageStatus.RUNNING).build());
        store.setRunStatus(id, RunStatus.RUNNING, null);

        engine.recover();

        awaitRun(id, RunStatus.PAUSED);
        assertThat(countAudit(id, "STAGE_INTERRUPTED")).isEqualTo(1);
        assertThat(stage(id, "documentation").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(llm.callsFor("documentation")).as("the lost stage was run again").hasSize(2);
        assertThat(audit.verify().valid()).isTrue();
    }
}
