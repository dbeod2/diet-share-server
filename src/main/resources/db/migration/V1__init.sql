-- Users -----------------------------------------------------------------

CREATE TABLE users (
    id               BIGSERIAL PRIMARY KEY,
    provider         VARCHAR(20)  NOT NULL,
    provider_user_id VARCHAR(100) NOT NULL,
    nickname         VARCHAR(30)  NOT NULL,
    goal_type        VARCHAR(20)  NOT NULL DEFAULT 'HEALTH',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_provider UNIQUE (provider, provider_user_id)
);

CREATE TABLE user_profiles (
    user_id        BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    liked_foods    JSONB NOT NULL DEFAULT '[]',
    disliked_foods JSONB NOT NULL DEFAULT '[]',
    diet_type      VARCHAR(20),
    height_cm      INT,
    weight_kg      NUMERIC(5, 1),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Recent 7-day summary fed into the AI prompt (not shown to the user).
CREATE TABLE user_pattern_summaries (
    user_id    BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    summary    JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Meals -----------------------------------------------------------------

CREATE TABLE meals (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    request_id VARCHAR(64) NOT NULL,
    meal_type  VARCHAR(10) NOT NULL,
    taken_at   TIMESTAMPTZ NOT NULL,
    -- PENDING / ANALYZING / DONE / FAILED / NOT_FOOD / LIMITED / REJECTED
    status     VARCHAR(20) NOT NULL,
    temp_key   VARCHAR(200),
    image_key  VARCHAR(200),
    thumb_key  VARCHAR(200),
    image_hash VARCHAR(64),
    -- KST date whose analysis quota this meal reserved; used for refunds.
    quota_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_meals_request UNIQUE (user_id, request_id)
);

CREATE INDEX idx_meals_user_taken ON meals (user_id, taken_at);
CREATE INDEX idx_meals_user_hash ON meals (user_id, image_hash) WHERE image_hash IS NOT NULL;

CREATE TABLE meal_analyses (
    meal_id    BIGINT PRIMARY KEY REFERENCES meals (id) ON DELETE CASCADE,
    foods      JSONB        NOT NULL,
    grade      VARCHAR(10)  NOT NULL,
    nutrients  JSONB        NOT NULL,
    feedback   TEXT,
    goal_type  VARCHAR(20)  NOT NULL,
    model      VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Work queue. API inserts in the same transaction as the meal; workers claim with FOR UPDATE SKIP LOCKED.
CREATE TABLE analysis_jobs (
    id         BIGSERIAL PRIMARY KEY,
    meal_id    BIGINT      NOT NULL REFERENCES meals (id) ON DELETE CASCADE,
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / RUNNING / DONE / FAILED
    attempts   INT         NOT NULL DEFAULT 0,
    run_after  TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at  TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_pending ON analysis_jobs (run_after) WHERE status = 'PENDING';
CREATE INDEX idx_jobs_running ON analysis_jobs (locked_at) WHERE status = 'RUNNING';

-- Rooms -----------------------------------------------------------------

CREATE TABLE rooms (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(20) NOT NULL,
    owner_id     BIGINT      NOT NULL REFERENCES users (id),
    invite_code  VARCHAR(8)  NOT NULL,
    member_count INT         NOT NULL DEFAULT 0,
    max_members  INT         NOT NULL DEFAULT 5,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_rooms_invite_code UNIQUE (invite_code),
    -- Last line of defence behind the conditional UPDATE used when joining.
    CONSTRAINT ck_rooms_capacity CHECK (member_count >= 0 AND member_count <= max_members)
);

CREATE TABLE room_members (
    room_id   BIGINT      NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    user_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role      VARCHAR(10) NOT NULL, -- OWNER / MEMBER
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

CREATE INDEX idx_room_members_user ON room_members (user_id);

-- A meal is stored once; sharing only links it to rooms. visible_at is set when moderation passes.
CREATE TABLE meal_shares (
    meal_id    BIGINT      NOT NULL REFERENCES meals (id) ON DELETE CASCADE,
    room_id    BIGINT      NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    visible_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (meal_id, room_id)
);

CREATE INDEX idx_meal_shares_feed ON meal_shares (room_id, visible_at DESC, meal_id DESC) WHERE visible_at IS NOT NULL;

CREATE TABLE reactions (
    meal_id    BIGINT      NOT NULL,
    room_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(10) NOT NULL, -- LIKE / GREAT / CHEER / YUMMY
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (meal_id, room_id, user_id, type),
    FOREIGN KEY (meal_id, room_id) REFERENCES meal_shares (meal_id, room_id) ON DELETE CASCADE
);

CREATE TABLE reports (
    id          BIGSERIAL PRIMARY KEY,
    meal_id     BIGINT      NOT NULL REFERENCES meals (id) ON DELETE CASCADE,
    room_id     BIGINT      NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    reporter_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason      VARCHAR(20) NOT NULL, -- INAPPROPRIATE / SPAM / OTHER
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reports UNIQUE (meal_id, room_id, reporter_id)
);

CREATE INDEX idx_reports_share ON reports (meal_id, room_id);
