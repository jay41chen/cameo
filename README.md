# 客串短劇（Kimhit 候選人作業）

> 狀態：進行中。Android App 的片段清單、點數、點數明細（含分頁）已在 API 37 模擬器驗證過。後端另已實作建單（冪等、點數占用、每日額度）、訂單查詢、額度、上傳自拍、送出（扣點、寫 `CHARGE` 明細）與上傳逾期釋放；Android App 的額度顯示、確認支付（建單）、本機草稿已在 API 37 模擬器接真實後端驗證，涵蓋：額度用完、點數不足、額度達上限、網路錯誤後重試與重用同一個冪等 key、已建單再點同片段拿新 key、CREATE 草稿重用同 key、同 key 回原訂單、`409` 後移除重建、重試進行中不顯示錯誤、逾期重新客串／移除、重開接續。「請求已到伺服器但 App 沒收到回應」以 curl 先送同 key 模擬（非真實斷線）。例外與未驗項目：確認時 `401` 的畫面未在模擬器驗證（單元測試涵蓋）；強制停止只驗到 POST 沒到的情況；另有數項未涵蓋與外觀問題，請見 `docs/manual-test-checklist.md` 第 3 輪。App 已有拍照與預覽（CameraX），詳見 `docs/manual-test-checklist.md` 第 4 輪；App 已接上上傳與送出，並在 API 37 模擬器接真實後端驗證（4.4，詳見 `docs/manual-test-checklist.md` 第 4 輪「Integration」：主流程到「排隊中」、上傳／送出回應遺失後接續且只扣一次（以 curl 模擬）、重試、離開畫面、強制停止、逾期、照片遺失、後端回「照片太大」時引導重拍；上傳途中進背景、真實斷線、`401`、真機未驗）；生成、通知、訂單清單等尚未實作。確認頁問題：返回轉場中點「確認支付」，修正前 6 次嘗試皆建單；已加一行防護（`dropUnlessResumed`），只擋掉「頁面已在離開中」的點擊，修正後 7 次嘗試未建單（每個延遲各 1 次、間隔未量；d=0.7 點到清單列、沒打到按鈕，d=0.5 沒有修正前對照，可直接對照的是 d=0、0.05、0.1、0.2、0.3 這 5 個值）。這不是完整解法：先按確認再按返回時，若回應在頁面銷毀後才回來，只占用點數，按「繼續」或等期限釋放；若在淡出期間回來，一樣導到進度畫面並自動上傳、送出、扣款（MVP 決定，見 `ARCHITECTURE.md` 取捨表；兩者皆讀碼與修正前證據推論，未實測）；傳輸中途被殺、真實手勢返回與網路延遲未測，詳見該節。API 契約中尚未實作的部分仍是設計，未確認的欄位標 `[待確認]`。

## 專案介紹

客串短劇讓觀眾從營運挑選的片段中選一段、當場拍一張自拍、支付點數後生成一段 15 秒、自己出現在劇中的短片，可收藏並分享連結。

- 後端：Go、Postgres、Docker，外加模擬廠商 `vendor-sim`。
- Android App：Kotlin、Jetpack Compose。
- 設計文件：`spec.md`（規格）、`ARCHITECTURE.md`（架構與取捨）。

## 執行方式

### 後端

需要 Docker。在專案根目錄執行：

```
docker compose up --build
```

會啟動 Postgres（`postgres:17-alpine`）和 `api`（`http://localhost:8080`）。`api` 等 Postgres 健康後才啟動，並自動執行 migration 與 seed（可重複啟動，不會重複插入）。不需要任何帳號或金鑰；連線字串、port 與下列設定可用環境變數覆寫（見 `docker-compose.yml`）。Postgres 只對本機開放 `127.0.0.1:5433`，供測試使用。

升級注意：migration `0004` 遇到懸空的 `cameo_id`，或同一訂單重複的 `CHARGE` 明細時，API 會啟動失敗，並在 log 顯示原因；需先處理資料再啟動。

`api` 的業務設定（皆為環境變數，不寫死在程式碼）：

| 變數 | 預設 | 說明 |
|---|---|---|
| `DAILY_LIMIT` | `3` | 每人每日（台北時間）客串次數上限 |
| `UPLOAD_TTL` | `1h` | 建單後的上傳期限，超過會自動釋放點數與額度（Go duration，例如 `5s`、`30m`） |
| `EXPIRY_SWEEP_INTERVAL` | `30s` | 逾期掃描間隔 |
| `SELFIE_DIR` | `/data/selfies` | 自拍存放目錄；`docker-compose.yml` 把 named volume `selfies` 掛在這裡，容器重啟後檔案仍在（容器以 `nobody` 執行，目錄在映像檔內已設為可寫） |
| `MAX_SELFIE_BYTES` | `10485760` | 自拍檔案大小上限（bytes），超過回 `413 SELFIE_TOO_LARGE` |

例如 `UPLOAD_TTL=5s EXPIRY_SWEEP_INTERVAL=1s docker compose up -d api` 可快速觀察逾期釋放。

Seed：測試帳號初始 100 點；3 個片段（`c1` 雨夜重逢、`c2` 屋頂告白、`c3` 最後一班車），每段 10 點；點數明細初始為空。

快速檢查：

```
curl -H 'Authorization: Bearer test-token' localhost:8080/v1/points
```

後端測試（不需啟動 App）：本機需要 Go 1.27.1（`backend/go.mod` 的 `go` 指令）。測試分兩類，`go test ./...` 兩類都會跑：

- 單元測試放在各自套件旁：`backend/internal/layering_test.go`（import 方向）、`backend/internal/domain`、`backend/internal/usecase`、`backend/internal/adapter/http`、`backend/internal/adapter/filestore`。不需要資料庫。
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
- 模擬器相機：AVD 啟動時加 `-camera-front emulated -camera-back virtualscene`（例如 `$ANDROID_HOME/emulator/emulator -avd Pixel_10_Pro -camera-front emulated -camera-back virtualscene`）；本輪驗證即用此參數，未測不加參數時的相機預覽 `[待確認]`。App 預設用前鏡頭，沒有前鏡頭用後鏡頭。
- 目前畫面：片段清單（頂端顯示今日額度，額度用完時點片段會跳提示；有未完成的客串時列在清單上方）、確認支付（顯示占用點數，建單成功後進進度畫面）、點數（可用／占用、明細分頁載入）。點片段後先進相機（需相機權限；被拒絕時顯示說明與「前往設定」），拍照後預覽、可重拍，按「使用這張」進確認支付；確認時照片才移到 App 私有目錄（`files/selfies/<key>.jpg`），建單成功後進進度畫面，依後端訂單狀態上傳照片、送出（上傳成功才刪本機照片）。未完成列在訂單仍是等待上傳或送出中時有「繼續」，並顯示上傳期限；可重試的失敗有重試按鈕，照片遺失、格式不符或太大時要求重新拍照。每個區塊有載入中、失敗（顯示後端 `message`，沒有則通用文字）與重試。已在 API 37 模擬器接真實後端驗證其中的額度、確認支付、本機草稿流程（步驟、例外與未驗項目見 `docs/manual-test-checklist.md` 第 3 輪）；上傳、送出已在第 4 輪 Integration 於模擬器接真實後端驗證（範圍與未驗項目見該節）。

單元測試（不需啟動 App 或後端）：

```
cd android
./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks
```

手動驗證步驟見 `docs/manual-test-checklist.md`。

## 待確認欄位

以下契約細節尚未確認，不在每列重複標註（`/debug/faults` 端點本身已定案）：

- 訂單資料格式中 `SUCCEEDED`、`FAILED` 的 `display` 文字、`video_available`、`share` 的結構（目前後端固定回 `false` 與 `{"active":false,"url":null}`）。
- `X-App-Version` 與 `426` 尚未實作。
- `POST /webhooks/vendor` 的 header 與 body。
- 訂單清單 `GET /v1/cameos` 的回應形狀（尚未實作，目前該路徑只接受 `POST`，`GET` 回 `405`）。
- `POST /debug/faults` body 的 `target`、`times`、`params`。

## API 說明

### 通用規則

- Header：`Authorization: Bearer test-token`（假設已用測試帳號登入）、`X-App-Version: <版本碼整數>` `[待確認]`。
- 錯誤格式：`{"code":"...","message":"給使用者看的文字","retryable":false}`；`retryable` 為 `true` 時 App 可稍後重試。
- 先判斷路由再驗 token：未知路徑（不論有沒有 token）回 `404 NOT_FOUND`；已知路徑但方法不對回 `405 METHOD_NOT_ALLOWED`（帶 `Allow`）；已知路徑且方法正確，缺少或錯誤的 `Authorization` 才回 `401 UNAUTHORIZED`；後端錯誤回 `500 INTERNAL`（`retryable: true`）。都使用上述錯誤格式。
- App 忽略未知欄位。未知的訂單 `status` 視為非終態；有後端 `display` 就顯示，沒有才用通用文字；兩種情況都重新查詢。
- 版本過舊回 `426 UPGRADE_REQUIRED`。
- 時間為 ISO 8601，台北時間（+08:00）。

### 常見錯誤碼

| 碼 | 意義 |
|---|---|
| `400 INVALID_REQUEST` | `Idempotency-Key` 缺少、去掉前後 ASCII 空格與 Tab 後為空、超過 128 bytes 或含 0x21–0x7E 以外的字元；或 body 不是有效 JSON（`retryable: false`）；含控制字元的 key 可能由 HTTP 層直接回純文字 400，不一定是此 JSON 格式 |
| `402 INSUFFICIENT_POINTS` | 可用點數不足；`message`：「點數不足」 |
| `404 CLIP_NOT_FOUND`、`404 CAMEO_NOT_FOUND` | 片段或訂單不存在（訂單不屬於該使用者也回 `CAMEO_NOT_FOUND`）；id 含 NUL 或不是合法 UTF-8 也視為不存在 |
| `409 IDEMPOTENCY_CONFLICT` | 同一個 `Idempotency-Key` 用在不同 `clip_id` |
| `400 INVALID_SELFIE` | 上傳內容前 3 bytes 不是 JPEG 標頭（`FF D8 FF`），或 body 為空；`message`：「照片格式不正確」 |
| `400 INVALID_REQUEST`（`retryable: true`） | 上傳讀取 body 中途失敗（連線中斷、實際內容少於 `Content-Length`）；暫存檔已丟棄、訂單狀態不變，可整檔重傳 |
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
- `failure_reason`：`CONTENT_REJECTED` 退點不退額度；其餘值 `[待確認]`。
- `display`：`AWAITING_UPLOAD` 為 `{"title":"等待上傳","message":"已占用 N 點，送出後才扣款"}`（N 為 `clip.cost`）；`UPLOADED` 為 `{"title":"送出中","message":"照片已上傳，尚未扣款"}`；`QUEUED` 為 `{"title":"排隊中","message":"完成後會通知你"}`；`GENERATING` 為 `{"title":"生成中","message":"完成後會通知你"}`；`EXPIRED` 為 `{"title":"已過期","message":"上傳期限已過，點數沒有扣款，請重新客串。"}`；其餘狀態暫用 `{"title":"處理中","message":""}`。
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
| 讀 body 中途失敗（連線中斷、實際內容少於 `Content-Length`） | 暫存檔丟棄、狀態不變，`400 INVALID_REQUEST`（`retryable: true`） |

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
- 送出後自拍檔案保留。

### 訂單查詢

- `GET /v1/cameos?cursor=`：`{"items":[cameo],"next_cursor":null}`（尚未實作）
- `GET /v1/cameos/{id}`：`{"cameo":{...},"upload":...}`（`upload` 規則同 `POST /v1/cameos`）；不存在、不屬於該使用者、或 id 含 NUL／非法 UTF-8 回 `404 CAMEO_NOT_FOUND`。
- 失敗後 `points_state = REFUNDED` 並有 `REFUND` 明細。

### 影片與分享

以下端點滿足作業的影片播放與分享需求；分享連結 token 的形式、Range 支援細節 `[待確認]`：

- `GET /v1/cameos/{id}/video`：影片串流，支援 Range；未完成回 `404`。
- `PUT /v1/cameos/{id}/share`：`{"share":{"active":true,"url":"..."}}`，僅 `SUCCEEDED` 可分享，否則 `409 NOT_SHAREABLE`。
- `DELETE /v1/cameos/{id}/share`：`204`，取消後連結失效。
- `GET /s/{token}`：分享網頁，只播放影片；已取消或不存在回 `404`。

### 即時事件

`GET /v1/events`（WebSocket）

- 事件僅一種：

```json
{"type":"cameo_updated","cameo_id":"..."}
```

- App 忽略未知事件。事件在交易提交後才發出，只是提示，狀態以 API 為準。

### 廠商 webhook

`POST /webhooks/vendor`

- Header：`X-Signature`（對原始 body 做 HMAC-SHA256，hex）。
- Body：

```json
{"event_id":"e1","job_id":"uuid","status":"SUCCEEDED","video_url":"...","reason":null}
```

- `status`：`SUCCEEDED` 或 `FAILED`。
- 簽章錯誤回 `401`；重複 `event_id` 回 `200` 且不重複處理。

### Debug 異常 API

僅存在於模擬環境，不檢查 App 版本。異常用 JSON 情境檔（`backend/faults/*.json`）和 `GET/POST/DELETE /debug/faults` 觸發；不做 console 網站。

| 方法與路徑 | 說明 |
|---|---|
| `GET /debug/faults` | 查看目前設定的異常 |
| `POST /debug/faults` | 設定異常 |
| `DELETE /debug/faults` | 清除異常 |
| `POST /debug/reset` | 清空訂單與異常，重設點數與額度 |

設定異常 body：

```json
{"name":"response_lost_after_commit","target":"create","times":1,"params":{}}
```

可設定的異常名稱（8 種）：`response_lost_after_commit`、`upload_interrupted`、`upload_expired`、`vendor_failed`、`vendor_duplicate_callback`、`vendor_no_callback`、`socket_event_lost`、`upgrade_required`。各異常的參數：待實作後補。

## 異常觸發方式

待實作後補

> **注意：** 廠商回呼類異常（例如 `vendor_failed`、`vendor_duplicate_callback`、`vendor_no_callback`）要先設定再送單，因為異常綁定下一個被接受的工作。
