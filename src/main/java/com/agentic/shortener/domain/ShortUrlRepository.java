package com.agentic.shortener.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByShortCode(String shortCode);

    Optional<ShortUrl> findByIdempotencyKey(String idempotencyKey);

    boolean existsByShortCode(String shortCode);
}
