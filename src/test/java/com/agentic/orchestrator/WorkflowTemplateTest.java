package com.agentic.orchestrator;

import com.agentic.orchestrator.engine.WorkflowTemplate;
import com.agentic.orchestrator.engine.WorkflowTemplate.DynamicStageTemplate;
import com.agentic.orchestrator.engine.WorkflowTemplate.StageTemplate;
import com.agentic.orchestrator.engine.WorkflowTemplateLoader;
import com.agentic.orchestrator.graph.Dag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowTemplateTest {

    private static StageTemplate stage(String key, String... dependsOn) {
        return new StageTemplate(key, "AGENT", List.of(dependsOn), null, List.of(), null, null, null, null);
    }

    private static WorkflowTemplate template(StageTemplate... stages) {
        return new WorkflowTemplate("t", List.of(stages), List.of());
    }

    @Test
    void theShippedWorkflowIsAValidDagWithTheExpectedShape() {
        OrchestratorProperties props = new OrchestratorProperties(Path.of("."), Path.of("."),
                new ClassPathResource("workflows/sdlc.yaml"), new ClassPathResource("policies/default-policy.yaml"),
                null, 4, 0.5, 5, 1000, null, "k");
        WorkflowTemplate sdlc = new WorkflowTemplateLoader(props).template();

        assertThat(sdlc.stages()).extracting(StageTemplate::key).containsExactly("requirements", "impact_analysis",
                "design", "planning", "test_generation", "documentation", "validation", "release_readiness", "release");
        assertThat(sdlc.stage("impact_analysis").orElseThrow().onlyForChangeType()).isNotNull();
        assertThat(sdlc.stage("release").orElseThrow().autonomy()).isEqualTo(WorkflowTemplate.Autonomy.ALWAYS_APPROVE);
        assertThat(sdlc.dynamicStages()).singleElement().satisfies(d -> {
            assertThat(d.expandFrom()).isEqualTo("planning");
            assertThat(d.joinedBy()).containsExactly("validation");
        });
        assertThat(sdlc.resolve("impl:T7").agent()).isEqualTo("CODER");
    }

    @Test
    void aCycleIsRejectedAndNamed() {
        WorkflowTemplate cyclic = template(stage("a", "c"), stage("b", "a"), stage("c", "b"));
        assertThatThrownBy(cyclic::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle").hasMessageContaining("a").hasMessageContaining("c");
    }

    @Test
    void aSelfDependencyIsACycle() {
        assertThatThrownBy(() -> template(stage("a", "a")).validate()).hasMessageContaining("cycle");
    }

    @Test
    void anUnknownDependencyIsRejected() {
        assertThatThrownBy(() -> template(stage("a", "ghost")).validate()).hasMessageContaining("unknown stage ghost");
    }

    @Test
    void duplicateKeysAreRejected() {
        assertThatThrownBy(() -> template(stage("a"), stage("a")).validate()).hasMessageContaining("Duplicate");
    }

    @Test
    void aDynamicGroupThatJoinsBackIntoItsOwnSourceIsACycle() {
        DynamicStageTemplate group = new DynamicStageTemplate("impl", "impl:", "CODER", "plan", List.of("plan"),
                List.of(), null, null, null);
        WorkflowTemplate bad = new WorkflowTemplate("t", List.of(stage("plan")), List.of(group));
        assertThatThrownBy(bad::validate).hasMessageContaining("cycle");
    }

    @Test
    void aDiamondWithAJoinIsValid() {
        WorkflowTemplate diamond = template(stage("start"), stage("left", "start"), stage("right", "start"),
                stage("join", "left", "right"));
        assertThat(diamond.validate()).isSameAs(diamond);
    }

    @Test
    void kahnCheckAcceptsAnEmptyGraph() {
        Dag.assertAcyclic(Map.of());
    }
}
