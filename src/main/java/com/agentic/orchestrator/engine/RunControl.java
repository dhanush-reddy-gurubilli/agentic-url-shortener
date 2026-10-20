package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Run;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Safe-stop: a cooperative stop request that the scheduler honours. In-flight stages finish; nothing new starts. */
@Component
public class RunControl {

    private final OrchestratorStore store;
    private final AuditService audit;
    private final RunLocks locks;

    public RunControl(OrchestratorStore store, AuditService audit, RunLocks locks) {
        this.store = store;
        this.audit = audit;
        this.locks = locks;
    }

    /** Idempotent. @return true if this call raised the flag. */
    public boolean requestStop(UUID runId, String actor, String reason) {
        try (var ignored = locks.run(runId)) {
            Run run = store.getRun(runId);
            if (run.stopRequested() || run.status().isTerminal()) {
                return false;
            }
            store.requestStop(runId, reason);
            audit.record(runId, null, actor, "SAFE_STOP_REQUESTED", reason);
            store.insertDecision(runId, null, actor, "SAFE_STOP", "Safe-stop requested: " + reason, reason, null);
            return true;
        }
    }
}
