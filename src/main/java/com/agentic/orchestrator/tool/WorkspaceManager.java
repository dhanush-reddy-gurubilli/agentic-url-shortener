package com.agentic.orchestrator.tool;

import com.agentic.orchestrator.OrchestratorProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.UUID;

/**
 * Creates the per-run workspace. When the source is a git repository the workspace is a real git worktree on a
 * new branch {@code agent/<runId>} (so a human can review and merge it); otherwise (tests) the source tree is
 * copied into a fresh repository on the same branch name.
 */
@Component
public class WorkspaceManager {

    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "target", "workspaces", ".idea", "node_modules");

    private final OrchestratorProperties properties;

    public WorkspaceManager(OrchestratorProperties properties) {
        this.properties = properties;
    }

    public Workspace create(UUID runId) {
        try {
            Path source = properties.sourceRoot().toAbsolutePath().normalize();
            Path rootDir = properties.workspaceRoot().toAbsolutePath().normalize();
            Files.createDirectories(rootDir);
            Path target = rootDir.resolve(runId.toString());
            String branch = "agent/" + runId;
            if (Files.exists(source.resolve(".git"))) {
                new GitTool(source).run("worktree", "add", "-q", "-b", branch, target.toString(), "HEAD");
            } else {
                copyTree(source, target);
                GitTool git = new GitTool(target);
                git.run("init", "-q", "-b", branch);
                git.commitAll("baseline");
            }
            return new Workspace(runId, target.toRealPath(), branch, new GitTool(target).head());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Re-opens an existing workspace (used after a restart). */
    public Workspace open(UUID runId, String path, String baselineSha) {
        return new Workspace(runId, Path.of(path), "agent/" + runId, baselineSha);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
