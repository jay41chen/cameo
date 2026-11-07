# 客串短劇

> 狀態：進行中。主流程（選片段到生成完成通知）的後端與 Android App 已接通，只在 API 37 模擬器驗證過；後端的影片與分享 API 已完成；App 首頁改版、App 的播放與分享入口尚未完成；異常的登記與重設已完成；上傳與回應類 3 種異常已會觸發，廠商回呼類 3 種尚未完成。已驗證與未涵蓋項目的總表，待完整情境驗證後補上。

## 專案介紹

客串短劇讓觀眾從營運挑選的片段中選一段、當場拍一張自拍、支付點數後生成一段 15 秒、自己出現在劇中的短片，可收藏並分享連結。

- 後端：Go、Postgres、Docker，外加模擬廠商 `vendor-sim`（`backend/cmd/vendor-sim`，同一個 Go module、同一個 Dockerfile）。
- Android App：Kotlin、Jetpack Compose。
- 設計文件：`spec.md`（規格）、`ARCHITECTURE.md`（架構與取捨）。

## 執行方式

### 後端

需要 Docker。在專案根目錄執行：

```
docker compose up --build
```

會啟動 Postgres（`postgres:17-alpine`）、`api`（`http://localhost:8080`）和模擬廠商 `vendor-sim`（容器內 `http://vendor-sim:9000`，不對外發布 port）。`api` 等 Postgres 健康後才啟動，並自動執行 migration 與 seed（可重複啟動，不會重複插入）。不需要任何帳號或金鑰（`VENDOR_WEBHOOK_SECRET` 在 `docker-compose.yml` 預設為模擬用的 `cameo-sim-secret`，僅供模擬，不是正式密鑰）；連線字串、port 與下列設定可用環境變數覆寫（見 `docker-compose.yml`）。Postgres 只對本機開放 `127.0.0.1:5433`，供測試使用。

升級注意：migration `0004` 遇到懸空的 `cameo_id`，或同一訂單重複的 `CHARGE` 明細時，API 會啟動失敗，並在 log 顯示原因；需先處理資料再啟動。

`api` 的業務設定（皆為環境變數，不寫死在程式碼）：

| 變數 | 預設 | 說明 |
|---|---|---|
| `DAILY_LIMIT` | `3` | 每人每日（台北時間）客串次數上限 |
| `UPLOAD_TTL` | `1h` | 建單後的上傳期限，超過會自動釋放點數與額度（Go duration，例如 `5s`、`30m`） |
| `EXPIRY_SWEEP_INTERVAL` | `30s` | 逾期掃描間隔 |
| `SELFIE_DIR` | `/data/selfies` | 自拍存放目錄；`docker-compose.yml` 把 named volume `selfies` 掛在這裡，容器重啟後檔案仍在（容器以 `nobody` 執行，目錄在映像檔內已設為可寫） |
| `MAX_SELFIE_BYTES` | `10485760` | 自拍檔案大小上限（bytes），超過回 `413 SELFIE_TOO_LARGE` |
| `GENERATION_TTL` | `30m`（compose 將設為 `2m`，尚未實作，目前 compose 仍為 `30m`） | 生成期限：送出（轉 `QUEUED`）起算，涵蓋排隊與生成；到期（`<=` 現在）仍未完成的訂單轉 `FAILED`（原因 `TIMEOUT`）並退點。期限在送出時寫入訂單，改設定只影響新訂單。`VENDOR_DELAY_SEC` 調到接近或超過期限時，要同時調大 `GENERATION_TTL`，否則正常訂單也會逾時 |
| `DEBUG_API_ENABLED` | 程式預設關閉；compose 預設 `true` | 控制 `/debug/*`（見「Debug 異常 API」）；只有值為 `true` 才開啟，未開啟時 `/debug/*` 回 `404` |
| `PUBLIC_BASE_URL` | `http://localhost:8080`（程式與 compose 相同） | 分享網址的 base，不寫死。預設用電腦瀏覽器開；要在模擬器內開，設成 `http://10.0.2.2:8080`；`adb reverse` 只是選用 |
| `DISPATCH_SWEEP_INTERVAL` | `2s` | 派工與生成逾時掃描間隔；每個週期先處理逾時、再派工（每次最多 20 筆，`created_at` 由舊到新） |
| `VENDOR_URL` | `http://localhost:9000`（compose 設為 `http://vendor-sim:9000`） | 廠商的 base URL；派工是對 `{VENDOR_URL}/jobs` 的 `POST`，單次逾時 5 秒 |
| `VENDOR_WEBHOOK_SECRET` | 無預設 | webhook 簽章密鑰。**空字串或未設定時 `api` 拒絕啟動**；程式碼內沒有預設密鑰。compose 以 `${VENDOR_WEBHOOK_SECRET:-cameo-sim-secret}` 同時傳給 `api` 與 `vendor-sim`（僅供模擬） |

例如 `UPLOAD_TTL=5s EXPIRY_SWEEP_INTERVAL=1s docker compose up -d api` 可快速觀察逾期釋放。

本機要放寬每日額度可用 `DAILY_LIMIT=100 docker compose up -d`；預設值不變。

`vendor-sim` 的設定（環境變數，見 `docker-compose.yml`）：

| 變數 | compose 預設 | 說明 |
|---|---|---|
| `VENDOR_DELAY_SEC` | `20` | 收到工作後等幾秒才送回呼（可用小數） |
| `VENDOR_CALLBACK_URL` | `http://api:8080/webhooks/vendor` | 回呼目標 |
| `VENDOR_PUBLIC_URL` | `http://vendor-sim:9000` | 回呼 `video_url` 的 base（`{base}/videos/{job_id}.mp4`） |
| `VENDOR_WEBHOOK_SECRET` | `cameo-sim-secret` | 與 `api` 相同；空字串時拒絕啟動 |

`vendor-sim` 的行為：`POST /jobs`，body `{"job_id":"..."}`，回 `202`；同一 `job_id` 重送也回 `202`，不開第二個工作（去重只在記憶體，重啟後清空）；缺 `job_id` 回 `400`。等 `VENDOR_DELAY_SEC` 後送一次 `SUCCEEDED` 回呼（`event_id` 隨機，`X-Signature` 為對原始 body 的 HMAC-SHA256 hex）；回呼失敗只記 log、不重試，由 `GENERATION_TTL` 兜底。`vendor-sim` 不接收照片、不提供影片檔（`video_url` 指向的檔案不存在），目前也不會回報失敗（異常的實際觸發實作後，由 `vendor_*` 異常控制）。

背景掃描（逾期釋放、派工、生成逾時）假設同一時間只有一個 `api` 實例，不做多實例協調。

Seed：測試帳號初始 100 點；3 個片段（`c1` 雨夜重逢、`c2` 屋頂告白、`c3` 最後一班車），每段 10 點；點數明細初始為空。

要把環境重設回初始狀態（訂單、點數明細、廠商事件、自拍檔案與異常清空，餘額回到初始 100 點，額度恢復）：

```bash
curl -X POST -H "Authorization: Bearer test-token" http://localhost:8080/debug/reset
```

成功回 `204`。不會清 App 本機資料。`DEBUG_API_ENABLED` 不是 `true` 時回 `404`，細節見「Debug 異常 API」。

快速檢查：

```
curl -H 'Authorization: Bearer test-token' localhost:8080/v1/points
```

後端測試（不需啟動 App）：本機需要 Go 1.27.1（`backend/go.mod` 的 `go` 指令）。測試分兩類，`go test ./...` 兩類都會跑：

- 單元測試放在各自套件旁：`backend/internal/layering_test.go`（import 方向）、`backend/internal/domain`、`backend/internal/usecase`、`backend/internal/adapter/http`、`backend/internal/adapter/filestore`、`backend/internal/adapter/vendor`、`backend/cmd/vendor-sim`。不需要資料庫。
- 整合測試在 `backend/test/integration`：真實 Postgres 加 HTTP，需要 `TEST_DATABASE_URL`，所以先讓 Postgres 在跑再指定它。測試會在該資料庫建立獨立 schema，結束後刪除，不動其他資料；沒設定時整合測試會失敗。測試用的資料庫工具在 `backend/test/internal/testdb`，Go 的 `internal` 規則讓只有 `backend/test` 底下的程式能引用，正式程式（如 `cmd/api`）無法引用。

```
cd backend
TEST_DATABASE_URL='postgres://cameo:cameo@127.0.0.1:5433/cameo?sslmode=disable' go test ./... -count=1
```

### Android App

需求：JDK 21（`android/gradle/gradle-daemon-jvm.properties` 要求 Gradle daemon 用 JDK 21，編譯用的 toolchain 也是 21；本機沒有時 Gradle 會透過 foojay 自動下載，需連網）、Android SDK 37（compileSdk 需要；targetSdk 為 36）。SDK 位置要設 `ANDROID_HOME` 環境變數，或在 `android/local.properties` 寫 `sdk.dir=/path/to/sdk`（此檔不進 git）。

1. 先啟動後端（見上）。
2. 用 Android Studio 開啟 `android/` 資料夾並執行 `app`；或用 Gradle：

```
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.kimhit.cameo/.MainActivity
```

- App 連 `http://10.0.2.2:8080`（模擬器看到的宿主機），測試 token 為 `test-token`，兩者由 `:app` 的 build config 提供；只有 debug build 允許對 `10.0.2.2` 明文 HTTP。`adb reverse` 不是必要步驟。
- 模擬器相機：AVD 啟動時加 `-camera-front emulated -camera-back virtualscene`（例如 `$ANDROID_HOME/emulator/emulator -avd Pixel_10_Pro -camera-front emulated -camera-back virtualscene`）；驗證時即用此參數，未測不加參數時的相機預覽 `[待確認]`。App 預設用前鏡頭，沒有前鏡頭用後鏡頭。
- 目前畫面：片段清單（頂端顯示今日額度，額度用完時點片段會跳提示；有未完成的客串時列在清單上方）、確認支付（顯示占用點數，建單成功後進進度畫面）、點數（可用／占用、明細分頁載入）、我的客串（訂單列表，分頁載入，回到前景時刷新第一頁；回前景時依 id 合併第一頁：就地更新既有項目、新項目放最前、保留已載入項目；第一頁和現有列表完全沒有重疊時整份取代，這時位置可能改變；已載入但不在第一頁的項目不會被更新（此刷新行為只有單元測試涵蓋，模擬器尚未實測）；列不可點、不輪詢；回前景或資料更新時，若第一筆在畫面上（只露出一部分也算）而且出現新的第一筆，列表自動捲到新的第一筆；第一筆不在畫面上時不捲動、位置不變，並在右下角顯示「回到頂端」（第一筆離開畫面就出現、看得到就消失，點擊回到第一筆））。點片段後先進相機（需相機權限；被拒絕時顯示說明與「前往設定」），拍照後預覽、可重拍，按「使用這張」進確認支付；確認時照片才移到 App 私有目錄（`files/selfies/<key>.jpg`），建單成功後進進度畫面，依後端訂單狀態上傳照片、送出（上傳成功才刪本機照片）。未完成列在訂單仍是等待上傳或送出中時有「繼續」，並顯示上傳期限；可重試的失敗有重試按鈕，照片遺失、格式不符或太大時要求重新拍照。每個區塊有載入中、失敗（顯示後端 `message`，沒有則通用文字）與重試。已在 API 37 模擬器接真實後端驗證其中的額度、確認支付、本機草稿流程（確認時 `401` 只有單元測試涵蓋；「請求已到伺服器但 App 沒收到回應」只用 curl 模擬，不是真實斷線）；上傳、送出已於模擬器接真實後端驗證主流程與接續情境（上傳途中進背景、真實斷線、`401`、真機未驗）。

單元測試（不需啟動 App 或後端）：

```
cd android
./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks
```

過去的手動驗證紀錄：`git show 8ca2c96:docs/manual-test-checklist.md`。

## 待確認欄位

以下契約細節尚未確認，不在每列重複標註：

- `X-App-Version` 與 `426` 尚未實作。

## API 說明

API 說明中尚未實作的部分（例如 `GET /v1/events`）是設計，未確認的欄位標 `[待確認]`。

### 通用規則

- Header：`Authorization: Bearer test-token`（假設已用測試帳號登入）、`X-App-Version: <版本碼整數>` `[待確認]`。
- 錯誤格式：`{"code":"...","message":"給使用者看的文字","retryable":false}`；`retryable` 為 `true` 時 App 可稍後重試。
- 先判斷路由再驗 token：未知路徑（不論有沒有 token）回 `404 NOT_FOUND`；已知路徑但方法不對回 `405 METHOD_NOT_ALLOWED`（帶 `Allow`）；已知路徑且方法正確，缺少或錯誤的 `Authorization` 才回 `401 UNAUTHORIZED`；後端錯誤回 `500 INTERNAL`（`retryable: true`）。都使用上述錯誤格式。
- App 忽略未知欄位。未知的訂單 `status` 視為非終態；有後端 `display` 就顯示，沒有才用通用文字；兩種情況都重新查詢。
- 版本過舊回 `426 UPGRADE_REQUIRED`。
- `GET /s/{token}`、`GET /s/{token}/video` 免登入，不需 `Authorization`；`GET /s/{token}` 的 404 與查詢錯誤時的 500 回 HTML，是上述錯誤格式的例外，細節見「影片與分享」。
- 時間為 ISO 8601，台北時間（+08:00）。

### 常見錯誤碼

| 碼 | 意義 |
|---|---|
| `400 INVALID_REQUEST` | `Idempotency-Key` 缺少、去掉前後 ASCII 空格與 Tab 後為空、超過 128 bytes 或含 0x21–0x7E 以外的字元；或 body 不是有效 JSON（`retryable: false`）；含控制字元的 key 可能由 HTTP 層直接回純文字 400，不一定是此 JSON 格式 |
| `402 INSUFFICIENT_POINTS` | 可用點數不足；`message`：「點數不足」 |
| `404 CLIP_NOT_FOUND`、`404 CAMEO_NOT_FOUND` | 片段或訂單不存在（訂單不屬於該使用者也回 `CAMEO_NOT_FOUND`）；id 含 NUL 或不是合法 UTF-8 也視為不存在 |
| `404 VIDEO_NOT_READY` | 訂單存在但不是 `SUCCEEDED`，沒有影片可播（`GET /v1/cameos/{id}/video`） |
| `409 IDEMPOTENCY_CONFLICT` | 同一個 `Idempotency-Key` 用在不同 `clip_id` |
| `409 NOT_SHAREABLE` | 訂單不是 `SUCCEEDED`，不能分享（`PUT /v1/cameos/{id}/share`） |
| `400 INVALID_SELFIE` | 上傳內容前 3 bytes 不是 JPEG 標頭（`FF D8 FF`），或 body 為空；`message`：「照片格式不正確」 |
| `408 UPLOAD_INTERRUPTED`（`retryable: true`） | 上傳讀取 body 中途失敗（連線中斷、實際內容少於 `Content-Length`）；暫存檔已丟棄、訂單狀態不變，可整檔重傳；`message`：「上傳中斷，請重試」。寫入暫存檔本身失敗（例如磁碟滿）不屬於此類，回 `500 INTERNAL` |
| `401 INVALID_SIGNATURE` | 只用於 `POST /webhooks/vendor`：`X-Signature` 缺少或不符；`message`：「簽章無效」 |
| `409 INVALID_STATE` | 訂單已是 `QUEUED` 或之後的狀態，不能再上傳；`message`：「這筆客串目前無法上傳」 |
| `409 SELFIE_NOT_UPLOADED` | 還沒上傳照片就送出；`message`：「請先上傳照片」 |
| `410 UPLOAD_EXPIRED` | 上傳／送出已超過期限（或訂單已是 `EXPIRED`），不扣款；`message`：「上傳期限已過，點數沒有扣款，請重新客串。」 |
| `413 SELFIE_TOO_LARGE` | 上傳超過 `MAX_SELFIE_BYTES`；`message`：「照片太大」 |
| `426 UPGRADE_REQUIRED` | App 版本過舊 |
| `429 DAILY_LIMIT_REACHED` | 已達每日上限；`message`：「今日客串次數已達上限，明天再來」 |
| `5xx` | 後端錯誤，可重試 |

### 訂單資料格式

```json
{
  "id": "uuid",
  "clip": { "id": "c1", "title": "雨夜重逢", "cost": 10 },
  "status": "GENERATING",
  "display": { "title": "生成中", "message": "完成後會通知你" },
  "points_state": "CHARGED",
  "failure_reason": null,
  "video_available": false,
  "share": { "active": false, "url": null },
  "created_at": "2026-11-07T10:00:00+08:00",
  "updated_at": "2026-11-07T10:01:00+08:00"
}
```

- `status`：`AWAITING_UPLOAD`、`UPLOADED`、`QUEUED`、`GENERATING`、`SUCCEEDED`、`FAILED`、`EXPIRED`。
- `points_state`：`HELD`、`CHARGED`、`REFUNDED`、`RELEASED`。
- `failure_reason`（只在 `FAILED` 時有值，其餘為 `null`）：
  - `CONTENT_REJECTED`：廠商回報照片未通過審核。退點，**不退每日額度**（只有這個值不退額度）。
  - `VENDOR_FAILED`：廠商回報失敗，且原因不是 `CONTENT_REJECTED`（廠商傳來的其他 `reason`、或沒有 `reason`，一律記為此值）。退點、退額度。
  - `TIMEOUT`：到 `GENERATION_TTL` 期限仍沒有結果（含廠商不回呼、派工一直失敗）。退點、退額度。
- `video_available` 在 `SUCCEEDED` 時為 `true`，其他狀態為 `false`；`video_url` 不出現在回應。
- `display`：`AWAITING_UPLOAD` 為 `{"title":"等待上傳","message":"已占用 N 點，送出後才扣款"}`（N 為 `clip.cost`）；`UPLOADED` 為 `{"title":"送出中","message":"照片已上傳，尚未扣款"}`；`QUEUED` 為 `{"title":"排隊中","message":"完成後會通知你"}`；`GENERATING` 為 `{"title":"生成中","message":"完成後會通知你"}`；`EXPIRED` 為 `{"title":"已過期","message":"上傳期限已過，點數沒有扣款，請重新客串。"}`；`SUCCEEDED` 為 `{"title":"已完成","message":"你的客串短片完成了"}`；`FAILED` 且 `failure_reason` 為 `CONTENT_REJECTED` 為 `{"title":"無法生成","message":"照片未通過審核，點數已退還，今日次數不退還"}`；其他 `FAILED` 為 `{"title":"生成失敗","message":"點數已退還"}`（不向使用者提廠商故障）；未知狀態用 `{"title":"處理中","message":""}`。
- `clip.cost` 是建單當下的快照。

### 片段

`GET /v1/clips`

- 參數：無。
- 成功 `200`：

```json
{"items":[{"id":"c1","title":"雨夜重逢","cost":10}]}
```

### 點數

`GET /v1/points`

- 參數：無。`held` 為 `points_state = HELD` 訂單的 `cost` 總和，`available = balance - held`。
- 成功 `200`：

```json
{"available":90,"held":10}
```

### 點數明細

`GET /v1/points/ledger?cursor=`

- 參數：`cursor`（選填；不透明字串，取自上一頁的 `next_cursor`）。依時間新到舊，同一時間的順序固定，分頁不重複不遺漏；每頁 20 筆；沒有下一頁時 `next_cursor` 為 `null`。
- `cursor` 無效回 `400 INVALID_CURSOR`。`cameo_id` 在訂單功能完成前可能為 `null`。
- 成功 `200`（`type` 為 `CHARGE` 或 `REFUND`，`amount` 一律為正數）：

```json
{"items":[{"id":"l1","type":"CHARGE","amount":10,"cameo_id":"uuid","clip_title":"雨夜重逢","created_at":"2026-11-07T10:01:00+08:00"}],"next_cursor":null}
```

- 占用與釋放不寫明細。

### 額度

`GET /v1/quota`

- 參數：無。額度由訂單計算，不存計數器：已用 = 建立時間落在當前台北日的訂單，排除 `EXPIRED`，也排除 `FAILED` 且 `failure_reason` 不是 `CONTENT_REJECTED` 的訂單；`remaining = max(DAILY_LIMIT - 已用, 0)`；`resets_at` 為下一個台北午夜。
- `display`：`remaining > 0` 為「今日還可客串 N 次」，等於 0 為「今日客串次數已用完」。
- 成功 `200`：

```json
{"remaining":2,"resets_at":"2026-11-07T00:00:00+08:00","display":"今日還可客串 2 次"}
```

### 建單

`POST /v1/cameos`

- Header：`Idempotency-Key`（即 App 的 `client_request_id`），必填；去掉前後 ASCII 空格與 Tab 後須為 1–128 bytes、且只含 0x21–0x7E 的可見 ASCII，否則回 `400 INVALID_REQUEST`（`retryable: false`）。
- Body：`{"clip_id":"c1"}`
- 效果：整個流程在同一個資料庫交易內，先鎖住使用者的點數帳戶讓同一使用者的建單依序處理，再依序檢查：
  1. 同一 key 已存在：`clip_id` 相同回 `200` 和原訂單（不論它目前狀態，不重複占用，也不再檢查點數和額度）；`clip_id` 不同回 `409 IDEMPOTENCY_CONFLICT`。
  2. 片段不存在回 `404 CLIP_NOT_FOUND`（`clip_id` 含 NUL 或不是合法 UTF-8 也視為不存在）。
  3. 當日額度用完回 `429 DAILY_LIMIT_REACHED`。
  4. 可用點數不足回 `402 INSUFFICIENT_POINTS`。
  5. 建立訂單：`status = AWAITING_UPLOAD`、`points_state = HELD`，新建回 `201`。不寫明細。
- 成功：新建 `201`、重送 `200`，body 相同形狀：

```json
{"cameo":{"id":"uuid","clip":{"id":"c1","title":"雨夜重逢","cost":10},"status":"AWAITING_UPLOAD","display":{"title":"等待上傳","message":"已占用 10 點，送出後才扣款"},"points_state":"HELD","failure_reason":null,"video_available":false,"share":{"active":false,"url":null},"created_at":"2026-11-07T10:00:00+08:00","updated_at":"2026-11-07T10:00:00+08:00"},"upload":{"url":"/v1/cameos/uuid/selfie","expires_at":"2026-11-07T11:00:00+08:00"}}
```

- 上傳期限預設 1 小時（`UPLOAD_TTL`，回在 `upload.expires_at`）；生成期限 30 分鐘。理由：縮短點數被占用的時間；代價：離線太久要重新建單。
- 逾期（`AWAITING_UPLOAD`、`UPLOADED` 且 `upload.expires_at` 小於等於現在）由後端定時掃描轉為 `EXPIRED`、`points_state = RELEASED`，點數與額度恢復，不寫明細，並刪除已上傳的自拍。上傳與送出遇到已逾期但尚未被掃到的訂單時，會在同一個交易內做同樣的釋放，再回 `410`；三條路徑使用同一個逾期判斷。
- `upload` 與 `GET /v1/cameos/{id}` 共用同一格式：`status = AWAITING_UPLOAD` 時為 `{"url":"/v1/cameos/{id}/selfie","expires_at":"..."}`，`url` 是相對路徑；其他狀態（例如重送已 `EXPIRED` 的訂單，仍回 `200` 與同一筆訂單）為 `null`。App 以 `cameo.status` 判斷下一步，不以 `upload` 是否存在推斷。
- `upload.url` 指向的上傳端點見下節。
- `426` 尚未實作。

### 上傳自拍

`PUT {upload.url}`（即 `/v1/cameos/{id}/selfie`）

- Body：JPEG 整檔（中斷後整檔重傳）。只收 JPEG，判斷只看內容前 3 bytes 是否為 `FF D8 FF`，不看 `Content-Type`（可省略）；空 body 視為格式錯誤。影片不適用，客串只有相機拍照入口。大小上限 `MAX_SELFIE_BYTES`（預設 10 MiB）。
- 流程：先查訂單，擋掉明顯不能上傳的（不讀 body）；再串流寫入暫存檔；最後在交易內鎖住訂單列、重新檢查狀態與期限，更新狀態並把暫存檔原子地移到正式位置。程序仍在執行時，所有錯誤路徑都會刪除暫存檔；程序中止（crash、kill）留下的 `.tmp-*` 暫存檔，由下次啟動時 `filestore.New` 清除（前提：同一個目錄同一時間只有一個 API 實例）。
- 檢查順序：內容檢查（格式、大小、讀取）先於逾期判斷。已逾期但尚未被掃描的訂單，內容不合法或超限時回 `400`／`413`（訂單不變，仍留待掃描釋放）；內容合法才在交易內釋放並回 `410`。掃描會釋放這類訂單。
- 規則（錯誤皆為 `{code,message,retryable}`，`retryable: false`，除非註明）：

| 情況 | 結果 |
|---|---|
| 訂單不存在或不屬於該使用者 | `404 CAMEO_NOT_FOUND` |
| `AWAITING_UPLOAD`、未逾期 | 存檔，轉 `UPLOADED`，`200` |
| `UPLOADED`、未逾期 | 新檔覆蓋舊檔，`200` |
| `AWAITING_UPLOAD`／`UPLOADED` 但已逾期（尚未被掃描） | 同一交易轉 `EXPIRED`／`RELEASED`（點數、額度恢復，不寫明細），刪除檔案，`410 UPLOAD_EXPIRED` |
| `EXPIRED` | `410 UPLOAD_EXPIRED` |
| `QUEUED` 或之後 | `409 INVALID_STATE` |
| 內容前 3 bytes 不是 `FF D8 FF`，或 body 為空 | `400 INVALID_SELFIE` |
| 超過 `MAX_SELFIE_BYTES` | `413 SELFIE_TOO_LARGE` |
| 讀 body 中途失敗（連線中斷、實際內容少於 `Content-Length`） | 暫存檔丟棄、狀態不變，`408 UPLOAD_INTERRUPTED`（`retryable: true`） |
| 寫入暫存檔失敗（例如磁碟滿） | `500 INTERNAL`（`retryable: true`），暫存檔丟棄、狀態不變 |

- 成功 `200`：`{"cameo":{...},"upload":null}`（與 `GET /v1/cameos/{id}` 同形狀；`UPLOADED` 的 `upload` 為 `null`）。
- 目前沒有檔案校驗碼；截斷靠 `Content-Length` 與讀取錯誤發現，整檔重傳。

### 送出

`POST /v1/cameos/{id}/submit`

- 參數：無 body。需要 `Authorization`。
- 效果：同一交易內先鎖點數帳戶、再鎖訂單列（順序固定）。**先判斷期限、再判斷是否已上傳**：

| 情況 | 結果 |
|---|---|
| 不存在 | `404 CAMEO_NOT_FOUND` |
| `AWAITING_UPLOAD`／`UPLOADED` 但已逾期 | 轉 `EXPIRED`／`RELEASED` 並提交，刪除檔案，`410 UPLOAD_EXPIRED` |
| `EXPIRED` | `410 UPLOAD_EXPIRED` |
| `AWAITING_UPLOAD`、未逾期 | `409 SELFIE_NOT_UPLOADED` |
| `UPLOADED`、未逾期 | 餘額扣除訂單快照的 `cost`、寫一筆 `CHARGE` 明細（`created_at` 取系統時間）、轉 `QUEUED`／`CHARGED`，`200` |
| `QUEUED`、`GENERATING`、`SUCCEEDED`、`FAILED` | `200` 回同一筆，不重複扣款 |

- 成功 `200`：`{"cameo":{...},"upload":null}`。
- 每筆訂單最多一筆 `CHARGE` 明細（資料庫的 partial unique index 保證），明細的 `cameo_id` 有外鍵指向訂單。
- 自拍檔案送出後仍保留，直到訂單進入終態（`SUCCEEDED`、`FAILED`，或上傳階段的 `EXPIRED`）；終態的交易提交後才刪除，刪除失敗只記 log。

### 訂單查詢

- `GET /v1/cameos?cursor=`：`{"items":[cameo],"next_cursor":null}`。只列已送出的訂單（`QUEUED`、`GENERATING`、`SUCCEEDED`、`FAILED`；`AWAITING_UPLOAD`、`UPLOADED`、`EXPIRED` 不列），依 `(created_at, id)` 由新到舊，一頁 20 筆；有下一頁時 `next_cursor` 為字串，剛好滿一頁或最後一頁為 `null`；沒有資料時 `items` 為 `[]`。`items` 每筆與 `GET /v1/cameos/{id}` 的 `cameo` 物件完全相同（含 `display`、`points_state`、`failure_reason`、`video_available`、`share`），不含 `upload`。cursor 無法解析回 `400 INVALID_CURSOR`（同點數明細）。唯讀，需要 token。
- `GET /v1/cameos/{id}`：`{"cameo":{...},"upload":...}`（`upload` 規則同 `POST /v1/cameos`）；不存在、不屬於該使用者、或 id 含 NUL／非法 UTF-8 回 `404 CAMEO_NOT_FOUND`。
- 失敗後 `points_state = REFUNDED` 並有 `REFUND` 明細（金額為建單時的 `clip.cost` 快照；資料庫的 partial unique index 保證每筆訂單最多一筆 `REFUND`）。

### 影片與分享

以下端點提供影片播放與分享。影片端點 `GET /v1/cameos/{id}/video`，以及 `PUT`／`DELETE /v1/cameos/{id}/share`、`GET /s/{token}`、`GET /s/{token}/video` 都已實作；App 的播放與分享入口尚未實作。

- `GET /v1/cameos/{id}/video`：影片串流，需要 token，支援 `HEAD` 與 Range：單段 Range 已測試（例如 `Range: bytes=0-99` 回 `206`），多段 Range 由標準庫處理、未測試；起點超出檔尾（例如 `bytes=999999999-`）回 `416`，`416` 的 body 是標準庫的純文字，不是通用 JSON 錯誤格式，屬通用格式的例外。不設 `ETag`、`Last-Modified`，帶 `If-Range` 時回整支影片。此端點尚未用真實播放器驗證（App 播放入口尚未實作）。訂單不存在（或不屬於該使用者）回 `404 CAMEO_NOT_FOUND`；存在但不是 `SUCCEEDED` 回 `404 VIDEO_NOT_READY`。
- `PUT /v1/cameos/{id}/share`：`{"share":{"active":true,"url":"..."}}`，成功一律回 `200`。僅 `SUCCEEDED` 可分享，否則 `409 NOT_SHAREABLE`；訂單不存在回 `404 CAMEO_NOT_FOUND`。分享中重複 PUT 回同一個 URL；取消後再分享會發新 token，舊連結永久失效。
- `DELETE /v1/cameos/{id}/share`：`204`，取消後連結失效。冪等：未分享或非 `SUCCEEDED` 的訂單也回 `204`；訂單不存在回 `404 CAMEO_NOT_FOUND`。
- `GET /s/{token}`：分享網頁，只播放影片，免登入。有效時回 `200 text/html`；已取消或不存在時回簡短 HTML 404、查詢錯誤時回簡短 HTML 500（都是上方通用錯誤格式的例外）。
- `GET /s/{token}/video`：分享頁用的影片，免登入。已取消或不存在時維持 JSON 404（通用錯誤格式）。Range 超出範圍回 `416`。
- `GET`／`HEAD /s/{token}` 與 `/s/{token}/video` 的回應都帶 `Cache-Control: no-store`、`X-Robots-Tag: noindex`、`Referrer-Policy: no-referrer`。例外：`405` 與 `/s/{token}/` 這類未註冊路徑的通用 JSON `404` 不帶；`/s/{token}/video` 的 `416` 不帶 `Cache-Control`，body 是標準庫純文字。
- 驗證：Agent 以 Claude in Chrome 在桌面 Chrome 自動化驗證 `<video>` 可載入、播放與跳轉（JS 操作、靜音），取消分享後舊連結失效（頁面顯示「找不到這個分享。」、影片回 `404`）。未經真人操作，也未在 App 或模擬器驗證。Range 由自動測試與 curl 驗證，沒有瀏覽器紀錄。資料庫真的斷線時的 HTML `500` 也尚未驗證（目前只用已取消的 context 模擬）。
- token 以 Go 標準庫 `crypto/rand.Text()` 產生，存在 `cameos.share_token`，`NULL` 代表未分享。
- 分享網址的 base 由環境變數 `PUBLIC_BASE_URL` 設定，預設 `http://localhost:8080`，不寫死。預設用電腦瀏覽器開；要在模擬器內開，把它設成 `http://10.0.2.2:8080`；`adb reverse` 只是選用，不是必要步驟。
- 範例影片：`backend/assets/sample.mp4`（約 558 KB、H.264、無音軌），所有 `SUCCEEDED` 訂單共用。實測長度 15 秒、解析度 640×360；畫面上有浮水印字樣與 `…AMPLE - NOT FOR REUSE`（兩端被裁切，照原樣記錄），分享頁會顯示這些字。來源與授權未確認。
- 訂單資料的 `video_available` 在 `SUCCEEDED` 時為 `true`；`share` 反映實際分享狀態（`{"active":true,"url":"..."}` 或 `{"active":false,"url":null}`）。App 的播放與分享入口依這兩個欄位判斷。

### 即時事件

`GET /v1/events`（WebSocket）

- 事件僅一種：

```json
{"type":"cameo_updated","cameo_id":"..."}
```

- App 忽略未知事件。事件在交易提交後才發出，只是提示，狀態以 API 為準。

### 廠商 webhook

`POST /webhooks/vendor`

不需要 `Authorization`，以簽章驗證。

- Header：`X-Signature`（對原始 body 做 HMAC-SHA256，hex；密鑰 `VENDOR_WEBHOOK_SECRET`）。
- Body（最多 64 KB）：

```json
{"event_id":"e1","job_id":"uuid","status":"SUCCEEDED","video_url":"http://vendor-sim:9000/videos/uuid.mp4","reason":null}
```

- `job_id` 就是訂單 id。`status`：`SUCCEEDED`（必須有 `video_url`）或 `FAILED`（`reason` 可為 `null`）。
- 檢查順序：讀 body 時先檢查 64 KB 上限，超過回 `400 INVALID_REQUEST`；之後以 `hmac.Equal` 驗 `X-Signature`，缺少或不符回 `401 INVALID_SIGNATURE`（驗過之前不解析 JSON、不碰資料庫）；再驗內容，JSON 不合法、`event_id`／`job_id` 為空或含 NUL／非法 UTF-8、`status` 不是 `SUCCEEDED`／`FAILED`、`SUCCEEDED` 缺 `video_url` 回 `400 INVALID_REQUEST`；`job_id` 找不到訂單回 `404 CAMEO_NOT_FOUND`（事件不留，之後訂單存在時同一 `event_id` 仍可處理）；資料庫錯誤回 `500 INTERNAL`；其餘（處理成功、重複 `event_id`、被忽略的回呼）都回 `200`。
- 處理在單一交易內，鎖序固定為點數帳戶 → 訂單列：先寫 `vendor_events`（`event_id` 重複就是重複回呼，直接回 `200` 不再處理），再依訂單的資料庫狀態處理：
  - `QUEUED` 或 `GENERATING` + `SUCCEEDED`：轉 `SUCCEEDED`、存 `video_url`，`points_state` 維持 `CHARGED`（不退點）。接受 `QUEUED` 是因為回呼可能先於「廠商已接受」的寫入到達。
  - `QUEUED` 或 `GENERATING` + `FAILED`：同一交易內餘額加回快照 `cost`、寫一筆 `REFUND` 明細、轉 `FAILED`／`REFUNDED`、寫 `failure_reason`（`reason` 是 `CONTENT_REJECTED` 照用，其他一律 `VENDOR_FAILED`）。
  - 其他狀態（`SUCCEEDED`、`FAILED`、`EXPIRED`、`AWAITING_UPLOAD`、`UPLOADED`）：不改訂單，記 log，事件保留，回 `200`。
- **先到的為準**：終態以資料庫狀態為準，回呼內不比對生成期限；因此逾時掃描先把訂單轉 `FAILED` 後，晚到的成功回呼不會改變結果；反過來，已 `SUCCEEDED` 的訂單也不會被逾時掃描或失敗回呼改變。
- 派工：`QUEUED` 且未逾期的訂單由背景掃描對廠商 `POST /jobs`（`job_id` = 訂單 id）；廠商回 2xx 才在交易內（仍是 `QUEUED` 時）轉 `GENERATING`。派工失敗只記 log，下次掃描重試（廠商以 `job_id` 去重，重送安全），直到逾時。HTTP 呼叫不在資料庫交易內。

### Debug 異常 API

已實作 `GET`／`POST`／`DELETE /debug/faults`、`POST /debug/reset`，以及 `response_lost_after_commit`、`upload_interrupted`、`upload_expired` 的實際觸發。`POST /debug/faults/consume` 尚未實作；**`vendor_*` 3 種異常目前只會登記，不會真的觸發**，其行為是已定案的約定。

驗證程度：自動測試涵蓋上述端點和 3 種已會觸發的異常；在 Docker 實測確認 reset、互斥、並存、部分輸入驗證和這 3 種異常的觸發。`DEBUG_API_ENABLED=false` 已於 Docker 重跑（`/debug/reset`、`/debug/faults` 回 `404`，訂單與額度不變），開關讀環境變數的判斷沒有自動測試；同名覆蓋和其餘輸入驗證只有自動測試，沒有 Docker 實測。

僅存在於模擬環境，不檢查 App 版本；不做 console 網站。

開關與驗證：`/debug/*` 由環境變數 `DEBUG_API_ENABLED` 控制，程式預設關閉，未開啟時回 `404`；`docker-compose.yml` 預設開啟（模擬環境）。開啟時仍需 Bearer token。

| 方法與路徑 | 說明 |
|---|---|
| `GET /debug/faults` | 查看目前設定的異常，回 `{"active":[...],"available":[...]}`；`active` 每筆為 `{name,target,times,params}`（`times` 是剩餘次數），沒有異常時為空陣列；`available` 是名稱字串陣列，永遠列出 6 種名稱 |
| `POST /debug/faults` | 設定異常，成功回 `200` 和目前清單（同 `GET`） |
| `DELETE /debug/faults` | 全部清除，回 `204` |
| `POST /debug/reset` | 重設環境，回 `204` |
| `POST /debug/faults/consume` | 尚未實作（目前回 `404`）。內部端點，只給 `vendor-sim` 取得異常；和其他 `/debug/*` 一樣受 `DEBUG_API_ENABLED` 與 Bearer token 保護 |

`POST /debug/reset`：清空訂單、點數明細、廠商事件、自拍檔案和異常；餘額回到 seed 的值；片段保留；額度因訂單清空而恢復。重設後才到的廠商回呼，照未知 `job_id` 的處理（見上方 webhook 檢查順序：回 `404 CAMEO_NOT_FOUND`、事件不留），訂單不會復活。reset 不會清 App 本機資料。自拍檔案只刪被清除訂單對應的檔案，刪除失敗只記 log，仍回 `204`。

設定異常 body：

```json
{"name":"response_lost_after_commit","target":"create","times":1,"params":{}}
```

- 輸入驗證：body 是空的、JSON 語法錯誤、尾端有多餘內容、含多個 JSON 值、超過 64 KB、不是合法 UTF-8、或頂層不是物件，回 `400 INVALID_REQUEST`；`name`、`times`、`target` 型別錯誤回 `400 INVALID_FAULT`。
- 未知名稱回 `400 UNKNOWN_FAULT`；其他參數錯誤（`times` 小於 1、缺 `target` 等）回 `400 INVALID_FAULT`。
- `times` 預設 1。`target` 只用於 `response_lost_after_commit`，必填，值為 `create` 或 `submit`。
- `params` 帶不認得的 key，或 `response_lost_after_commit` 以外的異常帶了 `target`，都回 `400 INVALID_FAULT`。
- 同名異常重複設定就覆蓋。同一時間只允許一種 `vendor_*` 異常：已有另一種 `vendor_*` 時，設定新的回 `400 INVALID_FAULT`，要換就先 `DELETE`。其他異常可並存。
- `vendor_*` 異常的 `times`：每個被接受的工作扣 1，連續套用到接下來 N 個工作，和 `vendor_duplicate_callback` 的 `count` 無關。
- 異常存在記憶體，API 重啟就清空。
- 情境檔：`backend/faults/*.json` 每種異常一個檔案，只當 `curl -d @...` 的 body，後端不載入。檔名是異常名稱把底線改成連字號再加 `.json`，例如 `backend/faults/response-lost-after-commit.json`（對應 `response_lost_after_commit`）。

`vendor-sim` 取得異常：收到新工作時對 `POST /debug/faults/consume` 呼叫一次，重送的同一 `job_id` 不再呼叫。查詢失敗就當沒有異常、正常回呼，並記 log。異常綁定下一個被接受的工作。

可設定的異常名稱（6 種）與行為（`vendor_*` 3 種是約定，尚未觸發）：

| 名稱 | 行為 |
|---|---|
| `response_lost_after_commit` | 交易提交後中斷連線，不送回應；`target` 為 `create` 或 `submit` |
| `upload_interrupted` | `params.after_bytes`（預設 1024），內容超過這個值才中斷連線，長度相等會成功且不消耗異常；小於 3 時以 3 計算（要先檢查是不是 JPEG），`GET /debug/faults` 仍顯示原設定值；以上只保證固定長度內容，chunked 長度相等也可能中斷；暫存檔丟棄、訂單狀態不變。App 看到的是網路錯誤，不是 `408`（`408 UPLOAD_INTERRUPTED` 仍是真實的讀取失敗規則） |
| `upload_expired` | 作用在下一次上傳或送出請求所指的訂單，把該訂單的上傳期限（`upload_expires_at`）設為現在，走真實的 `410` 和釋放流程。訂單已不是 `AWAITING_UPLOAD`／`UPLOADED` 時不消耗這次異常。內容檢查仍先於逾期判斷，所以內容不合法時回 `400`／`413`，不是 `410` |
| `vendor_failed` | `params.reason` 預設空值（記為 `VENDOR_FAILED`），可設 `CONTENT_REJECTED` |
| `vendor_duplicate_callback` | `params.count` 預設 3、`params.status` 預設 `SUCCEEDED`；前 count-1 次用同一個 `event_id`，最後一次用新的 `event_id`，預設值會走到兩條去重路徑 |
| `vendor_no_callback` | 不回呼，靠 `GENERATION_TTL` 逾時 |

`socket_event_lost`、`upgrade_required` 只寫在規格文件，不實作。

## 異常觸發方式

待實作後補

> **注意：** 廠商回呼類異常（例如 `vendor_failed`、`vendor_duplicate_callback`、`vendor_no_callback`）要先設定再送單，因為異常綁定下一個被接受的工作。
