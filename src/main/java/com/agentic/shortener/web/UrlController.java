package com.agentic.shortener.web;

import com.agentic.shortener.ShortenerProperties;
import com.agentic.shortener.domain.ShortUrl;
import com.agentic.shortener.service.ShortenerService;
import com.agentic.shortener.service.ShortenerService.CreateCommand;
import com.agentic.shortener.web.ShortenerDtos.CreateUrlRequest;
import com.agentic.shortener.web.ShortenerDtos.UrlResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/urls")
@Tag(name = "URLs", description = "Create, inspect and delete short URLs")
public class UrlController {

    private final ShortenerService service;
    private final String baseUrl;

    public UrlController(ShortenerService service, ShortenerProperties properties) {
        this.service = service;
        this.baseUrl = properties.baseUrl();
    }

    @PostMapping
    @Operation(summary = "Create a short URL")
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest request,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ShortUrl created = service.create(new CreateCommand(request.url(), request.customAlias(), request.expiresAt(), idempotencyKey));
        UrlResponse body = UrlResponse.of(created, baseUrl);
        return ResponseEntity.created(URI.create(body.shortUrl())).body(body);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Get short URL metadata")
    public UrlResponse get(@PathVariable String code) {
        return UrlResponse.of(service.get(code), baseUrl);
    }

    @DeleteMapping("/{code}")
    @Operation(summary = "Soft-delete a short URL; it then redirects with 410 Gone")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        service.delete(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{code}/stats")
    @Operation(summary = "Click analytics: total, per day (UTC) and top referrers; defaults to the last 30 days")
    public ShortenerService.Stats stats(@PathVariable String code,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.stats(code, from, to);
    }
}
