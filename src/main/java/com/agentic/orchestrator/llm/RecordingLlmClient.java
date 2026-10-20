package com.agentic.orchestrator.llm;

import com.agentic.orchestrator.scenario.ScenarioCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Calls a live client and saves each response where {@link ReplayLlmClient} will find it. */
public class RecordingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final ScenarioCatalog catalog;

    public RecordingLlmClient(LlmClient delegate, ScenarioCatalog catalog) {
        this.delegate = delegate;
        this.catalog = catalog;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        LlmResponse response = delegate.complete(request);
        if (request.scenario() != null) {
            Path file = catalog.responsesDir(request.scenario()).resolve(
                    ReplayLlmClient.fileStem(request.stageKey()) + "." + request.attempt() + ".json");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, response.text(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return response;
    }
}
