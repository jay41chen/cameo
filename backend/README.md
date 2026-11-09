# 後端

以下指令除了標明 `cd backend` 的之外，都在專案根目錄執行。API 與異常觸發方式見 [`../API.md`](../API.md)。

需要 Docker。在專案根目錄執行：

```
docker compose up --build
```

這個指令會在前景執行。接下來的 `curl` 和測試請另開終端機，或改用 `docker compose up --build -d`。

會啟動 Postgres（`postgres:17-alpine`）、`api`（`http://localhost:8080`）和模擬廠商 `vendor-sim`（容器內 `http://vendor-sim:9000`，不對外發布 port）。`api` 等 Postgres 健康後才啟動，並自動執行 migration 與 seed（可重複啟動，不會重複插入）。不需要任何帳號或金鑰（`VENDOR_WEBHOOK_SECRET` 在 `docker-compose.yml` 預設為模擬用的 `cameo-sim-secret`，僅供模擬，不是正式密鑰）；連線字串、port（`API_PORT` 預設 `8080`、`POSTGRES_HOST_PORT` 預設 `5433`）與下列設定可用環境變數覆寫（見 `docker-compose.yml`）。Postgres 只對本機開放 `127.0.0.1:5433`，供測試使用。本文件的指令都用預設 port，改了要跟著換。

升級注意：migration `0004` 遇到懸空的 `cameo_id`，或同一訂單重複的 `CHARGE` 明細時，API 會啟動失敗，並在 log 顯示原因；需先處理資料再啟動。

`api` 的業務設定（皆為環境變數，不寫死在程式碼）：

| 變數 | 預設 | 說明 |
|---|---|---|
| `DAILY_LIMIT` | `3` | 每人每日（台北時間）客串次數上限 |
| `UPLOAD_TTL` | `1h` | 建單後的上傳期限，超過會自動釋放點數與額度（Go duration，例如 `5s`、`30m`） |
| `EXPIRY_SWEEP_INTERVAL` | `30s` | 逾期掃描間隔 |
| `SELFIE_DIR` | `/data/selfies` | 自拍存放目錄；`docker-compose.yml` 把 named volume `selfies` 掛在這裡，容器重啟後檔案仍在（容器以 `nobody` 執行，目錄在映像檔內已設為可寫） |
| `MAX_SELFIE_BYTES` | `10485760` | 自拍檔案大小上限（bytes），超過回 `413 SELFIE_TOO_LARGE` |
| `GENERATION_TTL` | `30m`（compose 預設 `2m`） | 生成期限：送出（轉 `QUEUED`）起算，涵蓋排隊與生成；到期（`<=` 現在）仍未完成的訂單轉 `FAILED`（原因 `TIMEOUT`）並退點。期限在送出時寫入訂單，改設定只影響新訂單，容器重建後才生效。`VENDOR_DELAY_SEC` 調到接近或超過期限時，要同時調大 `GENERATION_TTL`，否則正常訂單也會逾時 |
| `DEBUG_API_ENABLED` | 程式預設關閉；compose 預設 `true` | 控制 `/debug/*`（含網頁控制台 `/debug/console`，見 [`../API.md`](../API.md)「Debug 異常 API」），也控制 `PUT share` 的 `debug_base_url`（見 [`../API.md`](../API.md)「影片與分享」）；只有值為 `true` 才開啟，未開啟時 `/debug/*` 回 `404` |
| `PUBLIC_BASE_URL` | `http://localhost:8080`（程式與 compose 相同） | 分享網址的 base，不寫死。預設用電腦瀏覽器開；要在模擬器內開，設成 `http://10.0.2.2:8080`；`adb reverse` 只是選用。不會跟著 `API_PORT` 變動；改 `API_PORT` 時要一起設定，否則分享連結的 port 會錯 |
| `DISPATCH_SWEEP_INTERVAL` | `2s` | 派工與生成逾時掃描間隔；每個週期先處理逾時、再派工（每次最多 20 筆，`created_at` 由舊到新） |
| `VENDOR_URL` | `http://localhost:9000`（compose 設為 `http://vendor-sim:9000`） | 廠商的 base URL；派工是對 `{VENDOR_URL}/jobs` 的 `POST`，單次逾時 5 秒 |
| `VENDOR_WEBHOOK_SECRET` | 無預設 | webhook 簽章密鑰。**空字串或未設定時 `api` 拒絕啟動**；程式碼內沒有預設密鑰。compose 以 `${VENDOR_WEBHOOK_SECRET:-cameo-sim-secret}` 同時傳給 `api` 與 `vendor-sim`（僅供模擬） |

例如 `UPLOAD_TTL=5s EXPIRY_SWEEP_INTERVAL=1s docker compose up -d` 可快速觀察逾期釋放。

本機要放寬每日額度可用 `DAILY_LIMIT=100 docker compose up -d`；預設值不變。

`vendor-sim` 的設定（環境變數，見 `docker-compose.yml`）：

| 變數 | compose 預設 | 說明 |
|---|---|---|
| `VENDOR_DELAY_SEC` | `20` | 收到工作後等幾秒才送回呼（可用小數） |
| `VENDOR_CALLBACK_URL` | `http://api:8080/webhooks/vendor` | 回呼目標 |
| `VENDOR_PUBLIC_URL` | `http://vendor-sim:9000` | 回呼 `video_url` 的 base（`{base}/videos/{job_id}.mp4`） |
| `VENDOR_FAULTS_URL` | `http://api:8080/debug/faults/consume` | 取得異常的端點（程式預設相同），單次逾時 2 秒 |
| `VENDOR_WEBHOOK_SECRET` | `cameo-sim-secret` | 與 `api` 相同；空字串時拒絕啟動 |

`vendor-sim` 的行為：`POST /jobs`，body `{"job_id":"..."}`，回 `202`；同一 `job_id` 重送也回 `202`，不開第二個工作（去重只在記憶體，重啟後清空）；缺 `job_id` 回 `400`。收到新工作時向 `VENDOR_FAULTS_URL` 取一次異常，取不到、查詢失敗或逾時就當沒有異常；沒有異常時等 `VENDOR_DELAY_SEC` 後送一次 `SUCCEEDED` 回呼（`event_id` 隨機，`X-Signature` 為對原始 body 的 HMAC-SHA256 hex）；回呼失敗只記 log、不重試，由 `GENERATION_TTL` 兜底。`vendor-sim` 不接收照片、不提供影片檔（`video_url` 指向的檔案不存在）；有登記 `vendor_*` 異常時，改送失敗、重複回呼或不回呼。

背景掃描（逾期釋放、派工、生成逾時）假設同一時間只有一個 `api` 實例，不做多實例協調。

Seed：測試帳號初始 100 點；3 個片段（`c1` 雨夜重逢、`c2` 屋頂告白、`c3` 最後一班車），每段 10 點；點數明細初始為空。

要把環境重設回初始狀態（訂單、點數明細、廠商事件、自拍檔案與異常清空，餘額回到初始 100 點，額度恢復）：

```bash
curl -X POST -H "Authorization: Bearer test-token" http://localhost:8080/debug/reset
```

成功回 `204`。不會清 App 本機資料。`DEBUG_API_ENABLED` 不是 `true` 時回 `404`，細節見 [`../API.md`](../API.md)「Debug 異常 API」。也可以用 `backend/scripts/fault.sh reset` 或網頁控制台 `http://localhost:8080/debug/console` 的「重設環境」；登記異常見 [`../API.md`](../API.md)「異常觸發方式」。

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
