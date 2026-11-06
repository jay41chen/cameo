# 客串短劇（Kimhit 候選人作業）

> 狀態：進行中。第 2 輪已實作 Docker 後端與 Android App 的片段清單、點數、點數明細（含分頁）；其他功能（建單、上傳、送出、通知等）尚未實作。App 只在 API 37 模擬器驗證過。API 契約中尚未實作的部分仍是設計，未確認的欄位標 `[待確認]`。

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

會啟動 Postgres（`postgres:17-alpine`）和 `api`（`http://localhost:8080`）。`api` 等 Postgres 健康後才啟動，並自動執行 migration 與 seed（可重複啟動，不會重複插入）。不需要任何帳號或金鑰；連線字串、port 可用環境變數覆寫（見 `docker-compose.yml`）。Postgres 只對本機開放 `127.0.0.1:5433`，供測試使用。

Seed：測試帳號初始 100 點；3 個片段（`c1` 雨夜重逢、`c2` 屋頂告白、`c3` 最後一班車），每段 10 點；點數明細初始為空。

快速檢查：

```
curl -H 'Authorization: Bearer test-token' localhost:8080/v1/points
```

後端測試（不需啟動 App）：本機需要 Go 1.27.1（`backend/go.mod` 的 `go` 指令）。先讓 Postgres 在跑，再指定 `TEST_DATABASE_URL`。測試會在該資料庫建立獨立 schema，結束後刪除，不動其他資料；沒設定時測試會失敗。

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
- 目前畫面：片段清單、點數（可用／占用、明細分頁載入）。每個區塊有載入中、失敗（顯示後端 `message`，沒有則通用文字）與重試。

單元測試（不需啟動 App 或後端）：

```
cd android
./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks
```

手動驗證步驟見 `docs/manual-test-checklist.md`。

## 待確認欄位

以下契約細節尚未確認，不在每列重複標註（`/debug/faults` 端點本身已定案）：

- 訂單資料格式中 `display`、`video_available`、`share` 的結構。
- `POST /v1/cameos` 的 `Idempotency-Key` header、新建 `201`／重送 `200` 語意，以及 `402 INSUFFICIENT_POINTS`。
- `POST /webhooks/vendor` 的 header 與 body。
- 訂單查詢（`GET /v1/cameos`、`GET /v1/cameos/{id}`）的回應形狀。
- 錯誤碼 `CLIP_NOT_FOUND`、`CAMEO_NOT_FOUND`、`IDEMPOTENCY_CONFLICT` 等名稱尚未定案。
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
| `402 INSUFFICIENT_POINTS` | 可用點數不足 |
| `404` | 資源不存在 |
| `409` | 狀態衝突或冪等鍵衝突 |
| `410` | 上傳／送出已超過期限，不扣款；App 顯示「上傳期限已過，點數沒有扣款，請重新客串。」 |
| `426 UPGRADE_REQUIRED` | App 版本過舊 |
| `429 DAILY_LIMIT_REACHED` | 已達每日上限 |
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

### 片段

`GET /v1/clips`

- 參數：無。
- 成功 `200`：

```json
{"items":[{"id":"c1","title":"雨夜重逢","cost":10}]}
```

### 點數

`GET /v1/points`

- 參數：無。`available = balance - held`。
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

- 參數：無。
- 成功 `200`：

```json
{"remaining":2,"resets_at":"2026-11-07T00:00:00+08:00","display":"今日還可客串 2 次"}
```

### 建單

`POST /v1/cameos`

- Header：`Idempotency-Key`（即 App 的 `client_request_id`）。
- Body：`{"clip_id":"c1"}`
- 效果：占用點數與額度（`points_state = HELD`），不寫明細。
- 成功：新建 `201`、重送 `200`：

```json
{"cameo":{"id":"uuid","status":"AWAITING_UPLOAD","points_state":"HELD"},"upload":{"url":"/v1/cameos/uuid/selfie","expires_at":"2026-11-07T11:00:00+08:00"}}
```

- 錯誤：`402 INSUFFICIENT_POINTS`、`429 DAILY_LIMIT_REACHED`、`409 IDEMPOTENCY_CONFLICT`（同 key 不同 `clip_id`）、`404 CLIP_NOT_FOUND`、`426`。
- 上傳期限 1 小時（`upload.expires_at`）；生成期限 30 分鐘。理由：縮短點數被占用的時間；代價：離線太久要重新建單。
- `upload.url` 可能是相對或絕對網址 `[待確認]`。

### 上傳自拍

`PUT {upload.url}`（即 `/v1/cameos/{id}/selfie`）

- Header：`Content-Type: image/jpeg`；`X-Content-SHA256` `[待確認]`。
- Body：JPEG 整檔（中斷後整檔重傳）。
- 成功 `200`：`{"cameo":{...}}`
- 錯誤：`410 UPLOAD_EXPIRED`（釋放占用，`points_state = RELEASED`）、`409 INVALID_STATE`、`413 SELFIE_TOO_LARGE`、`422 CHECKSUM_MISMATCH`（後三者 `[待確認]`）。

### 送出

`POST /v1/cameos/{id}/submit`

- 參數：無 body。
- 效果：同一交易扣點並寫 `CHARGE`（`points_state = CHARGED`）；可重複呼叫，不重複扣點。
- 成功 `200`：`{"cameo":{...}}`
- 錯誤：`410`（送出晚於期限，不扣款）、`409 SELFIE_NOT_UPLOADED`（`[待確認]`）、`404`。

### 訂單查詢

- `GET /v1/cameos?cursor=`：`{"items":[cameo],"next_cursor":null}`
- `GET /v1/cameos/{id}`：`{"cameo":{...}}`；`404 CAMEO_NOT_FOUND`
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
