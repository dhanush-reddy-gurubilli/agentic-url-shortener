package com.agentic.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "click_events")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "url_id", nullable = false)
    private Long urlId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "referrer_host")
    private String referrerHost;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "ip_hash")
    private String ipHash;

    protected ClickEvent() {
    }

    public ClickEvent(Long urlId, Instant occurredAt, String referrerHost, String userAgent, String ipHash) {
        this.urlId = urlId;
        this.occurredAt = occurredAt;
        this.referrerHost = referrerHost;
        this.userAgent = userAgent;
        this.ipHash = ipHash;
    }

    public Long getId() {
        return id;
    }

    public Long getUrlId() {
        return urlId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getReferrerHost() {
        return referrerHost;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpHash() {
        return ipHash;
    }
}
