package com.agentic.orchestrator.agent;

/** A unit of work run by a stage. Agents propose output; gates, policies and humans decide whether it is accepted. */
public interface Agent {

    String id();

    AgentResult execute(StageContext context);

    /** @param content the artifact as JSON text; the stage's exit gates validate it */
    record AgentResult(String artifactType, String content, long tokensUsed) {
    }
}
