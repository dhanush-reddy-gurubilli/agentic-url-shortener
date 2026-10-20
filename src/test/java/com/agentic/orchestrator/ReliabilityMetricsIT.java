package com.agentic.orchestrator;

import com.agentic.orchestrator.audit.ReliabilityMetrics;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.llm.LlmException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReliabilityMetricsIT extends EngineTest {

    @Autowired
    ReliabilityMetrics metrics;

    @Test
    void metricsReflectRetriesFallbacksAndRecoveryTimeOfARealRun() {
        scripts.happyPath(llm);
        llm.failAlways("documentation", new LlmException.Timeout("model timed out"));
        llm.on("design", 1, "not json");
        llm.on("design", 2, scripts.design(com.agentic.orchestrator.testing.Scripts.create(com.agentic.orchestrator.testing.Scripts.SERVICE),
                com.agentic.orchestrator.testing.Scripts.create(com.agentic.orchestrator.testing.Scripts.HELPER)));

        UUID id = runs.start("Add QR code support for short URLs", null, null).id();
        awaitRun(id, RunStatus.PAUSED);
        runs.approve(pendingApproval(id, "release").id(), "alice", "ok");
        awaitRun(id, RunStatus.COMPLETED);

        ReliabilityMetrics.Report report = metrics.compute();

        assertThat(report.runs()).isEqualTo(1);
        assertThat(report.completed()).isEqualTo(1);
        assertThat(report.runSuccessRate()).isEqualTo(1.0);
        assertThat(report.fallbacks()).isEqualTo(1);
        assertThat(report.retries()).as("one retry for the design, one for the documentation").isEqualTo(2);
        assertThat(report.mttrSamples()).as("design and documentation both recovered").isEqualTo(2);
        assertThat(report.mttrSeconds()).isNotNull().isPositive();
        assertThat(report.stageSuccessRate()).containsEntry("design", 1.0).containsEntry("implementation", 1.0);
        assertThat(report.endToEndLatency().count()).isEqualTo(1);
        assertThat(report.endToEndLatency().p95Seconds()).isPositive();
        assertThat(report.stageLatency()).containsKeys("requirements", "implementation", "validation");
        assertThat(report.avgApprovalWaitSeconds()).isNotNull();
        assertThat(report.tokensUsed()).isPositive();
    }

    @Test
    void policyViolationsAreCountedPerRule() {
        scripts.happyPath(llm);
        String migration = "src/main/resources/db/migration/V1__init.sql";
        llm.on("design", scripts.design(com.agentic.orchestrator.testing.Scripts.create(com.agentic.orchestrator.testing.Scripts.SERVICE),
                com.agentic.orchestrator.testing.Scripts.create(com.agentic.orchestrator.testing.Scripts.HELPER),
                com.agentic.orchestrator.testing.Scripts.modify(migration)));
        llm.on("impl:T1", 1, scripts.patch("bad", com.agentic.orchestrator.testing.Scripts.write(migration, "drop table urls;")));
        llm.on("impl:T1", 2, scripts.patch("ok", com.agentic.orchestrator.testing.Scripts.write(
                com.agentic.orchestrator.testing.Scripts.SERVICE, com.agentic.orchestrator.testing.Scripts.javaClass("QrCodeService"))));

        UUID id = runs.start("Add QR code support for short URLs", null, null).id();
        awaitRun(id, RunStatus.PAUSED);

        ReliabilityMetrics.Report report = metrics.compute();
        assertThat(report.policyViolations()).isGreaterThanOrEqualTo(1);
        assertThat(report.policyViolationsByRule()).containsKey("migrations-append-only");
    }
}
