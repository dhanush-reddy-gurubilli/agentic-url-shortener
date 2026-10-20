package com.agentic.orchestrator;

import com.agentic.orchestrator.domain.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ApiTest extends EngineTest {

    private static final String KEY = "local-dev-key";

    @Autowired
    MockMvc mvc;

    private UUID startRunViaApi() throws Exception {
        scripts.happyPath(llm);
        MvcResult result = mvc.perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"Add QR code support for short URLs\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.branch", containsString("agent/")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\":\"([0-9a-f-]{36})\".*", "$1"));
    }

    @Test
    void aRunWithoutARequirementIsABadRequestProblem() throws Exception {
        mvc.perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void anUnknownScenarioIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"x\",\"scenario\":\"does-not-exist\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownRunIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/runs/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/approvals/999999")).andExpect(status().isNotFound());
    }

    @Test
    void theRunDetailExposesTheDependencyGraphAndItsStates() throws Exception {
        UUID id = startRunViaApi();
        awaitRun(id, RunStatus.PAUSED);

        mvc.perform(get("/api/v1/runs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status", is("PAUSED")))
                .andExpect(jsonPath("$.stages[?(@.key=='design')].status", org.hamcrest.Matchers.contains("SUCCEEDED")))
                .andExpect(jsonPath("$.stages[?(@.key=='impact_analysis')].status", org.hamcrest.Matchers.contains("SKIPPED")))
                .andExpect(jsonPath("$.stages[?(@.key=='validation')].dependsOn[*]",
                        org.hamcrest.Matchers.hasItems("impl:T1", "impl:T2", "test_generation")))
                .andExpect(jsonPath("$.approvals", hasSize(1)));
        mvc.perform(get("/api/v1/runs/" + id + "/decisions")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/runs/" + id + "/artifacts")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").doesNotExist());
        mvc.perform(get("/api/v1/runs/" + id + "/audit")).andExpect(status().isOk());
    }

    @Test
    void humanDecisionsNeedTheApprovalKey() throws Exception {
        UUID id = startRunViaApi();
        awaitRun(id, RunStatus.PAUSED);
        long approvalId = pendingApproval(id, "release").id();
        String body = "{\"comment\":\"looks good\"}";

        mvc.perform(post("/api/v1/approvals/" + approvalId + "/approve").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/approvals/" + approvalId + "/approve").header("X-Approval-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/approvals/" + approvalId + "/approve").header("X-Approval-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"  \"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/approvals/" + approvalId + "/approve").header("X-Approval-Key", KEY).header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.decidedBy", is("alice")));
        awaitRun(id, RunStatus.COMPLETED);

        mvc.perform(post("/api/v1/approvals/" + approvalId + "/approve").header("X-Approval-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void pendingApprovalsAreListedByDefault() throws Exception {
        UUID id = startRunViaApi();
        awaitRun(id, RunStatus.PAUSED);
        mvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stageKey", is("release")))
                .andExpect(jsonPath("$[0].reasons", is("RELEASE")));
        mvc.perform(get("/api/v1/approvals?status=APPROVED")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void stopNeedsNoKeyButResumeAndRollbackDo() throws Exception {
        UUID id = startRunViaApi();
        awaitRun(id, RunStatus.PAUSED);

        mvc.perform(post("/api/v1/runs/" + id + "/stop").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"pause\"}"))
                .andExpect(status().isOk());
        awaitRun(id, RunStatus.STOPPED);
        mvc.perform(post("/api/v1/runs/" + id + "/resume").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/runs/" + id + "/rollback?toStage=planning")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/runs/" + id + "/resume").header("X-Approval-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        awaitRun(id, RunStatus.PAUSED);
    }

    @Test
    void auditVerificationAndMetricsAreAvailable() throws Exception {
        UUID id = startRunViaApi();
        awaitRun(id, RunStatus.PAUSED);
        mvc.perform(get("/api/v1/audit/verify")).andExpect(status().isOk()).andExpect(jsonPath("$.valid", is(true)));
        mvc.perform(get("/api/v1/metrics/reliability")).andExpect(status().isOk())
                .andExpect(jsonPath("$.runs", is(1))).andExpect(jsonPath("$.mttrSamples").exists());
        mvc.perform(get("/api/v1/audit?runId=" + id)).andExpect(status().isOk());
    }

    @Test
    void theOpenApiDocumentCoversBothFunctionalities() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/urls")))
                .andExpect(content().string(containsString("/api/v1/runs")))
                .andExpect(content().string(containsString("/api/v1/approvals/{id}/approve")));
    }
}
