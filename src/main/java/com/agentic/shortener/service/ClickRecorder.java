package com.agentic.shortener.service;

import com.agentic.shortener.ShortenerProperties;
import com.agentic.shortener.domain.ClickEvent;
import com.agentic.shortener.domain.ClickEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Records clicks off the redirect hot path on a small bounded executor. When the queue is full the click
 * is dropped and counted: redirect availability is deliberately preferred over analytics completeness.
 */
@Component
public class ClickRecorder implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);

    private final ClickEventRepository repository;
    private final IpHasher ipHasher;
    private final ThreadPoolExecutor executor;
    private final Counter recorded;
    private final Counter dropped;

    public ClickRecorder(ClickEventRepository repository, IpHasher ipHasher, ShortenerProperties properties,
                         MeterRegistry meterRegistry) {
        this.repository = repository;
        this.ipHasher = ipHasher;
        this.executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.clickExecutorQueueCapacity()),
                Thread.ofPlatform().name("click-recorder-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.recorded = meterRegistry.counter("shortener.clicks.recorded");
        this.dropped = meterRegistry.counter("shortener.clicks.dropped");
    }

    public void record(long urlId, Instant at, String referrer, String userAgent, String clientIp) {
        ClickEvent event = new ClickEvent(urlId, at, referrerHost(referrer), truncate(userAgent, 255), ipHasher.hash(clientIp));
        try {
            executor.execute(() -> save(event));
        } catch (RejectedExecutionException e) {
            dropped.increment();
        }
    }

    private void save(ClickEvent event) {
        try {
            repository.save(event);
            recorded.increment();
        } catch (RuntimeException e) {
            dropped.increment();
            log.warn("Failed to record click for url {}: {}", event.getUrlId(), e.getMessage());
        }
    }

    public double droppedCount() {
        return dropped.count();
    }

    /** Test hook: waits until queued clicks are written. */
    public void awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while ((executor.getActiveCount() > 0 || !executor.getQueue().isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    static String referrerHost(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(referrer.trim()).getHost();
            return truncate(host, 255);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
