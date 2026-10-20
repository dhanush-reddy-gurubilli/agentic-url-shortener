package com.agentic.shortener;

import com.agentic.IntegrationTest;
import com.agentic.shortener.ShortenerFixtures.MutableClock;
import com.agentic.shortener.domain.ClickEventRepository;
import com.agentic.shortener.domain.ShortUrlRepository;
import com.agentic.shortener.service.ShortCodeGenerator;
import com.agentic.shortener.service.ShortenerService;
import com.agentic.shortener.service.ShortenerService.CreateCommand;
import com.agentic.shortener.service.UrlValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents a KNOWN DEFECT that the baseline ships with on purpose: expiry is checked only when a link is loaded into
 * the redirect cache, so a link that expires while cached keeps redirecting until its cache entry expires.
 * The brownfield scenario ("expired links still redirect sometimes") finds and fixes it; the patch replaces this
 * test with one asserting the correct behaviour.
 */
class ExpiryDefectTest extends IntegrationTest {

    @Autowired
    ShortUrlRepository urls;
    @Autowired
    ClickEventRepository clicks;

    @Test
    void knownDefect_aCachedLinkKeepsRedirectingAfterItExpires() {
        ShortenerProperties props = ShortenerFixtures.properties();
        MutableClock clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
        ShortenerService service = new ShortenerService(urls, clicks, new ShortCodeGenerator(props),
                new UrlValidator(props), clock, props);
        String code = service.create(new CreateCommand("https://example.com/t", null, clock.instant().plusSeconds(60), null)).getShortCode();
        service.resolve(code);   // cached while still valid

        clock.advance(Duration.ofSeconds(120));   // now expired

        assertThat(service.resolve(code).originalUrl()).as("DEFECT: should be 410 Gone").isEqualTo("https://example.com/t");
        assertThat(urls.findByShortCode(code).orElseThrow().isExpiredAt(clock.instant())).isTrue();
    }
}
