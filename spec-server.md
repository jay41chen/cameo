# 客串短劇：後端規格

> 從 `spec.md` 提煉，只含 server side；有衝突時以 `spec.md` 為準。`ARCHITECTURE.md` 仍寫 8 種異常和 WebSocket，`README.md` 仍寫 WebSocket，第 8、9 輪才同步；不一致時以規格為準。App 行為只列會影響後端的部分。`[待確認]` 沿用原標記。

## 1. 目標與範圍（§1、§2、§9）

- **目標：** 網路、App 或廠商出事時，點數不會算錯，結果拿得到，是否分享由觀眾決定。
- **責任：** 每日上限、浮水印、分享權限都由後端負責，不能交給 App。
- **技術：** Go、Docker、Postgres、`vendor-sim`。
- **限制：** 乾淨電腦一行指令啟動；不依賴個人帳號、金鑰或 Firebase。
- **不做：**
  - 取消訂單
  - 位元組續傳
  - 驗證照片來源
  - 正式推播服務
  - console 網站
  - 登入（使用固定測試帳號與 token）

## 2. 額度（§3）

- 每人每日上限預設 3，由設定值 `DAILY_LIMIT` 決定，以台北時間計算，建單時占用。
- 失敗或過期都退額度；只有 `CONTENT_REJECTED` 退點不退額度。
- `GET /v1/quota` 回應：
  `{"remaining":2,"resets_at":"2026-11-07T00:00:00+08:00","display":"今日還可客串 2 次"}`
- 超過上限時建單回 `429`。
- 必測：建單減少、失敗或過期恢復、內容拒絕不恢復、台北午夜重置。

## 3. 點數（§4）

| 時機 | 點數 | 明細 |
|---|---|---|
| 建單 | 可用減、占用增 | 不寫 |
| 送出（可重複呼叫，冪等） | 同一交易扣點 | `CHARGE` |
| 上傳階段過期 | 釋放占用 | 不寫 |
| 送出後失敗 | 退點 | `REFUND` |
| 送出晚於期限 | 回 `410`，不扣款 | 不寫 |

- `GET /v1/points` 回 `{available, held}`，`available = balance - held`。
- `points_state` 的值：`HELD`、`CHARGED`、`REFUNDED`、`RELEASED`。
- 轉終態、退點或釋放、退額度必須在同一個資料庫交易內完成。
- 必測：送出與過期排程同時發生、重複送出只扣一次、占用中不能超額建單。

## 4. 訂單狀態（§5）

| 從 | 到 | 觸發 |
|---|---|---|
| 無 | `AWAITING_UPLOAD` | 建單（占用點數與額度） |
| `AWAITING_UPLOAD` | `UPLOADED` | 上傳自拍 |
| `UPLOADED` | `QUEUED` | 送出（扣點） |
| `QUEUED` | `GENERATING` | 廠商接受工作 |
| `QUEUED`／`GENERATING` | `SUCCEEDED` | 成功回呼（回呼可能先於「接受」寫入） |
| `QUEUED`／`GENERATING` | `FAILED` | 失敗回呼或逾時（退點） |
| `AWAITING_UPLOAD`／`UPLOADED` | `EXPIRED` | 逾時（釋放，無明細） |

- `SUCCEEDED`、`FAILED`、`EXPIRED` 是終態，先到的為準；晚到或矛盾的回呼只記 log。
- 逾時後才收到成功回呼：維持 `FAILED`，只記 log。
- 上傳期限 1 小時，生成期限程式預設 30 分鐘，compose 預設將改為 2 分鐘（尚未實作）。
- `failure_reason` 的值：
  - `CONTENT_REJECTED`：退點，不退額度。
  - `VENDOR_FAILED`：廠商回報失敗且原因不是 `CONTENT_REJECTED`；退點也退額度。
  - `TIMEOUT`：生成期限到仍無結果；退點也退額度。
- 分享狀態獨立於狀態機，只有 `SUCCEEDED` 能分享。

`display` 文字：

| 狀態 | 標題 | 說明 |
|---|---|---|
| `AWAITING_UPLOAD` | 等待上傳 | 已占用 N 點，送出後才扣款 |
| `UPLOADED` | 送出中 | 照片已上傳，尚未扣款 |
| `QUEUED` | 排隊中 | 完成後會通知你 |
| `GENERATING` | 生成中 | 完成後會通知你 |
| `SUCCEEDED` | 已完成 | 你的客串短片完成了 |
| `FAILED`（`CONTENT_REJECTED`） | 無法生成 | 照片未通過審核，點數已退還，今日次數不退還 |
| `FAILED`（其他原因） | 生成失敗 | 點數已退還 |
| `EXPIRED` | 已過期 | 上傳期限已過，點數沒有扣款，請重新客串。 |

不向使用者提示廠商故障。未知狀態用通用文字「處理中」。

## 5. API

| 端點 | 需求 | 現況 |
|---|---|---|
| `GET /v1/clips`（README） | 片段清單由後端提供 | 已實作 |
| `GET /v1/points`、`GET /v1/points/ledger`（路徑見 README） | 點數與明細；初始點數由模擬後端提供 | 已實作 |
| `GET /v1/quota` | 額度 | 已實作 |
| `POST /v1/cameos` | 建單，以 `client_request_id` 作冪等鍵，透過 `Idempotency-Key` header 傳遞（README） | 已實作 |
| `GET /v1/cameos?cursor=` | 只列 `QUEUED`、`GENERATING`、`SUCCEEDED`、`FAILED`，每筆含 `display` 文字 | 已實作 |
| `GET /v1/cameos/{id}` | App 改版後，已送出區可視的 `QUEUED`、`GENERATING` 每 3 秒查一次；背景服務每 10 秒查一次 | 已實作 |
| `PUT /v1/cameos/{id}/selfie`（README） | 上傳自拍 | 已實作 |
| `POST /v1/cameos/{id}/submit`（README） | 送出 | 已實作 |
| `POST /webhooks/vendor`（README） | 廠商回呼，HMAC 驗簽 | 已實作 |
| `GET /v1/cameos/{id}/video`（README） | 播放，回傳固定範例影片（`spec.md` §2）；訂單不存在或不屬於該使用者回 `404 CAMEO_NOT_FOUND`，存在但非 `SUCCEEDED` 回 `404 VIDEO_NOT_READY` | 已實作（7.1）：SUCCEEDED 回 200／206（Range）、HEAD、`video/mp4`；其他狀態 `404 VIDEO_NOT_READY`；實測（Docker）下載 sha256 與 `sample.mp4` 相符，`Range: bytes=0-99` 回 206、超出檔尾回 416；`video_available` 同步為 true |
| `PUT`、`DELETE /v1/cameos/{id}/share`（README） | 建立與取消分享；`PUT` 成功一律 `200`，非 `SUCCEEDED` 回 `409 NOT_SHAREABLE`；`DELETE` 冪等回 `204`；訂單不存在回 `404 CAMEO_NOT_FOUND`（見下方「影片與分享約定」） | 已實作（7.2）。整合測試與 Docker 冒煙通過：非 `SUCCEEDED` 回 `409`、重複 `PUT` 同一網址、`DELETE` 冪等、取消後再分享發新 token |
| `GET /s/{token}`（README） | 分享網頁，免登入；已取消或不存在回簡短 HTML 404（通用錯誤格式的例外） | 已實作（7.2）。有效回 `200 text/html`；已取消、不存在、含 NUL 回 HTML `404`；查詢錯誤回 HTML `500` |
| `GET /s/{token}/video` | 分享頁播放，免登入，回固定範例影片；分享已取消或不存在維持 JSON `404` | 已實作（7.2）。Range `206`、HEAD、免登入；已取消或不存在回 JSON `404`；`sha256` 與範例影片相同 |
| `GET`、`POST`、`DELETE /debug/faults`、`POST /debug/reset`、`POST /debug/faults/consume`（內部） | 異常觸發與重設；consume 供 vendor-sim 取得異常 | 部分實作（8.1；`POST /debug/faults/consume` 尚未實作，排 8.3）：`DEBUG_API_ENABLED=true` 才註冊（其他值回一般 `404`），需 Bearer token；`GET`／`POST`／`DELETE /debug/faults` 只登記，異常尚未真的觸發；同名重複設定為覆蓋（新的排到最後），同時只允許一種 `vendor_*`（另一種回 `400 INVALID_FAULT`，要換先 `DELETE`）；`POST /debug/reset` 清空訂單、明細、廠商事件、自拍與異常，餘額回 `seed_balance`（migration `0007`）。輸入驗證：尾端內容、多個 JSON 值、超過 64 KB、非法 UTF-8、頂層非物件回 `400 INVALID_REQUEST`，`name`／`times`／`target` 型別錯誤回 `400 INVALID_FAULT`，字串但未知的名稱回 `UNKNOWN_FAULT`。實測：rebase 後整合測試（真實 Postgres）與單元測試 186 個 PASS、0 失敗；Docker 確認 reset 後 `remaining` 3、`available` 100，互斥回 `400 INVALID_FAULT`，`vendor_failed` 與 `upload_expired` 可並存，尾端內容 `INVALID_REQUEST`、`times: 1.5` `INVALID_FAULT`、未知名稱 `UNKNOWN_FAULT`，沒 token `401`，consume `404`。較早的 Docker 冒煙：額度已用完的 volume reset 後 `remaining` 0→3、`available` 70→100，`DEBUG_API_ENABLED=false` 時 `/debug/reset` 回 `404` 且資料不變（修正後未重跑此項） |

**影片與分享約定（已定案；約定都已實作；App 的入口尚未實作）：**

1. `GET /v1/cameos/{id}/video`：訂單不存在（或不屬於該使用者）回 `404 CAMEO_NOT_FOUND`；存在但不是 `SUCCEEDED` 回 `404 VIDEO_NOT_READY`。
2. `PUT /v1/cameos/{id}/share`：取消後再分享發新 token，舊連結永久失效；分享中重複 PUT 回同一個 URL。成功一律回 `200`。非 `SUCCEEDED` 維持 `409 NOT_SHAREABLE`；訂單不存在回 `404 CAMEO_NOT_FOUND`。
3. `DELETE /v1/cameos/{id}/share`：未分享或非 `SUCCEEDED` 的訂單也回 `204`（冪等）；訂單不存在回 `404 CAMEO_NOT_FOUND`。
4. `GET /s/{token}`：已取消或不存在時回簡短 HTML 404、查詢錯誤時回簡短 HTML 500（都是 README 通用錯誤格式的例外）。`GET /s/{token}/video`：已取消或不存在時維持 JSON 404。兩者免登入。`GET`／`HEAD /s/{token}` 與 `/s/{token}/video` 的回應都帶 `Cache-Control: no-store`、`X-Robots-Tag: noindex`、`Referrer-Policy: no-referrer`；例外：`405` 與 `/s/{token}/` 這類未註冊路徑的通用 JSON `404` 不帶，`/s/{token}/video` 的 `416` 不帶 `Cache-Control`，body 是標準庫純文字。
5. token 以 Go 標準庫 `crypto/rand.Text()` 產生，存在 `cameos.share_token`，`NULL` 代表未分享。
6. 範例影片：`backend/assets/sample.mp4`（約 558 KB、H.264、無音軌），實測 15 秒、640×360；畫面上有 `…IMHIT CAMEO WATERMAR…`、`…AMPLE - NOT FOR REUSE` 字樣（兩端被裁切，照原樣記錄），分享頁會顯示這些字；先沿用，測試發現問題再換。來源與授權未確認。換影片的做法：換掉 `backend/assets/sample.mp4`，並更新測試裡的 sha256 和長度；舊檔仍會留在 git 歷史。
7. 分享網址的 base 由環境變數 `PUBLIC_BASE_URL` 設定，預設 `http://localhost:8080`，不寫死。預設用電腦瀏覽器開；要在模擬器內開，設成 `http://10.0.2.2:8080`；`adb reverse` 只是選用，不是必要步驟。
8. 第 7 輪起，訂單資料的 `video_available` 在 `SUCCEEDED` 時為 true；`share` 反映實際分享狀態（`{"active":true,"url":"..."}`／`{"active":false,"url":null}`）。App 的播放和分享入口依這兩個欄位判斷。

**建單錯誤契約（§10，App 依此處理草稿）：**

- `402`、`429`、`404 CLIP_NOT_FOUND`：後端查過冪等鍵才回，保證這個 key 沒有訂單。
- `5xx`、網路錯誤、回應遺失：不確定是否已建單，App 保留草稿、顯示「重試」，用同一個 key。
- 其他 `retryable=false`（`400`、`401`、`409`、`426`、非 JSON 4xx）：不確定是否已建單，App 保留草稿並記錄錯誤、不顯示重試；未完成列可「繼續」（同一個 key）或「移除」。
- 建單遇 `429` 時 App 顯示後端的 `message`，所以後端錯誤回應要帶 `message` 與 `retryable`。

### WebSocket（§6，只寫文件、不實作）

已決定不實作（見第 9 節第 1 點），以下內容只寫文件。

- 端點 `GET /v1/events`，只有一種事件：`{"type":"cameo_updated","cameo_id":"..."}`。
- 交易提交後才發出事件；連線清單放 `api` 記憶體。
- 事件只是提示，狀態一律以 API 為準。
- 建議使用 `github.com/coder/websocket`，實作前再確認，引入前須經專案負責人同意。

## 6. 上傳與自拍（§2、§11）

- 只收 JPEG，看內容前 3 bytes（`FF D8 FF`），不看 `Content-Type`，整檔重傳。
- 上傳流程：
  1. 先查訂單，擋掉明顯不能上傳的。
  2. 串流寫入暫存檔。
  3. 在交易內鎖列，重查狀態與期限，更新狀態並 rename。
  4. commit。
- `FileStore` 存在 `SELFIE_DIR`，以 Docker named volume 掛載，用 rename 原子覆蓋。
- 訂單結束後刪自拍，只留浮水印成品；訂單轉終態的交易 commit 後才刪，刪除失敗只記 log。
- 送出在同一交易內固定先鎖點數帳戶，再鎖訂單列。
- 掃描、上傳、送出共用同一條逾期規則：`upload_expires_at <= now`。

## 7. 異常（§8）

- **要實作（由 debug API 觸發）：**
  - `response_lost_after_commit`
  - `upload_interrupted`
  - `upload_expired`
  - `vendor_failed`
  - `vendor_duplicate_callback`
  - `vendor_no_callback`
- **只寫文件：** `socket_event_lost`、`upgrade_required`、`server_error`、`slow_network`、`vendor_dispatch_error`、`vendor_out_of_order`、`unknown_status`。
- **做法（部分實作）：** 登記（`GET`／`POST`／`DELETE /debug/faults`）、重設（`POST /debug/reset`）與開關（`DEBUG_API_ENABLED`）已實作；`POST /debug/faults/consume` 和 6 種異常的實際觸發尚未實作。`docker-compose.yml` 的 `GENERATION_TTL` 目前仍是 `30m`，改為 `2m` 尚未實作。
  - 生成期限：程式預設 `GENERATION_TTL` 維持 30 分鐘；`docker-compose.yml` 預設將改為 `2m`（尚未實作）；期限在送出時寫入訂單，改設定只影響新訂單；`VENDOR_DELAY_SEC` 接近或超過期限時，要同時調大 `GENERATION_TTL`，否則正常訂單也會逾時。
  - registry 放 adapter 層。
  - 開關：`/debug/*` 由 `DEBUG_API_ENABLED` 控制，程式預設關閉，未開啟回 `404`；`docker-compose.yml` 預設開啟；同時需要 Bearer token。
  - `POST /debug/reset` 回 `204`：清空訂單、點數明細、廠商事件、自拍檔案和異常；餘額回到 seed 的值（不寫死）；片段保留；額度因訂單清空而恢復。重設後才到的廠商回呼照未知 `job_id` 處理（`404 CAMEO_NOT_FOUND`、事件不留），訂單不會復活。自拍檔案只刪被清除訂單對應的檔案，刪除失敗只記 log，仍回 `204`。
  - `GET`／`POST`／`DELETE /debug/faults`：`POST` body `{"name","target","times","params"}`，成功回 `200` 和目前清單；body 是空的、JSON 語法錯誤、尾端有多餘內容、含多個 JSON 值、超過 64 KB、不是合法 UTF-8、或頂層不是物件回 `400 INVALID_REQUEST`，`name`、`times`、`target` 型別錯誤回 `400 INVALID_FAULT`；未知名稱 `400 UNKNOWN_FAULT`，其他參數錯誤 `400 INVALID_FAULT`；`times` 預設 1；`target` 只用於 `response_lost_after_commit`，必填，`create` 或 `submit`；同名覆蓋；`vendor_*` 異常同一時間只允許一種，已有另一種 `vendor_*` 時設定新的回 `400 INVALID_FAULT`，要換就先 `DELETE`，其他異常可並存；`params` 帶不認得的 key，或 `response_lost_after_commit` 以外的異常帶了 `target`，都回 `400 INVALID_FAULT`；`GET` 回 `{"active":[{"name","target","times","params"}],"available":["名稱"]}`（`times` 為剩餘次數），沒有異常時 `active` 為空陣列；`available` 是名稱字串陣列，永遠列出 6 種名稱；`DELETE` 回 `204`；異常存記憶體，重啟清空。
  - 情境檔 `backend/faults/*.json` 每種異常一個，只當 `curl -d @...` 的 body，後端不載入；檔名是異常名稱把底線改成連字號再加 `.json`，例如 `backend/faults/response-lost-after-commit.json`。
  - `vendor_*` 異常的 `times`：每個被接受的工作扣 1，連續套用到接下來 N 個工作，和 `vendor_duplicate_callback` 的 `count` 無關。
  - `POST /debug/faults/consume` 和其他 `/debug/*` 一樣受 `DEBUG_API_ENABLED` 與 Bearer token 保護，只給 vendor-sim 用。
  - vendor-sim 收到新工作時呼叫一次內部端點 `POST /debug/faults/consume`，重送的同一 `job_id` 不再呼叫；查詢失敗當沒有異常、正常回呼並記 log；異常綁定下一個被接受的工作。
  - 六種異常行為：`response_lost_after_commit` 交易提交後中斷連線、不送回應；`upload_interrupted` 的 `params.after_bytes`（預設 1024）讀到後中斷連線，暫存檔丟棄、狀態不變，App 看到網路錯誤而非 `408`；`upload_expired` 作用在下一次上傳或送出請求所指的訂單，把該訂單的上傳期限（`upload_expires_at`）設為現在，走真實的 `410` 與釋放，訂單已不是 `AWAITING_UPLOAD`／`UPLOADED` 時不消耗這次異常，內容檢查仍先於逾期判斷（內容不合法回 `400`／`413`，不是 `410`）；`vendor_failed` 的 `params.reason` 預設空值（記為 `VENDOR_FAILED`），可設 `CONTENT_REJECTED`；`vendor_duplicate_callback` 的 `params.count` 預設 3、`params.status` 預設 `SUCCEEDED`，前 count-1 次同一 `event_id`、最後一次新 `event_id`，預設值會走到兩條去重路徑；`vendor_no_callback` 不回呼，靠 `GENERATION_TTL` 逾時。

## 8. 架構與測試（§9、§11）

- **目錄：** `backend/internal/{domain,usecase,adapter/{http,postgres,filestore,vendor},app}`（`spec.md` 的 `realtime` 不實作，見第 9 節第 1 點），`cmd` 負責組裝，整合測試放 `backend/test`，用 Go 測試檢查 import 方向；`vendor-sim` 不套分層。
- **Port：** 定義在 `usecase`，包括 `TxRunner`、`Tx`、`Queries`、`FileStore`、`VendorClient`、`EventPublisher`（不實作）、`Clock`。
- **冪等：** 所有會重送的操作都要冪等。
- **測試：** 金流用真實 Postgres，domain 用純 Go 測試，廠商用 fake。
- **Backlog：** 位元組續傳、自拍 checksum、access／refresh token、自拍鏡射、自拍儲存的崩潰一致性。

## 9. 已解決的規格衝突

1. **WebSocket：** 不實作，只寫文件。
2. **強制更新：** 不實作，只寫文件。
3. **輪次：** 第 6 輪是 App 首頁改版，第 7 輪是播放與分享。
4. **自拍刪除：** 訂單結束（`SUCCEEDED`、`FAILED`、`EXPIRED`）就刪，與 `spec.md` §2 一致。
5. **浮水印：** 模擬環境不處理浮水印，範例影片視為成品。
