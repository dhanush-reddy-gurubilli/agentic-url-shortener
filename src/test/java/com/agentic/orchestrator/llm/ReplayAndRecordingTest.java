package com.agentic.orchestrator.llm;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.scenario.ScenarioCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayAndRecordingTest {

    @TempDir
    Path root;
    ScenarioCatalog catalog;
    ReplayLlmClient replay;

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeEach
    void setUp() throws Exception {
        write("s1/scenario.yaml", """
                name: s1
                type: GREENFIELD
                requirement: do it
                faults:
                  - stage: design
                    type: TIMEOUT
                    attempts: [1]
                  - stage: planning
                    type: TOKEN_SURGE
                    tokens: 99000
                """);
        write("s1/responses/design.2.json", "{\"from\":\"exact\"}");
        write("s1/responses/design.json", "{\"from\":\"generic\"}");
        write("s1/responses/planning.json", "{\"tasks\":[]}");
        write("s1/responses/impl_T1.1.json", "{\"from\":\"colon\"}");
        var llm = new OrchestratorProperties.Llm("replay", "m", 1000, Duration.ofSeconds(1), root);
        catalog = new ScenarioCatalog(new OrchestratorProperties(Path.of("."), Path.of("."), null, null, llm, 4, 0.5, 5, 1000, null, "k"));
        replay = new ReplayLlmClient(catalog);
    }

    private static LlmClient.LlmRequest request(String scenario, String stage, int attempt) {
        return new LlmClient.LlmRequest(UUID.randomUUID(), scenario, stage, "AGENT", attempt, "system", "user");
    }

    @Test
    void anExactNumberedResponseBeatsTheGenericOneWhichCoversEveryOtherStart() {
        assertThat(replay.complete(request("s1", "design", 2)).text()).contains("exact");
        assertThat(replay.complete(request("s1", "design", 3)).text()).contains("generic");
        assertThat(replay.complete(request("s1", "design", 7)).text()).contains("generic");
    }

    @Test
    void aTimeoutFaultAppliesOnlyToTheListedStartNumbers() {
        assertThatThrownBy(() -> replay.complete(request("s1", "design", 1))).isInstanceOf(LlmException.Timeout.class);
        assertThat(replay.complete(request("s1", "design", 2)).text()).isNotBlank();
    }

    @Test
    void aTokenSurgeReplacesTheEstimatedUsage() {
        assertThat(replay.complete(request("s1", "planning", 1)).tokensUsed()).isEqualTo(99_000);
    }

    @Test
    void otherwiseUsageIsEstimatedFromTheTextSizes() {
        String text = "{\"from\":\"exact\"}";
        assertThat(replay.complete(request("s1", "design", 2)).tokensUsed()).isEqualTo(("system".length() + "user".length() + text.length()) / 4L);
    }

    @Test
    void aStageKeyWithAColonMapsToAnUnderscoreFileName() {
        assertThat(replay.complete(request("s1", "impl:T1", 1)).text()).contains("colon");
    }

    @Test
    void aMissingResponseNamesTheFilesItLookedFor() {
        assertThatThrownBy(() -> replay.complete(request("s1", "validation", 1)))
                .isInstanceOf(LlmException.class).hasMessageContaining("validation.1.json").hasMessageContaining("validation.json");
    }

    @Test
    void aRunWithoutAKnownScenarioCannotBeReplayed() {
        assertThatThrownBy(() -> replay.complete(request(null, "design", 1))).isInstanceOf(LlmException.class).hasMessageContaining("scenario");
        assertThatThrownBy(() -> replay.complete(request("nope", "design", 1))).isInstanceOf(LlmException.class).hasMessageContaining("Unknown scenario");
    }

    @Test
    void recordingSavesWhatTheLiveModelSaidWhereReplayWillFindIt() {
        LlmClient live = r -> new LlmClient.LlmResponse("{\"recorded\":true}", 321);
        var recording = new RecordingLlmClient(live, catalog);

        var response = recording.complete(request("s1", "documentation", 4));

        assertThat(response.tokensUsed()).isEqualTo(321);
        assertThat(root.resolve("s1/responses/documentation.4.json")).hasContent("{\"recorded\":true}");
        assertThat(replay.complete(request("s1", "documentation", 4)).text()).isEqualTo("{\"recorded\":true}");
    }

    @Test
    void recordingWithoutAScenarioStillReturnsTheResponseAndWritesNothing() throws Exception {
        var recording = new RecordingLlmClient(r -> new LlmClient.LlmResponse("x", 1), catalog);
        assertThat(recording.complete(request(null, "design", 1)).text()).isEqualTo("x");
        try (var files = Files.list(root)) {
            assertThat(files.count()).isEqualTo(1);   // only s1
        }
    }
}
