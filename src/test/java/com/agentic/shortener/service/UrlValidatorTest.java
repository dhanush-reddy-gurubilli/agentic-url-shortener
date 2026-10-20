package com.agentic.shortener.service;

import com.agentic.shortener.ShortenerExceptions.InvalidRequest;
import com.agentic.shortener.ShortenerFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator(ShortenerFixtures.properties());

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://example.com/a/b?c=d#e", "HTTPS://Example.com/Path",
            "https://sub.example.co.uk:8443/x", "  https://example.com/trim  "})
    void ordinaryWebUrlsAreAccepted(String url) {
        assertThat(validator.validateTargetUrl(url)).isEqualTo(url.trim());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void blankUrlsAreRejected(String url) {
        assertThatThrownBy(() -> validator.validateTargetUrl(url)).isInstanceOf(InvalidRequest.class).hasMessageContaining("required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "data:text/html;base64,PHNjcmlwdD4=", "file:///etc/passwd", "ftp://example.com/x",
            "mailto:a@b.c", "//example.com/x", "example.com", "gopher://example.com"})
    void onlyHttpAndHttpsSchemesAreAllowed(String url) {
        assertThatThrownBy(() -> validator.validateTargetUrl(url)).isInstanceOf(InvalidRequest.class);
    }

    @Test
    void aUrlWithoutAHostIsRejected() {
        assertThatThrownBy(() -> validator.validateTargetUrl("https:///path-only")).isInstanceOf(InvalidRequest.class);
    }

    @Test
    void malformedUrisAreRejected() {
        assertThatThrownBy(() -> validator.validateTargetUrl("https://exa mple.com")).isInstanceOf(InvalidRequest.class);
        assertThatThrownBy(() -> validator.validateTargetUrl("https://example.com/<script>")).isInstanceOf(InvalidRequest.class);
    }

    @Test
    void theShortenerItselfCannotBeTheTargetBecauseThatWouldLoop() {
        assertThatThrownBy(() -> validator.validateTargetUrl("https://short.test/abc")).hasMessageContaining("loop");
        assertThatThrownBy(() -> validator.validateTargetUrl("http://SHORT.TEST/abc")).hasMessageContaining("loop");
    }

    @Test
    void urlLengthIsCapped() {
        String ok = "https://example.com/" + "a".repeat(2048 - 20);
        String tooLong = ok + "a";
        assertThat(validator.validateTargetUrl(ok)).hasSize(2048);
        assertThatThrownBy(() -> validator.validateTargetUrl(tooLong)).hasMessageContaining("2048");
    }

    @ParameterizedTest
    @ValueSource(strings = {"my-link", "My_Link_2", "abc", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void sensibleAliasesAreAccepted(String alias) {
        validator.validateAlias(alias);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "with/slash", "dot.dot", "ünïcode", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "semi;colon", "../etc"})
    void malformedAliasesAreRejected(String alias) {
        assertThatThrownBy(() -> validator.validateAlias(alias)).isInstanceOf(InvalidRequest.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "Actuator", "swagger-ui", "admin"})
    void reservedAliasesAreRejectedCaseInsensitively(String alias) {
        assertThatThrownBy(() -> validator.validateAlias(alias)).hasMessageContaining("reserved");
    }
}
