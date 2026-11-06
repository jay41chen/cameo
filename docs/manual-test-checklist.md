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

## 第 4 輪：拍照

### 環境

- 模擬器：AVD `Pixel_10_Pro`（API 37），以 `-no-snapshot-save -no-boot-anim -no-audio -camera-front emulated -camera-back virtualscene` 啟動；相機畫面是模擬器的虛擬畫面，不是真實鏡頭，方向測試力道有限（見未涵蓋）。
- 後端：`docker compose -p cameo42 up --build -d`；步驟 10 另以 `UPLOAD_TTL=45s EXPIRY_SWEEP_INTERVAL=2s DAILY_LIMIT=10 docker compose -p cameo42 up -d api` 重建 api（沿用同一個 DB）。
- CameraX `1.6.2`；以 uiautomator 與 `adb shell input tap` 操作；證據在 repo 外的 `~/cameo-iter4-evidence/`。
- 步驟 7 之後的「確認失敗」做法：先在 api 正常時進確認頁（頁面載入需要 api），再 `stop api`，按「確認支付」。api 停著時進確認頁只會看到整頁「發生錯誤，請稍後再試」＋「重試」（確認頁載入片段失敗，非本輪功能）。
- 未完成列「繼續」是否進相機依列載入當下的 `photoPath`；刪檔後需讓清單重新載入（Home 後回前景）才會改判。

### 步驟

| # | 步驟 | 預期 | 實際與證據 |
|---|---|---|---|
| 1 | 新安裝，點「雨夜重逢」 | 進相機頁、出現權限對話框；按「Don't allow」後顯示「需要相機權限才能拍攝自拍」、「允許相機」「前往設定」 | 符合。再按「前往設定」開啟系統 App info 頁。`02-permission-dialog.png`、`02-ui.txt`、`03-denied.png`、`04-settings.png` |
| 2 | 以 `pm grant` 授權（代替在設定頁手動開啟），按返回 | 回 App 後權限重查，顯示取景畫面（非黑畫面） | 符合，顯示虛擬相機畫面與「拍照」。`05-camera-viewfinder.png` |
| 3 | 拍照 →「重拍」→ 再拍 →「使用這張」 | 預覽方向正常；重拍後舊暫存被刪；進確認頁 | 符合：`cache/captures` 重拍後為空，再拍有新檔；確認頁顯示片段名與「確認支付」。EXIF `Orientation=8（ROTATE_270）`（`file` 顯示為 lower-left；原圖 1440x1080），預覽以轉正後顯示。取景（Preview）為鏡像，存檔與預覽（使用者按「使用這張」前看到的照片）不鏡像；鏡像分支（`FLIP_*`、`TRANSPOSE`、`TRANSVERSE`）沒有在模擬器驗到。`06-*`、`08-*`、`09-confirm.png`、`10-jpeg-check.txt` |
| 4 | 預覽時旋轉（`user_rotation 1`，之後改回） | 照片仍在 | 符合，橫向仍顯示同一張、暫存檔同一個。`07-preview-rotated.png`、`07-captures.txt` |
| 5 | 確認支付 | 畫面「等待上傳」；`files/selfies/<key>.jpg` 存在且為 JPEG；Room `photo_path` 正確；DB `AWAITING_UPLOAD`/`HELD`；暫存檔已移走 | 符合：檔案 `FFD8FF`（`file` 判定 JPEG 1440x1080）；Room `step=UPLOAD`、`photo_path=/data/user/0/com.kimhit.cameo/files/selfies/<key>.jpg`；DB `idempotency_key`＝Room key；`cache/captures` 為空。`10-*` |
| 6 | 強制停止後重開 | 未完成列與照片仍在 | 符合。`11-*` |
| 7 | 停 api 後確認失敗；回清單；「繼續」；`start api`；確認 | Room `CREATE` 且有 `photo_path`；「繼續」直接進確認頁；成功且同 key | 符合：失敗後 Room 為 `CREATE`、有 `photo_path`、`files/selfies` 有檔、`cache/captures` 為空；「繼續」進確認頁（非相機）；成功後 DB 只有 1 筆該 key。`13-*`、`14-*`、`15-*`、`16-*` |
| 8 | CREATE 草稿按「移除」 | 列與檔案消失 | 符合：Room 少一列、`files/selfies` 少一檔。`19-*` |
| 9 | 刪掉 CREATE 草稿的照片（`run-as rm`）、Home 後回前景，按「繼續」 | 進相機，不是確認頁 | 符合（Room 欄位 `photo_path` 仍留舊值，但 list 回傳 null）。`18-*`。之後在相機重拍並確認，同一 key（`11ac807f-…`）的 `files/selfies` 檔案由刪除前的 14:29／38382 bytes 變為 14:31／38597 bytes，Room 列仍為 `CREATE` 且 `photo_path` 指向同一路徑（`17-selfies-draft.txt`、`19-selfies-before-remove.txt`、`19-room-before-remove.txt`）；步驟 8 的「移除」是在這之後做的 |
| 10 | 逾期設定下建單，等逾期，按「重新客串」 | 舊檔被刪、進相機 | 符合：逾期前 `files/selfies` 有該檔，按後該檔消失，畫面為相機。`21-*`、`22-*` |
| 11 | 額度用完時點片段 | 提示、不進相機 | 符合：顯示「今日客串次數已用完，2026-11-07 00:00 重置」。`20-quota-zero.png`、`20-ui.txt` |
| 12 | 一次指令連點同一片段 3 下，按一次返回 | 只有一層相機頁 | 符合：返回後直接回清單。`12-*` |

### 單元測試與 mutation（JVM）

修正後（相機例外處理、過期刪照片、文案、Manifest）的全量結果：`:domain:test` 27 筆、`:data:testDebugUnitTest` 36 筆、`:app:testDebugUnitTest` 60 筆，共 123 筆，0 失敗、0 錯誤、0 略過（`./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks`，筆數取自各模組 `build/test-results` XML）。

修正後跑過的 mutation（各自只改一處、確認真的改到、跑對應模組、還原後 `cmp` 一致），均被測試抓到：

| 改動 | 被抓到的測試 |
|---|---|
| `markCreated` 清掉 `photoPath` | `PendingCameoRepositoryTest.markCreated_keepsPhotoPath` |
| `CaptureFilesImpl.deleteOthers` 忽略 `keep` | `CaptureFilesImplTest.deleteOthers_keepsKeepAndDeletesOthers` |
| `GetUnfinishedCameosUseCase` 不刪 EXPIRED 照片 | `CameoUseCasesTest.unfinished_expiredDeletesPhotoButKeepsRow` |
| `cameraCatching` 吞掉 `CancellationException` | `CameraCatchingTest.cancellation_isRethrown` |

未被測試抓到：`PendingCameoRepositoryImpl.delete` 把 `NonCancellable` 拿掉（刪檔被取消打斷）；取消時序難以穩定重現，沒有寫測試，只靠讀碼。

此前（修正前）的 mutation 輸出仍存於 `~/cameo-iter4-evidence/mut-a.txt` 至 `mut-e.txt`，修正後沒有重跑。

### 未涵蓋

- `PhotoMissing` 畫面（當時文案「照片已遺失，請重新拍照」，4.4 後統一為「照片無法使用，請重新拍照」）未在模擬器出現過；只有單元測試涵蓋。步驟 9 驗到的是列的判斷提前改走相機。
- 拍照失敗畫面（「拍照失敗，請再試一次」）、「這台裝置無法使用相機」、永久拒絕後的系統行為、從系統設定手動開啟權限（步驟 2 用 `pm grant` 代替）未在模擬器驗證。
- 預覽方向只用虛擬相機（EXIF `Orientation=8（ROTATE_270）`）驗到一種；鏡像分支沒有在模擬器驗到；其他方向、真機未驗。取景為鏡像、存檔與預覽不鏡像（自拍水平鏡射已列 spec 第 12 節 Backlog）。
- 程序在確認途中被系統回收，可能誤判「照片已遺失」，最多多拍一次。
- 程序被回收後回到 Ready 再按確認：現在建單成功後會導到進度畫面並把確認頁移出返回堆疊（4.3），第二張單改成實際處理；建單成功導頁之前仍有一段窗口，窗口上限未量到。這個情境（程序被回收後回到 Ready）沒有在模擬器驗過。4.4 情境 10 測的是另一件事：返回轉場中誤點「確認支付」，修正前會建單、修正後 7 次都沒有建單（見第 4 輪 Integration）；兩者不要混為一談。
- 本輪審查後的修正（相機初始化／綁定失敗不閃退並顯示「這台裝置無法使用相機」、過期訂單刪照片、「照片不存在，請重新拍照」文案、Manifest 的 `camera` 改為非必要）只有單元測試或讀碼，沒有在模擬器重驗，留到 4.4。
- 相機頁處理程序被系統回收後的 `SavedStateHandle` 還原，只有單元測試涵蓋；模擬器只驗旋轉。
- 逾期確認頁的「重新客串」按鈕現在會導到相機；此路徑未在模擬器驗證，且逾期列「移除」後舊檔被刪也未在模擬器驗（只驗「重新客串」）。4.4 情境 8 只驗了未完成清單列的「重新客串」，確認頁 `Expired` 與進度畫面 `Expired` 仍未驗。
- 不加 `-camera-front emulated` 的 AVD 預設相機未測。
- 當輪（4.2）上傳、送出尚未實作，4.3 已接上；通知尚未實作。

### 4.3 上傳與送出（App）

4.3 只有 JVM 單元測試（`:domain`、`:data`、`:app`），沒有在模擬器驗證。實機項目留到 4.4，至少包含：

- 建單後進進度畫面：上傳中、送出中、完成（`QUEUED`）的顯示與扣點、點數明細（回點數頁時明細會靜默刷新）。
- 上傳或送出途中斷網／殺 App 後，從未完成列「繼續」是否接續；送出回應遺失後不重複扣款。
- 上傳逾期（`UPLOAD_TTL` 縮短）時進度畫面顯示過期，「重新客串」導到相機並用新 key。（4.4 只驗了清單列的「重新客串」，進度畫面 `Expired` 未驗。）
- 照片被刪或後端回 `INVALID_SELFIE`／`SELFIE_TOO_LARGE` 時的「重新拍照」流程，重拍後從未完成列回進度畫面並成功上傳。（4.4 只有情境 9 走完重拍；情境 11 只到「重新拍照」按鈕出現，重拍沒有再驗。）
- 訂單已不存在（`CAMEO_NOT_FOUND`）時的畫面與草稿清除。
- 上傳 client 在連線中斷時不自動重送整個 body（單元測試只驗到 MockWebServer 的 `requestCount == 1`）；主 client 閒置後重開首頁無 network error 的回歸。
- 上傳中離開畫面後請求被取消、草稿與照片仍在。

## 第 4 輪：Integration（4.4）

模擬器接真實後端，驗上傳、送出、接續。證據在 repo 外的 `~/cameo-iter4-evidence/`，檔名皆以 `44-<情境>-` 開頭；DB、Room、檔案、curl 輸出為 `.txt`，截圖只留判斷用的幾張。

### 環境

- 後端：`docker compose -p cameo44 up --build -d`（隔離的 Postgres 與 selfies volume）；情境需要時以環境變數重建 api：`DAILY_LIMIT=50`（避免額度用完）、`UPLOAD_TTL=45s EXPIRY_SWEEP_INTERVAL=2s`（逾期）、`MAX_SELFIE_BYTES=1000`（413）、`DAILY_LIMIT=1`（額度用完）；每次都用 `printenv` 確認生效並於結束後恢復。
- 模擬器：AVD `Pixel_10_Pro`（API 37），`-no-snapshot-save -no-boot-anim -no-audio -camera-front emulated -camera-back virtualscene`；相機為虛擬畫面。
- 修正後重跑（情境 10、主流程）的證據檔名以 `44b-` 開頭，環境同上，後端為 `docker compose -p cameo44 up --build -d`。
- App：debug APK，以 `pm clear` 乾淨狀態開始，除情境 14 外 `pm grant` 相機。操作用 uiautomator dump 與 `input tap`；Room 用 `run-as cat databases/pending.db`（含 `-wal`、`-shm`）拉出後以 `sqlite3` 讀。

### 製造「訂單已建立」狀態的方法（如實）

App 的建單、上傳、送出在本機幾乎瞬間完成，時機抓不到，所以用兩種方法：

- **方法 A（curl 模擬回應遺失）：** 先進確認頁，`stop api`，按「確認支付」留下 CREATE 草稿 K（照片已 attach、Room 無 `cameo_id`），`start api`，再用 curl 以 K 建單（`POST /v1/cameos`），視情境再 curl `PUT selfie`、`POST submit`。此時 Room 仍是 `CREATE`，「繼續」會回確認頁，再按「確認支付」由冪等建單取回原訂單。情境 4a、4b 使用。
- **方法 B（改 Room）：** 方法 A 之後 `am force-stop`，把 Room 列改成 `step=UPLOAD`、`cameo_id=<訂單 id>`（pull、`sqlite3` 改、checkpoint、push 回 `run-as`），重開後「繼續」直接進進度畫面，從 `GET /v1/cameos/{id}` 開始。情境 5、6、7、8、9、14（逾期移除）使用。
- 兩種都不是真實的「建單後當機」；Room 狀態是人工構造，與 App 自己寫出來的 `UPLOAD` 列欄位相同（`photo_path` 保留、`cameo_id` 有值），但「`markCreated` 之後才中斷」的時序沒有被真實走過 `[待確認]`。
- 「繼續」的第一個請求一律是 `GET /v1/cameos/{id}`（`AdvanceCameoUseCase`）。所以情境 5、6 的失敗／取消都發生在 **GET**，不是 PUT；本輪沒有驗到「PUT 途中失敗」。

### 實際指令（最小序列）

```sh
# reset（每次前）：App 與隔離 DB
adb shell pm clear com.kimhit.cameo && adb shell pm grant com.kimhit.cameo android.permission.CAMERA
docker compose -p cameo44 exec -T postgres psql -U cameo -d cameo \
  -c "delete from ledger_entries; delete from cameos; update points_account set balance=100"

# 讀 DB 狀態
docker compose -p cameo44 exec -T postgres psql -U cameo -d cameo -At \
  -c "select count(*) from cameos" -c "select id,status,points_state from cameos" \
  -c "select count(*) from ledger_entries where type='CHARGE'" -c "select balance from points_account"

# 拉 Room（-wal、-shm 一起拉，沒有的略過；只拉 .db 會漏掉還沒 checkpoint 的寫入）
for f in pending.db pending.db-wal pending.db-shm; do
  adb exec-out run-as com.kimhit.cameo cat databases/$f > /tmp/room/$f; done
sqlite3 /tmp/room/pending.db "select client_request_id,clip_id,cameo_id,step,photo_path from pending_cameos"

# 方法 B 改 Room（App 先 am force-stop；改完把 .db 推回，並刪掉裝置上舊的 -wal、-shm，否則舊 WAL 會蓋掉修改）
adb shell am force-stop com.kimhit.cameo
sqlite3 /tmp/room/pending.db "pragma wal_checkpoint(truncate); update pending_cameos set step='UPLOAD', cameo_id='<訂單 id>'"
adb push /tmp/room/pending.db /data/local/tmp/pending.db
adb shell "run-as com.kimhit.cameo sh -c 'cp /data/local/tmp/pending.db databases/pending.db; rm -f databases/pending.db-wal databases/pending.db-shm'"

# 情境 10（座標由 uiautomator dump 取得；修正後重跑為 (205,516)）
adb shell "input keyevent 4; sleep $d; input tap 205 516"    # d = 0 0.05 0.1 0.2 0.3 0.5 0.7

# 情境 13：插 45 筆 CHARGE（cameo_id NULL，created_at 依序遞減 1 分鐘）
docker compose -p cameo44 exec -T postgres psql -U cameo -d cameo -c \
  "insert into ledger_entries(type,amount,cameo_id,clip_title,created_at)
   select 'CHARGE',10,NULL,'測試'||g, now() - (g||' minutes')::interval from generate_series(1,45) g"
```

以上是為文件整理的最小序列：reset、DB 查詢、拉 Room、情境 10 的指令是 4.4 修正後重跑實際用的；方法 B 的改 Room／push 步驟與情境 13 的 SQL 是依當時做法重寫的，沒有再執行一次確認 `[待確認]`。

### 步驟與結果

| # | 情境 | 預期 | 實際 | 結果 | 證據 |
|---|---|---|---|---|---|
| 1 | 全新安裝 → 雨夜重逢 → 拍照 → 使用這張 → 確認支付 | 最後「排隊中」「完成後會通知你」 | 符合；連續 3 次 dump 都已是「排隊中」，途中的「上傳中」「送出中」來不及看到 | 通過（途中文字未見） | `44-s1-ui-seq.txt`、`44-s1-final.png` |
| 2 | 點數頁與 API、DB、清單 | 可用 90、占用 0；明細 1 筆 `CHARGE -10`；quota 少 1；DB `QUEUED/CHARGED`；未完成列「排隊中」無按鈕 | 符合：`{"available":90,"held":0}`、quota 3→2、明細 1 筆、DB 1 筆；未完成列只有文字無可點項 | 通過 | `44-s2-api-db.txt`、`44-s2-list-ui.txt`、`44-s2-points-ui.txt` |
| 3 | 檔案與 Room | 伺服器有 `<cameo_id>.jpg`（JPEG）；App `files/selfies`、`cache/captures` 空；Room `TRACK`、`photo_path` 空 | 符合：伺服器 38209 bytes、開頭 `ff d8 ff`；Room `step=TRACK`、`photo_path` 空 | 通過 | `44-s3-files-room.txt` |
| 4a | 上傳回應遺失（方法 A，再 curl PUT）→「繼續」→ 確認 | 不重傳內容、1 筆 `CHARGE`、照片已刪 | 符合：伺服器檔 sha256 `a3dffd68…` 與 curl 那份一致（3006 bytes，非 App 的照片）；DB 1 筆訂單、1 筆 `CHARGE`、balance 90；App 照片已刪；Room `TRACK`；最後「排隊中」 | 通過 | `44-s4a-before.txt`、`44-s4a-after.txt` |
| 4b | 送出回應遺失（再 curl submit）→「繼續」→ 確認 | `QUEUED` 直接結束、1 筆 `CHARGE`、balance 90 | 符合：curl 後 DB 已是 `QUEUED/CHARGED`；App 確認後 1 筆訂單、1 筆 `CHARGE`、balance 90、伺服器檔仍是 curl 那份、照片已刪、「排隊中」 | 通過 | `44-s4b-before.txt`、`44-s4b-after.txt` |
| 5 | 清單載入後 `stop api` →「繼續」→ 錯誤與「重試」→ `start api` → 重試 | 錯誤、Room 照片還在、重試後完成 | 符合：失敗發生在 **GET**（api 停止，第一個請求即失敗）；畫面「發生錯誤，請稍後再試」「重試」「回片段清單」，Room 與 `files/selfies` 照片仍在、DB 仍 `AWAITING_UPLOAD`；重試後 `QUEUED/CHARGED`、1 筆 `CHARGE`、伺服器 38434 bytes | 通過 | `44-s5-error-ui.txt`、`44-s5-error.png`、`44-s5-after-fail.txt`、`44-s5-after-retry.txt` |
| 6 | `pause api` →「繼續」→ 返回 → `unpause` → 再「繼續」 | 請求取消後可接續；1 筆訂單、1 筆 `CHARGE` | 符合：中斷時請求停在 **GET**（api 暫停，請求懸著）；「請求取消」是由畫面與本機狀態推得，沒有在伺服器端或網路層直接觀察 `[待確認]`；返回後回清單，Room 照片仍在、DB 仍 `AWAITING_UPLOAD`；`unpause` 後「繼續」完成，1 筆訂單、1 筆 `CHARGE`。暫停期間的畫面沒有取得 dump（dump 無輸出），只確認返回後的清單 | 通過（暫停期間畫面未記錄） | `44-s6-after-back.txt`、`44-s6-after-continue.txt` |
| 7 | 方法 B 後 `am force-stop` → 重開 → 「繼續」 | 完成、不多占 | 符合：1 筆訂單、1 筆 `CHARGE`、balance 90、照片已刪、Room `TRACK` | 通過 | `44-s7-after.txt` |
| 8 | `UPLOAD_TTL=45s` 逾期 → 回前景 → 清單列「重新客串」→ 確認（只驗清單列；確認頁 `Expired`、進度畫面 `Expired` 未驗） | 顯示「已過期」與原因；照片已刪、Room 列在；額度恢復；新 key | 符合：列顯示「已過期」「上傳期限已過，點數沒有扣款，請重新客串。」；`files/selfies` 空、Room 列仍在；quota 49→50、`/v1/points` 100/0；「重新客串」進相機、確認後 DB 第二筆 key 不同（`3557101d…` ≠ `f06ab721…`），`QUEUED/CHARGED`，舊列（`EXPIRED/RELEASED`）仍在 DB；Room 只剩新 key 的 `TRACK` 列。之後恢復預設 TTL（`printenv` 確認 `1h`） | 通過 | `44-s8-ui.txt`、`44-s8-expired.png`、`44-s8-after-expire.txt`、`44-s8-restart.txt` |
| 9 | 刪照片 →「繼續」→「重新拍照」→ 拍 → 使用這張 | 「照片已遺失…」；回進度畫面完成；同一筆訂單、1 筆 `CHARGE` | 符合：進度畫面顯示「照片已遺失或無法使用，請重新拍照」（修正前文案，現已統一為「照片無法使用，請重新拍照」，修正後未在模擬器重驗）；重拍後「排隊中」；DB 仍只有同一筆訂單（`04e62794…`）、1 筆 `CHARGE`。第一次嘗試時 `run-as rm files/selfies/*.jpg` 的萬用字元沒有展開、檔案沒刪，該次「繼續」直接完成，已改用完整檔名重做 | 通過 | `44-s9-ui.txt`、`44-s9-missing.png`、`44-s9-after.txt` |
| 10 | 確認頁返回轉場中誤點：`input keyevent 4; [sleep d;] input tap <確認支付>`，d＝0、0.05、0.1、0.2、0.3、0.5、0.7，每個 d 跑 1 次 | 記錄最後畫面、訂單數、狀態、`CHARGE`、balance、Room | **修正前（舊 APK，d＝0、0.05、0.1、0.2、0.3、0 共 6 次）：** 6 次都建出 1 筆訂單；d≤0.2 的 5 次畫面「排隊中」、Room `TRACK`，依程式碼已扣 10 點，但證據沒有直接記錄 `CHARGE` 筆數；d＝0.3 的 1 次畫面回到清單，Room `CREATE`（無 `cameo_id`），訂單狀態未記錄。每次先 reset，只看到 1 筆，沒有觀察到第二張單。實驗限制：adb 依序執行、實際間隔未量、每個 d 只有 1 次。證據 `44-s10-fadetap.txt`。**修正後（`dropUnlessResumed`，7 個 d 各 1 次）：** 確認頁都是 Ready（按鈕在）；d＝0、0.05、0.1、0.2、0.3、0.5 最後畫面是片段清單，d＝0.7 是相機頁（點擊落在清單上的「屋頂告白」列，與此修法無關）；7 次 DB 訂單數都是 0、`CHARGE` 0 筆、balance 100，Room 查詢無輸出（無列），`points_state` 無列可記。按鈕座標 (205,516)。限制同上（每個 d 1 次、間隔未量），只能說這 7 次沒有重現，不能證明窗口已完全關閉。**摘要：** 7 次未建單，但不是 7 次都打到按鈕：d＝0.7 的點擊落在清單列（相機頁）；d＝0.5 沒有修正前對照；可與修正前直接對照的只有 d＝0、0.05、0.1、0.2、0.3 這 5 個值，每個 d 只跑 1 次。「確認頁 Ready（`ready: 1`）」的判定方式在 `44b-s10-fadetap.txt` 中只看得到結果，未記錄判定方式 | 修正前：發現；修正後：7/7 未建單 | `44-s10-fadetap.txt`（修正前）、`44b-s10-fadetap.txt`、`44b-s10-d<d>.png`（修正後） |
| 11 | `MAX_SELFIE_BYTES=1000` 走主流程 | 記錄 App 實際結果 | 變數生效（`printenv` 顯示 1000）；確認後上傳被拒，App 顯示「照片已遺失或無法使用，請重新拍照」（修正前文案）與「重新拍照」（「收到 `SELFIE_TOO_LARGE`」是由畫面與本機狀態推得，未直接記錄回應 `[待確認]`）；DB `AWAITING_UPLOAD/HELD`、無 `CHARGE`、App 照片已刪、Room 仍是 `UPLOAD` 列。文案只說「遺失或無法使用」，沒有說太大。已恢復預設（`10485760`）。重拍後送出沒有再驗 | 通過（文案籠統） | `44-s11-env.txt`、`44-s11-ui.txt`、`44-s11.png`、`44-s11-state.txt` |
| 13 | 插 45 筆 `CHARGE`（`cameo_id` NULL），點數頁捲到底 → Home → 回前景 | 只記錄 | 捲到底時顯示「測試32」到「測試45」（第 3 頁已載入）；Home 後回前景，從測試 32–45 跳到 7–20（第一頁尾端）；再往下捲才接著載入，不會連鎖載入。閱讀位置丟失（已列 spec 第 12 節 Backlog）。資料已清掉。 | 發現（未修） | `44-s13-before-home.txt`、`44-s13-after-foreground.txt`、`44-s13-after-scroll-end.txt`、`44-s13-before.png`、`44-s13-after.png` |
| 14a | 未授權點片段 → 權限對話框 → 拒絕 → `pm grant` → 回前景 | 說明與「前往設定」；回前景看到相機 | 符合：對話框後按「Don't allow」顯示「需要相機權限才能拍攝自拍」「允許相機」「前往設定」；`pm grant` 後回前景為相機（拍照鈕）。（`pm revoke` 會殺掉 App，需重開後再點） | 通過 | `44-s14-perm-dialog.txt`、`44-s14-perm-dialog.png`、`44-s14-denied-ui.txt`、`44-s14-after-grant-ui.txt` |
| 14b | 額度用完點片段 | 提示、不進相機 | 符合：`DAILY_LIMIT=1` 並用 curl 完成 1 筆後 quota 0；點片段跳「今日客串次數已用完，2026-11-07 00:00 重置」「知道了」，沒有進相機 | 通過 | `44-s14-quota-ui.txt`、`44-s14-quota.png` |
| 14c | CREATE 草稿「移除」；再點同片段 | 列消失、照片消失、新 key | 符合：移除前 `files/selfies` 有檔、Room 有列；移除後兩者皆無、清單回原樣；再建草稿 key 為新的（`dbdfa5bc…` → `8b8d7182…`） | 通過 | `44-s14-remove.txt` |
| 14d | 逾期列「移除」 | 列消失 | 符合：Room 列與清單項消失；DB 的 `EXPIRED/RELEASED` 訂單仍在（伺服器端本來就不刪） | 通過 | `44-s14-expired-remove.txt` |
| 14e | CREATE 草稿照片遺失的文案 | 「照片不存在，請重新拍照」 | 符合但路徑不同：刪檔後清單重新載入（Home 回前景）列文字為「照片不存在，請重新拍照」。若不重新載入就按「繼續」→「確認支付」，畫面是確認頁的「照片已遺失，請重新拍照」；此時 DB 無訂單、無 `CHARGE`。以上是修正前的文案，三處（列、確認頁、進度畫面）各不相同；之後統一為「照片無法使用，請重新拍照」，只有單元測試（`CameraFailureTest`）涵蓋，未在模擬器重驗 | 通過（文案不一致，見下） | `44-s14-draftmissing-list-ui.txt`、`44-s14-draftmissing-list.png`、`44-s14-draftmissing-ui.txt`、`44-s14-draftmissing2.png` |

上表是修正前的結果（情境 10、13 為發現；其他情境通過，文案類為修正前）。4.4 審查後的修正（確認頁「確認支付」與進度畫面「重試」加 `dropUnlessResumed`；照片文案統一）已重跑情境 10 與主流程，見該列與「修正後主流程」。

實際有證據的項目（證據檔都在 `~/cameo-iter4-evidence/`）：

- 最後畫面有文字或截圖證據：情境 1（`44-s1-ui-seq.txt`、`44-s1-final.png`）、2（`44-s2-list-ui.txt`、`44-s2-points-ui.txt`）、5（`44-s5-error-ui.txt`、`44-s5-error.png`）、8 的逾期列（`44-s8-ui.txt`、`44-s8-expired.png`）、9 的「需要重拍」畫面（`44-s9-ui.txt`、`44-s9-missing.png`）、10（`44-s10-fadetap.txt`）、11（`44-s11-ui.txt`、`44-s11.png`）、13、14。
- 4a、4b、6、7、8、9 的最後畫面（「排隊中」等）沒有文字證據：這幾個情境的證據檔只有 DB、`CHARGE`、balance、Room、檔案（`44-s4a-*`、`44-s4b-*`、`44-s6-*`、`44-s7-after.txt`、`44-s8-after-expire.txt`、`44-s8-restart.txt`、`44-s9-after.txt`），表中寫的最後畫面是當時操作時的觀察，沒有留存 `[待確認]`。
- DB 訂單數、`CHARGE` 筆數、balance、Room 有證據檔：情境 2、3、4a、4b、5、6、7、8、9、11（`44-s11-state.txt`）；情境 14c、14d 只有 Room 與檔案；情境 10 修正前只有訂單數與 Room。

### 4.2 留下的項目在本輪驗到哪些

- 清單列的「重新客串」導到相機、新 key：情境 8 驗到（確認頁 `Expired` 的「重新客串」未驗）。
- 逾期列「移除」（含照片檔）：情境 14d 驗到（逾期時照片已由列載入刪除，移除時沒有檔）。
- 「照片不存在，請重新拍照」新文案（已於 4.4 統一為「照片無法使用，請重新拍照」）：情境 14e 驗到修正前的版本（僅清單列）。
- `PhotoMissing` 畫面：情境 9（進度畫面）與 14e（確認頁）出現過。
- 確認頁返回轉場中誤點「確認支付」：情境 10 修正前會建單，修正後 7 次未建單。「程序被回收後回到 Ready 再按確認」沒有在模擬器驗。
- 4.3 清單：建單後進度畫面、「排隊中」、扣點與明細（1、2）；上傳／送出回應遺失後接續且只扣一次（4a、4b，以 curl 模擬）；上傳逾期後清單列重新客串（8）；照片遺失重拍（只算 9；11 只到按鈕出現）；離開畫面後請求取消、草稿與照片仍在（6，只驗到 GET）。
- 仍未驗：相機初始化／綁定失敗的「這台裝置無法使用相機」畫面、Manifest `camera` 非必要的實際效果、`INVALID_SELFIE`（非 JPEG）在 App 的畫面（單元測試涵蓋）、`CAMEO_NOT_FOUND` 畫面、上傳 client 連線中斷不自動重送整個 body、主 client 閒置後重開首頁無 network error 的回歸、不加 `-camera-front emulated` 的 AVD 預設相機。

### 發現

1. 確認頁返回淡出期間仍可點「確認支付」並建單（情境 10 修正前，6/6 次建單）。**已修（只修一段）：** 「確認支付」與進度畫面「重試」改用 `dropUnlessResumed`，頁面已在離開中的點擊不啟動扣款流程；修正後 7 次未建單（見情境 10）。**這不是完整解法**：使用者先按確認、再按返回時，請求可能已經到後端，App 收不回來。分兩種情況（以下皆為讀碼與修正前證據的推論，未實測）：(a) 回應在頁面銷毀後才回來：只占用，不上傳、不扣款；按「繼續」接續，或等上傳期限釋放。(b) 回應在淡出期間（頁面尚未銷毀）回來：一樣導到進度畫面，自動上傳、送出、扣款；`dropUnlessResumed` 擋的是「返回後才點」，不包含這種情況。(b) 是 MVP 決定，取捨見 `ARCHITECTURE.md` 第 8 節（按確認後立刻返回仍可能被扣款且無法取消，正式版需再評估）。按未完成列的「移除」只刪本機：伺服器端的占用與每日額度留到上傳期限，App 沒有列可以解釋。`dropUnlessResumed` 是 Compose 接線，沒有 JVM 測試。
2. 後端回 `SELFIE_TOO_LARGE` 與照片遺失共用同一句文案，沒有說「太大」（情境 11）。
3. 照片遺失的文案原本有三種（情境 9、14e）；已統一為「照片無法使用，請重新拍照」（單元測試涵蓋，模擬器未重驗）。
4. 點數明細 Home 回前景後，清單縮回第一頁，閱讀位置丟失（情境 13）；已列 spec 第 12 節 Backlog。

### 修正後主流程（4.4 重跑）

全新開始（reset）→ 點「雨夜重逢」→ 拍照 →「使用這張」→ 等 3 秒 → 按「確認支付」：最後畫面「排隊中」；DB 1 筆訂單 `QUEUED/CHARGED`、`CHARGE` 1 筆、balance 90；伺服器有 JPEG（`ff d8 ff e1`，38134 bytes）；App 的 `files/selfies`、`cache/captures` 為空；Room 一列 `TRACK`。確認修正後正常確認沒被擋。另做一次「使用這張」後立刻（同一個 `adb shell` 內，間隔未量）點「確認支付」：點擊被丟棄，仍停在確認頁，DB 0 筆、balance 100；只做 1 次，無法說明淡入窗口多長。單元測試 `:app:testDebugUnitTest --rerun-tasks`：82 筆、0 失敗、0 錯誤、0 略過（筆數取自 XML）。證據：`44b-main.txt`、`44b-main.png`、`44b-fadein.txt`、`44b-fadein.png`、`44b-unit-test.txt`、`44b-unit-count.txt`。

### 未涵蓋

- 上傳途中進背景（程序仍活著時能否完成）：本機上傳幾乎瞬間完成，很難剛好在途中按 Home；系統是否凍結程序、封鎖網路也看時機，結果不能當結論，移到第 5 輪；本輪不對這個情境做保證。
- 相機初始化失敗畫面、真機與真實前鏡頭、真實斷線（第 8 輪）、`401`。
- 失敗發生在 PUT／POST submit 請求途中（5、6 只驗到 GET 階段）；Room 狀態是用方法 A／B 製造，不是 App 自己在建單後中斷。
- 真實回應遺失的 Room 組合未走過：`UPLOAD`＋伺服器 `UPLOADED`、`SUBMIT`＋伺服器 `QUEUED`。
- 傳輸中途被殺未實測：情境 7 是閒置時強制停止，且 Room 為人工修改。
- 真實手勢返回（情境 10 用 `keyevent 4`）與真實網路延遲未測。
- 確認頁 `Expired`、進度畫面 `Expired`（情境 8 只驗清單列的「重新客串」）。
- 先按確認再按返回：只讀碼與修正前證據推論，未實測。回應在頁面銷毀後才回來：只占用，按「繼續」或等期限釋放；回應在淡出期間回來：導到進度畫面，自動上傳、送出、扣款（MVP 決定，見 `ARCHITECTURE.md` 第 8 節；`dropUnlessResumed` 不涵蓋此情況）。
- 「程序被回收後回到 Ready」未在模擬器驗。
- 已建單的重拍流程：相機頁「使用這張」沒有 `dropUnlessResumed`，返回淡出期間點到可能接著上傳、送出（讀碼，未實測；使用者按的是明確操作，MVP 不改）。
- 上傳途中、送出途中「真實」的回應遺失只用 curl 模擬。
- 通知尚未實作。
- 只在 API 37 模擬器與虛擬相機驗證，照片是同一張虛擬畫面。
