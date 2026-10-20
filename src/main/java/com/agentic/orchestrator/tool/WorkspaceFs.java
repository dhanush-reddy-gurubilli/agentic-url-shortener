package com.agentic.orchestrator.tool;

import com.agentic.orchestrator.artifact.Artifacts.FileChange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * File access confined to one workspace directory. Every path is canonicalised and must stay inside the root
 * (no absolute paths, no {@code ..}, no symlink escapes, no {@code .git}); anything else is a hard
 * {@link SandboxViolation}.
 */
public final class WorkspaceFs {

    private final Path root;

    public WorkspaceFs(Path root) {
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path root() {
        return root;
    }

    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new SandboxViolation("Empty path");
        }
        String normalized = relative.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new SandboxViolation("Absolute paths are not allowed: " + relative);
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new SandboxViolation("Path traversal is not allowed: " + relative);
            }
            if (segment.equals(".git")) {
                throw new SandboxViolation("Access to .git is not allowed: " + relative);
            }
        }
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) {
            throw new SandboxViolation("Path escapes the workspace: " + relative);
        }
        Path existing = resolved;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            try {
                if (!existing.toRealPath().startsWith(root)) {
                    throw new SandboxViolation("Symlink escapes the workspace: " + relative);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return resolved;
    }

    public boolean exists(String relative) {
        return Files.isRegularFile(resolve(relative));
    }

    public String read(String relative) {
        try {
            return Files.readString(resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Relative paths ('/' separated) of regular files under a directory, or an empty list if it does not exist. */
    public List<String> listFiles(String directory) {
        Path dir = resolve(directory);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The content after the change, or null for DELETE. Does not touch the disk. */
    public String simulate(FileChange change) {
        Path target = resolve(change.path());
        switch (change.op()) {
            case WRITE -> {
                if (change.content() == null) {
                    throw new ChangeFailed("WRITE of " + change.path() + " has no content");
                }
                return change.content();
            }
            case DELETE -> {
                if (!Files.isRegularFile(target)) {
                    throw new ChangeFailed("Cannot delete missing file " + change.path());
                }
                return null;
            }
            case REPLACE -> {
                if (!Files.isRegularFile(target)) {
                    throw new ChangeFailed("Cannot REPLACE in missing file " + change.path());
                }
                if (change.find() == null || change.find().isEmpty() || change.replacement() == null) {
                    throw new ChangeFailed("REPLACE of " + change.path() + " needs 'find' and 'replacement'");
                }
                String current = read(change.path());
                int first = current.indexOf(change.find());
                if (first < 0) {
                    throw new ChangeFailed("Snippet to replace was not found in " + change.path());
                }
                if (current.indexOf(change.find(), first + 1) >= 0) {
                    throw new ChangeFailed("Snippet to replace is not unique in " + change.path());
                }
                return current.substring(0, first) + change.replacement()
                        + current.substring(first + change.find().length());
            }
            default -> throw new ChangeFailed("Unknown op " + change.op());
        }
    }

    public void apply(FileChange change) {
        String content = simulate(change);
        Path target = resolve(change.path());
        try {
            if (content == null) {
                Files.delete(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.writeString(target, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
