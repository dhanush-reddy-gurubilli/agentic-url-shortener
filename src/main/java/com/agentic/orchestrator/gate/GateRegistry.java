package com.agentic.orchestrator.gate;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class GateRegistry {

    private final Map<String, Gate> gates = new HashMap<>();

    public GateRegistry(List<Gate> all) {
        all.forEach(g -> gates.put(g.name(), g));
    }

    public Gate get(String name) {
        Gate gate = gates.get(name);
        if (gate == null) {
            throw new IllegalArgumentException("Unknown gate '" + name + "'; known gates: " + gates.keySet());
        }
        return gate;
    }
}
