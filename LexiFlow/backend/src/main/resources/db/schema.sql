-- LexiFlow backend schema. Executed at startup; every statement must be idempotent.
-- Data minimisation: accounts are anonymous (no e-mail/phone), battle texts are not stored
-- (only scores); reports keep a snapshot of the reported text for moderation.

CREATE TABLE IF NOT EXISTS users (
    id           VARCHAR(36)  PRIMARY KEY,
    display_name VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS battle_results (
    id           BIGSERIAL    PRIMARY KEY,
    room_id      VARCHAR(36)  NOT NULL,
    mode         VARCHAR(32)  NOT NULL,
    player_id    VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    display_name VARCHAR(32)  NOT NULL,
    overall      INTEGER      NOT NULL,
    placement    INTEGER      NOT NULL,
    won          BOOLEAN      NOT NULL,
    submitted    BOOLEAN      NOT NULL,
    finished_at  TIMESTAMPTZ  NOT NULL,
    UNIQUE (room_id, player_id)
);

CREATE INDEX IF NOT EXISTS idx_battle_results_mode ON battle_results (mode);
CREATE INDEX IF NOT EXISTS idx_battle_results_player ON battle_results (player_id);

CREATE TABLE IF NOT EXISTS reports (
    id            VARCHAR(36)  PRIMARY KEY,
    room_id       VARCHAR(36)  NOT NULL,
    reporter_id   VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reported_id   VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason        VARCHAR(500) NOT NULL,
    reported_text TEXT,
    status        VARCHAR(16)  NOT NULL DEFAULT 'open',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reports_status ON reports (status);
