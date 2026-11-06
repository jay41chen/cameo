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

## 第 3 輪：額度、確認支付、本機草稿

環境：隔離的 Docker 後端、API 37 模擬器（`Pixel_10_Pro`）、repo 的 debug APK（`./gradlew :app:assembleDebug`，`adb install -r`）。App 連 `http://10.0.2.2:8080`。

證據與保存範圍：

- 截圖與 s15 起的輸出都放在 repo 外的 `~/cameo-iter3-evidence/`，不在 repo 內；檔名前綴 `sN-` 的 N 是驗證時的情境編號，與下表的步驟編號不同，步驟與檔名的完整對照當時沒有記錄，此處不虛構。
- **情境 s1–s14 的 DB 與 Room 查詢結果沒有保存輸出，僅憑當時紀錄**；目錄內只有截圖。s15 起（下表步驟 23–25）的 DB、Room、curl 輸出都存成 `.txt`，檔名在各步驟的「證據」中列出。
- 已知對應（由檔名與內容判斷）：`s9-*` 為逾期；`s11-*` 為雙擊、連點兩片段、旋轉、回前景額度更新；`s13-*` 為 `409`；`s14-*` 為重試進行中。其餘不列。

隔離後端（專案根目錄；不碰其他 volume）：

- 隔離只隔離資料（`cameo33_pgdata`）。主機埠 8080 與 5433 仍與正式組態共用，執行前要先讓出（API 必須維持在 8080，因為 App 寫死連 `10.0.2.2:8080`）；`lsof -i :8080 -i :5433` 確認無人占用。

- 啟動：`docker compose -p cameo33 up --build -d`
- DB：`docker compose -p cameo33 exec -T postgres psql -U cameo -d cameo`（表：`cameos`、`points_account`、`ledger_entries`、`clips`）
- 每個情境前還原：`adb shell pm clear com.kimhit.cameo`，再 `delete from ledger_entries; delete from cameos; update points_account set balance=100;`
- 「隔日 00:00（台北）」指額度重置時間，以畫面顯示為準，不寫死日期。
- 逾期情境：`UPLOAD_TTL=45s EXPIRY_SWEEP_INTERVAL=2s docker compose -p cameo33 up -d api`；完成後不帶變數再 `up -d api` 還原。
- 收尾：`docker compose -p cameo33 down -v`、`docker image rm cameo33-api`。

Room 草稿：`adb exec-out run-as com.kimhit.cameo cat databases/pending.db`（連同 `-wal`、`-shm`）拉到本機，用 `sqlite3` 讀 `select * from pending_cameos`（欄位 `client_request_id, clip_id, cameo_id, step, photo_path, last_error, updated_at`）。

點確認支付之前，確認頁要先載入完成（它需要後端回應）。要製造「確認失敗」，要在確認頁顯示後才停後端。

| # | 步驟 | 預期（實際走過且符合） |
|---|---|---|
| 1 | 清資料後開啟 App | 「今日還可客串 3 次」，3 段片段，各 10 點 |
| 2 | 點「雨夜重逢」 | 確認頁：「確認後會占用 10 點，送出後才扣款」 |
| 3 | 按「確認支付」 | 「等待上傳」、「已占用 10 點，送出後才扣款」、上傳期限；DB 1 筆 `AWAITING_UPLOAD`/`HELD`；Room `step=UPLOAD`、`cameo_id` 等於 DB 訂單 id、`client_request_id` 等於 DB `idempotency_key` |
| 4 | 回清單、進「點數」頁 | 額度 2 次、有「未完成的客串」列；可用 90、占用 10、「目前沒有點數明細」 |
| 5 | `am force-stop` 後重開 | 未完成列還在；額度 2；DB 仍 1 筆 |
| 6 | 確認頁按「確認支付」後立刻（同一個 `adb shell` 指令內）`am force-stop`。可能有兩種結果：(a) POST 沒到：重開後 Room 為 `step=CREATE`、無 `cameo_id`，DB 0 筆，未完成列顯示「尚未確認是否建立成功」、「繼續」「移除」；按「繼續」再確認：DB 1 筆，`idempotency_key` 等於 Room 的 `client_request_id`。(b) POST 已到、回應來不及處理：DB 已有 1 筆但 Room 仍為 `CREATE`，處理方式見步驟 19（回應遺失） | 本輪模擬器**只走到 (a)**；(b) 沒有用強制停止觸發，只以步驟 19 的 curl 模擬覆蓋，時間窗太短無法穩定重現 |
| 7 | 建滿 3 次，點任一片段 | 清單顯示「今日客串次數已用完」、「（隔日 00:00（台北））重置」；跳出對話框（含重置時間、「知道了」），關閉後仍在清單；DB 維持 3 筆；3 個未完成列與 Room 草稿不變 |
| 8 | 把 `points_account.balance` 改 5，點確認支付 | 「點數不足」、只有「回片段清單」；DB 0 筆、Room 無草稿；回清單正常 |
| 9 | 開確認頁後 `docker compose -p cameo33 stop api`，按確認（Room 留下 `CREATE`）；`start api`，用 curl（`Authorization: Bearer test-token`、不同 `Idempotency-Key`、`POST /v1/cameos {"clip_id":"c2"}`）建 3 張；返回清單按「繼續」再確認 | 「今日客串次數已達上限，明天再來」；Room 草稿刪除；回清單仍顯示已用完；DB 3 筆 |
| 10 | 開確認頁後停後端，按確認；`start api`，按「重試」 | 先顯示「發生錯誤，請稍後再試」與「重試」（Room 為 `CREATE`）；重試後成功；DB `idempotency_key` 等於停機時 Room 的 `client_request_id`，1 筆 |
| 11 | 逾期設定下建單，回清單、按 Home，等 60 秒，回前景 | DB 為 `EXPIRED`/`RELEASED`；未完成列顯示「已過期」、「上傳期限已過，點數沒有扣款，請重新客串。」、「重新客串」「移除」；額度回到 3 次 |
| 12 | 按「重新客串」，再按確認支付 | 舊列消失，確認頁開啟；確認後 DB 第 2 筆的 `idempotency_key` 與第 1 筆不同 |
| 13 | 逾期列按「移除」 | 該列消失、Room 空；DB 不變 |
| 14 | 雙擊同一片段，按一次系統返回 | 回清單（只有一層確認頁） |
| 15 | 一次指令連點兩個不同片段 | 顯示第一個片段的確認頁；確認後 DB 訂單是第一個片段（`c1`） |
| 16 | 確認頁、清單、點數頁 | 確認頁無底部導覽列；清單與點數頁有 |
| 17 | 確認頁旋轉（`accelerometer_rotation 0`、`user_rotation 1`，之後改回原值 `1`/`0`） | 確認頁文字與按鈕保留；DB 筆數不變 |
| 18 | App 在背景時用 curl 建一張單，回前景 | 額度由 2 次變 1 次 |
| 19 | 回應遺失（用 curl 模擬，不是真實斷線）：開確認頁後 `stop api`，按確認（Room 為 `CREATE`，記下 `client_request_id` 為 K）；返回清單、`start api`；`curl -i -X POST localhost:8080/v1/cameos -H 'Authorization: Bearer test-token' -H "Idempotency-Key: K" -H 'Content-Type: application/json' -d '{"clip_id":"c1"}'` 回 201；App 未完成列按「繼續」→「確認支付」 | 「等待上傳」、「已占用 10 點，送出後才扣款」；DB 仍 1 筆，`id` 等於 curl 回的訂單、`idempotency_key`=K、`HELD`；API 與點數頁為可用 90／占用 10；額度 2；Room `step=UPLOAD`、`cameo_id` 等於 DB 的 `id` |
| 20 | 第三類錯誤（`409`）：同上前置，但 curl 帶同一個 K、`clip_id` 為 `c2`（201）；未完成列按「繼續」→「確認支付」 | 後端回 `409 IDEMPOTENCY_CONFLICT`，畫面只顯示 message「同一個請求編號不能用於不同片段」與「回片段清單」，沒有「重試」；Room 草稿保留（key 仍為 K、`step=CREATE`），`last_error` 為該文字；回清單後未完成列顯示該文字、有「繼續」「移除」；再按「繼續」→「確認支付」同樣 409、草稿仍在、DB 不多一筆 |
| 21 | 接步驟 20，按「移除」，再點「雨夜重逢」→「確認支付」 | 列消失、Room 無資料；DB 多一筆 `c1`，`idempotency_key` 不等於 K，建單 201 |
| 22 | 重試進行中：前置後停在確認頁的錯誤畫面；`start api` 至可用，`docker compose -p cameo33 pause api`，按「重試」，立刻 `screencap` 與 uiautomator dump，再 `unpause api` | 進行中：錯誤文字消失、「重試」按鈕 disabled（dump 中該按鈕 `enabled="false"`，截圖為灰色）；`unpause` 後顯示「等待上傳」，DB 1 筆、`idempotency_key`=K、Room `step=UPLOAD` |
| 23 | 情境 15：已建單再點同片段拿新 key。清資料後建單 c1 成功 → 回清單（UPLOAD 列仍在）→ 再點「雨夜重逢」→ 確認支付 → 回清單 | DB 2 筆，都是 c1，`idempotency_key` 不同；API 可用 80／占用 20；Room 2 列都是 `step=UPLOAD`，key 各自對應 DB。**實際：符合。** 清單顯示額度 1 次、兩列「未完成的客串」。證據：`s15-db.txt`、`s15-room.txt`、`s15-api-points.txt`（`{"available":80,"held":20}`）、`s15-list.png` |
| 24 | 情境 16：CREATE 草稿重用同 key。進 c1 確認頁 → `stop api` → 按確認失敗 → 系統返回清單（Room 為 `CREATE`，記下 key）→ `start api` 並確認健康 → **直接點片段「雨夜重逢」（不按「繼續」）** → 確認頁 → 確認支付 | Room 全程 1 列、key 不變（`951a8a7f-…`）；DB 1 筆，key 相同、`HELD`；Room 最後 `step=UPLOAD` 且 `cameo_id` 等於 DB `id`。**實際：符合。** 證據：`s16-room-fail.txt`（失敗後）、`s16-room-back.txt`（返回清單後）、`s16-room-before.txt`（再點片段後）、`s16-room-after.txt`（成功後）、`s16-db.txt`、`s16-success.png`。順帶觀察：API 停機時清單頂端顯示「無法取得今日額度」與「重試」（本步驟沒截圖） |
| 25 | 情境 17：全新狀態建單成功，按「回片段清單」後不切頁，連續 `screencap`（實際擷取間隔見右） | 第一次 resume 就出現未完成列。**實際：符合。** `screencap` 本身每張約耗 1.5–3 秒，無法精確到 0／1／3 秒：以點擊完成為基準，三張指令完成時間為 +3.2、+4.7、+6.9 秒（`s17-timing.txt`）。第一張（`s17-0s.png`）畫面是確認頁與底部導覽列同時存在的轉場畫面，尚無未完成列；第二張（`s17-1s.png`，約 +4.7 秒指令完成）已有未完成列與額度 2 次，第三張（`s17-3s.png`）相同。沒有切頁。由此只能確定：列在第一次回清單後數秒內出現，精確延遲未量到。證據：`s17-0s.png`、`s17-1s.png`、`s17-3s.png`、`s17-timing.txt`、`s17-db.txt`、`s17-room.txt` |

補充說明：

- 步驟 19 的「回應遺失」是用 curl 先送同一個 key 模擬「第一次 POST 已到達」，不是真實斷線；真實斷線情境留第 8 輪。
- 步驟 20 的第三類錯誤是用 `409` 驗證。`401` 走同一分支（單元測試涵蓋），但沒有在模擬器觸發。原因的說法（以錯誤 token 組出的 APK 開啟後，片段清單本身就回 401、顯示「請先登入」與「重試」，無法進入確認頁）**僅憑當時紀錄**：該次只拍到啟動畫面的截圖，沒有拍到「請先登入」畫面，輸出也沒保存。
- 步驟 22 受 OkHttp 預設 read timeout 10 秒限制：第一次實測從按「重試」到 `unpause` 約 12 秒，已逾時，畫面回到「發生錯誤，請稍後再試」、DB 0 筆（Room 仍為 `CREATE`）；第二次在 1 秒內 `unpause`，成功。兩次都有截到進行中畫面（錯誤消失、按鈕 disabled）。

未涵蓋：

- 錯誤 token 的 `401`（顯示「請先登入」）沒有在模擬器驗到，原因見上。
- 「回應遺失」只以 curl 模擬，沒有真實斷線（留第 8 輪）。
- 步驟 4 之後第一次回清單時，曾有一張截圖尚未出現未完成列（約 2 秒後的畫面），稍後重新擷取已正常；當時的原圖沒有保留。情境 17（步驟 25）重做：不切頁、第一次 resume 後列在數秒內出現，未重現「久久不出現」；第一張是轉場畫面，清單內容都還沒顯示，無法判斷未完成列是否有額外延遲；本次只跑 1 次、1 列：不切頁、未離開 App，約 4.7 秒內已出現，期間沒有再切頁或回前景；不足以推論其他次數或多列時的行為；也沒有量從背景返回時未完成列的出現時間（步驟 11、18 只驗回前景後的畫面內容，沒有量時間）。
- 後端停機時直接點片段（確認頁尚未載入）會顯示整頁的「發生錯誤，請稍後再試」與「重試」，不是確認頁上的錯誤；本輪未把它當成預期行為驗證。
- EXPIRED 的 App 端通用文字 fallback：只有後端缺 `display` 時才會走到，目前畫面上的那段文字來自後端。
- 確認頁的 `Expired` 狀態沒有驗。
- `order==null` 的未完成列顯示「暫時無法取得狀態」沒有驗。
- 「無法取得今日額度」只在情境 16 順帶看到文字（無截圖），沒有獨立驗證。
- `getOrInsertDraft` 的交易原子性（併發寫入）沒有驗；情境 16 只驗正向重用。
- 送出中或成功後旋轉：只驗閒置確認頁（步驟 17）。
- 送出中按系統返回沒有驗。
- 逾期列在額度為 0 時按「重新客串」沒有驗。
- 確認頁載入失敗的整頁錯誤沒有截圖（見上方「後端停機時直接點片段」一項，也未作為預期行為）。
- 外觀問題（留待後續 UI 修正，本輪不改程式）：狀態列在淺色背景幾乎看不見（時間與圖示為白色）、底部導覽列沒有圖示。回清單時曾拍到確認頁內容與底部導覽列同框（s17-0s.png）；先後順序與持續時間未測，判斷只影響外觀 [待確認]。
- 上傳、送出、通知、背景同步、訂單清單尚未實作。
