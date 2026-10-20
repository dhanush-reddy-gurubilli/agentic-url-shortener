package com.agentic.shortener.web;

import com.agentic.shortener.domain.ShortUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ShortenerDtos {

    private ShortenerDtos() {
    }

    public record CreateUrlRequest(
            @NotBlank @Size(max = 2048) @Schema(example = "https://example.com/some/long/path") String url,
            @Size(max = 32) @Schema(example = "my-link", nullable = true) String customAlias,
            @Schema(nullable = true) Instant expiresAt) {
    }

    public record UrlResponse(String code, String shortUrl, String originalUrl, Instant createdAt,
                              Instant expiresAt, Instant deletedAt) {

        static UrlResponse of(ShortUrl url, String baseUrl) {
            return new UrlResponse(url.getShortCode(), baseUrl + "/" + url.getShortCode(), url.getOriginalUrl(),
                    url.getCreatedAt(), url.getExpiresAt(), url.getDeletedAt());
        }
    }
}
