# 手動驗證清單

## 第 2 輪：片段、點數、點數明細

環境：Docker 後端（`docker compose up --build -d`）、API 37 模擬器（`Pixel_10_Pro`）、debug APK 已安裝（不需手動授權）。

資料庫指令用 `docker compose exec -T postgres psql -U cameo -d cameo`。驗證完要刪掉自己插入的明細（本清單用 `id` 以 `verify-` 開頭）。

| # | 步驟 | 預期 |
|---|---|---|
| 1 | 開啟 App | 預設在「片段清單」，先短暫顯示載入中，然後列出 3 段：雨夜重逢、屋頂告白、最後一班車，各 10 點 |
| 2 | 點「點數」頁籤 | 可用點數 100、占用點數 0；明細顯示「目前沒有點數明細」 |
| 3 | 插入 2 筆明細（`CHARGE`、`REFUND` 各一，`cameo_id` 為 NULL），強制停止並重開 App，進「點數」頁 | 顯示 2 筆，新到舊；退點為 `+10`，扣點為 `-10`；時間為 `yyyy-MM-dd HH:mm`；`cameo_id` 為 NULL 不影響顯示 |
| 4 | 刪除上列資料，插入 45 筆（`verify-p001`…），重開 App 進「點數」頁，往下捲 | 先顯示前 20 筆；捲到底自動載入下一頁並接在後面（第 21 筆起），不重複；最後一頁後不再載入 |
| 5 | 刪除 45 筆 | `select count(*) from ledger_entries` 為 0 |
| 6 | `docker compose stop api`，強制停止並重開 App，進「點數」頁 | 點數區與明細區各顯示失敗文字與「重試」按鈕；後端沒回 JSON 錯誤時顯示通用文字「發生錯誤，請稍後再試」；片段清單頁同樣顯示失敗與重試 |
| 7 | `docker compose start api`，按各區塊的「重試」 | 先顯示載入中，再恢復 100／0 與空明細（片段清單頁重試後恢復 3 段） |
| 8 | 收尾：關模擬器、`docker compose down`、`./gradlew --stop` | 沒有殘留的驗證資料 |

未涵蓋：後端回傳帶 `message` 的錯誤時畫面顯示該文字，只在單元測試（ViewModel）與 MockWebServer 測試驗證，沒有在模擬器上手動觸發；明細分頁中途失敗的畫面（單元測試涵蓋狀態轉換）。
