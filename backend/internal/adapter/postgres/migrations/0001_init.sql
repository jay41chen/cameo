CREATE TABLE clips (
    id         text PRIMARY KEY,
    title      text NOT NULL,
    cost       integer NOT NULL CHECK (cost > 0),
    sort_order integer NOT NULL
);

-- 單一測試使用者：只有一列。
CREATE TABLE points_account (
    id      integer PRIMARY KEY CHECK (id = 1),
    balance integer NOT NULL CHECK (balance >= 0)
);

-- cameo_id 之後會對應訂單，訂單表建立前不加外鍵。
CREATE TABLE ledger_entries (
    id         text PRIMARY KEY DEFAULT gen_random_uuid()::text,
    type       text NOT NULL CHECK (type IN ('CHARGE', 'REFUND')),
    amount     integer NOT NULL CHECK (amount > 0),
    cameo_id   text,
    clip_title text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entries_order_idx ON ledger_entries (created_at DESC, id DESC);
