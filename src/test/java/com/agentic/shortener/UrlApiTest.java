package com.agentic.shortener;

import com.agentic.IntegrationTest;
import com.agentic.shortener.service.ClickRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class UrlApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ClickRecorder clickRecorder;

    private String create(String json) throws Exception {
        String body = mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"code\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void creatingReturns201WithALocationAndTheShortUrl() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/some/long/path\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern("http://localhost:8080/[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.code", matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.originalUrl", is("https://example.com/some/long/path")))
                .andExpect(jsonPath("$.expiresAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.shortUrl", matchesPattern("http://localhost:8080/[0-9A-Za-z]{7}")));
    }

    @Test
    void redirectIsA302ThatBrowsersDoNotCache() throws Exception {
        String code = create("{\"url\":\"https://example.com/target\"}");
        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.com/target"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void unknownCodesAre404ProblemJson() throws Exception {
        mvc.perform(get("/nosuchcode"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    void aDeletedLinkIs410() throws Exception {
        String code = create("{\"url\":\"https://example.com/target\"}");
        mvc.perform(get("/" + code)).andExpect(status().isFound());
        mvc.perform(delete("/api/v1/urls/" + code)).andExpect(status().isNoContent());
        mvc.perform(get("/" + code)).andExpect(status().isGone());
        mvc.perform(get("/api/v1/urls/" + code)).andExpect(status().isOk()).andExpect(jsonPath("$.deletedAt").exists());
    }

    @Test
    void anExpiredLinkIs410() throws Exception {
        String code = create("{\"url\":\"https://example.com/target\",\"expiresAt\":\""
                + java.time.Instant.now().plusMillis(1200) + "\"}");
        Thread.sleep(1500);
        mvc.perform(get("/" + code)).andExpect(status().isGone())
                .andExpect(jsonPath("$.detail", containsString("expired")));
    }

    @Test
    void metadataShowsTheLink() throws Exception {
        String code = create("{\"url\":\"https://example.com/m\",\"customAlias\":\"my-alias\"}");
        assertThat(code).isEqualTo("my-alias");
        mvc.perform(get("/api/v1/urls/my-alias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUrl", is("http://localhost:8080/my-alias")));
        mvc.perform(get("/api/v1/urls/unknown1")).andExpect(status().isNotFound());
    }

    @Test
    void invalidRequestsAre400ProblemJson() throws Exception {
        String[] bad = {
                "{}",
                "{\"url\":\"\"}",
                "{\"url\":\"javascript:alert(1)\"}",
                "{\"url\":\"ftp://example.com\"}",
                "{\"url\":\"http://localhost:8080/abc\"}",
                "{\"url\":\"https://example.com\",\"customAlias\":\"a b\"}",
                "{\"url\":\"https://example.com\",\"customAlias\":\"api\"}",
                "{\"url\":\"https://example.com\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}",
                "{\"url\":\"https://example.com/" + "a".repeat(2100) + "\"}",
                "not json at all"};
        for (String body : bad) {
            mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    void aDuplicateAliasIsA409() throws Exception {
        create("{\"url\":\"https://example.com/1\",\"customAlias\":\"dup-alias\"}");
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/2\",\"customAlias\":\"dup-alias\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void theIdempotencyKeyMakesCreateSafeToRetry() throws Exception {
        String first = mvc.perform(post("/api/v1/urls").header("Idempotency-Key", "abc-123").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/idem\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/urls").header("Idempotency-Key", "abc-123").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/idem\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void statsCountClicksPerDayAndTopReferrers() throws Exception {
        String code = create("{\"url\":\"https://example.com/stats\"}");
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/" + code).header(HttpHeaders.REFERER, "https://news.example/story").header(HttpHeaders.USER_AGENT, "JUnit"))
                    .andExpect(status().isFound());
        }
        mvc.perform(get("/" + code).header(HttpHeaders.REFERER, "https://blog.example/post")).andExpect(status().isFound());
        mvc.perform(get("/" + code)).andExpect(status().isFound());
        clickRecorder.awaitIdle(5000);

        mvc.perform(get("/api/v1/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks", is(5)))
                .andExpect(jsonPath("$.perDay", hasSize(1)))
                .andExpect(jsonPath("$.perDay[0].clicks", is(5)))
                .andExpect(jsonPath("$.topReferrers[0].referrer", is("news.example")))
                .andExpect(jsonPath("$.topReferrers[0].clicks", is(3)))
                .andExpect(jsonPath("$.topReferrers[?(@.referrer=='(direct)')].clicks").value(1));
    }

    @Test
    void statsHonourTheRequestedRange() throws Exception {
        String code = create("{\"url\":\"https://example.com/range\"}");
        mvc.perform(get("/" + code)).andExpect(status().isFound());
        clickRecorder.awaitIdle(5000);
        String future = java.time.Instant.now().plus(java.time.Duration.ofDays(1)).toString();
        String later = java.time.Instant.now().plus(java.time.Duration.ofDays(2)).toString();
        mvc.perform(get("/api/v1/urls/" + code + "/stats?from=" + future + "&to=" + later))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalClicks", is(0)));
        mvc.perform(get("/api/v1/urls/" + code + "/stats?from=" + later + "&to=" + future)).andExpect(status().isBadRequest());
    }

    @Test
    void rawClientIpsAreNeverStored() throws Exception {
        String code = create("{\"url\":\"https://example.com/pii\"}");
        mvc.perform(get("/" + code).with(r -> {
            r.setRemoteAddr("203.0.113.77");
            return r;
        })).andExpect(status().isFound());
        clickRecorder.awaitIdle(5000);

        String stored = jdbc.sql("select ip_hash from click_events limit 1").query(String.class).single();
        assertThat(stored).matches("[0-9a-f]{64}").doesNotContain("203.0.113.77");
        Integer leaked = jdbc.sql("select count(*) from click_events where ip_hash like '%203.0.113%' or user_agent like '%203.0.113%'")
                .query(Integer.class).single();
        assertThat(leaked).isZero();
    }

    @Test
    void healthEndpointsAreUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("UP")));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }
}
