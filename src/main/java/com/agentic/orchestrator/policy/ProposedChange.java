package com.agentic.orchestrator.policy;

import com.agentic.orchestrator.artifact.Artifacts.ChangeOp;
import com.agentic.orchestrator.artifact.Artifacts.FileChange;
import com.agentic.orchestrator.tool.WorkspaceFs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A file change together with the file's content before and after, so policies can inspect exactly what a patch
 * would add or remove without touching the disk.
 */
public record ProposedChange(FileChange change, String oldContent, String newContent) {

    public String path() {
        return change.path().replace('\\', '/');
    }

    public boolean isDelete() {
        return change.op() == ChangeOp.DELETE;
    }

    public boolean isNewFile() {
        return oldContent == null && newContent != null;
    }

    /** Lines present after the change but not before. */
    public List<String> addedLines() {
        if (newContent == null) {
            return List.of();
        }
        Set<String> before = oldContent == null ? Set.of() : lineSet(oldContent);
        return Arrays.stream(newContent.split("\\R", -1)).filter(l -> !before.contains(l)).toList();
    }

    /** Lines present before the change but not after. */
    public List<String> removedLines() {
        if (oldContent == null) {
            return List.of();
        }
        Set<String> after = newContent == null ? Set.of() : lineSet(newContent);
        return Arrays.stream(oldContent.split("\\R", -1)).filter(l -> !after.contains(l)).toList();
    }

    private static Set<String> lineSet(String content) {
        return Arrays.stream(content.split("\\R", -1)).collect(Collectors.toCollection(HashSet::new));
    }

    public static ProposedChange of(WorkspaceFs fs, FileChange change) {
        String old = fs.exists(change.path()) ? fs.read(change.path()) : null;
        return new ProposedChange(change, old, fs.simulate(change));
    }
}
