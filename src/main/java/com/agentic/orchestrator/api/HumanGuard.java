package com.agentic.orchestrator.api;

import com.agentic.orchestrator.OrchestratorProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Human decisions (approve, reject, answer, resume, roll back, revise) need the approval key; reading state and
 * the stop switch do not. The comparison is constant-time.
 */
@Component
public class HumanGuard {

    private final byte[] expected;
    private final String llmMode;

    public HumanGuard(OrchestratorProperties properties) {
        this.expected = properties.approvalApiKey().getBytes(StandardCharsets.UTF_8);
        this.llmMode = properties.llm().mode();
    }

    /**
     * Starting a run is open in replay mode (it costs nothing) but needs the key when it would call the real model,
     * which spends money.
     */
    public void requireIfRunsCostMoney(String suppliedKey) {
        if (!"replay".equals(llmMode)) {
            require(suppliedKey);
        }
    }

    public void require(String suppliedKey) {
        byte[] supplied = suppliedKey == null ? new byte[0] : suppliedKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new ApiExceptions.Unauthorized("A valid X-Approval-Key header is required for human decisions");
        }
    }

    public static String actor(String header) {
        return header == null || header.isBlank() ? "human" : header.strip();
    }
}
