package com.agentic.shortener.service;

import com.agentic.shortener.ShortenerFixtures;
import com.agentic.shortener.ShortenerProperties;
import com.agentic.shortener.domain.ClickEvent;
import com.agentic.shortener.domain.ClickEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ClickRecorderTest {

    private static ShortenerProperties withQueue(int capacity) {
        var base = ShortenerFixtures.properties();
        return new ShortenerProperties(base.baseUrl(), base.codeLength(), base.cacheTtl(), base.cacheMaxSize(),
                base.createRateLimitPerMinute(), capacity, base.ipHashSalt(), base.reservedAliases());
    }

    @Test
    void clicksAreWrittenOffTheCallingThread() throws Exception {
        ClickEventRepository repo = mock(ClickEventRepository.class);
        List<String> savedOn = new CopyOnWriteArrayList<>();
        doAnswer(i -> {
            savedOn.add(Thread.currentThread().getName());
            return i.getArgument(0);
        }).when(repo).save(any(ClickEvent.class));
        var registry = new SimpleMeterRegistry();
        ClickRecorder recorder = new ClickRecorder(repo, new IpHasher(ShortenerFixtures.properties()), withQueue(100), registry);

        recorder.record(1, Instant.now(), "https://ref.example/x", "UA", "10.0.0.1");
        recorder.awaitIdle(2000);

        assertThat(savedOn).hasSize(1).allMatch(n -> n.startsWith("click-recorder-"));
        assertThat(registry.counter("shortener.clicks.recorded").count()).isEqualTo(1.0);
        recorder.destroy();
    }

    @Test
    void whenTheQueueIsFullClicksAreDroppedAndCountedInsteadOfBlockingTheRedirect() throws Exception {
        ClickEventRepository repo = mock(ClickEventRepository.class);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(i -> {
            release.await(10, TimeUnit.SECONDS);
            return i.getArgument(0);
        }).when(repo).save(any(ClickEvent.class));
        var registry = new SimpleMeterRegistry();
        ClickRecorder recorder = new ClickRecorder(repo, new IpHasher(ShortenerFixtures.properties()), withQueue(1), registry);

        long started = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            recorder.record(1, Instant.now(), null, "UA", "10.0.0.1");
        }
        Duration callerTime = Duration.ofNanos(System.nanoTime() - started);

        assertThat(callerTime).as("record() never blocks on a slow database").isLessThan(Duration.ofSeconds(1));
        // 2 worker threads busy + 1 queued = 3 accepted; the other 7 are dropped.
        assertThat(recorder.droppedCount()).isEqualTo(7.0);
        release.countDown();
        recorder.awaitIdle(5000);
        assertThat(registry.counter("shortener.clicks.recorded").count()).isEqualTo(3.0);
        recorder.destroy();
    }

    @Test
    void aFailingDatabaseWriteIsCountedAndDoesNotKillTheWorker() throws Exception {
        ClickEventRepository repo = mock(ClickEventRepository.class);
        doThrow(new IllegalStateException("db down")).doAnswer(i -> i.getArgument(0)).when(repo).save(any(ClickEvent.class));
        var registry = new SimpleMeterRegistry();
        ClickRecorder recorder = new ClickRecorder(repo, new IpHasher(ShortenerFixtures.properties()), withQueue(10), registry);

        recorder.record(1, Instant.now(), null, "UA", "10.0.0.1");
        recorder.awaitIdle(2000);
        recorder.record(1, Instant.now(), null, "UA", "10.0.0.1");
        recorder.awaitIdle(2000);

        assertThat(recorder.droppedCount()).isEqualTo(1.0);
        assertThat(registry.counter("shortener.clicks.recorded").count()).isEqualTo(1.0);
        recorder.destroy();
    }

    @Test
    void referrersAreReducedToTheirHostAndGarbageIsIgnored() {
        assertThat(ClickRecorder.referrerHost("https://news.example/story?id=1")).isEqualTo("news.example");
        assertThat(ClickRecorder.referrerHost("http://a.b:8080/x")).isEqualTo("a.b");
        assertThat(ClickRecorder.referrerHost(null)).isNull();
        assertThat(ClickRecorder.referrerHost("  ")).isNull();
        assertThat(ClickRecorder.referrerHost("not a uri at all")).isNull();
    }

    @Test
    void ipHashesAreSaltedStableAndIrreversibleLooking() {
        IpHasher hasher = new IpHasher(ShortenerFixtures.properties());
        String a = hasher.hash("203.0.113.5");
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(hasher.hash("203.0.113.5"));
        assertThat(a).isNotEqualTo(hasher.hash("203.0.113.6"));
        assertThat(hasher.hash(null)).isNull();
        assertThat(hasher.hash(" ")).isNull();
    }
}
