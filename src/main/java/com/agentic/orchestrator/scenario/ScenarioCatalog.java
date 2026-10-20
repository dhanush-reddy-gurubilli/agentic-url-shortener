package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.OrchestratorProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Reads scenario definitions from the replay root (one directory per scenario). */
@Component
public class ScenarioCatalog {

    private final Path root;
    private final YAMLMapper yaml = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public ScenarioCatalog(OrchestratorProperties properties) {
        this.root = properties.llm().replayRoot();
    }

    public Path responsesDir(String scenario) {
        return root.resolve(scenario).resolve("responses");
    }

    public Optional<ScenarioConfig> find(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            return Optional.empty();
        }
        Path file = root.resolve(name).resolve("scenario.yaml");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(yaml.readValue(file.toFile(), ScenarioConfig.class));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid scenario file " + file + ": " + e.getMessage(), e);
        }
    }

    public List<ScenarioConfig> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).map(d -> find(d.getFileName().toString()))
                    .flatMap(Optional::stream).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
