package com.agentic.orchestrator.tool;

/** Thrown when a tool call would leave the workspace sandbox or use a forbidden command. Hard deny, never retried. */
public class SandboxViolation extends RuntimeException {

    public SandboxViolation(String message) {
        super(message);
    }
}
