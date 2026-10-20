package com.agentic.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "orchestrator")
public record OrchestratorProperties(
        Path workspaceRoot,
        Path sourceRoot,
        Resource workflow,
        Resource policy,
        Llm llm,
        int parallelism,
        double ambiguityThreshold,
        int maxPolicyViolations,
        long defaultTokenBudget,
        Build build,
        String approvalApiKey) {

    public record Llm(String mode, String model, int maxTokens, Duration timeout, Path replayRoot) {
    }

    public record Build(String command, Duration timeout, double minLineCoverage) {
    }
}
