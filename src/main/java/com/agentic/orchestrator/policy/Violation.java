package com.agentic.orchestrator.policy;

public record Violation(String rule, String category, String path, String message) {

    @Override
    public String toString() {
        return "[" + category + "/" + rule + "] " + (path == null ? "" : path + ": ") + message;
    }
}
