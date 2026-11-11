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
- 用模擬後端觸發 6 種異常（7 個情境，內容審核拒絕是廠商失敗的一種）

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

2. App：需要 JDK 21、Android SDK 37，並設 `ANDROID_HOME` 或 `android/local.properties`（只設 `local.properties`、沒設 `ANDROID_HOME` 時，把下面的 `$ANDROID_HOME` 換成 SDK 路徑）。要先有一個 AVD：沒有時在 Android Studio 的 Device Manager 建立一個 API 37 的裝置（第 1 行 `-list-avds` 才取得到名稱）。`adb` 在 SDK 的 `platform-tools` 目錄（`$ANDROID_HOME/platform-tools`），要加進 PATH（或把指令裡的 `adb` 換成完整路徑）。後端啟動後，在專案根目錄把下面整段一次貼上執行（第 2 行會用到第 1 行設定的 `AVD` 變數，且區塊裡有 `cd android`，分行貼或在別的目錄貼都會失敗）。各行用途：第 1 行取本機 AVD 清單的第一個（要用別的 AVD 就把 `AVD=` 改成那個名稱），第 2 行帶相機參數在背景啟動模擬器，第 3 行等開機完成，之後 build、安裝、開啟 App。

   ```
   AVD=$($ANDROID_HOME/emulator/emulator -list-avds | head -1)
   $ANDROID_HOME/emulator/emulator -avd "$AVD" -camera-front emulated -camera-back virtualscene &
   adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
   cd android
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n io.github.jay41chen.cameo/.MainActivity
   ```

   細節見 [`android/README.md`](android/README.md)。App 分享出的連結開頭是 `http://10.0.2.2:8080`，要在模擬器的瀏覽器開；在電腦瀏覽器開時把 `10.0.2.2` 換成 `localhost`。

**8080 被占用時**：後端的 port、分享網址與 App 的網址要一起改。後端：

```
API_PORT=9090 PUBLIC_BASE_URL=http://localhost:9090 docker compose up --build
```

Postgres 的 5433 也被占用時，再加 `POSTGRES_HOST_PORT=<新 port>`；這時 [`backend/README.md`](backend/README.md) 的整合測試連線字串也要改成同一個 port。

App 在 `android/` 目錄重新 build 並安裝時帶同樣的 port（只支援模擬器）：

```
./gradlew :app:assembleDebug -PcameoBaseUrl=http://10.0.2.2:9090
```

細節見 [`backend/README.md`](backend/README.md)、[`android/README.md`](android/README.md)。

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

也可以用瀏覽器開 `http://localhost:8080/debug/console`：按情境按鈕會填入範例 JSON，可先改參數（例如送出回應遺失把 `target` 改成 `submit`）再按「登記」；頁面也能清空異常、重設環境，並每 3 秒顯示點數、額度和最近 10 筆已送出的訂單；「設定」區塊可調整每日額度、生成期限、廠商延遲和初始點數（只存在記憶體，後端重啟還原；初始點數按重設後才生效）。

廠商預設延遲 20 秒（`VENDOR_DELAY_SEC`），一般訂單送出後約 20–30 秒（廠商延遲 20 秒，加上 App 每 10 秒查一次訂單）才會收到結果通知；可在控制台「設定」調短（只存在記憶體），或啟動時帶 `VENDOR_DELAY_SEC=5 docker compose up --build`。

| 異常 | 指令 | App 上會看到 |
|---|---|---|
| 廠商回報失敗 | `backend/scripts/fault.sh vendor-failed` | 失敗通知，點數退還 |
| 內容審核拒絕 | `backend/scripts/fault.sh content-rejected` | 「無法生成」，點數退還，今日次數不退 |
| 廠商不回呼 | `backend/scripts/fault.sh vendor-no-callback` | 約 2 分鐘後逾時（預設生成期限；在控制台改過就不同），失敗通知，點數退還 |
| 廠商重複回呼 | `backend/scripts/fault.sh vendor-duplicate-callback` | 只有 1 則通知、只扣 1 次 |
| 上傳中斷 | `backend/scripts/fault.sh upload-interrupted` | 錯誤與「重試」，重試後成功，只扣 1 次 |
| 上傳期限已過 | `backend/scripts/fault.sh upload-expired` | 顯示「已過期」與「上傳期限已過，點數沒有扣款，請重新客串。」 |
| 建單回應遺失 | `backend/scripts/fault.sh response-lost-after-commit` | 照常完成，這是預期；常被自動重送吸收、看不到錯誤；要確認的是只多 1 筆訂單、只扣 1 次 |

- `backend/scripts/fault.sh` 不帶參數會列出所有情境；`reset` 重設環境，`list` 看已登記的異常，`clear` 清除。
- 廠商類異常（`vendor_*`）同時只能登記一種，換別種前先 `clear`。`content-rejected` 和 `vendor-failed` 是同一種異常（`vendor_failed`），後登記的會直接覆蓋前一筆。
- 送出回應遺失：情境檔預設是建單；要測送出，用自訂 JSON `{"name":"response_lost_after_commit","target":"submit"}`，存成檔案後執行 `backend/scripts/fault.sh <檔案>.json`。
- 控制台只列已送出的訂單；上傳期限已過、上傳中斷的結果要看 App 或 API 回應。改過 port 時，網址換成對應的 port。
- 示範點數不足（`402`）：在控制台「設定」把初始點數設成 5（或 0）→ 按「重設環境」→ 在 App 客串一次，按「確認支付」會看到點數不足；示範完把初始點數清空儲存、再重設，餘額回到 100。
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
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 常見改動要動哪些地方 |
| [`AI-LOG.md`](AI-LOG.md) | AI 協作紀錄 |
