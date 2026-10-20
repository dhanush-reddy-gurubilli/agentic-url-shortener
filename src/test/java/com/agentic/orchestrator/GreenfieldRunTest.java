package com.agentic.orchestrator;

import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static com.agentic.orchestrator.testing.Scripts.HELPER;
import static com.agentic.orchestrator.testing.Scripts.SERVICE;
import static com.agentic.orchestrator.testing.Scripts.javaClass;
import static org.assertj.core.api.Assertions.assertThat;

class GreenfieldRunTest extends EngineTest {

    @Test
    void completesWithParallelImplementationAndAHumanApprovedRelease() throws Exception {
        scripts.happyPath(llm);
        // Both implementation tasks must be running at the same moment, or the barrier times out and the run fails.
        CyclicBarrier bothRunning = new CyclicBarrier(2);
        llm.on("impl:T1", r -> {
            await(bothRunning);
            return scripts.patch("service", com.agentic.orchestrator.testing.Scripts.write(SERVICE, javaClass("QrCodeService")));
        });
        llm.on("impl:T2", r -> {
            await(bothRunning);
            return scripts.patch("helper", com.agentic.orchestrator.testing.Scripts.write(HELPER, javaClass("QrSizes")));
        });

        Run started = runs.start("Add QR code support for short URLs", null, null);
        UUID id = started.id();

        Run paused = awaitRun(id, RunStatus.PAUSED);
        assertThat(paused.statusReason()).contains("release");
        assertThat(stage(id, "impact_analysis").status()).as("greenfield skips impact analysis").isEqualTo(StageStatus.SKIPPED);
        for (String key : new String[]{"requirements", "design", "planning", "impl:T1", "impl:T2", "test_generation",
                "documentation", "validation", "release_readiness"}) {
            assertThat(stage(id, key).status()).as(key).isEqualTo(StageStatus.SUCCEEDED);
        }
        assertThat(stage(id, "validation").dependsOn()).as("validation joins the dynamic stages")
                .contains("impl:T1", "impl:T2", "test_generation", "documentation");

        Approval release = pendingApproval(id, "release");
        assertThat(release.reasons()).isEqualTo("RELEASE");
        assertThat(workspaceHas(id, "docs/runs/" + id + "/ENGINEERING_SUMMARY.md")).as("not applied before approval").isFalse();

        runs.approve(release.id(), "alice", "Reviewed the summary");
        awaitRun(id, RunStatus.COMPLETED);

        assertThat(workspaceFile(id, "docs/runs/" + id + "/ENGINEERING_SUMMARY.md")).contains("GO").contains("Decision log");
        assertThat(workspaceFile(id, SERVICE)).contains("QrCodeService");
        assertThat(audit.verify().valid()).isTrue();
        assertThat(store.listDecisions(id)).anyMatch(d -> d.actor().equals("alice") && d.type().equals("APPROVED"));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The implementation stages did not run in parallel", e);
        }
    }
}
