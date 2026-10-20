package com.agentic.shortener.service;

import com.agentic.IntegrationTest;
import com.agentic.shortener.ShortenerExceptions.AliasTaken;
import com.agentic.shortener.ShortenerExceptions.Gone;
import com.agentic.shortener.ShortenerExceptions.InvalidRequest;
import com.agentic.shortener.ShortenerExceptions.NotFound;
import com.agentic.shortener.ShortenerFixtures;
import com.agentic.shortener.ShortenerFixtures.MutableClock;
import com.agentic.shortener.ShortenerProperties;
import com.agentic.shortener.domain.ClickEventRepository;
import com.agentic.shortener.domain.ShortUrl;
import com.agentic.shortener.domain.ShortUrlRepository;
import com.agentic.shortener.service.ShortenerService.CreateCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortenerServiceTest extends IntegrationTest {

    @Autowired
    ShortUrlRepository urls;
    @Autowired
    ClickEventRepository clicks;

    final ShortenerProperties props = ShortenerFixtures.properties();
    MutableClock clock;
    ShortenerService service;

    /** Generator that hands out scripted codes first, then random ones. */
    static class ScriptedCodes extends ShortCodeGenerator {
        private final Deque<String> scripted = new ArrayDeque<>();

        ScriptedCodes(ShortenerProperties props, String... codes) {
            super(props);
            scripted.addAll(List.of(codes));
        }

        @Override
        public synchronized String next() {
            return scripted.isEmpty() ? super.next() : scripted.poll();
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
        service = serviceWith(new ShortCodeGenerator(props));
    }

    private ShortenerService serviceWith(ShortCodeGenerator generator) {
        return new ShortenerService(urls, clicks, generator, new UrlValidator(props), clock, props);
    }

    private ShortUrl create(String url) {
        return service.create(new CreateCommand(url, null, null, null));
    }

    @Test
    void createStoresTheUrlUnderARandomCode() {
        ShortUrl created = create("https://example.com/page");
        assertThat(created.getShortCode()).matches("[0-9A-Za-z]{7}");
        assertThat(created.getOriginalUrl()).isEqualTo("https://example.com/page");
        assertThat(created.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(urls.findByShortCode(created.getShortCode())).isPresent();
    }

    @Test
    void aCustomAliasBecomesTheCode() {
        ShortUrl created = service.create(new CreateCommand("https://example.com", "my-link", null, null));
        assertThat(created.getShortCode()).isEqualTo("my-link");
    }

    @Test
    void aTakenAliasIsAConflict() {
        service.create(new CreateCommand("https://example.com", "taken", null, null));
        assertThatThrownBy(() -> service.create(new CreateCommand("https://other.example", "taken", null, null)))
                .isInstanceOf(AliasTaken.class);
    }

    @Test
    void invalidInputIsRejectedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> create("javascript:alert(1)")).isInstanceOf(InvalidRequest.class);
        assertThatThrownBy(() -> service.create(new CreateCommand("https://example.com", "api", null, null)))
                .isInstanceOf(InvalidRequest.class);
        assertThat(urls.count()).isZero();
    }

    @Test
    void anExpiryInThePastIsRejected() {
        assertThatThrownBy(() -> service.create(new CreateCommand("https://example.com", null, clock.instant().minusSeconds(1), null)))
                .hasMessageContaining("future");
        assertThatThrownBy(() -> service.create(new CreateCommand("https://example.com", null, clock.instant(), null)))
                .hasMessageContaining("future");
    }

    @Test
    void aCodeCollisionIsRetriedWithAFreshCode() {
        urls.save(new ShortUrl("COLLIDE", "https://first.example", clock.instant(), null, null));
        ShortenerService svc = serviceWith(new ScriptedCodes(props, "COLLIDE", "COLLIDE", "Fresh01"));

        ShortUrl created = svc.create(new CreateCommand("https://second.example", null, null, null));

        assertThat(created.getShortCode()).isEqualTo("Fresh01");
        assertThat(urls.findByShortCode("COLLIDE").orElseThrow().getOriginalUrl()).isEqualTo("https://first.example");
    }

    @Test
    void givingUpAfterThreeCollisionsIsAnError() {
        urls.save(new ShortUrl("COLLIDE", "https://first.example", clock.instant(), null, null));
        ShortenerService svc = serviceWith(new ScriptedCodes(props, "COLLIDE", "COLLIDE", "COLLIDE", "NeverUsd"));
        assertThatThrownBy(() -> svc.create(new CreateCommand("https://second.example", null, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unique");
    }

    @Test
    void anIdempotencyKeyReturnsTheOriginalLink() {
        ShortUrl first = service.create(new CreateCommand("https://example.com/a", null, null, "key-1"));
        ShortUrl again = service.create(new CreateCommand("https://example.com/a", null, null, "key-1"));
        assertThat(again.getShortCode()).isEqualTo(first.getShortCode());
        assertThat(urls.count()).isEqualTo(1);
    }

    @Test
    void timestampsAreTruncatedToMicrosecondsSoWhatIsReturnedIsWhatIsStored() {
        // A clock with nanosecond precision, like a real one: PostgreSQL would silently drop the extra digits.
        clock = new MutableClock(Instant.parse("2026-03-01T10:00:00.123456789Z"));
        service = serviceWith(new ShortCodeGenerator(props));

        ShortUrl created = service.create(new CreateCommand("https://example.com/t", null,
                Instant.parse("2026-03-01T11:00:00.987654321Z"), null));

        assertThat(created.getCreatedAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00.123456Z"));
        assertThat(created.getExpiresAt()).isEqualTo(Instant.parse("2026-03-01T11:00:00.987654Z"));
        ShortUrl stored = urls.findByShortCode(created.getShortCode()).orElseThrow();
        assertThat(stored.getCreatedAt()).isEqualTo(created.getCreatedAt());
        assertThat(stored.getExpiresAt()).isEqualTo(created.getExpiresAt());
    }

    @Test
    void concurrentCreatesWithTheSameIdempotencyKeyYieldOneLink() throws Exception {
        List<String> codes = runConcurrently(12, i -> service.create(
                new CreateCommand("https://example.com/same", null, null, "same-key")).getShortCode());
        assertThat(Set.copyOf(codes)).hasSize(1);
        assertThat(urls.count()).isEqualTo(1);
    }

    @Test
    void concurrentCreatesOfTheSameAliasHaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                go.await();
                try {
                    service.create(new CreateCommand("https://example.com/" + n, "race-alias", null, null));
                    return true;
                } catch (AliasTaken e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int winners = 0;
        for (Future<Boolean> f : results) {
            winners += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
        assertThat(urls.count()).isEqualTo(1);
    }

    @Test
    void manyConcurrentCreatesAllGetDistinctCodes() throws Exception {
        List<String> codes = runConcurrently(40, i -> create("https://example.com/" + i).getShortCode());
        assertThat(codes.stream().collect(Collectors.toSet())).hasSize(40);
    }

    @Test
    void resolveReturnsTheTargetAndUnknownCodesAreNotFound() {
        ShortUrl created = create("https://example.com/target");
        assertThat(service.resolve(created.getShortCode()).originalUrl()).isEqualTo("https://example.com/target");
        assertThatThrownBy(() -> service.resolve("nope123")).isInstanceOf(NotFound.class);
    }

    @Test
    void aDeletedLinkIsGoneAndStaysGoneEvenIfItWasCached() {
        ShortUrl created = create("https://example.com/target");
        service.resolve(created.getShortCode());   // warm the cache
        service.delete(created.getShortCode());

        assertThatThrownBy(() -> service.resolve(created.getShortCode())).isInstanceOf(Gone.class).hasMessageContaining("deleted");
        assertThat(service.get(created.getShortCode()).isDeleted()).isTrue();
    }

    @Test
    void deletingTwiceIsHarmlessAndDeletingUnknownIsNotFound() {
        ShortUrl created = create("https://example.com/target");
        service.delete(created.getShortCode());
        Instant firstDeletion = service.get(created.getShortCode()).getDeletedAt();
        clock.advance(Duration.ofHours(1));
        service.delete(created.getShortCode());
        assertThat(service.get(created.getShortCode()).getDeletedAt()).isEqualTo(firstDeletion);
        assertThatThrownBy(() -> service.delete("unknown1")).isInstanceOf(NotFound.class);
    }

    @Test
    void aLinkThatExpiredBeforeItWasFirstLoadedIsGone() {
        ShortUrl created = service.create(new CreateCommand("https://example.com/t", null, clock.instant().plusSeconds(60), null));
        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> service.resolve(created.getShortCode())).isInstanceOf(Gone.class).hasMessageContaining("expired");
    }

    @Test
    void statsRejectAnInvertedRange() {
        ShortUrl created = create("https://example.com/t");
        assertThatThrownBy(() -> service.stats(created.getShortCode(), clock.instant(), clock.instant().minusSeconds(5)))
                .isInstanceOf(InvalidRequest.class);
    }

    @Test
    void statsForAnUnknownCodeAreNotFound() {
        assertThatThrownBy(() -> service.stats("unknown1", null, null)).isInstanceOf(NotFound.class);
    }

    private interface Task<T> {
        T run(int index) throws Exception;
    }

    private <T> List<T> runConcurrently(int threads, Task<T> task) throws InterruptedException, ExecutionException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                go.await();
                return task.run(n);
            }));
        }
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }
}
