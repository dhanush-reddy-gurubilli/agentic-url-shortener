package com.agentic.orchestrator.tool;

/** A proposed file change cannot be applied as written (e.g. the snippet to replace is missing or ambiguous). */
public class ChangeFailed extends RuntimeException {

    public ChangeFailed(String message) {
        super(message);
    }
}
