package com.agentic.shortener.web;

import com.agentic.shortener.ShortenerExceptions.AliasTaken;
import com.agentic.shortener.ShortenerExceptions.Gone;
import com.agentic.shortener.ShortenerExceptions.InvalidRequest;
import com.agentic.shortener.ShortenerExceptions.NotFound;
import com.agentic.shortener.ShortenerExceptions.RateLimited;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps shortener failures to RFC 9457 {@code application/problem+json}. */
@RestControllerAdvice(basePackages = "com.agentic.shortener")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShortenerExceptionHandler {

    @ExceptionHandler(NotFound.class)
    ProblemDetail notFound(NotFound e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(Gone.class)
    ProblemDetail gone(Gone e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
    }

    @ExceptionHandler(InvalidRequest.class)
    ProblemDetail invalid(InvalidRequest e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(AliasTaken.class)
    ProblemDetail aliasTaken(AliasTaken e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RateLimited.class)
    ProblemDetail rateLimited(RateLimited e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }
}
