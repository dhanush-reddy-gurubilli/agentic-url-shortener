package com.agentic.shortener;

import com.agentic.IntegrationTest;
import com.agentic.shortener.ShortenerFixtures.MutableClock;
import com.agentic.shortener.service.CreateRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "shortener.create-rate-limit-per-minute=3")
class RateLimitTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void creatingIsLimitedPerClientButRedirectsAreNot() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/" + i + "\",\"customAlias\":\"rl-" + i + "\"}")).andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com/4\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/rl-0")).andExpect(status().isFound());
        }
        mvc.perform(post("/api/v1/urls").with(r -> {
            r.setRemoteAddr("198.51.100.9");
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com/other-client\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void theWindowResetsAfterAMinute() {
        var base = ShortenerFixtures.properties();
        var props = new ShortenerProperties(base.baseUrl(), base.codeLength(), base.cacheTtl(), base.cacheMaxSize(), 2,
                base.clickExecutorQueueCapacity(), base.ipHashSalt(), List.of());
        MutableClock clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
        CreateRateLimiter limiter = new CreateRateLimiter(props, clock);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).as("clients are independent").isTrue();

        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("a")).isTrue();
    }
}
