-- 分享 token：NULL 代表未分享；取消分享時清成 NULL，再分享會發新 token。
ALTER TABLE cameos ADD COLUMN share_token text;
CREATE UNIQUE INDEX cameos_share_token_idx ON cameos (share_token) WHERE share_token IS NOT NULL;
