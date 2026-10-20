package com.agentic.orchestrator.tool;

import java.nio.file.Path;
import java.util.UUID;

/** One run's isolated checkout: a git worktree on branch {@code agent/<runId>}. */
public record Workspace(UUID runId, Path root, String branch, String baselineSha) {

    public WorkspaceFs fs() {
        return new WorkspaceFs(root);
    }

    public GitTool git() {
        return new GitTool(root);
    }
}
