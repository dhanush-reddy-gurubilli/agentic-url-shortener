package com.agentic.orchestrator.artifact;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** JSON helpers shared by artifacts, agents and the API. Reads are lenient about extra fields from LLMs. */
@Component
public class Json {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public String writePretty(Object value) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(stripFences(json), type);
    }

    public Map<String, String> readStringMap(String json) {
        return mapper.readValue(json, new TypeReference<Map<String, String>>() {
        });
    }

    public JsonNode tree(String json) {
        return mapper.readTree(stripFences(json));
    }

    public <T> T convert(JsonNode node, Class<T> type) {
        return mapper.treeToValue(node, type);
    }

    public JsonNode toTree(Object value) {
        return mapper.valueToTree(value);
    }

    /** LLMs often wrap JSON in markdown fences; take what is between the outermost braces. */
    static String stripFences(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
