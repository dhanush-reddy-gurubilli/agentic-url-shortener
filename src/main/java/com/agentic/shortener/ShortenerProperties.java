package com.agentic.shortener;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        String baseUrl,
        int codeLength,
        Duration cacheTtl,
        long cacheMaxSize,
        int createRateLimitPerMinute,
        int clickExecutorQueueCapacity,
        String ipHashSalt,
        List<String> reservedAliases) {
}
