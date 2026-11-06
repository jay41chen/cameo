-- 單一測試使用者：user_id 對應 points_account.id。
-- cost 是建單時的片段點數快照；點數占用與額度都由這張表計算，不另存計數器。
CREATE TABLE cameos (
    id                text PRIMARY KEY DEFAULT gen_random_uuid()::text,
    user_id           integer NOT NULL REFERENCES points_account (id),
    idempotency_key   text NOT NULL,
    clip_id           text NOT NULL REFERENCES clips (id),
    cost              integer NOT NULL CHECK (cost > 0),
    status            text NOT NULL CHECK (status IN
        ('AWAITING_UPLOAD', 'UPLOADED', 'QUEUED', 'GENERATING', 'SUCCEEDED', 'FAILED', 'EXPIRED')),
    points_state      text NOT NULL CHECK (points_state IN ('HELD', 'CHARGED', 'REFUNDED', 'RELEASED')),
    failure_reason    text,
    upload_expires_at timestamptz NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    UNIQUE (user_id, idempotency_key)
);

CREATE INDEX cameos_user_created_idx ON cameos (user_id, created_at);
CREATE INDEX cameos_expiry_idx ON cameos (upload_expires_at) WHERE status IN ('AWAITING_UPLOAD', 'UPLOADED');
