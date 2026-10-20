CREATE TABLE urls (
    id              BIGSERIAL PRIMARY KEY,
    short_code      VARCHAR(32)  NOT NULL UNIQUE,
    original_url    TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    idempotency_key VARCHAR(64)  UNIQUE
);

CREATE TABLE click_events (
    id            BIGSERIAL PRIMARY KEY,
    url_id        BIGINT       NOT NULL REFERENCES urls (id),
    occurred_at   TIMESTAMPTZ  NOT NULL,
    referrer_host VARCHAR(255),
    user_agent    VARCHAR(255),
    -- Compliance: client IPs are stored only as salted SHA-256 hashes, never raw
    ip_hash       VARCHAR(64)
);

CREATE INDEX idx_click_events_url_time ON click_events (url_id, occurred_at);
