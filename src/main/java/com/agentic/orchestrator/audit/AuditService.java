package com.agentic.orchestrator.audit;

import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Append-only, hash-chained audit trail: {@code hash = sha256(prevHash | fields)}. Any edit or deletion of a row
 * breaks the chain, which {@link #verify()} detects. Appends are serialised with a Postgres advisory lock.
 */
@Service
public class AuditService {

    static final String GENESIS = "0".repeat(64);
    private static final long LOCK_KEY = 7_001L;
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcClient jdbc;
    private final OrchestratorStore store;

    public AuditService(JdbcClient jdbc, OrchestratorStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public record VerifyResult(boolean valid, long eventsChecked, Long firstBrokenEventId, String message) {
    }

    private static final RowMapper<AuditEvent> MAPPER = (rs, i) -> new AuditEvent(
            rs.getLong("id"), rs.getObject("run_id", UUID.class), rs.getString("stage_key"), rs.getString("actor"),
            rs.getString("event_type"), rs.getString("payload"), instant(rs.getObject("created_at", OffsetDateTime.class)),
            rs.getString("prev_hash"), rs.getString("hash"));

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    @Transactional
    public AuditEvent record(UUID runId, String stageKey, String actor, String eventType, String payload) {
        jdbc.sql("select pg_advisory_xact_lock(:k)").param("k", LOCK_KEY).query().listOfRows();
        String prev = jdbc.sql("select hash from audit_event order by id desc limit 1").query(String.class)
                .optional().orElse(GENESIS);
        Instant now = store.now();
        String hash = hash(prev, runId, stageKey, actor, eventType, payload, now);
        jdbc.sql("""
                        insert into audit_event (run_id, stage_key, actor, event_type, payload, created_at, prev_hash, hash)
                        values (:run, :stage, :actor, :type, :payload, :now, :prev, :hash)""")
                .param("run", runId).param("stage", stageKey).param("actor", actor).param("type", eventType)
                .param("payload", payload).param("now", now.atOffset(ZoneOffset.UTC)).param("prev", prev)
                .param("hash", hash).update();
        String previousRun = MDC.get("runId");
        MDC.put("runId", String.valueOf(runId));
        if (stageKey != null) {
            MDC.put("stageKey", stageKey);
        }
        try {
            log.info("audit {} actor={} stage={} {}", eventType, actor, stageKey, payload == null ? "" : payload);
        } finally {
            if (previousRun == null) {
                MDC.remove("runId");
            } else {
                MDC.put("runId", previousRun);
            }
            MDC.remove("stageKey");
        }
        return new AuditEvent(0, runId, stageKey, actor, eventType, payload, now, prev, hash);
    }

    public long count(UUID runId, String stageKey, String eventType) {
        return jdbc.sql("select count(*) from audit_event where run_id = :r and stage_key = :k and event_type = :t")
                .param("r", runId).param("k", stageKey).param("t", eventType).query(Long.class).single();
    }

    public List<AuditEvent> forRun(UUID runId) {
        return jdbc.sql("select * from audit_event where run_id = :r order by id").param("r", runId)
                .query(MAPPER).list();
    }

    public List<AuditEvent> all() {
        return jdbc.sql("select * from audit_event order by id").query(MAPPER).list();
    }

    /** Recomputes the whole chain in insertion order. */
    public VerifyResult verify() {
        List<AuditEvent> events = all();
        String prev = GENESIS;
        for (AuditEvent e : events) {
            String expected = hash(prev, e.runId(), e.stageKey(), e.actor(), e.eventType(), e.payload(), e.createdAt());
            if (!e.prevHash().equals(prev) || !e.hash().equals(expected)) {
                return new VerifyResult(false, events.size(), e.id(),
                        "Audit chain broken at event " + e.id() + " (tampered, removed or reordered)");
            }
            prev = e.hash();
        }
        return new VerifyResult(true, events.size(), null, "Audit chain intact");
    }

    static String hash(String prevHash, UUID runId, String stageKey, String actor, String eventType, String payload,
                       Instant createdAt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = String.join("\u001f", prevHash, String.valueOf(runId), String.valueOf(stageKey), actor,
                    eventType, String.valueOf(payload), createdAt.toString());
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
