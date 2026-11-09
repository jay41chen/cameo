# 客串短劇

客串短劇讓觀眾從營運挑選的片段中選一段、當場拍一張自拍、支付點數後生成一段 15 秒、自己出現在劇中的短片，可收藏並分享連結。

## 功能

- 選片段
- 拍自拍
- 確認支付，占用點數
- 上傳並送出後扣款
- 離開 App 後背景追蹤，並發完成通知
- 首頁「我的客串」看進度
- 觀看短片
- 分享連結與取消分享
- 失敗時退還點數
- 點數明細
- 用模擬後端觸發 6 種異常

## 技術

- 後端：Go、Postgres、Docker，外加模擬廠商 `vendor-sim`（`backend/cmd/vendor-sim`，同一個 Go module、同一個 Dockerfile）。
- Android App：Kotlin、Jetpack Compose。

## 狀態

MVP 已完成。Android App 只在 API 37 模擬器驗證，沒有真機；已驗證與未涵蓋的項目見 [`VERIFICATION.md`](VERIFICATION.md)。

## 快速開始

1. 後端：需要 Docker，在專案根目錄執行：

   ```
   docker compose up --build
   ```

   不需要任何帳號或金鑰。細節見 [`backend/README.md`](backend/README.md)。

2. App：需要 JDK 21、Android SDK 37，並設 `ANDROID_HOME` 或 `android/local.properties`。模擬器啟動時加相機參數 `-camera-front emulated -camera-back virtualscene`。後端啟動後執行：

   ```
   cd android
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n io.github.jay41chen.cameo/.MainActivity
   ```

   細節見 [`android/README.md`](android/README.md)。

## API 一覽

所有 `/v1` 請求都要帶 `Authorization: Bearer test-token`。參數、回應格式與錯誤碼見 [`API.md`](API.md)。

| 方法與路徑 | 用途 |
|---|---|
| `GET /v1/clips` | 片段清單 |
| `GET /v1/points`、`GET /v1/points/ledger?cursor=` | 點數餘額、點數明細 |
| `GET /v1/quota` | 今日剩餘次數 |
| `POST /v1/cameos` | 建單並占用點數（需 `Idempotency-Key`） |
| `PUT /v1/cameos/{id}/selfie` | 上傳自拍（JPEG） |
| `POST /v1/cameos/{id}/submit` | 送出並扣點 |
| `GET /v1/cameos?cursor=`、`GET /v1/cameos/{id}` | 我的客串、單筆訂單 |
| `GET /v1/cameos/{id}/video` | 觀看影片 |
| `PUT`、`DELETE /v1/cameos/{id}/share` | 建立、取消分享 |
| `GET /s/{token}`、`GET /s/{token}/video` | 分享網頁與影片（免登入） |
| `POST /webhooks/vendor` | 廠商回呼（HMAC 簽章） |

範例：

```
curl -H 'Authorization: Bearer test-token' localhost:8080/v1/points
```

## 異常觸發

後端啟動後，在專案根目錄用 `backend/scripts/fault.sh` 登記異常，再到 App 上照常客串一次，就會觸發。

也可以用瀏覽器開 `http://localhost:8080/debug/console`：按情境按鈕會填入範例 JSON，可先改參數（例如內容審核拒絕的 `CONTENT_REJECTED`）再按「登記」；頁面也能清空異常、重設環境，並每 3 秒顯示點數、額度和最近 10 筆已送出的訂單。

| 異常 | 指令 | App 上會看到 |
|---|---|---|
| 廠商回報失敗 | `backend/scripts/fault.sh vendor-failed` | 失敗通知，點數退還 |
| 廠商不回呼 | `backend/scripts/fault.sh vendor-no-callback` | 約 2 分鐘後逾時，失敗通知，點數退還 |
| 廠商重複回呼 | `backend/scripts/fault.sh vendor-duplicate-callback` | 只有 1 則通知、只扣 1 次 |
| 上傳中斷 | `backend/scripts/fault.sh upload-interrupted` | 錯誤與「重試」，重試後成功，只扣 1 次 |
| 上傳期限已過 | `backend/scripts/fault.sh upload-expired` | 顯示「已過期」與「上傳期限已過，點數沒有扣款，請重新客串。」 |
| 建單回應遺失 | `backend/scripts/fault.sh response-lost-after-commit` | 常被自動重送吸收，看不到錯誤；只扣 1 次 |

- `backend/scripts/fault.sh` 不帶參數會列出所有情境；`reset` 重設環境，`list` 看已登記的異常，`clear` 清除。
- 廠商類的 3 種同時只能登記一種，換別種前先 `clear`。
- 內容審核拒絕：用自訂 JSON，`{"name":"vendor_failed","params":{"reason":"CONTENT_REJECTED"}}`，存成檔案後執行 `backend/scripts/fault.sh <檔案>.json`。
- 送出回應遺失：情境檔預設是建單；要測送出，用自訂 JSON `{"name":"response_lost_after_commit","target":"submit"}`，存成檔案後執行 `backend/scripts/fault.sh <檔案>.json`。
- 控制台只列已送出的訂單；上傳期限已過、上傳中斷的結果要看 App 或 API 回應。改過 port 時，網址換成對應的 port。
- 每種異常的參數、後端結果與 curl 寫法見 [`API.md`](API.md)「異常觸發方式」。

## 文件導覽

| 文件 | 內容 |
|---|---|
| [`backend/README.md`](backend/README.md) | 後端啟動、設定、重設與測試 |
| [`android/README.md`](android/README.md) | App 建置、執行、畫面與背景通知的限制 |
| [`API.md`](API.md) | 後端 API、Debug 異常 API 與異常觸發方式 |
| [`VERIFICATION.md`](VERIFICATION.md) | 已驗證與未涵蓋的項目 |
| [`BACKLOG.md`](BACKLOG.md) | MVP 之後的待做項目 |
| [`spec-server.md`](spec-server.md) | 後端開發規格 |
| [`spec-mobile.md`](spec-mobile.md) | App 開發規格 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 架構與取捨 |
| [`AI-LOG.md`](AI-LOG.md) | AI 協作紀錄 |
