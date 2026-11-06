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

## 第 5.1 輪：後端（派工、廠商回呼、逾時、退點）

只驗後端，沒有開模擬器，App 沒有改（除 `CameoRepositoryTest` 的 fixture）。

### 環境

- 隔離專案：`API_PORT=18080 POSTGRES_HOST_PORT=15433 VENDOR_DELAY_SEC=3 docker compose -p cameo51smoke up -d --build`；結束 `docker compose -p cameo51smoke down -v`（只清這個專案，沒有動 `cameo_pgdata`）。
- 單元與整合測試用另一個隔離專案的 Postgres（`-p cameo51test`，port 15434），`TEST_DATABASE_URL='postgres://cameo:cameo@127.0.0.1:15434/cameo?sslmode=disable'`。
- 簽章用 `openssl dgst -sha256 -hmac cameo-sim-secret -hex` 對原始 body 計算。

### 冒煙步驟與實際結果（curl）

1. 建單 → 上傳 JPEG → 送出 → 輪詢：`QUEUED/CHARGED（排隊中）` → `GENERATING（生成中）` ×3 → 約 4 秒後 `SUCCEEDED/CHARGED（已完成）`。
2. `GET /v1/points`：`{"available":90,"held":0}`，只扣一次；明細一筆 `CHARGE`。
3. 容器內 `ls /data/selfies`：送出前有 `<id>.jpg`；`SUCCEEDED` 後目錄為空（自拍已刪）。訂單回應的 `video_available` 為 `false`，沒有 `video_url`。
4. 第二筆訂單送出後（`QUEUED`），手動簽 `FAILED`／`CONTENT_REJECTED` 回呼：錯誤簽章 `401 INVALID_SIGNATURE`、缺簽章 `401`、正確簽章 `200`；`GET /v1/points` 由 80 回到 90；訂單 `FAILED/REFUNDED`、`failure_reason: CONTENT_REJECTED`、顯示「無法生成」；明細多一筆 `REFUND`（10）。
5. 相同 `event_id` 再送一次：`200`，點數仍 90、明細沒有第二筆 `REFUND`。
6. `GET /v1/quota`：`remaining: 1`（兩筆訂單，`CONTENT_REJECTED` 的額度不退）。第二筆自拍已刪。
7. 這次手動回呼先於派工掃描到達（訂單還是 `QUEUED`），所以 `vendor-sim` 沒有收到這筆工作；「廠商晚到的成功回呼被忽略」只由自動測試涵蓋，冒煙沒有走到。
8. 啟動檢查：`VENDOR_WEBHOOK_SECRET` 未設定時 `api` 與 `vendor-sim` 都以 `VENDOR_WEBHOOK_SECRET is required` 結束。
9. migration `0005` 回填：在獨立 schema 套用 `0001`–`0004`、插入 `QUEUED`／`GENERATING`／`SUCCEEDED`／`UPLOADED` 各一筆後套用 `0005`：`QUEUED`、`GENERATING` 的 `generation_expires_at` 為 `updated_at + 30 分鐘`，其餘為 `NULL`（用 `psql` 手動檢查，沒有寫成自動測試）。

### 自動測試

- `go vet ./...` 通過；`go test -count=1 ./...`（含整合測試）與 `go test -race -count=1 ./...` 全部通過；`go test -v` 計 123 個頂層測試通過、0 略過、0 失敗。
- Android：`./gradlew :data:testDebugUnitTest --rerun-tasks` 通過（`CameoRepositoryTest` 17 筆、0 失敗；含新增的 `upload_408IsNotAutoRetriedByOkHttp`，確認 408 只送一次請求）。
- 手動 mutation 抽查（跑完已還原）：回呼內拿掉「只處理 `QUEUED`／`GENERATING`」的判斷、退款金額多加 1，測試都會失敗。

### 未涵蓋

- App 通知、App 對 `GENERATING`／`SUCCEEDED`／`FAILED` 的畫面（見下）、影片播放（`vendor-sim` 不提供影片檔）、異常 API（第 8 輪）。
- 冒煙沒有走到：逾時（`GENERATION_TTL` 預設 30 分鐘，只由自動測試以假時鐘涵蓋）、廠商不可用時的派工重試（自動測試涵蓋）、真實併發回呼（自動測試涵蓋）。
- 單一 `api` 實例的前提沒有驗證多實例行為。

### 目前 App 對新狀態的表現（讀碼，未實測；5.2 用）

- `AdvanceCameoUseCase`：`QUEUED`、`GENERATING`、`SUCCEEDED`、`FAILED` 一律視為已送出，刪本機照片、標記已送出，回 `Done(order)`，不再輪詢。未知狀態（`UNKNOWN`）也回 `Done`，不動本機。
- `CameoProgressViewModel` 轉成 `ProgressUiState.Done`；進度畫面顯示後端 `display.title`／`message`（沒有才顯示「處理中」）加「回片段清單」。所以 App 只會顯示進入畫面當下那一次查到的文字：停在「排隊中」或「生成中」，不會自己更新到「已完成」或「生成失敗」。
- `GetUnfinishedCameosUseCase` 會對每個未刪的草稿查訂單；清單列顯示後端 `display`。`canContinueOrder` 只在 `AWAITING_UPLOAD`、`UPLOADED` 顯示「繼續」，其餘狀態沒有按鈕；列不會因為 `SUCCEEDED` 或 `FAILED` 自動移除（Room 列要等「通知發出後刪除」，通知尚未實作），只有「移除」入口僅限逾期列與 `cameoId == null` 的列。
- 沒有針對 `FAILED`／`SUCCEEDED` 的獨立畫面、通知、影片入口；`failure_reason` 沒有被 App 讀取。

## 第 5.2 輪：Android 前景服務（背景完成通知 PoC）

證據在 repo 外的 `~/cameo-iter5-evidence/`，檔名以 `r<編號>-` 開頭（`logcat`、`dumpsys notification`、`dumpsys activity services`、`dumpsys netpolicy`、UI 截圖）。`invalid-*` 資料夾是操作失誤、不計入結果的嘗試，原樣保留。

**哪些有存檔、哪些沒有（如實）：** 下列內容是當場查詢，沒有存檔：訂單 `updated_at`（延遲數字的減數）、第 4 項回呼的簽章內容與 `reason`、訂單 `FAILED/REFUNDED` 狀態、`REFUND` 明細、`printenv VENDOR_DELAY_SEC` 的輸出、把 `balance` 設為 100 的 SQL、`dumpsys activity recents` 的輸出。第 5 項「程序存活」、第 7 項「`force-stop` 後服務與程序消失」沒有事後 dump，只有間接證據（日誌與通知輪詢）。模擬器與後端的時鐘偏差未量測，延遲數字（模擬器 `logcat` 時間減後端 `updated_at`）含這個未知誤差。`r2-logcat`、`r3-logcat` 含後續輪次的內容，統計要依訂單 id 過濾。

**結論範圍：** 長時間背景樣本只有第 3 項一次（n=1）；第 2 項不能視為 2 分鐘背景驗證。

### 範圍

做了什麼：

- `:domain`：`decideTracking(查詢結果, 已追蹤毫秒)` 回「繼續查」「發完成通知並結束」「結束且不通知」。`SUCCEEDED`、`FAILED` 通知並結束；`QUEUED`、`GENERATING`、查詢失敗繼續；其他狀態（`EXPIRED`、`AWAITING_UPLOAD`、`UPLOADED`、`UNKNOWN`）結束且不通知。上限 `TRACK_LIMIT_MS` = 35 分鐘（後端 `GENERATION_TTL` 預設 30 分鐘加 5 分鐘）。單元測試含上限邊界（`TrackCameoTest`）。
- `:app`：`CameoTrackService`（`@AndroidEntryPoint`、`foregroundServiceType="dataSync"`），每 10 秒對所有追蹤中的訂單呼叫 `CameoRepository.getCameo`（主 client）；以記憶體表追蹤，已在跑時新 id 只加入表、不開第二個輪詢；全部結束才呼叫 `stopSelfResult(最後的 startId)`，回 true 才 `stopForeground`；`START_NOT_STICKY`。常駐通知（低重要度頻道，「生成中，完成後會通知你」）；完成通知（高重要度頻道，內容用後端 `display.title`／`message`，通知 id 由 `cameoId` 衍生，點按開啟 App 的 launcher intent）。
- 啟動時機：進度頁進入 `Done` 且訂單為 `QUEUED`／`GENERATING` 時，由 `TrackWhileGenerating`（`CameoProgressScreen` 內）在畫面至少 STARTED 時啟動，不請求通知權限；回到前景的恢復追蹤也會啟動（見「5.2 收尾」）。下方實測矩陣是第 5.2 輪實測矩陣當時的行為：進度頁請求權限。
- Manifest：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`。

不做（第 5.2 輪實測矩陣當時的範圍；補發、防重複、刪草稿已由「5.2 收尾」實作）：開啟 App 補發通知、防重複通知、訂單結束後刪草稿或清未完成清單、成功／失敗畫面、進度頁自動更新、WebSocket、影片播放、上傳途中進背景的測試、持久化追蹤（程序被殺即不再追蹤）。

### 代價與限制

- `APP_BACKGROUND` 對照：`r2-bg-evidence-t40.txt` 為 `procState=LAST`、`effective=APP_BACKGROUND`（權限對話框沒點掉的那一輪，服務未啟動）。無服務的有效樣本 1 次（作廢資料夾另有 1 份同值，不計入）；有服務的 3 份 dump（第 2、3 項：`r2-bg-evidence-t15`、`r3-bg-evidence-t5min`、`r3-bg-evidence-t9min`）皆為 `procState=FGS`、`effective=NONE`。
- 服務執行期間有常駐通知。
- 每 10 秒一次 `GET /v1/cameos/{id}`：耗電與流量；單筆訂單最久追蹤 35 分鐘，約 210 次請求。
- `am force-stop` 後服務與追蹤一併消失，不會收到通知（第 7 項實測：無通知）。
- 追蹤上限 35 分鐘；超過不再通知（單元測試涵蓋，沒有實際等滿 35 分鐘）。
- 只存在記憶體：程序被系統回收或被殺就不再追蹤，沒有補發（第 5.2 輪實測矩陣當時；補發見 5.2 收尾）。
- 通知權限被拒絕時（PoC 當下的缺口）：服務照跑、訂單照完成，但完成通知被略過（日誌 `notifications disabled, skip`），使用者收不到任何提示，連常駐通知也沒有；進度頁仍寫「完成後會通知你」；因為旗標已寫入，不再詢問，只能到系統設定開啟。這個缺口由 5.2 收尾實作的決定 2（沒開通知就不能建新訂單）處理，已由 5.2 收尾實作，模擬器實測排在 5.3。
- 服務只在「畫面在前景時送出成功」才啟動；畫面不在前景時不啟動（避免背景啟動前景服務被系統拒絕），這條路徑沒有實測。
- 所有實測只在 API 37 模擬器（AVD `Pixel_10_Pro`）進行，不代表真機；各廠牌的省電機制未驗。

### 環境與做法

- 後端：`API_PORT=8080 POSTGRES_HOST_PORT=15441 DAILY_LIMIT=20 VENDOR_DELAY_SEC=<值> docker compose -p cameo52 up -d --build`；換延遲時只重建 `vendor-sim`（`docker compose -p cameo52 up -d --no-deps vendor-sim`），每次以 `printenv VENDOR_DELAY_SEC` 確認。結束 `docker compose -p cameo52 down -v`。
- 模擬器：`caffeinate` 包著 `emulator -avd Pixel_10_Pro -no-snapshot-save -no-boot-anim -no-audio -camera-front emulated -camera-back virtualscene`；每輪前 `pm clear`、`pm grant CAMERA`。第 1、2、5 項用系統對話框回應通知權限；第 3、4、6、7 項用 `pm grant POST_NOTIFICATIONS` 預先同意（沒有走對話框）。
- 每一輪實際用 App 操作「片段 → 拍照 → 使用這張 → 確認支付」，進度頁出現後按 Home，之後不再打開 App。通知到達時間以 `dumpsys notification --noredact` 每約 2 秒輪詢（`r*-notif-first-seen.txt`，解析度約 2 秒）與 `logcat` 標籤 `CameoTrack` 的 `notified` 時間（較準）為準。
- 通知實際到達時間以 `logcat` 的 `notified` 為準；「延遲」＝ `notified` 時間減訂單 `updated_at`（進入 `SUCCEEDED`／`FAILED` 的時間）。

### 實測矩陣與實際結果

| # | 情境 | 結果 | 時間（本機 CST） | 證據 |
|---|---|---|---|---|
| 0 | `vendor-sim` 由 compose 啟動並實際回呼 | 通過：訂單到 `SUCCEEDED`（用第 1 項的訂單） | 見第 1 項 | `r1-*` |
| 1 | 延遲 20 秒，回主畫面 | 通過：通知「已完成」（`dumpsys` 只驗到標題，內文未驗）；延遲 7.1 秒（後端完成 18:28:58.5，通知 18:29:05.6）。回 Home（18:28:57）與後端完成只差約 1.5 秒，所以回 Home 後只經過 1 次輪詢，說服力有限 | 回 Home 18:28:57 | `r1-home.txt`、`r1-logcat.txt`、`r1-notif-dump.txt`、`r1-ui-*.png` |
| 2 | 延遲 2 分鐘，回主畫面 | 通過：回 Home 18:36:30、後端完成 18:36:52.5、通知 18:36:56.2，延遲 3.7 秒；回主畫面到後端完成僅約 22 秒，不能視為 2 分鐘背景驗證；中間 `procState=FGS`（`r2-bg-evidence-t15.txt`）。注意：此輪通知權限對話框第一次沒點掉（見下），Allow 是在較晚時手動點的，所以服務是點 Allow 後才啟動、回 Home 在其後 | 回 Home 18:36:30 | `r2-*` |
| 3 | 延遲 10 分鐘，回主畫面（實際等滿） | 通過：回 Home 18:39:51、後端完成 18:49:46.5、通知 18:49:51.7，延遲 5.2 秒；`dumpsys netpolicy` 5 分與 9 分時 `procState=FGS`、`effective=NONE`（即無限制）；該訂單共 61 次輪詢（`r3-logcat` 含後續輪次內容，依訂單 id 過濾後為 61；檔內有 65 筆 poll，含其他訂單，`f3354ed2` 為 61 筆）。長時間背景樣本只有這一次（n=1） | 回 Home 18:39:51 | `r3-*`（含 `r3-bg-evidence-t5min.txt`、`t9min.txt`） |
| 4 | `VENDOR_DELAY_SEC=600`，簽 `FAILED` 回呼（`CONTENT_REJECTED`） | 通過：通知標題是後端的「無法生成」（`CONTENT_REJECTED` 的後端文字；App 直接用 `display.title`），預期即為此文字。回呼 200；`/v1/points` 由 30 回到 40；訂單 `FAILED/REFUNDED`；明細有 `REFUND` 10。回呼送出 18:52:13.5，通知 18:52:16.5，延遲 2.9 秒（此項基準是回呼送出時間，不是 `updated_at`，與本節其他項的定義不同） | 回 Home 18:51:52 | `r4-*` |
| 5 | 拒絕通知權限（對話框「Don’t allow」），延遲 20 秒 | 不崩潰：記錄 `permission granted=false`，服務照啟動、輪詢、訂單 `SUCCEEDED` 後 `all finished` 並結束，程序存活；沒有任何通知（連常駐通知也沒有；`dumpsys notification` 查無該 App 通知）；日誌 `notifications disabled, skip` | 18:56 | `r5-*` |
| 6 | 從最近使用畫面滑掉 App，延遲 60 秒 | **未能驗證**：共 5 輪（4 個作廢資料夾加根目錄 `r6-*` 最後 1 輪），只有 3 輪有滑動紀錄（`r6-swiped.txt` 只在 `invalid-r6-recents-not-opened`、`invalid-r6-swipe-not-dismissed` 與根目錄 `r6-*`）。依當時紀錄任務仍在最近使用清單，但該 dump 未存檔；最後一輪的 `r6-ui-recents-before-swipe.png` 實際是桌面、不是最近使用畫面，所以「任務仍在最近使用」沒有截圖佐證。最後一輪服務照跑、通知於後端完成後 10.2 秒發出（延遲較大，在 10 秒輪詢加請求時間內）。這不能代表「滑掉 App」的行為 | 19:01–19:14（最後一輪為 19:13–19:14） | `r6-*`、`invalid-r6-*` |
| 7 | `am force-stop`，延遲 60 秒 | 如預期：`force-stop`（19:17:14.9，`r7-forcestop.txt`；19:17:11 是回主畫面）後約 117 秒、後端完成（19:18:02.5）後約 69 秒內，`dumpsys notification` 都沒有該 App 通知，輪詢到 19:19:11（`r7-notif-poll.txt` 最後一筆 `none fgs=0`）；「服務與程序消失」沒有事後 dump，只有這個間接證據；訂單照常 `SUCCEEDED`；重開 App 前不補發（補發在收尾實作） | 19:17 | `r7-*` |

### 操作失誤與重跑（如實）

- 第 1 項前兩次嘗試無效：權限對話框的「Allow」點錯座標（`uiautomator dump` 回報的對話框座標與實際螢幕不同，實際約 (640, 1597)），回 Home 時對話框還在，服務沒啟動，訂單已完成；檔案放在 `invalid-first-attempt`、`invalid-second-attempt`。之後都用截圖座標。
- 第 2、5 項各有一次相同的點錯（`invalid-r2-dialog-miss`、`invalid-r5-dialog-miss`）；第 2 項最後那次是手動補點 Allow，因此該輪「進度頁出現後立即按 Home」的順序不完整。
- 這些失誤都是測試腳本問題，不是服務失敗；但也顯示：使用者在權限對話框還沒回應時按 Home，服務不會啟動，也就不會有通知（本輪未針對這個路徑另測，只在失誤中觀察到）。PoC 當下的後果：`ASKED` 旗標在 launch 前寫入，若結果沒回來，之後不會再詢問（推測，未實測）。這條路徑由 5.2 收尾實作的決定 2 取代（檢查點移到按「確認支付」、App 不在進度頁請求權限）。
- 第 6 項共 5 輪：4 個作廢資料夾（`invalid-r6-late`、`invalid-r6-recents-not-opened`、`invalid-r6-recents-not-opened-2`、`invalid-r6-swipe-not-dismissed`）加根目錄 `r6-*` 最後 1 輪。作廢原因：時間來不及、最近使用畫面沒開（兩次）、或手勢沒把任務滑掉。只有 3 輪有滑動紀錄；recents 的 dump 未保存；最後一輪的 `r6-ui-recents-before-swipe.png` 是桌面。第 6 項仍是「未能驗證」。
- 第 6 項重試途中測試點數用完（進度頁顯示「點數不足」，該次不計）；對隔離資料庫執行 `update points_account set balance=100` 後繼續，第 6、7 項之後的訂單用重設後的點數；第 4 項的點數數字（40→30→40）是重設之前量的。

### 自動測試

- `./gradlew :domain:test :app:assembleDebug :app:testDebugUnitTest --rerun-tasks`：BUILD SUCCESSFUL（77 個任務、77 個執行）；`:domain` 60 筆、`:app` 82 筆，0 失敗、0 略過（`TrackCameoTest` 6 筆含上限邊界）。服務與 `TrackWhileGenerating` 沒有單元測試，只靠上面的模擬器實測。

### 未涵蓋

- 真機、其他 API 版本、省電模式／Doze／廠牌電源管理。
- 從最近使用畫面真的滑掉 App（第 6 項未能驗）。
- 實際等滿 35 分鐘上限；多筆訂單同時追蹤；網路中斷時的查詢失敗續查（只由單元測試涵蓋）。
- 上傳途中進背景；畫面不在前景時送出成功的路徑；權限對話框未回應就離開（PoC 行為，由決定 2 取代）。
- `VENDOR_FAILED`／生成失敗路徑未在模擬器實測（第 4 項測的是 `CONTENT_REJECTED` 回呼）。
- 通知內容：`dumpsys` 只驗到標題，內文未驗；點按通知開啟 App 未實測。
- 通知欄展開畫面的截圖：`input swipe` 在模擬器上沒有可靠截到，通知內容以 `dumpsys notification --noredact`（`r*-notif-dump.txt`）為證。
- 開啟 App 補發、防重複通知、完成通知點按後的深層連結（只開 launcher）。補發在 5.2 收尾實作。

### 排到 5.3 補驗

以下項目排到 5.3：真正的 2 分鐘背景（回主畫面後確實等滿 2 分鐘才完成）、`VENDOR_FAILED`、從最近使用畫面滑掉 App、點按通知開啟 App。其餘未涵蓋項目照上面保留。

### 5.2 收尾：通知權限檢查、補發與恢復追蹤

結論範圍：只有單元測試與組建，沒有啟動 App、沒有開模擬器、沒有跑 Docker。以下行為在模擬器的實測排在 5.3。

範圍（實際行為）：

- 確認頁按「確認支付」，在建單（`POST /v1/cameos`）之前檢查「通知可用」：`areNotificationsEnabled()` 且「生成結果」頻道 importance 不是 `IMPORTANCE_NONE`（頻道還沒建立視為可用；保留 `dropUnlessResumed`）。可用就建單；App 通知開著但頻道被關，一律直接顯示提示（不請求權限）；App 通知關閉且 API 33+、系統還能顯示對話框（沒請求過，或 `shouldShowRequestPermissionRationale` 為 true）就請求 `POST_NOTIFICATIONS`，同意後重新檢查，通知可用才建單、頻道仍關就顯示提示，拒絕就停在確認頁、不建單，並顯示 App 自己的提示；其餘情況（含 API 33 以下）直接顯示提示「需要開啟通知才能客串」／「生成完成時要用通知告訴你。」，按鈕「前往設定」（`ACTION_APP_NOTIFICATION_SETTINGS`）與「取消」。從設定回來不自動建單。「已請求過」旗標只在收到權限結果後寫入。
- App 開啟與進度頁不請求權限；有 `cameoId` 的訂單（未完成清單的繼續、重試）不檢查；還不確定是否建單的 `CREATE` 草稿會到確認頁並經過通知檢查，代價是通知關著時，這筆可能要等上傳期限才釋放點數。通知關閉時保留的草稿在未完成清單沒有「移除」按鈕，但這時也建不了新單，不會累積。
- 「已請求過」旗標在收到權限結果時寫入，對話框被取消時也會寫入，之後走「前往設定」路徑（Android 行為為推論，未實測）。
- 訂單結束（`SUCCEEDED`／`FAILED`）：前景服務與回前景恢復都先 `claimFinishedCameo`（刪草稿列與本機照片，依單一 `DELETE` 的回傳筆數），刪到才發完成通知；每筆認領成功立刻通知，「認領＋通知」在 `withContext(NonCancellable)` 內，取消不會漏發。通知不可用（App 通知或結果頻道被關）時不認領、不通知，草稿保留。少發範圍：除了程序在認領之後、發通知之前被殺之外，還有回前景判斷「通知可用」到實際認領之間，使用者剛好關掉通知的極窄窗口，這時草稿已刪、不補發（讀碼推論，未實測）；協程取消不會少發。細節見 `ARCHITECTURE.md` 第 8 節。
- 狀態寫入（`markCreated`、`recordError`、`markUploaded`、`markSubmitted`、清除／附上照片路徑）都是條件式 `UPDATE ... WHERE client_request_id = ?`，列已被認領刪除時不會重建。
- 回到前景（`MainActivity.onStart`）：對所有有 `cameoId` 的草稿查訂單，`SUCCEEDED`／`FAILED` 認領後通知，`QUEUED`／`GENERATING` 啟動前景服務，其他狀態與查詢失敗不動。
- 前景服務：`stopSelfResult(最後的 startId)`（回 false 不停）、`onTimeout`（API 35+）停止服務、`startForeground` 被拒不崩潰。
- 後果：草稿刪除後訂單從未完成清單消失；「我的客串」尚未實作，到 5.2b 前使用者只能從通知文字與點數明細看到結果，點通知目前只開首頁，5.2b 會改。

自動測試：`:domain` 認領與恢復、權限判斷三條分支與旗標寫入時機、確認頁 ViewModel 未通過檢查不建單（含權限同意後頻道仍關不建單、可用只建一次）、`:data` 認領只有第一次回 true、`mark*` 的讀寫競爭用 fake DAO 測過（`get` 回傳列後立刻刪列、寫入前先刪列，兩種路徑都斷言不重建；`markSubmitted` 目前不呼叫 `get`，測試守的是不得改回先 `get` 再 `upsert`），這些競爭測試未在真 Room 執行；`AttachCameoPhotoUseCase` 在 `attachPhoto` 回 false 且列已被刪時回 `Gone`。指令 `cd android && ./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`，結果 BUILD SUCCESSFUL；從測試 XML 統計：`:domain` 71、`:data` 60、`:app` 98，共 229 筆，0 失敗、0 skipped。`:data` 沒有真 Room 測試環境，認領測的是 repository 搭配記憶體 DAO，`DELETE` 與各條件式 `UPDATE` 的實際回傳筆數／SQL 語意沒有在真 Room 驗證（`FakeDao` 照 SQL 語意模擬；`UPDATE` 的語法只通過 Room 編譯期檢查）。

未驗證：

- 服務、`MainActivity` 回前景、權限對話框、設定頁往返、通知實際發出，全部沒有單元測試也沒有實測。
- `onTimeout`（API 35+）、`stopSelfResult` 回 false 的分支、`startForeground` 被拒的分支。
- 草稿被刪後進度頁與未完成清單的畫面。
- 真機、其他 API 版本。

交給 5.3 的操作：

0. 先 `adb shell pm clear <applicationId>` 或重新安裝，清掉舊的「已請求過」旗標。
1. 權限對話框三條路徑：(a) API 33+ 從未請求過，按「確認支付」跳系統對話框，同意後建單（頻道未被關時；頻道仍關就顯示提示、不建單）；(b) 拒絕後留在確認頁、不建單、顯示「需要開啟通知才能客串」提示；再按一次確認若系統仍可顯示對話框（`shouldShowRequestPermissionRationale`）就再跳，第二次拒絕後改直接顯示提示；(c) 系統通知關閉（含 API 33 以下）時不跳對話框，直接顯示提示。
2. 「前往設定」開啟本 App 的通知設定；開啟通知後返回，確認不會自動建單，再按「確認支付」才建單；按「取消」關閉提示且不建單。
3. 補發只發一次：訂單完成時 App 被殺（或服務被殺），重開 App 收到一則完成通知；再次進出前景不重複通知；未完成清單中該筆消失。
4. 回前景恢復追蹤：送出後殺 App 程序（訂單仍在 `QUEUED`／`GENERATING`），重開 App 後前景服務重啟、完成時發通知；與進度頁 `TrackWhileGenerating` 同時存在時不重複輪詢。
5. 已刪草稿的畫面：通知發出後草稿已刪；在進度頁停留時不報錯；程序被殺、訂單完成後回到 App、導覽還原到進度頁時會看到「這筆客串已結束」（導覽還原未實測，排 5.3；結果將由「我的客串」提供）；未完成清單不崩潰。
6. 訂單進行中才關通知：不阻擋，完成時不發通知也不刪草稿；重新開啟通知後開啟 App 補發一則。
7. 頻道被關：App 通知開著、只關「生成結果」頻道，按「確認支付」直接顯示提示（不跳權限對話框），「前往設定」開到 App 通知設定；服務與回前景此時不認領、不通知。
8. 另列：`startForeground` 被拒、`onTimeout`、`stopSelfResult` 回 false 的情境能否製造需另評估。

## 第 5.2b-1 輪：後端訂單列表 `GET /v1/cameos?cursor=`

只驗後端，沒有改 App、沒有開模擬器。

### 範圍

- `GET /v1/cameos?cursor=`（需要 token）：只列 `QUEUED`、`GENERATING`、`SUCCEEDED`、`FAILED`，依 `(created_at, id)` 由新到舊，一頁 20 筆（`Config.CameoPageSize`，預設 20，與點數明細頁大小分開）。
- 回應 `{"items":[cameo],"next_cursor":null|string}`；`items` 每筆與 `GET /v1/cameos/{id}` 的 `cameo` 共用 `toCameoJSON`，不含 `upload`；無資料時 `items` 為 `[]`。
- 分頁判斷與 `Ledger` 相同：查 `limit+1`、回傳前 20 筆，剛好 20 筆時 `next_cursor` 為 `null`（與 `Ledger` 無差異）。
- cursor 與點數明細共用 `encodeCursor`／`decodeCursor`，`domain.LedgerCursor` 改名為 `domain.PageCursor`；不合法回 `400 INVALID_CURSOR`。唯讀，不呼叫 `ExpireDue`、不開交易。
- 既有 `TestRoutingAndAuthHTTP` 原本斷言 `GET /v1/cameos` 為 `405`，已改成 `PUT` 為 `405`（`Allow: GET, HEAD, POST`）並把 `GET /v1/cameos` 加進 `401` 清單。

### 測試

指令（隔離專案 `cameo52b1`，埠 15442）：

```
POSTGRES_HOST_PORT=15442 docker compose -p cameo52b1 up -d postgres
cd backend && TEST_DATABASE_URL='postgres://cameo:cameo@127.0.0.1:15442/cameo?sslmode=disable' go test -count=1 ./...
```

結果：全部套件 `ok`，`go test -v` 統計 128 個 `--- PASS`、0 FAIL、0 SKIP；`go vet ./...` 無輸出，`gofmt -l .` 無輸出。新測試在 `backend/test/integration/list_test.go`（真實 Postgres）：同 `created_at` 45 筆跨 3 頁、7 種狀態篩選、20／21 筆的 `next_cursor`、列表項目與 `GET /v1/cameos/{id}` 的 `cameo` 完全相同、不合法 cursor 與點數明細回應相同、空列表、無 token `401`、`POST` 不變。

### Mutation 結果（`go test -overlay`，不改 repo 檔案）

| mutation | 結果 |
|---|---|
| `(c.created_at, c.id) <` 改 `<=` | 抓到：分頁測試 id 重複（`id-025`、`id-006` 出現兩次），第 2 頁測試失敗 |
| 拿掉狀態篩選 | 抓到：狀態測試回 7 種、邊界測試看到 `AWAITING_UPLOAD` |
| 不多查一筆（`limit` 不加 1） | 抓到：21 筆時 `next_cursor` 為 `nil`，分頁只有 1 頁 |
| 列表少 `display` | 抓到：列表與單筆的 `cameo` 不同 |
| 列表少 `failure_reason` | 抓到：`FAILED` 項目 `failure_reason` 為 `nil` |

### 未涵蓋

- App「我的客串」頁、點通知開此頁（5.2b-2 以後）。
- `video_available`、`share` 目前固定 `false`／`{"active":false,"url":null}`（同單筆查詢）。
- 並行寫入時的分頁一致性（只測了靜態資料）。
- `user_id` 條件沒有回歸測試（`points_account` 有 `CHECK (id = 1)`，目前 schema 只能有一個使用者）。
- 「列表不觸發逾期、不改狀態」沒有自動測試，只靠讀碼（`Service.Cameos` 只呼叫 `ListCameos`）。

## 第 5.2b-2 輪：App「我的客串」頁

底部導覽第三分頁，顯示 `GET /v1/cameos?cursor=`。不做：自動輪詢、點通知開頁、列可點擊、播放、分享。

### 行為

- 進入時載入第一頁；每次回到前景（`ON_RESUME`）靜默刷新第一頁，且只在已載入（`Idle`）時刷新；載入中或錯誤時不重查。刷新失敗保留列表，並在列表上方的固定區（`LazyColumn` 外，不隨捲動移走）顯示錯誤與重試；按重試後固定區改顯示轉圈（`refreshing`），完成後成功就清掉、失敗就回到錯誤與重試。沒有刷新失敗時的回前景刷新不顯示轉圈。
- 載入更多、失敗重試、捲到底自動載入同點數明細；點數明細與此頁共用 `PagedList`（`ui/PagedList.kt`），點數頁行為不變（仍忽略刷新失敗）。
- 已知代價：回前景時列表換回第一頁的最新內容，可能先變短，捲到底會再載入後面的頁，不保留已載入的後面頁面；不在前景的頁面不輪詢，看到的是最近一次查詢結果。

### 單元測試與建置

指令（`android/`）：`./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`，結果 `BUILD SUCCESSFUL`；測試數（讀 `build/test-results` XML）：`:domain` 71、`:data` 64、`:app` 112，失敗 0。新增 `PagedListTest` 14 個、`CameoRepositoryTest` 新增 4 個（空列表、含 `created_at`／未知 status／cursor、cursor 參數、`400 INVALID_CURSOR`）。

方案修正：`ViewModelTest.refreshLedger_failureKeepsItemsAndState` 因刷新失敗現在會設 `refreshFailed=true` 與 `errorMessage`，斷言改為 `before.copy(refreshFailed = true, errorMessage = "後端訊息")`；其餘明細測試只改型別名。

### Mutation（逐一改、跑測試、確認失敗、還原）

| mutation | 結果 |
|---|---|
| loadMore 成功只留新資料 | 抓到（3 個測試失敗） |
| 第二頁 fetch 傳 `null` | 抓到（4 個） |
| refresh 失敗清空 items | 抓到（1 個） |
| refresh 成功不重設 `refreshFailed` | 抓到（1 個） |
| 拿掉 refresh 的 `job?.cancel()` | 抓到（1 個） |
| 拿掉 loadFirstPage 的 `job?.cancel()` | 抓到（1 個） |
| 拿掉 loadMore 的 phase 防重入 | 第一版測試存活（連續呼叫之間沒讓協程啟動，被取消的協程根本沒跑 fetch）；測試改成每次呼叫後 `advanceUntilIdle()` 後抓到（1 個） |
| 拿掉 loadMore 的 `job?.cancel()` | 抓到（1 個） |
| 拿掉 refresh 的 phase 檢查 | 抓到（2 個） |
| loadMore 失敗清空 items | 抓到（1 個） |
| `CameoListResponse` 的 `next_cursor` SerialName 改錯 | 抓到（`:data` 1 個）。第一次嘗試用了不存在的 `:data:test` 任務，不算結果，已用 `:data:testDebugUnitTest` 重跑 |

### 模擬器最小冒煙（一次）

環境：API 37 AVD `Pixel_10_Pro`（`-camera-front emulated -camera-back virtualscene -no-snapshot-save`），後端 `docker compose -p cameo52b2`。用 curl 建 2 筆（`c1`、`c2`）、上傳、送出。截圖在 `~/cameo-iter5-evidence/r52b2-*`（repo 外）。

- 底部有「片段清單／點數／我的客串」三個分頁。
- 剛送出後（22:41:38）列表顯示兩筆「生成中／完成後會通知你」與時間 `2026-11-06 22:41`，片段名稱與 `curl GET /v1/cameos` 一致。
- 約 22:42:04 後端已全部 `SUCCEEDED`；留在原頁時畫面仍是舊狀態（不輪詢）；切到「點數」再切回後顯示「已完成／你的客串短片完成了」。
- 點數頁正常：可用 80、占用 0，兩筆「扣點 -10」。

### 審查修正（5.2b-2 修正輪）

**修前重現（步驟 A，未改程式前，同一模擬器與後端 `-p cameo52b2fix`，`DAILY_LIMIT=30`、`VENDOR_DELAY_SEC=3`）：**

- R1（切到點數期間 curl 新建一筆，切回「我的客串」不捲動）：做了 2 次，**未重現**。新訂單都在畫面最上方（第一個可見列是新那筆：「屋頂告白」、「最後一班車」，bounds 起點 y=180）。當時列表只有 1～3 筆、不到一屏，所以這個結果只代表短列表未重現，**不代表長列表沒問題**；沒有加標題。截圖 `~/cameo-iter5-evidence/r52b2fix-before-r1-*.png`。後來的獨立審查換成回前景路徑、加長列表：超過一屏 3/3 重現（新訂單被擠到畫面上方），不到一屏 3/3 未重現，證據 `~/cameo-iter5-evidence/r52b2judge-r3-long-*.png`；處理見下一節「回前景捲動」。
- R2（停 api 後切回）：錯誤框在畫面內（列表在最上方、未捲動時，bounds y=204–415）。截圖 `r52b2fix-before-r2.png`。修前結構下錯誤框是 `LazyColumn` 的第一個 item，捲離最上方時會跟著捲走，這個情況修前未實測（讀碼推論）；仍依方案移出列表。

**修改：**

- 刷新失敗提示移到 `LazyColumn` 外的固定區（`Column { 固定區; LazyColumn(weight(1f)) }`）。
- `PagedUiState.refreshing`：`refresh()` 通過 phase 檢查後設 true，成功或失敗清 false；`loadMore()`、`loadFirstPage()` 也使重設為 false。畫面：`refreshFailed && refreshing` 顯示轉圈，`refreshFailed && !refreshing` 顯示錯誤與重試，其他不顯示（沒有失敗時的回前景刷新不顯示轉圈）。點數頁不讀此欄位。
- `Models.kt` 的 `createdAt` 註解改為符合事實（列表與單筆都有）；預設值 `null` 保留（多個測試與 domain 程式以不帶 `createdAt` 建構 `Cameo`，DTO 也容許缺漏）。
- 文件：「回前景跳回第一頁」改成實際行為（換回第一頁最新內容，可能先變短，捲到底再載入後面的頁）。

**測試：** `./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`，`BUILD SUCCESSFUL`；測試數（`build/test-results` XML）：`:domain` 71、`:data` 64、`:app` 118，失敗 0（`:app` 由 112 增為 118）。`PagedListTest` 把 `refresh_doesNotChangeStateWhileInFlight` 改為 `refresh_inFlightOnlySetsRefreshing`（進行中只多 `refreshing=true`，其他不變；這是方案要求的行為改變，不是放寬）並新增 6 個；`refresh_whileLoadMoreErrorOrError_doesNotFetch` 補上 Error 路徑。`ViewModelTest.refreshLedger_failureKeepsItemsAndState` 未改、通過。

**Mutation（`:app:testDebugUnitTest --rerun-tasks`，逐一改、確認失敗、還原）：**

| mutation | 結果 |
|---|---|
| 刷新成功時不清 `refreshing` | 抓到（1 個） |
| 刷新失敗時不清 `refreshing` | 抓到（2 個：`PagedListTest` 1、`ViewModelTest.refreshLedger_failureKeepsItemsAndState` 1） |
| `loadMore` 不清 `refreshing` | 抓到（1 個） |
| `loadFirstPage` 重設時保留 `refreshing` | 抓到（1 個） |
| `refresh()` 不設 `refreshing=true` | 抓到（4 個） |
| `loadMore` 不清 `refreshFailed` | 抓到（1 個） |
| `refresh` 的 phase 檢查放行 Error | 抓到（1 個） |

**修後模擬器（重裝新 APK，同一後端；截圖 `~/cameo-iter5-evidence/r52b2fix-after-*.png`，已開圖確認）：**

- V1：當時只有短列表（不到一屏），R1 未重現、未加標題；仍跑了同流程，新訂單（「雨夜重逢」排隊中）在最上方（`after-v1-b.png`）。
- V2：停 api 後切回，錯誤框在畫面內（`after-v2-error.png`）；恢復 api 後按重試，錯誤框消失、列表更新（`after-v2-retry.png`）。
- V3：共 10 筆（略超過一屏，只能捲一點點；未做 20 筆以上的多頁），捲到底後重複 R2：錯誤框固定在最上方、列表在其下方捲動位置不變（`after-v3-error-scrolled.png`）；恢復 api 後按重試，錯誤框消失（`after-v3-retry.png`）。
- 點數頁正常：可用 0、占用 0、10 筆「扣點 -10」（`after-points.png`）。
- 重試時的轉圈只會短暫出現，截圖沒抓到：由單元測試驗證，未目視。

**已知限制（未修，讀碼推論，未量測）：** 首次進頁若第一頁在轉場結束前就回來，`ON_RESUME` 的刷新可能多查一次第一頁。這是一次靜默查詢、不閃爍；少見情況下這次查詢失敗會多出錯誤提示，按重試可恢復。

### 回前景捲動（5.2b-2 修正輪二）

**規則（「第一頁」指畫面的第一屏，不是 API 分頁）：** 第一筆在畫面上（只露出一部分也算）時，有新訂單就自動捲到新的第一筆，並且不顯示按鈕；第一筆不在畫面上時，不自動捲、位置不動（例外：捲過第一頁後回前景，列表會換回第一頁，位置會變），右下角顯示「回到頂端」（第一筆離開畫面就出現、看得到就消失），點擊回到第一筆。第一筆 `id` 沒變（只有狀態更新或回前景）時不捲動。沒有加標題、沒有改 `PagedList`、沒有第一頁筆數的狀態。

**做法：** 新增 `ui/ListTopPolicy.kt` 的純函式 `shouldScrollToTop(previousFirstId, currentFirstId, visibleKeys)`；`MyCameosScreen` 用 `rememberLazyListState`，`LaunchedEffect(firstId)` 以「上一個第一筆的 key 是否在可見項目內」判斷（不查 index：新訂單插入後 `LazyColumn` 會改 index，副作用與量測先後不一定 [待確認]；上一個第一筆是否可見在一般情況下量測前後答案相同。例外：兩次查詢之間新增超過一頁（目前 20 筆）時，上一個第一筆不在新資料內，此時可能不捲 [待確認]）。按鈕用 `firstVisibleItemIndex > 0` 決定。

**單元測試與建置：** `./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`，`BUILD SUCCESSFUL`；測試數（`build/test-results` XML）：`:domain` 71、`:data` 64、`:app` 123，失敗 0、略過 0（`:app` 由 118 增為 123，新增 `ListTopPolicyTest` 5 個）。

| mutation（`:app:testDebugUnitTest --rerun-tasks`） | 結果 |
|---|---|
| 拿掉 previous 為 null 的判斷 | 第一版測試存活（`null in visibleKeys` 本來就是 false）；測試改成 `visibleKeys` 含 `null` 後抓到（1 個） |
| 拿掉第一筆 id 比對 | 抓到（1 個） |
| 拿掉可見判斷 | 抓到（1 個） |
| 拿掉 current 為 null 的判斷 | 抓到（1 個） |
| 函式固定回傳 false | 抓到（1 個） |

還原後以 `diff` 確認與原檔相同。

**模擬器實測（API 37 AVD `Pixel_10_Pro`，後端 `docker compose -p cameo52b2top`，`DAILY_LIMIT=1000`；列表資料用 SQL 直接寫入隔離資料庫：超過 20 筆 `SUCCEEDED`，新訂單也用 SQL 寫入；回前景用 `KEYCODE_HOME` 加 launcher 意圖 `monkey`，每次 pid 都是 2520，沒有重建；截圖 `~/cameo-iter5-evidence/r52b2top-*.png`）：**

| 情境 | 結果 |
|---|---|
| 1 看得到第一筆（頂端，3 次） | 通過 3/3：第一個可見列都是新訂單（title 起點 y=180，完整），按鈕不存在（`1a`、`1b`、`1c`） |
| 2 第一筆只露出一部分（小幅 swipe，第一列只剩 y=156–217 的訊息與時間） | 通過：回前景後捲到新的第一筆（y=180），按鈕不存在（`2`） |
| 3 切到「點數」、新增訂單、切回 | 通過：第一個可見列是新訂單（y=180），按鈕不存在（`3`） |
| 4 看不到第一筆 | 通過：回前景前後第一個可見列相同（時間 23:30，y=156），按鈕存在（`4-before-click`）；點按鈕後第一個可見列是新訂單（23:32，y=180），按鈕消失（`4-after-click`） |
| 5 按鈕切換 | 通過：第一筆剛離開畫面（第一個可見列為第二筆，y=171）按鈕出現（`5-button-on`）；捲回第一筆部分可見（訊息列 y=156）按鈕消失（`5-button-off`） |
| 6 沒有新訂單，停在中段 | 通過：回前景前後第一個可見列相同（`屋頂告白`，y=179），位置不變，按鈕存在 |
| 7 第 2 頁 | 捲到底時最後一列為 20:11（共超過 20 筆，已載入後面的頁）；回前景後列表換回第一頁（第一個可見列變成 21:55 那筆，不是原本的位置），捲到底再載入後面的頁、最後一列又是 20:11；按鈕存在，點擊回到第一筆（`7-before`、`7-after`、`7-bottom`、`7-after-click`） |

點數頁正常：可用 100、占用 0、「目前沒有點數明細」（`points`）。

**未驗證：** 第一筆 `id` 沒變但狀態更新時不捲動只由單元測試（`shouldScrollToTop`）涵蓋，模擬器只驗了「沒有新訂單」的情境 6；情境 7 回前景後位置變成第一頁後段（回前景後第一個可見列是第 14 筆附近，不是頂端也不是原位置），按需求只記錄；Loading／Error 時按鈕不顯示只由讀碼（按鈕只在列表分支內組合），空列表則因 `firstVisibleItemIndex` 為 0 不顯示，未在模擬器逐一觸發；沒有 Compose UI 測試；真機、動畫期間再次回前景、背景與程序被殺未測。

- 回前景更新途中切走分頁、且第一筆當時只露出一部分時，切回後不會捲到完整（讀碼推論，未實測，條件很窄，不修）。
- 自動捲動途中按鈕可能短暫出現（推測，未目視，5.3 順便看）。
- 底部留白 `contentPadding = PaddingValues(bottom = 80.dp)` 是審查後補的，只跑建置與單元測試，沒有再開模擬器。

### 未驗證

- 空列表、載入更多失敗畫面：只靠單元測試（狀態機層），沒有 Compose UI 測試，畫面接線未在模擬器重現。刷新失敗畫面已在修正輪 V2、V3 於模擬器驗過；翻頁（第 2 頁）只在「回前景捲動」情境 7 看到資料與回前景表現，載入更多失敗畫面仍未驗。
- `INVALID_CURSOR` 在 App 端的畫面（只驗 data 層轉成 `Failure`）。
- 點通知開此頁（未實作）；真機；背景與程序被殺後的行為（排 5.3）。
