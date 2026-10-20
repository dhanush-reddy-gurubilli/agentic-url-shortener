package com.agentic.orchestrator.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Git operations in one workspace. Only an allowlisted set of sub-commands can be run; there is deliberately
 * no push, fetch, remote, merge or checkout, so an agent can never publish or merge anything.
 */
public final class GitTool {

    private static final Set<String> ALLOWED = Set.of("init", "add", "commit", "reset", "clean", "rev-parse",
            "status", "diff", "worktree", "branch", "ls-files", "log");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Path directory;

    public GitTool(Path directory) {
        this.directory = directory;
    }

    public String run(String subcommand, String... args) {
        if (!ALLOWED.contains(subcommand)) {
            throw new SandboxViolation("git " + subcommand + " is not allowed");
        }
        List<String> argv = new ArrayList<>(List.of("git", "-c", "user.name=agentic-orchestrator",
                "-c", "user.email=orchestrator@localhost", "-c", "core.autocrlf=false", subcommand));
        argv.addAll(List.of(args));
        ProcessRunner.Result result = ProcessRunner.run(argv, directory, Map.of(), TIMEOUT);
        if (!result.ok()) {
            throw new IllegalStateException("git " + subcommand + " failed (" + result.exitCode() + "): "
                    + ProcessRunner.tail(result.output(), 2000));
        }
        return result.output().strip();
    }

    public String head() {
        return run("rev-parse", "HEAD");
    }

    /** Stages everything and commits (allowing an empty commit so every stage has a checkpoint). */
    public String commitAll(String message) {
        run("add", "-A");
        run("commit", "--allow-empty", "-q", "-m", message);
        return head();
    }

    /** Discards everything after the checkpoint: tracked changes, untracked files and later commits. */
    public void resetTo(String sha) {
        run("reset", "--hard", "-q", sha);
        run("clean", "-fdq");
    }

    public List<String> changedFilesSince(String sha) {
        String out = run("diff", "--name-status", "--no-renames", sha, "HEAD");
        return out.isBlank() ? List.of() : List.of(out.split("\\R"));
    }
}
