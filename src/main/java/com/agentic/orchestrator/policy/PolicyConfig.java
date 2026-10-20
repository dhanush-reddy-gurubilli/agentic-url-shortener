package com.agentic.orchestrator.policy;

import java.util.List;

/** Tunable parts of the policy, loaded from {@code policies/default-policy.yaml}. */
public record PolicyConfig(List<String> protectedPaths, List<String> allowedNewDependencyGroups,
                           int maxFilesPerChangeSet, int maxChangedLines, int largeDiffLines,
                           List<String> securitySensitivePathPatterns) {

    public PolicyConfig {
        protectedPaths = protectedPaths == null ? List.of() : protectedPaths;
        allowedNewDependencyGroups = allowedNewDependencyGroups == null ? List.of() : allowedNewDependencyGroups;
        securitySensitivePathPatterns = securitySensitivePathPatterns == null ? List.of() : securitySensitivePathPatterns;
    }
}
