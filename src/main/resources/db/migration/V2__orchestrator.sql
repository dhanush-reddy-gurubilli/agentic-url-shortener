CREATE TABLE workflow_run (
    id               UUID PRIMARY KEY,
    scenario         VARCHAR(64),
    requirement      TEXT         NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    token_budget     BIGINT       NOT NULL,
    tokens_used      BIGINT       NOT NULL DEFAULT 0,
    policy_violations INT         NOT NULL DEFAULT 0,
    stop_requested   BOOLEAN      NOT NULL DEFAULT FALSE,
    status_reason    TEXT,
    workspace_path   TEXT,
    baseline_sha     VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    completed_at     TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE stage_execution (
    id              BIGSERIAL PRIMARY KEY,
    run_id          UUID         NOT NULL REFERENCES workflow_run (id),
    stage_key       VARCHAR(128) NOT NULL,
    agent           VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    depends_on      TEXT         NOT NULL DEFAULT '',
    dynamic_stage   BOOLEAN      NOT NULL DEFAULT FALSE,
    task_json       TEXT,
    generation      INT          NOT NULL DEFAULT 1,
    attempt         INT          NOT NULL DEFAULT 0,
    input_hashes    TEXT,
    output_artifact_id BIGINT,
    pending_artifact_id BIGINT,
    feedback        TEXT,
    error           TEXT,
    checkpoint_sha  VARCHAR(64),
    apply_order     INT,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (run_id, stage_key)
);

CREATE TABLE artifact (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID         NOT NULL REFERENCES workflow_run (id),
    stage_key   VARCHAR(128) NOT NULL,
    type        VARCHAR(64)  NOT NULL,
    version     INT          NOT NULL,
    content     TEXT         NOT NULL,
    sha256      CHAR(64)     NOT NULL,
    created_by  VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_artifact_run_stage ON artifact (run_id, stage_key);

CREATE TABLE decision (
    id           BIGSERIAL PRIMARY KEY,
    run_id       UUID         NOT NULL REFERENCES workflow_run (id),
    stage_key    VARCHAR(128),
    actor        VARCHAR(64)  NOT NULL,
    type         VARCHAR(64)  NOT NULL,
    summary      TEXT         NOT NULL,
    rationale    TEXT,
    input_hashes TEXT,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_decision_run ON decision (run_id);

CREATE TABLE approval_request (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID         NOT NULL REFERENCES workflow_run (id),
    stage_key   VARCHAR(128) NOT NULL,
    reasons     TEXT         NOT NULL,
    details     TEXT,
    status      VARCHAR(16)  NOT NULL,
    decided_by  VARCHAR(64),
    comment     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    decided_at  TIMESTAMPTZ
);
CREATE INDEX idx_approval_status ON approval_request (status);

CREATE TABLE clarification (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID         NOT NULL REFERENCES workflow_run (id),
    question    TEXT         NOT NULL,
    proposed_assumption TEXT,
    answer      TEXT,
    answered_by VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL,
    answered_at TIMESTAMPTZ
);

-- Append-only, hash-chained audit trail. hash = sha256(prev_hash || canonical event fields)
CREATE TABLE audit_event (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID,
    stage_key   VARCHAR(128),
    actor       VARCHAR(64)  NOT NULL,
    event_type  VARCHAR(64)  NOT NULL,
    payload     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    prev_hash   CHAR(64)     NOT NULL,
    hash        CHAR(64)     NOT NULL UNIQUE
);
CREATE INDEX idx_audit_run ON audit_event (run_id);
