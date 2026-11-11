# 客串短劇：後端規格

> Server 端規格，只含後端；App 行為只列會影響後端的部分。設計與取捨見 `ARCHITECTURE.md`，API 欄位見 `API.md`；有衝突時以這兩份為準。

## 1. 目標與範圍

- **目標：** 見 `ARCHITECTURE.md` 第 1 節。
- **責任：** 每日上限、浮水印、分享權限都由後端負責，不能交給 App。
- **技術：** Go、Docker、Postgres、`vendor-sim`。
- **限制：** 乾淨電腦一行指令啟動；不依賴個人帳號、金鑰或 Firebase。
- **不做：**
  - 取消訂單
  - 位元組續傳
  - 驗證照片來源
  - 正式推播服務
  - 登入（使用固定測試帳號與 token）

## 2. 額度

- 規則見 `ARCHITECTURE.md` 第 3、8 節，API 見 `API.md`「額度」。
- 必測：建單減少、失敗或過期恢復、內容拒絕不恢復、台北午夜重置。

## 3. 點數

| 時機 | 點數 | 明細 |
|---|---|---|
| 建單 | 可用減、占用增 | 不寫 |
| 送出（可重複呼叫，冪等） | 同一交易扣點 | `CHARGE` |
| 上傳階段過期 | 釋放占用 | 不寫 |
| 送出後失敗 | 退點 | `REFUND` |
| 送出晚於期限 | 回 `410`，不扣款 | 不寫 |

- 轉終態、退點或釋放、退額度必須在同一個資料庫交易內完成。
- 必測：送出與過期排程同時發生、重複送出只扣一次、占用中不能超額建單。

## 4. 訂單狀態

狀態圖與終態規則見 [`ARCHITECTURE.md`](ARCHITECTURE.md) 第 5 節；`failure_reason`、`display` 文字見 [`API.md`](API.md)「訂單資料格式」。

## 5. API

端點、欄位與錯誤碼見 [`API.md`](API.md)，除 `/debug/config` 的 `initial_points`（見第 7 章「執行期設定」）外全部已實作；驗證說明見 API.md 各段。

**影片與分享約定：**

- 範例影片：`backend/assets/sample.mp4`（265429 bytes、H.264 Main、yuv420p、無音軌、faststart），15 秒、640×360、30 fps；畫面是程式產生的動態漸層和 `Cameo sample video` 字樣，沒有第三方素材。產生指令見 commit `9f5cbeb`。換影片的做法：換掉這個檔案，並更新測試裡的 sha256 和長度；`gradients` 沒有固定 seed，重新產生也會得到不同檔案，同樣要更新測試。舊檔仍會留在 git 歷史。

**建單錯誤契約（App 依此處理草稿）：**

- `402`、`429`、`404 CLIP_NOT_FOUND`：後端查過冪等鍵才回，保證這個 key 沒有訂單。
- `5xx`、網路錯誤、回應遺失：不確定是否已建單，App 保留草稿、顯示「重試」，用同一個 key。
- 其他 `retryable=false`（`400`、`401`、`409`、`426`、非 JSON 4xx）：不確定是否已建單，App 保留草稿並記錄錯誤、不顯示重試；未完成列可「繼續」（同一個 key）或「移除」。
- 建單遇 `429` 時 App 顯示後端的 `message`，所以後端錯誤回應要帶 `message` 與 `retryable`。

### WebSocket（只寫文件、不實作）

已決定不實作，以下內容只寫文件。

- 端點 `GET /v1/events`，只有一種事件：`{"type":"cameo_updated","cameo_id":"..."}`。
- 交易提交後才發出事件；連線清單放 `api` 記憶體。
- 事件只是提示，狀態一律以 API 為準。
- 建議使用 `github.com/coder/websocket`，實作前再確認，引入前須經專案負責人同意。

## 6. 上傳與自拍

- 只收 JPEG，看內容前 3 bytes（`FF D8 FF`），不看 `Content-Type`，整檔重傳。
- 上傳流程：
  1. 先查訂單，擋掉明顯不能上傳的。
  2. 串流寫入暫存檔。
  3. 在交易內鎖列，重查狀態與期限，更新狀態並 rename。
  4. commit。
- `FileStore` 存在 `SELFIE_DIR`，以 Docker named volume 掛載，用 rename 原子覆蓋。
- 訂單結束後刪自拍，只留浮水印成品（模擬環境不加浮水印，範例影片視為成品）；訂單轉終態的交易 commit 後才刪，刪除失敗只記 log。
- 送出在同一交易內固定先鎖點數帳戶，再鎖訂單列。
- 掃描、上傳、送出共用同一條逾期規則：`upload_expires_at <= now`。

## 7. 異常

- 異常清單與取捨見 `ARCHITECTURE.md` 第 8 節；觸發方式見 `API.md`「異常觸發方式」。
- **做法：**
  - 生成期限：程式預設 `GENERATION_TTL` 維持 30 分鐘；`docker-compose.yml` 預設 `2m`；期限在送出時寫入訂單，改設定只影響新訂單；`VENDOR_DELAY_SEC` 接近或超過期限時，要同時調大 `GENERATION_TTL`，否則正常訂單也會逾時。
  - registry 放 adapter 層。
  - 六種異常行為：`response_lost_after_commit` 交易提交後中斷連線、不送回應；`upload_interrupted` 的 `params.after_bytes`（預設 1024）：內容超過生效值才中斷連線，暫存檔丟棄、狀態不變，App 看到網路錯誤而非 `408`；登記時接受任何正整數，`GET /debug/faults` 顯示原設定值；小於 3 時生效值是 3，因為要先讀前 3 bytes 檢查是不是 JPEG，內容不合法才能回 `400` 且不消耗異常（取捨：設定 1、2 時，設定值和實際斷線位置有落差；沒選登記時拒絕，是為了不改 API）；固定長度內容超過生效值才中斷，長度相等回 `200` 且不消耗；chunked 不在保證內：最後一段和結尾標記分開抵達（結尾延後 50ms）時，3 bytes（`after_bytes` 3）和 100 bytes（`after_bytes` 100）一次性探針實測（repo 內沒有對應測試）都會中斷並消耗，同時抵達未測；`upload_expired` 作用在下一次上傳或送出請求所指的訂單，把該訂單的上傳期限（`upload_expires_at`）設為現在，走真實的 `410` 與釋放，訂單已不是 `AWAITING_UPLOAD`／`UPLOADED` 時不消耗這次異常，內容檢查仍先於逾期判斷（內容不合法回 `400`／`413`，不是 `410`）；`vendor_failed` 的 `params.reason` 預設空值（記為 `VENDOR_FAILED`），可設 `CONTENT_REJECTED`；`vendor_duplicate_callback` 的 `params.count` 預設 3、`params.status` 預設 `SUCCEEDED`，前 count-1 次同一 `event_id`、最後一次新 `event_id`，預設值會走到兩條去重路徑；`vendor_no_callback` 不回呼，靠 `GENERATION_TTL` 逾時。
- **執行期設定（模擬環境用，給 demo 時調整）：**（實作狀態：`initial_points` 欄位尚未實作，Server 實作後更新；其餘三個設定已實作）
  - `GET /debug/config` 回 `{"daily_limit":3,"generation_ttl_sec":120,"vendor_delay_sec":null,"initial_points":null}`；`PUT /debug/config` 收同樣欄位的任意子集（可帶 `"initial_points": null` 清除覆寫），只改有帶的欄位，成功回 `200` 和改後的完整設定。和其他 `/debug/*` 一樣受 `DEBUG_API_ENABLED` 與 Bearer token 保護。
  - 範圍：`daily_limit` 整數 1–1000；`generation_ttl_sec` 整數 10–1800；`vendor_delay_sec` 數字 0–600，或 `null` 表示不覆寫、用 `vendor-sim` 自己的 `VENDOR_DELAY_SEC`；`initial_points` 整數 0–100000，或 `null` 表示不覆寫、`POST /debug/reset` 時餘額回到資料庫的 seed（`seed_balance`，目前 100）。`daily_limit`、`generation_ttl_sec` 不接受 `null`，`3.0` 這類帶小數點的寫法也不算整數，兩者都視為型別錯誤；`initial_points` 同樣不接受 `5.0` 這類帶小數點的寫法或字串，視為型別錯誤。空物件、未知欄位、型別錯誤或超出範圍回 `400 INVALID_CONFIG`；任一欄位不合法就全部不改。body 不是 JSON 物件（語法錯誤、`null`、陣列）回 `400 INVALID_REQUEST`，和 `POST /debug/faults` 一致。
  - 初始值：`daily_limit`、`generation_ttl_sec` 取自環境變數 `DAILY_LIMIT`、`GENERATION_TTL`（`GET` 顯示整秒，無條件捨去；環境變數超出 `PUT` 範圍，例如 `GENERATION_TTL=1h` 或小於 10 秒時，`GET` 照實顯示捨去後的值，不夾到範圍內，這時 `PUT` 只要不帶該欄位就能改其他欄位）；`vendor_delay_sec`、`initial_points` 為 `null`。值存在記憶體，API 重啟就回到初始值；`POST /debug/reset` 不改這些值。讀寫要能同時進行而不出錯（用鎖或 atomic）；usecase 透過介面讀目前值，不直接改 `Config`。
  - 生效：`daily_limit` 立即影響建單檢查與 `GET /v1/quota`，已建的訂單不變，調低到小於今日已用次數時剩餘為 0；`generation_ttl_sec` 只影響之後送出的訂單（期限在送出時寫入）；`vendor_delay_sec` 由 `POST /debug/faults/consume` 回應最外層的 `"vendor_delay_sec"` 欄位（和 `fault` 同一層，`fault` 為 `null` 時也帶）交給 `vendor-sim`，只影響之後 `vendor-sim` 收到的新工作（以收到工作的時間為準，不是送出訂單的時間；重送的同一工作不受影響）；欄位為 `null`、缺少、不是 0–600 的數字，或 consume 失敗時，`vendor-sim` 用自己的 `VENDOR_DELAY_SEC`；`initial_points` 在 `PUT` 時不動餘額，只在下一次 `POST /debug/reset` 時把餘額設成這個值（`null` 則設成 seed），reset 本來就清空明細與訂單，所以餘額與明細不會對不上。用途：demo 點數不足 `402`，初始點數設 5 → 重設 → 建單（片段 10 點）→ `402`。
  - 不檢查延遲與期限的關係：延遲大於生成期限時正常訂單也會逾時，由控制台提示。
  - 控制台：新增「設定」區塊，四個輸入框顯示目前值（廠商延遲空白代表用 `vendor-sim` 預設；新增「初始點數」，旁邊註明「按重設後生效」，空白代表用 seed），按「儲存」時只送值有變動的欄位（廠商延遲、初始點數由有值改成空白時送 `null`），沒有變動就不送，顯示結果。廠商延遲有值且大於或等於生成期限時，在儲存結果旁顯示「延遲不短於生成期限，正常訂單會逾時」；廠商延遲空白時不提示。設定區塊只在開頁和儲存後讀取 `GET /debug/config`，不跟著 3 秒更新。
  - 測試：建單與 `GET /v1/quota` 依覆寫值；生成期限只影響之後送出的訂單；`vendor-sim` 依 consume 回應的延遲，欄位缺少或 consume 失敗時用環境變數；`PUT` 的 `400`（超出範圍、未知欄位、空物件、`daily_limit: null`）且全部不改；關閉時 `404`；reset 不還原設定。`initial_points`：設 5 後 reset，`GET /v1/points` 可用 5；只 `PUT` 不 reset 餘額不變；設 0 後 reset 建單回 `402`；設回 `null` 後 reset 回到 seed；超出範圍（-1、100001）、小數回 `400 INVALID_CONFIG` 且所有欄位不改；reset 不還原這個設定。相關測試用 `go test -race` 跑一次。mutation 至少四個：建單檢查忽略覆寫值、送出時生成期限忽略覆寫值、`vendor-sim` 忽略回應的延遲、reset 忽略 `initial_points` 覆寫值。

## 8. 架構與測試

- **目錄：** `backend/internal/{domain,usecase,adapter/{http,postgres,filestore,vendor},app}`（`realtime` 不實作），`cmd` 負責組裝，整合測試放 `backend/test`，用 Go 測試檢查 import 方向；`vendor-sim` 不套分層。
- **Port：** 定義在 `usecase`，包括 `TxRunner`、`Tx`、`Queries`、`FileStore`、`VendorClient`、`EventPublisher`（不實作）、`Clock`。
- **冪等：** 所有會重送的操作都要冪等。
- **測試：** 金流用真實 Postgres，domain 用純 Go 測試，廠商用 fake。
- **Backlog：** 見 `BACKLOG.md`。
