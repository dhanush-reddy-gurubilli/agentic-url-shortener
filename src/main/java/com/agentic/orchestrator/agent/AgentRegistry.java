package com.agentic.orchestrator.agent;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentRegistry {

    private final Map<String, Agent> agents = new HashMap<>();

    public AgentRegistry(List<Agent> all) {
        all.forEach(a -> agents.put(a.id(), a));
    }

    public Agent get(String id) {
        Agent agent = agents.get(id);
        if (agent == null) {
            throw new IllegalArgumentException("Unknown agent '" + id + "'; known agents: " + agents.keySet());
        }
        return agent;
    }

    public boolean has(String id) {
        return agents.containsKey(id);
    }
}
