package com.agentic.orchestrator.domain;

import com.agentic.orchestrator.domain.Records.Approval;
import com.agentic.orchestrator.domain.Records.ApprovalStatus;
import com.agentic.orchestrator.domain.Records.Artifact;
import com.agentic.orchestrator.domain.Records.Clarification;
import com.agentic.orchestrator.domain.Records.Decision;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.Records.Stage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All SQL for the orchestrator tables. Plain JDBC keeps every state change explicit and auditable. */
@Repository
public class OrchestratorStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public OrchestratorStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Instant now() {
        // Postgres stores microseconds; truncating keeps values (and audit hashes) stable across a round trip.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // ---- runs ----------------------------------------------------------------------------------------------

    private static final RowMapper<Run> RUN = (rs, i) -> new Run(
            rs.getObject("id", UUID.class), rs.getString("scenario"), rs.getString("requirement"),
            RunStatus.valueOf(rs.getString("status")), rs.getLong("token_budget"), rs.getLong("tokens_used"),
            rs.getInt("policy_violations"), rs.getBoolean("stop_requested"), rs.getString("status_reason"),
            rs.getString("workspace_path"), rs.getString("baseline_sha"), instant(rs, "created_at"),
            instant(rs, "updated_at"), instant(rs, "completed_at"));

    public Run insertRun(String scenario, String requirement, long tokenBudget) {
        UUID id = UUID.randomUUID();
        Instant now = now();
        jdbc.sql("""
                        insert into workflow_run (id, scenario, requirement, status, token_budget, created_at, updated_at)
                        values (:id, :scenario, :requirement, 'CREATED', :budget, :now, :now)""")
                .param("id", id).param("scenario", scenario).param("requirement", requirement)
                .param("budget", tokenBudget).param("now", ts(now)).update();
        return getRun(id);
    }

    public Run getRun(UUID id) {
        return findRun(id).orElseThrow(() -> new NotFoundException("Unknown run " + id));
    }

    public Optional<Run> findRun(UUID id) {
        return jdbc.sql("select * from workflow_run where id = :id").param("id", id).query(RUN).optional();
    }

    public List<Run> listRuns() {
        return jdbc.sql("select * from workflow_run order by created_at desc").query(RUN).list();
    }

    public List<Run> runsWithStatus(RunStatus status) {
        return jdbc.sql("select * from workflow_run where status = :s").param("s", status.name()).query(RUN).list();
    }

    public void setRunStatus(UUID id, RunStatus status, String reason) {
        Run run = getRun(id);
        if (run.status() != status && !run.status().canTransitionTo(status)) {
            throw new IllegalStateException("Illegal run transition " + run.status() + " -> " + status);
        }
        Instant now = now();
        jdbc.sql("""
                        update workflow_run set status = :s, status_reason = :r, updated_at = :now,
                               completed_at = :completed, version = version + 1 where id = :id""")
                .param("s", status.name()).param("r", reason).param("now", ts(now))
                .param("completed", ts(status.isTerminal() ? now : null)).param("id", id).update();
    }

    public void setStopRequested(UUID id, boolean value) {
        jdbc.sql("update workflow_run set stop_requested = :v, updated_at = :now where id = :id")
                .param("v", value).param("now", ts(now())).param("id", id).update();
    }

    /** Raises the stop flag and keeps the reason on the run, so the STOPPED status explains itself. */
    public void requestStop(UUID id, String reason) {
        jdbc.sql("update workflow_run set stop_requested = true, status_reason = :r, updated_at = :now where id = :id")
                .param("r", reason).param("now", ts(now())).param("id", id).update();
    }

    public void setWorkspace(UUID id, String path, String baselineSha) {
        jdbc.sql("update workflow_run set workspace_path = :p, baseline_sha = :b, updated_at = :now where id = :id")
                .param("p", path).param("b", baselineSha).param("now", ts(now())).param("id", id).update();
    }

    public void setRequirement(UUID id, String requirement) {
        jdbc.sql("update workflow_run set requirement = :r, updated_at = :now where id = :id")
                .param("r", requirement).param("now", ts(now())).param("id", id).update();
    }

    public void setTokenBudget(UUID id, long budget) {
        jdbc.sql("update workflow_run set token_budget = :b, updated_at = :now where id = :id")
                .param("b", budget).param("now", ts(now())).param("id", id).update();
    }

    /** Atomically adds to the token counter and returns the new total. */
    public long addTokens(UUID id, long tokens) {
        return jdbc.sql("update workflow_run set tokens_used = tokens_used + :t where id = :id returning tokens_used")
                .param("t", tokens).param("id", id).query(Long.class).single();
    }

    public int addPolicyViolations(UUID id, int count) {
        return jdbc.sql("""
                        update workflow_run set policy_violations = policy_violations + :c where id = :id
                        returning policy_violations""")
                .param("c", count).param("id", id).query(Integer.class).single();
    }

    // ---- stages --------------------------------------------------------------------------------------------

    private static final RowMapper<Stage> STAGE = (rs, i) -> new Stage(
            rs.getLong("id"), rs.getObject("run_id", UUID.class), rs.getString("stage_key"), rs.getString("agent"),
            StageStatus.valueOf(rs.getString("status")), splitList(rs.getString("depends_on")),
            rs.getBoolean("dynamic_stage"), rs.getString("task_json"), rs.getInt("generation"), rs.getInt("attempt"),
            rs.getString("input_hashes"), (Long) rs.getObject("output_artifact_id"),
            (Long) rs.getObject("pending_artifact_id"), rs.getString("feedback"), rs.getString("error"),
            rs.getString("checkpoint_sha"), (Integer) rs.getObject("apply_order"), instant(rs, "started_at"),
            instant(rs, "finished_at"));

    static List<String> splitList(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.asList(value.split(","));
    }

    public Stage insertStage(UUID runId, String key, String agent, List<String> dependsOn, boolean dynamic,
                             String taskJson) {
        jdbc.sql("""
                        insert into stage_execution (run_id, stage_key, agent, status, depends_on, dynamic_stage, task_json)
                        values (:run, :key, :agent, 'PENDING', :deps, :dyn, :task)""")
                .param("run", runId).param("key", key).param("agent", agent)
                .param("deps", String.join(",", dependsOn)).param("dyn", dynamic).param("task", taskJson).update();
        return getStage(runId, key);
    }

    public Stage getStage(UUID runId, String key) {
        return jdbc.sql("select * from stage_execution where run_id = :r and stage_key = :k")
                .param("r", runId).param("k", key).query(STAGE).optional()
                .orElseThrow(() -> new NotFoundException("Unknown stage " + key + " in run " + runId));
    }

    public List<Stage> listStages(UUID runId) {
        return jdbc.sql("select * from stage_execution where run_id = :r order by id").param("r", runId)
                .query(STAGE).list();
    }

    /** Persists every mutable column of the stage; the transition table is enforced by callers via {@link #transition}. */
    public void saveStage(Stage s) {
        jdbc.sql("""
                        update stage_execution set agent = :agent, status = :status, depends_on = :deps, task_json = :task,
                               generation = :gen, attempt = :attempt, input_hashes = :hashes,
                               output_artifact_id = :out, pending_artifact_id = :pending, feedback = :feedback,
                               error = :error, checkpoint_sha = :sha, apply_order = :ord, started_at = :started,
                               finished_at = :finished, version = version + 1
                        where id = :id""")
                .param("agent", s.agent()).param("status", s.status().name())
                .param("deps", String.join(",", s.dependsOn())).param("task", s.taskJson())
                .param("gen", s.generation()).param("attempt", s.attempt()).param("hashes", s.inputHashes())
                .param("out", s.outputArtifactId()).param("pending", s.pendingArtifactId())
                .param("feedback", s.feedback()).param("error", s.error()).param("sha", s.checkpointSha())
                .param("ord", s.applyOrder()).param("started", ts(s.startedAt())).param("finished", ts(s.finishedAt()))
                .param("id", s.id()).update();
    }

    /** Checks the stage transition table, then saves. */
    public Stage transition(Stage stage, StageStatus next) {
        if (stage.status() != next && !stage.status().canTransitionTo(next)) {
            throw new IllegalStateException("Illegal stage transition " + stage.status() + " -> " + next
                    + " for stage " + stage.stageKey());
        }
        Stage updated = withStatus(stage, next);
        saveStage(updated);
        return updated;
    }

    private static Stage withStatus(Stage s, StageStatus status) {
        return new Stage(s.id(), s.runId(), s.stageKey(), s.agent(), status, s.dependsOn(), s.dynamicStage(),
                s.taskJson(), s.generation(), s.attempt(), s.inputHashes(), s.outputArtifactId(),
                s.pendingArtifactId(), s.feedback(), s.error(), s.checkpointSha(), s.applyOrder(), s.startedAt(),
                s.finishedAt());
    }

    public int nextApplyOrder(UUID runId) {
        return jdbc.sql("select coalesce(max(apply_order), 0) + 1 from stage_execution where run_id = :r")
                .param("r", runId).query(Integer.class).single();
    }

    // ---- artifacts -----------------------------------------------------------------------------------------

    private static final RowMapper<Artifact> ARTIFACT = (rs, i) -> new Artifact(
            rs.getLong("id"), rs.getObject("run_id", UUID.class), rs.getString("stage_key"), rs.getString("type"),
            rs.getInt("version"), rs.getString("content"), rs.getString("sha256"), rs.getString("created_by"),
            instant(rs, "created_at"));

    public Artifact insertArtifact(UUID runId, String stageKey, String type, String content, String sha256,
                                   String createdBy) {
        int version = jdbc.sql("select coalesce(max(version), 0) + 1 from artifact where run_id = :r and stage_key = :k")
                .param("r", runId).param("k", stageKey).query(Integer.class).single();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into artifact (run_id, stage_key, type, version, content, sha256, created_by, created_at)
                        values (:r, :k, :type, :v, :content, :sha, :by, :now)""")
                .param("r", runId).param("k", stageKey).param("type", type).param("v", version)
                .param("content", content).param("sha", sha256).param("by", createdBy).param("now", ts(now()))
                .update(keys, "id");
        return getArtifact(((Number) keys.getKeys().get("id")).longValue());
    }

    public Artifact getArtifact(long id) {
        return jdbc.sql("select * from artifact where id = :id").param("id", id).query(ARTIFACT).single();
    }

    public List<Artifact> listArtifacts(UUID runId) {
        return jdbc.sql("select * from artifact where run_id = :r order by id").param("r", runId)
                .query(ARTIFACT).list();
    }

    // ---- decisions -----------------------------------------------------------------------------------------

    private static final RowMapper<Decision> DECISION = (rs, i) -> new Decision(
            rs.getLong("id"), rs.getObject("run_id", UUID.class), rs.getString("stage_key"), rs.getString("actor"),
            rs.getString("type"), rs.getString("summary"), rs.getString("rationale"), rs.getString("input_hashes"),
            instant(rs, "created_at"));

    public void insertDecision(UUID runId, String stageKey, String actor, String type, String summary,
                               String rationale, String inputHashes) {
        jdbc.sql("""
                        insert into decision (run_id, stage_key, actor, type, summary, rationale, input_hashes, created_at)
                        values (:r, :k, :actor, :type, :summary, :rationale, :hashes, :now)""")
                .param("r", runId).param("k", stageKey).param("actor", actor).param("type", type)
                .param("summary", summary).param("rationale", rationale).param("hashes", inputHashes)
                .param("now", ts(now())).update();
    }

    public List<Decision> listDecisions(UUID runId) {
        return jdbc.sql("select * from decision where run_id = :r order by id").param("r", runId)
                .query(DECISION).list();
    }

    // ---- approvals -----------------------------------------------------------------------------------------

    private static final RowMapper<Approval> APPROVAL = (rs, i) -> new Approval(
            rs.getLong("id"), rs.getObject("run_id", UUID.class), rs.getString("stage_key"), rs.getString("reasons"),
            rs.getString("details"), ApprovalStatus.valueOf(rs.getString("status")), rs.getString("decided_by"),
            rs.getString("comment"), instant(rs, "created_at"), instant(rs, "decided_at"));

    public Approval insertApproval(UUID runId, String stageKey, String reasons, String details) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into approval_request (run_id, stage_key, reasons, details, status, created_at)
                        values (:r, :k, :reasons, :details, 'PENDING', :now)""")
                .param("r", runId).param("k", stageKey).param("reasons", reasons).param("details", details)
                .param("now", ts(now())).update(keys, "id");
        return getApproval(((Number) keys.getKeys().get("id")).longValue());
    }

    public Approval getApproval(long id) {
        return jdbc.sql("select * from approval_request where id = :id").param("id", id).query(APPROVAL)
                .optional().orElseThrow(() -> new NotFoundException("Unknown approval " + id));
    }

    public List<Approval> listApprovals(ApprovalStatus status) {
        if (status == null) {
            return jdbc.sql("select * from approval_request order by id desc").query(APPROVAL).list();
        }
        return jdbc.sql("select * from approval_request where status = :s order by id")
                .param("s", status.name()).query(APPROVAL).list();
    }

    public List<Approval> listApprovals(UUID runId) {
        return jdbc.sql("select * from approval_request where run_id = :r order by id").param("r", runId)
                .query(APPROVAL).list();
    }

    public void decideApproval(long id, ApprovalStatus status, String by, String comment) {
        jdbc.sql("""
                        update approval_request set status = :s, decided_by = :by, comment = :c, decided_at = :now
                        where id = :id""")
                .param("s", status.name()).param("by", by).param("c", comment).param("now", ts(now()))
                .param("id", id).update();
    }

    public void supersedePending(UUID runId, String stageKey) {
        jdbc.sql("""
                        update approval_request set status = 'SUPERSEDED', decided_at = :now
                        where run_id = :r and stage_key = :k and status = 'PENDING'""")
                .param("now", ts(now())).param("r", runId).param("k", stageKey).update();
    }

    // ---- clarifications ------------------------------------------------------------------------------------

    private static final RowMapper<Clarification> CLARIFICATION = (rs, i) -> new Clarification(
            rs.getLong("id"), rs.getObject("run_id", UUID.class), rs.getString("question"),
            rs.getString("proposed_assumption"), rs.getString("answer"), rs.getString("answered_by"),
            instant(rs, "created_at"), instant(rs, "answered_at"));

    public void insertClarification(UUID runId, String question, String proposedAssumption) {
        jdbc.sql("""
                        insert into clarification (run_id, question, proposed_assumption, created_at)
                        values (:r, :q, :a, :now)""")
                .param("r", runId).param("q", question).param("a", proposedAssumption).param("now", ts(now()))
                .update();
    }

    public List<Clarification> listClarifications(UUID runId) {
        return jdbc.sql("select * from clarification where run_id = :r order by id").param("r", runId)
                .query(CLARIFICATION).list();
    }

    public void answerClarification(long id, String answer, String by) {
        jdbc.sql("update clarification set answer = :a, answered_by = :by, answered_at = :now where id = :id")
                .param("a", answer).param("by", by).param("now", ts(now())).param("id", id).update();
    }
}
