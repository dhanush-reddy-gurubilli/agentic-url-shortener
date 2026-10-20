package com.agentic.orchestrator.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-graph checks shared by the workflow template and the planner's task graph. */
public final class Dag {

    private Dag() {
    }

    /**
     * Kahn's algorithm: every node must eventually reach indegree zero.
     *
     * @param dependsOn node -> the nodes it depends on
     * @throws IllegalStateException naming the nodes involved if there is a cycle
     */
    public static void assertAcyclic(Map<String, List<String>> dependsOn) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        dependsOn.forEach((node, deps) -> {
            indegree.put(node, deps.size());
            deps.forEach(dep -> dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(node));
        });
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((node, degree) -> {
            if (degree == 0) {
                ready.add(node);
            }
        });
        Set<String> visited = new HashSet<>();
        while (!ready.isEmpty()) {
            String node = ready.poll();
            visited.add(node);
            for (String next : dependents.getOrDefault(node, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (visited.size() != dependsOn.size()) {
            Set<String> stuck = new HashSet<>(dependsOn.keySet());
            stuck.removeAll(visited);
            throw new IllegalStateException("Dependency cycle among stages " + stuck);
        }
    }
}
