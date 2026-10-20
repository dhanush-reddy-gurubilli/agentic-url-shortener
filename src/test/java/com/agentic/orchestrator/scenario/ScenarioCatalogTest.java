package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.scenario.ScenarioConfig.FaultType;
import com.agentic.orchestrator.scenario.ScenarioConfig.HumanDecision;
import com.agentic.orchestrator.scenario.ScenarioConfig.HumanTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioCatalogTest {

    @TempDir
    Path root;
    ScenarioCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("good"));
        Files.writeString(root.resolve("good/scenario.yaml"), """
                name: good
                type: BROWNFIELD
                description: a scenario
                requirement: fix it
                token-budget: 5000
                faults:
                  - stage: documentation
                    type: TIMEOUT
                    attempts: [1, 2]
                human-actions:
                  - on: APPROVAL
                    stage: "impl:T1"
                    reason-contains: SCHEMA
                    decision: APPROVE
                    comment: ok
                    times: 3
                  - on: STOPPED
                    decision: RAISE_BUDGET
                    token-budget: 9000
                """);
        Files.createDirectories(root.resolve("empty-dir"));
        Files.createDirectories(root.resolve("broken"));
        Files.writeString(root.resolve("broken/scenario.yaml"), "name: [unclosed");
        var llm = new OrchestratorProperties.Llm("replay", "m", 1000, Duration.ofSeconds(1), root);
        catalog = new ScenarioCatalog(new OrchestratorProperties(Path.of("."), Path.of("."), null, null, llm, 4, 0.5, 5, 1000, null, "k"));
    }

    @Test
    void readsTheRequirementFaultsAndTheScriptedHumansDecisions() {
        ScenarioConfig s = catalog.find("good").orElseThrow();

        assertThat(s.requirement()).isEqualTo("fix it");
        assertThat(s.tokenBudget()).isEqualTo(5000L);
        assertThat(s.faults()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FaultType.TIMEOUT);
            assertThat(f.applies("documentation", 2)).isTrue();
            assertThat(f.applies("documentation", 3)).isFalse();
            assertThat(f.applies("design", 1)).isFalse();
        });
        assertThat(s.humanActions()).hasSize(2);
        assertThat(s.humanActions().get(0).on()).isEqualTo(HumanTrigger.APPROVAL);
        assertThat(s.humanActions().get(0).reasonContains()).isEqualTo("SCHEMA");
        assertThat(s.humanActions().get(0).maxTimes()).isEqualTo(3);
        assertThat(s.humanActions().get(1).decision()).isEqualTo(HumanDecision.RAISE_BUDGET);
        assertThat(s.humanActions().get(1).maxTimes()).as("default is once").isEqualTo(1);
        assertThat(s.humanActions().get(1).tokenBudget()).isEqualTo(9000L);
    }

    @Test
    void aFaultWithNoAttemptListAppliesToEveryAttempt() {
        var fault = new ScenarioConfig.Fault("design", FaultType.TIMEOUT, null, null);
        assertThat(fault.applies("design", 1)).isTrue();
        assertThat(fault.applies("design", 99)).isTrue();
    }

    @Test
    void unsafeOrUnknownNamesAreNotFound() {
        assertThat(catalog.find("../good")).isEmpty();
        assertThat(catalog.find("good/../good")).isEmpty();
        assertThat(catalog.find("")).isEmpty();
        assertThat(catalog.find(null)).isEmpty();
        assertThat(catalog.find("nope")).isEmpty();
        assertThat(catalog.find("empty-dir")).isEmpty();
    }

    @Test
    void aMalformedScenarioFileIsAClearError() {
        assertThatThrownBy(() -> catalog.find("broken")).isInstanceOf(IllegalStateException.class).hasMessageContaining("broken");
    }

    @Test
    void responsesLiveNextToTheScenario() {
        assertThat(catalog.responsesDir("good")).isEqualTo(root.resolve("good").resolve("responses"));
    }

    @Test
    void anAbsentRootListsNothing() {
        var llm = new OrchestratorProperties.Llm("replay", "m", 1000, Duration.ofSeconds(1), root.resolve("missing"));
        var missing = new ScenarioCatalog(new OrchestratorProperties(Path.of("."), Path.of("."), null, null, llm, 4, 0.5, 5, 1000, null, "k"));
        assertThat(missing.list()).isEmpty();
    }
}
