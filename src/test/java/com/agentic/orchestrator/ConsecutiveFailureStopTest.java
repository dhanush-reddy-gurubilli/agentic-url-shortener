package com.agentic.orchestrator;

import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;
import com.agentic.orchestrator.llm.LlmException;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Repeated model failures are an outage, not a bug in one stage: the run safe-stops instead of burning attempts. */
@TestPropertySource(properties = "orchestrator.max-consecutive-llm-failures=2")
class ConsecutiveFailureStopTest extends EngineTest {

    @Test
    void consecutiveModelFailuresSafeStopTheRunAndItCanBeResumedOnceTheModelIsBack() {
        scripts.happyPath(llm);
        llm.failAlways("requirements", new LlmException("model is down"));

        UUID id = runs.start("Add QR code support for short URLs", null, null).id();
        Run stopped = awaitRun(id, RunStatus.STOPPED);

        assertThat(stopped.statusReason()).contains("2 consecutive model failures");
        assertThat(stage(id, "requirements").status()).as("not failed: it can be retried after the outage").isEqualTo(StageStatus.PENDING);
        assertThat(llm.callsFor("requirements")).as("stopped before using all three attempts").hasSize(2);
        assertThat(countAudit(id, "SAFE_STOP_REQUESTED")).isEqualTo(1);

        scripts.happyPath(llm);   // the model is back
        runs.resume(id, "alice", null);
        awaitRun(id, RunStatus.PAUSED);   // now at the release approval
        assertThat(stage(id, "requirements").status()).isEqualTo(StageStatus.SUCCEEDED);
    }

    @Test
    void failuresBelowTheThresholdOnlyRetry() {
        scripts.happyPath(llm);
        llm.failOn("requirements", 1, new LlmException("blip"));

        UUID id = runs.start("Add QR code support for short URLs", null, null).id();
        awaitRun(id, RunStatus.PAUSED);

        assertThat(countAudit(id, "SAFE_STOP_REQUESTED")).isZero();
        assertThat(List.of(llm.callsFor("requirements").size())).containsExactly(2);
    }
}
