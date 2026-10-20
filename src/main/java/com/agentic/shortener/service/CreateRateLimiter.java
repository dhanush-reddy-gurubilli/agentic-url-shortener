package com.agentic.shortener.service;

import com.agentic.shortener.ShortenerProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory fixed-window limiter keyed by client IP (single instance only, by design).
 */
@Component
public class CreateRateLimiter {

    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();

    private record Window(long startMillis, AtomicInteger count) {
    }

    private final int limitPerMinute;
    private final Clock clock;
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(100_000)
            .build();

    public CreateRateLimiter(ShortenerProperties properties, Clock clock) {
        this.limitPerMinute = properties.createRateLimitPerMinute();
        this.clock = clock;
    }

    /** Returns true if the request is allowed. */
    public boolean tryAcquire(String clientKey) {
        long now = clock.millis();
        Window window = windows.asMap().compute(clientKey, (k, current) ->
                current == null || now - current.startMillis() >= WINDOW_MILLIS
                        ? new Window(now, new AtomicInteger(1))
                        : increment(current));
        return window.count().get() <= limitPerMinute;
    }

    private static Window increment(Window w) {
        w.count().incrementAndGet();
        return w;
    }
}
