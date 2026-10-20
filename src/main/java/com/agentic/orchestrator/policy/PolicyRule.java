package com.agentic.orchestrator.policy;

import java.util.List;

/** One deterministic guardrail over a proposed change set. Violations always block; nothing here is advisory. */
public interface PolicyRule {

    String id();

    String category();

    List<Violation> check(List<ProposedChange> changes);

    default Violation violation(String path, String message) {
        return new Violation(id(), category(), path, message);
    }
}
