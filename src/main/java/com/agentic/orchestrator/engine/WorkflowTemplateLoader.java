package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.OrchestratorProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

@Component
public class WorkflowTemplateLoader {

    private final WorkflowTemplate template;

    public WorkflowTemplateLoader(OrchestratorProperties properties) {
        try (InputStream in = properties.workflow().getInputStream()) {
            this.template = YAMLMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build().readValue(in, WorkflowTemplate.class).validate();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load workflow " + properties.workflow(), e);
        }
    }

    public WorkflowTemplate template() {
        return template;
    }
}
