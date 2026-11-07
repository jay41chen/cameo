-- 重設（POST /debug/reset）時餘額回到的值。既有資料列的 balance 不動，所以不用 balance 回填。
ALTER TABLE points_account ADD COLUMN seed_balance integer NOT NULL DEFAULT 100;
