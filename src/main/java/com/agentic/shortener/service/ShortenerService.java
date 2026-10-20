package com.agentic.shortener.service;

import com.agentic.shortener.ShortenerExceptions.AliasTaken;
import com.agentic.shortener.ShortenerExceptions.Gone;
import com.agentic.shortener.ShortenerExceptions.InvalidRequest;
import com.agentic.shortener.ShortenerExceptions.NotFound;
import com.agentic.shortener.ShortenerProperties;
import com.agentic.shortener.domain.ClickEventRepository;
import com.agentic.shortener.domain.ShortUrl;
import com.agentic.shortener.domain.ShortUrlRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class ShortenerService {

    static final int MAX_GENERATION_ATTEMPTS = 3;
    private static final Duration DEFAULT_STATS_WINDOW = Duration.ofDays(30);

    private final ShortUrlRepository urls;
    private final ClickEventRepository clicks;
    private final ShortCodeGenerator codeGenerator;
    private final UrlValidator validator;
    private final Clock clock;
    private final Cache<String, CachedLink> redirectCache;

    public ShortenerService(ShortUrlRepository urls, ClickEventRepository clicks, ShortCodeGenerator codeGenerator,
                            UrlValidator validator, Clock clock, ShortenerProperties properties) {
        this.urls = urls;
        this.clicks = clicks;
        this.codeGenerator = codeGenerator;
        this.validator = validator;
        this.clock = clock;
        this.redirectCache = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(properties.cacheMaxSize())
                .build();
    }

    public record CreateCommand(String url, String customAlias, Instant expiresAt, String idempotencyKey) {
    }

    /** Cached view of a link on the redirect hot path. */
    record CachedLink(long id, String originalUrl, Instant expiresAt) {
    }

    public record ResolvedLink(long id, String originalUrl) {
    }

    public record Stats(String code, Instant from, Instant to, long totalClicks,
                        List<ClickEventRepository.DailyClicks> perDay,
                        List<ClickEventRepository.ReferrerClicks> topReferrers) {
    }

    public ShortUrl create(CreateCommand command) {
        if (command.idempotencyKey() != null) {
            Optional<ShortUrl> existing = urls.findByIdempotencyKey(command.idempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String url = validator.validateTargetUrl(command.url());
        // PostgreSQL keeps microseconds. Truncating up front makes the create response identical to what is stored, so
        // a retry with the same idempotency key returns exactly the same link.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = command.expiresAt() == null ? null : command.expiresAt().truncatedTo(ChronoUnit.MICROS);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new InvalidRequest("expiresAt must be in the future");
        }
        if (command.customAlias() != null && !command.customAlias().isBlank()) {
            validator.validateAlias(command.customAlias());
            try {
                return urls.saveAndFlush(new ShortUrl(command.customAlias(), url, now, expiresAt, command.idempotencyKey()));
            } catch (DataIntegrityViolationException e) {
                throw new AliasTaken(command.customAlias());
            }
        }
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                return urls.saveAndFlush(new ShortUrl(codeGenerator.next(), url, now, expiresAt, command.idempotencyKey()));
            } catch (DataIntegrityViolationException e) {
                if (command.idempotencyKey() != null) {
                    // A concurrent request with the same key won the race: return its result.
                    Optional<ShortUrl> existing = urls.findByIdempotencyKey(command.idempotencyKey());
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                }
                // Otherwise a short-code collision: retry with a new code.
            }
        }
        throw new IllegalStateException("Could not generate a unique short code after " + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    /**
     * Resolves a code for redirecting. The link's state is checked when it is loaded into the cache.
     *
     * <p>KNOWN DEFECT (seeded on purpose for the brownfield scenario, see docs/SCENARIOS.md): expiry is only
     * evaluated on cache load, so a link that expires while cached keeps redirecting until the cache entry
     * itself expires.
     */
    public ResolvedLink resolve(String code) {
        CachedLink link = redirectCache.get(code, this::loadActiveLink);
        return new ResolvedLink(link.id(), link.originalUrl());
    }

    private CachedLink loadActiveLink(String code) {
        ShortUrl url = urls.findByShortCode(code).orElseThrow(() -> new NotFound(code));
        if (url.isDeleted()) {
            throw new Gone(code, "deleted");
        }
        if (url.isExpiredAt(clock.instant())) {
            throw new Gone(code, "expired");
        }
        return new CachedLink(url.getId(), url.getOriginalUrl(), url.getExpiresAt());
    }

    public ShortUrl get(String code) {
        return urls.findByShortCode(code).orElseThrow(() -> new NotFound(code));
    }

    public void delete(String code) {
        ShortUrl url = get(code);
        if (!url.isDeleted()) {
            url.markDeleted(clock.instant());
            urls.save(url);
        }
        redirectCache.invalidate(code);
    }

    public Stats stats(String code, Instant from, Instant to) {
        ShortUrl url = get(code);
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(DEFAULT_STATS_WINDOW);
        if (!start.isBefore(end)) {
            throw new InvalidRequest("'from' must be before 'to'");
        }
        return new Stats(code, start, end,
                clicks.countInRange(url.getId(), start, end),
                clicks.clicksPerDay(url.getId(), start, end),
                clicks.topReferrers(url.getId(), start, end));
    }
}
