INSERT INTO clips (id, title, cost, sort_order) VALUES
    ('c1', '雨夜重逢', 10, 1),
    ('c2', '屋頂告白', 10, 2),
    ('c3', '最後一班車', 10, 3)
ON CONFLICT (id) DO NOTHING;

INSERT INTO points_account (id, balance) VALUES (1, 100)
ON CONFLICT (id) DO NOTHING;
