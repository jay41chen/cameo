-- 廠商生成：影片位置、生成期限（涵蓋排隊與生成）、已收到的廠商事件。
ALTER TABLE cameos
    ADD COLUMN video_url text,
    ADD COLUMN generation_expires_at timestamptz;

-- 既有排隊中／生成中的訂單補上期限，避免永遠卡住。
UPDATE cameos SET generation_expires_at = updated_at + interval '30 minutes'
    WHERE status IN ('QUEUED', 'GENERATING');

CREATE INDEX cameos_generation_expiry_idx ON cameos (generation_expires_at)
    WHERE status IN ('QUEUED', 'GENERATING');

-- 廠商回呼的去重表。job_id 不加外鍵：找不到訂單的回呼交易會回滾，事件不留。
CREATE TABLE vendor_events (
    event_id    text PRIMARY KEY,
    job_id      text NOT NULL,
    status      text NOT NULL,
    reason      text,
    received_at timestamptz NOT NULL
);

-- 每筆訂單最多一筆 REFUND 明細，防止重複退款。
CREATE UNIQUE INDEX ledger_one_refund_per_cameo ON ledger_entries (cameo_id) WHERE type = 'REFUND';
