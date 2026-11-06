-- 明細的 cameo_id 對應訂單（維持可為 NULL）。
ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_cameo_fk FOREIGN KEY (cameo_id) REFERENCES cameos (id);

-- 每筆訂單最多一筆 CHARGE 明細，防止重複扣款。
CREATE UNIQUE INDEX ledger_one_charge_per_cameo ON ledger_entries (cameo_id) WHERE type = 'CHARGE';
