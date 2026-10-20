package com.agentic.orchestrator.approval;

/** Why a stage's output needs a human decision before it is accepted. */
public enum ImpactReason {
    SCHEMA_CHANGE("adds a database migration"),
    API_CHANGE("changes the public API"),
    SECURITY_SENSITIVE_FILE("touches security-relevant code"),
    DEPENDENCY_CHANGE("adds or changes a dependency"),
    FILE_DELETION("deletes files"),
    LARGE_DIFF("is a large change"),
    RELEASE("releases the change set"),
    ALWAYS_REVIEW("is configured to always need a human decision");

    private final String description;

    ImpactReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
