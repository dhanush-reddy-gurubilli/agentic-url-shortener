package com.agentic.shortener.web;

import com.agentic.shortener.service.ClickRecorder;
import com.agentic.shortener.service.ShortenerService;
import com.agentic.shortener.service.ShortenerService.ResolvedLink;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;

@RestController
@Tag(name = "Redirect")
public class RedirectController {

    private final ShortenerService service;
    private final ClickRecorder clickRecorder;
    private final Clock clock;

    public RedirectController(ShortenerService service, ClickRecorder clickRecorder, Clock clock) {
        this.service = service;
        this.clickRecorder = clickRecorder;
        this.clock = clock;
    }

    /** 302 (not 301) so browsers don't cache the redirect and every click is counted. */
    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    @Operation(summary = "Redirect to the original URL (302); 404 if unknown, 410 if expired or deleted")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        ResolvedLink link = service.resolve(code);
        clickRecorder.record(link.id(), clock.instant(), request.getHeader(HttpHeaders.REFERER),
                request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.originalUrl()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
