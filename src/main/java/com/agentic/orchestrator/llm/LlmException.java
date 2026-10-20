package com.agentic.orchestrator.llm;

/** The model call failed (timeout, refusal, API error, missing recording). The engine retries these. */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The call exceeded its time limit. */
    public static class Timeout extends LlmException {
        public Timeout(String message) {
            super(message);
        }
    }
}
