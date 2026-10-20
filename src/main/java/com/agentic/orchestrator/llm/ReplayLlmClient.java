package com.agentic.orchestrator.llm;

import com.agentic.orchestrator.scenario.ScenarioCatalog;
import com.agentic.orchestrator.scenario.ScenarioConfig;
import com.agentic.orchestrator.scenario.ScenarioConfig.Fault;
import com.agentic.orchestrator.scenario.ScenarioConfig.FaultType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves recorded or scripted responses from {@code scenarios/<name>/responses/<stage>.<attempt>.json} (falling
 * back to {@code <stage>.json}) and applies the scenario's injected faults. Deterministic: the same scenario
 * always yields the same run, which is what makes the demo and the end-to-end tests repeatable.
 */
public class ReplayLlmClient implements LlmClient {

    private final ScenarioCatalog catalog;

    public ReplayLlmClient(ScenarioCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (request.scenario() == null) {
            throw new LlmException("Replay mode needs a scenario; this run has none");
        }
        ScenarioConfig scenario = catalog.find(request.scenario())
                .orElseThrow(() -> new LlmException("Unknown scenario '" + request.scenario() + "'"));
        long surgeTokens = -1;
        for (Fault fault : scenario.faults()) {
            if (fault.applies(request.stageKey(), request.attempt())) {
                if (fault.type() == FaultType.TIMEOUT) {
                    throw new LlmException.Timeout("Injected fault: model call for stage " + request.stageKey()
                            + " attempt " + request.attempt() + " timed out");
                }
                surgeTokens = fault.tokens() == null ? 0 : fault.tokens();
            }
        }
        String text = read(responseFile(request));
        long tokens = surgeTokens >= 0 ? surgeTokens
                : (request.system().length() + request.user().length() + text.length()) / 4L;
        return new LlmResponse(text, tokens);
    }

    static String fileStem(String stageKey) {
        return stageKey.replace(':', '_');
    }

    private Path responseFile(LlmRequest request) {
        Path dir = catalog.responsesDir(request.scenario());
        Path exact = dir.resolve(fileStem(request.stageKey()) + "." + request.attempt() + ".json");
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        Path generic = dir.resolve(fileStem(request.stageKey()) + ".json");
        if (Files.isRegularFile(generic)) {
            return generic;
        }
        throw new LlmException("No recorded response for stage " + request.stageKey() + " attempt "
                + request.attempt() + " in scenario " + request.scenario() + " (looked for " + exact.getFileName()
                + " and " + generic.getFileName() + ")");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
