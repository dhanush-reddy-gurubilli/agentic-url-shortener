package com.agentic.orchestrator.testing;

import com.agentic.orchestrator.llm.LlmClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Test double for the model: responses (or failures) scripted per stage and attempt. */
public class ScriptedLlm implements LlmClient {

    private final Map<String, Function<LlmRequest, String>> handlers = new HashMap<>();
    private final List<LlmRequest> calls = Collections.synchronizedList(new ArrayList<>());

    public void reset() {
        handlers.clear();
        calls.clear();
    }

    /** Same response for every attempt of the stage. */
    public ScriptedLlm on(String stage, String response) {
        handlers.put(stage + "#*", r -> response);
        return this;
    }

    public ScriptedLlm on(String stage, int attempt, String response) {
        handlers.put(stage + "#" + attempt, r -> response);
        return this;
    }

    public ScriptedLlm on(String stage, Function<LlmRequest, String> handler) {
        handlers.put(stage + "#*", handler);
        return this;
    }

    public ScriptedLlm failOn(String stage, int attempt, RuntimeException failure) {
        handlers.put(stage + "#" + attempt, r -> {
            throw failure;
        });
        return this;
    }

    public ScriptedLlm failAlways(String stage, RuntimeException failure) {
        handlers.put(stage + "#*", r -> {
            throw failure;
        });
        return this;
    }

    public List<LlmRequest> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public List<LlmRequest> callsFor(String stage) {
        return calls().stream().filter(c -> c.stageKey().equals(stage)).toList();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        calls.add(request);
        Function<LlmRequest, String> handler = handlers.get(request.stageKey() + "#" + request.attempt());
        if (handler == null) {
            handler = handlers.get(request.stageKey() + "#*");
        }
        if (handler == null) {
            throw new IllegalStateException("No scripted response for " + request.stageKey() + " attempt " + request.attempt());
        }
        String text = handler.apply(request);
        return new LlmResponse(text, (request.system().length() + request.user().length() + text.length()) / 4L);
    }
}
