package com.agentic.orchestrator.audit;

import com.agentic.orchestrator.domain.Records.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReliabilityMetricsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static AuditEvent event(UUID run, String stage, String type, long secondsAfterT0) {
        return new AuditEvent(0, run, stage, "x", type, null, T0.plusSeconds(secondsAfterT0), "p", "h");
    }

    @Test
    void mttrIsTheTimeFromTheFirstFailureToTheEventualSuccess() {
        UUID run = UUID.randomUUID();
        List<Double> samples = ReliabilityMetrics.mttrSamples(List.of(
                event(run, "design", "STAGE_ATTEMPT_FAILED", 0),
                event(run, "design", "STAGE_ATTEMPT_FAILED", 4),
                event(run, "design", "STAGE_SUCCEEDED", 10)));
        assertThat(samples).containsExactly(10.0);
    }

    @Test
    void aStageThatNeverFailedContributesNoSample() {
        UUID run = UUID.randomUUID();
        assertThat(ReliabilityMetrics.mttrSamples(List.of(event(run, "design", "STAGE_SUCCEEDED", 5)))).isEmpty();
    }

    @Test
    void aStageThatFailedAndNeverRecoveredIsNotCountedAsRecovered() {
        UUID run = UUID.randomUUID();
        assertThat(ReliabilityMetrics.mttrSamples(List.of(event(run, "design", "STAGE_ATTEMPT_FAILED", 0)))).isEmpty();
    }

    @Test
    void sameNamedStagesInDifferentRunsAreMeasuredSeparately() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Double> samples = ReliabilityMetrics.mttrSamples(List.of(
                event(a, "design", "STAGE_ATTEMPT_FAILED", 0),
                event(b, "design", "STAGE_ATTEMPT_FAILED", 2),
                event(b, "design", "STAGE_SUCCEEDED", 5),
                event(a, "design", "STAGE_SUCCEEDED", 20)));
        assertThat(samples).containsExactlyInAnyOrder(3.0, 20.0);
    }

    @Test
    void aSecondFailureAfterRecoveryStartsANewMeasurement() {
        UUID run = UUID.randomUUID();
        List<Double> samples = ReliabilityMetrics.mttrSamples(List.of(
                event(run, "validation", "STAGE_ATTEMPT_FAILED", 0),
                event(run, "validation", "STAGE_SUCCEEDED", 6),
                event(run, "validation", "STAGE_ATTEMPT_FAILED", 100),
                event(run, "validation", "STAGE_SUCCEEDED", 109)));
        assertThat(samples).containsExactly(6.0, 9.0);
    }

    @Test
    void percentilesUseTheNearestRank() {
        List<Double> ten = IntStream.rangeClosed(1, 10).mapToObj(Double::valueOf).toList();
        assertThat(ReliabilityMetrics.percentile(ten, 0.50)).isEqualTo(5.0);
        assertThat(ReliabilityMetrics.percentile(ten, 0.95)).isEqualTo(10.0);
        assertThat(ReliabilityMetrics.percentile(List.of(7.0), 0.95)).isEqualTo(7.0);
    }

    @Test
    void latencySummarisesCountAverageAndPercentiles() {
        ReliabilityMetrics.Latency latency = ReliabilityMetrics.latency(List.of(4.0, 1.0, 3.0, 2.0));
        assertThat(latency.count()).isEqualTo(4);
        assertThat(latency.avgSeconds()).isCloseTo(2.5, within(1e-9));
        assertThat(latency.p50Seconds()).isEqualTo(2.0);
        assertThat(latency.p95Seconds()).isEqualTo(4.0);
        assertThat(ReliabilityMetrics.latency(List.of()).count()).isZero();
        assertThat(ReliabilityMetrics.latency(List.of()).avgSeconds()).isNull();
    }
}
