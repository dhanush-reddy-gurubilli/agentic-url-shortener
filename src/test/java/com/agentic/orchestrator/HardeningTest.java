package com.agentic.orchestrator;

import com.agentic.IntegrationTest;
import com.agentic.orchestrator.api.ApiExceptions;
import com.agentic.orchestrator.api.HumanGuard;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.engine.WorkflowEngine;
import com.agentic.orchestrator.tool.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class HardeningTest extends IntegrationTest {

    @MockitoBean
    WorkspaceManager workspaces;
    @Autowired
    WorkflowEngine engine;
    @Autowired
    OrchestratorStore store;

    private static HumanGuard guardForMode(String mode) {
        var llm = new OrchestratorProperties.Llm(mode, "m", 1000, Duration.ofSeconds(1), Path.of("."));
        return new HumanGuard(new OrchestratorProperties(Path.of("."), Path.of("."), null, null, llm, 4, 0.5, 5, 1000,
                null, "secret-key"));
    }

    @Test
    void startingARunIsOpenInReplayModeButNeedsTheKeyWhenItWouldSpendMoney() {
        assertThatCode(() -> guardForMode("replay").requireIfRunsCostMoney(null)).doesNotThrowAnyException();

        for (String mode : new String[]{"live", "record"}) {
            HumanGuard guard = guardForMode(mode);
            assertThatThrownBy(() -> guard.requireIfRunsCostMoney(null)).isInstanceOf(ApiExceptions.Unauthorized.class);
            assertThatThrownBy(() -> guard.requireIfRunsCostMoney("wrong")).isInstanceOf(ApiExceptions.Unauthorized.class);
            assertThatCode(() -> guard.requireIfRunsCostMoney("secret-key")).doesNotThrowAnyException();
        }
    }

    @Test
    void theKeyComparisonRejectsPrefixesAndEmptyValues() {
        HumanGuard guard = guardForMode("replay");
        assertThatThrownBy(() -> guard.require("secret")).isInstanceOf(ApiExceptions.Unauthorized.class);
        assertThatThrownBy(() -> guard.require("secret-key-and-more")).isInstanceOf(ApiExceptions.Unauthorized.class);
        assertThatThrownBy(() -> guard.require("")).isInstanceOf(ApiExceptions.Unauthorized.class);
        assertThatCode(() -> guard.require("secret-key")).doesNotThrowAnyException();
    }

    @Test
    void aRunWhoseWorkspaceCannotBeCreatedIsRecordedAsStoppedNotLeftHanging() {
        when(workspaces.create(any())).thenThrow(new IllegalStateException("git: command not found"));

        assertThatThrownBy(() -> engine.start("anything", null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("workspace").hasMessageContaining("git");

        Run run = store.listRuns().getFirst();
        assertThat(run.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(run.statusReason()).contains("could not create the workspace").contains("git: command not found");
        assertThat(engine.isDriving(run.id())).isFalse();
    }
}
