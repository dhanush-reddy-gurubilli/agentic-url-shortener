package com.agentic.orchestrator.audit;

import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.AuditEvent;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.StageStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reliability metrics computed from the persisted run, stage and audit data (so they survive restarts and match the
 * audit trail exactly): success rates, retry / fallback / rollback frequency, MTTR, and latency.
 */
@Service
public class ReliabilityMetrics {

    private static final Pattern VIOLATION_RULE = Pattern.compile("^\\[([\\w-]+)/([\\w-]+)]");

    private final OrchestratorStore store;
    private final AuditService audit;
    private final JdbcClient jdbc;

    public ReliabilityMetrics(OrchestratorStore store, AuditService audit, JdbcClient jdbc) {
        this.store = store;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public record Latency(long count, Double avgSeconds, Double p50Seconds, Double p95Seconds) {
    }

    public record Report(
            long runs, long completed, long failed, long stopped, long active, Double runSuccessRate,
            Map<String, Double> stageSuccessRate, Double overallStageSuccessRate,
            long retries, long fallbacks, long rollbacks, long fixLoops,
            Double retriesPerRun, Double rollbacksPerRun,
            long mttrSamples, Double mttrSeconds,
            Latency endToEndLatency, Map<String, Latency> stageLatency,
            Double avgApprovalWaitSeconds, long policyViolations, Map<String, Long> policyViolationsByRule,
            long tokensUsed) {
    }

    public Report compute() {
        List<Run> runs = store.listRuns();
        List<AuditEvent> events = audit.all();
        long completed = count(runs, RunStatus.COMPLETED);
        long failed = count(runs, RunStatus.FAILED);
        long stopped = count(runs, RunStatus.STOPPED);
        long active = runs.size() - completed - failed - stopped;
        Double runSuccess = ratio(completed, completed + failed + stopped);

        Map<String, long[]> perStage = new LinkedHashMap<>();
        Map<String, List<Double>> stageDurations = new LinkedHashMap<>();
        for (Run run : runs) {
            for (Stage s : store.listStages(run.id())) {
                String group = group(s.stageKey());
                if (s.status() == StageStatus.SUCCEEDED || s.status() == StageStatus.FAILED) {
                    long[] counts = perStage.computeIfAbsent(group, k -> new long[2]);
                    counts[s.status() == StageStatus.SUCCEEDED ? 0 : 1]++;
                }
                if (s.status() == StageStatus.SUCCEEDED && s.startedAt() != null && s.finishedAt() != null) {
                    stageDurations.computeIfAbsent(group, k -> new ArrayList<>())
                            .add(seconds(s.startedAt(), s.finishedAt()));
                }
            }
        }
        Map<String, Double> stageSuccess = new LinkedHashMap<>();
        long succeeded = 0;
        long attempted = 0;
        for (var e : perStage.entrySet()) {
            stageSuccess.put(e.getKey(), ratio(e.getValue()[0], e.getValue()[0] + e.getValue()[1]));
            succeeded += e.getValue()[0];
            attempted += e.getValue()[0] + e.getValue()[1];
        }

        long retries = countType(events, "STAGE_RETRY");
        long fallbacks = countType(events, "STAGE_FALLBACK");
        long rollbacks = countType(events, "ROLLBACK");
        long fixLoops = countType(events, "STAGE_REOPENED_FOR_FIX");

        List<Double> mttr = mttrSamples(events);
        List<Double> endToEnd = runs.stream().filter(r -> r.status() == RunStatus.COMPLETED && r.completedAt() != null)
                .map(r -> seconds(r.createdAt(), r.completedAt())).toList();
        Map<String, Latency> stageLatency = new LinkedHashMap<>();
        stageDurations.forEach((k, v) -> stageLatency.put(k, latency(v)));

        Double approvalWait = jdbc.sql("""
                select avg(extract(epoch from (decided_at - created_at))) from approval_request
                where decided_at is not null and status in ('APPROVED', 'REJECTED')""")
                .query(Double.class).optional().orElse(null);
        long violations = runs.stream().mapToLong(Run::policyViolations).sum();
        Map<String, Long> byRule = new HashMap<>();
        for (AuditEvent e : events) {
            if ("POLICY_VIOLATION".equals(e.eventType()) && e.payload() != null) {
                Matcher m = VIOLATION_RULE.matcher(e.payload());
                byRule.merge(m.find() ? m.group(2) : "other", 1L, Long::sum);
            }
        }
        long tokens = runs.stream().mapToLong(Run::tokensUsed).sum();
        return new Report(runs.size(), completed, failed, stopped, active, runSuccess,
                stageSuccess, ratio(succeeded, attempted), retries, fallbacks, rollbacks, fixLoops,
                runs.isEmpty() ? null : (double) retries / runs.size(),
                runs.isEmpty() ? null : (double) rollbacks / runs.size(),
                mttr.size(), mttr.isEmpty() ? null : avg(mttr), latency(endToEnd), stageLatency,
                approvalWait, violations, byRule, tokens);
    }

    /**
     * MTTR sample = time from a stage's first failed attempt to its eventual success, per (run, stage), measured on
     * the audit timestamps.
     */
    static List<Double> mttrSamples(List<AuditEvent> events) {
        Map<String, Instant> firstFailure = new HashMap<>();
        List<Double> samples = new ArrayList<>();
        for (AuditEvent e : events) {
            if (e.runId() == null || e.stageKey() == null) {
                continue;
            }
            String key = e.runId() + "/" + e.stageKey();
            if ("STAGE_ATTEMPT_FAILED".equals(e.eventType())) {
                firstFailure.putIfAbsent(key, e.createdAt());
            } else if ("STAGE_SUCCEEDED".equals(e.eventType()) && firstFailure.containsKey(key)) {
                samples.add(seconds(firstFailure.remove(key), e.createdAt()));
            }
        }
        return samples;
    }

    private static String group(String stageKey) {
        return stageKey.startsWith("impl:") ? "implementation" : stageKey;
    }

    private static long count(List<Run> runs, RunStatus status) {
        return runs.stream().filter(r -> r.status() == status).count();
    }

    private static long countType(List<AuditEvent> events, String type) {
        return events.stream().filter(e -> type.equals(e.eventType())).count();
    }

    private static Double ratio(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }

    private static double seconds(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / 1000.0;
    }

    private static Double avg(List<Double> values) {
        OptionalDouble average = values.stream().mapToDouble(Double::doubleValue).average();
        return average.isPresent() ? average.getAsDouble() : null;
    }

    static Latency latency(List<Double> values) {
        if (values.isEmpty()) {
            return new Latency(0, null, null, null);
        }
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return new Latency(sorted.size(), avg(sorted), percentile(sorted, 0.50), percentile(sorted, 0.95));
    }

    /** Nearest-rank percentile over a sorted list. */
    static double percentile(List<Double> sorted, double p) {
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
