# 改動指南

給新加入的人：常見的改動要動哪些地方。為什麼這樣設計看 [`ARCHITECTURE.md`](ARCHITECTURE.md)，欄位細節看 [`API.md`](API.md)，怎麼做看 [`spec-server.md`](spec-server.md) 與 [`spec-mobile.md`](spec-mobile.md)。

## 分層速查

### 後端（`backend/`）

- `internal/domain`：訂單、點數、額度的規則與狀態，純 Go。
- `internal/usecase`：流程與 Port（主要在 `ports.go`，`RuntimeConfig` 在 `service.go`），例如建單（`cameo.go`）、上傳自拍（`selfie.go`）、廠商派工與回呼（`vendor.go`）。
- `internal/adapter`：Port 的實作，分 `http`（路由、Debug API、控制台）、`postgres`（查詢、交易、`migrations/`）、`filestore`（自拍檔案）、`vendor`（廠商 client）。
- `internal/app`：服務啟動與逾期掃描；`cmd/api` 負責組裝，`cmd/vendor-sim` 是模擬廠商。
- 依賴方向：`adapter` 可依賴 `usecase` 與 `domain`，`app` 依賴 `usecase`，`usecase` 只依賴 `domain`；反過來不行，`adapter` 之間也不互相依賴。import 方向由 `backend/internal/layering_test.go` 檢查（`vendor-sim` 不套分層）。

### App（`android/`）

- `:domain`：模型（`domain/model/Models.kt`）、usecase、repository 介面；純 Kotlin。
- `:data`：repository 實作、API client（`data/network`）、Room `pending_cameos`（`data/pending`）。
- `:app`：畫面與 ViewModel（`app/.../ui`）、背景追蹤與通知（`app/.../tracking`）、提交流程（`app/.../submission`）、Hilt 組裝。
- 依賴方向：`:app` → `:data` → `:domain`。`:app` 透過 `:domain` 的介面使用資料層。

## 常見改動

### 新增 API 回應欄位

- 後端：只新增、不改既有欄位的語意。回應結構與組裝在 `backend/internal/adapter/http/server.go`；欄位來源若是新資料，再往 `domain`、`usecase`、`postgres` 補。
- App：在 `android/data/src/main/kotlin/.../data/network/ApiService.kt` 的 DTO 補欄位，需要時在 `data/cameos/CameoRepositoryImpl.kt` 等 repository 轉成 `domain/model/Models.kt` 的模型。JSON 設定是 `ignoreUnknownKeys = true`（`data/network/ApiFactory.kt`），所以舊版 App 遇到新欄位不會壞。
- 測試：`backend/test/integration` 對應的 API 測試補斷言；App 補對應 repository 的單元測試。
- 文件：`API.md` 對應的資料格式小節。
- 相容性注意：已上架的 App 不認得新欄位，不能讓它變成必要欄位；要改語意就走新版本路徑（見 `ARCHITECTURE.md` 第 6 節）。

### 新增訂單狀態

- 後端：
  - `backend/internal/domain/cameo.go` 的狀態常數與 `Display()`。
  - 轉移規則與點數處理：轉為終態和點數變化要在同一交易（見 `ARCHITECTURE.md` 第 5 節），流程在 `backend/internal/usecase/`（`vendor.go`、`selfie.go` 等），查詢與寫入在 `backend/internal/adapter/postgres/queries.go`。
  - 資料庫：`cameos.status` 有 CHECK 限制（`0003_cameos.sql`），新增狀態要用新的 migration 改限制。部分索引的條件（例如 `0005_vendor_callbacks.sql`）也要確認是否要納入。
- API：`display` 文字由 `backend/internal/domain/cameo.go` 的 `Display()` 產生；`failure_reason` 的值是同一個檔案裡的 `Failure*` 常數，在失敗流程中由 usecase 寫入。只有新狀態帶來新的失敗原因時，才需要動 `Failure*`。兩者都要同步更新 `API.md` 的資料格式。
- App：
  - 狀態列舉是 `android/domain/.../model/Models.kt` 的 `CameoStatus`；不認得的狀態解析成 `UNKNOWN`，在 `data/cameos/CameoRepositoryImpl.kt`。
  - 追蹤與終態判斷：`domain/usecase/TrackCameo.kt` 的 `decideTracking`（前景服務），以及 `app/.../tracking/TrackWhileGenerating.kt` 的 `needsTracking`；後者同時決定首頁 3 秒輪詢（`app/.../ui/MyCameosViewModel.kt`）和送出後要不要開始追蹤（`app/.../submission/SubmissionRunner.kt`）。
  - 提交流程在 `domain/usecase/AdvanceCameoUseCase.kt`，未完成清單在 `domain/usecase/UseCases.kt`。
  - 畫面文字在 `app/.../ui/Screens.kt`。
- 測試：`backend/internal/domain/cameo_test.go`、`backend/test/integration`；App 的狀態判斷單元測試。
- 文件：`ARCHITECTURE.md` 第 5 節的狀態圖、`API.md`、`spec-server.md` 第 4 章。
- 相容性注意：舊版 App 遇到新狀態時的處理規則見 `ARCHITECTURE.md` 第 6 節；新增狀態前，先確認舊版 App 仍會追蹤並通知。

### 新增一種模擬異常

- 後端：
  - 名稱常數與 `faultDefs` 在 `backend/internal/adapter/http/faults.go`；觸發點在對應的 handler 或 usecase。
  - 廠商類（`vendor_*`）的行為在模擬廠商 `backend/cmd/vendor-sim/main.go`，由它向 `POST /debug/faults/consume` 取得異常。
- 情境：在 `backend/faults/` 加 `<情境名>.json`（登記用的 body），並在 `backend/faults/scenarios.tsv` 加一行「情境名 Tab 說明」（行序就是控制台顯示順序）。控制台頁面與 `fault.sh` 都從這裡讀，不用改 `console.html` 或腳本；`backend/faults/faults.go` 載入時檢查兩邊一一對應。
- 測試：`backend/faults/faults_test.go`（情境檔一致性）、`backend/internal/adapter/http/faults_test.go`（登記與驗證）、`backend/test/integration/faults_trigger_test.go`（實際觸發）；`vendor-sim` 的行為補在 `backend/cmd/vendor-sim/main_test.go`。
- 文件：`API.md` 的 Debug 異常 API 異常表與「異常觸發方式」、`README.md` 的異常表、`spec-server.md` 第 7 章；新增異常種類時，`README.md` 和 `API.md` 裡的「6 種異常」也要改。
- 只加情境（同一種異常、不同參數，例如 `content-rejected`）：只要上面「情境」那一步，加上 `README.md` 異常表與 `API.md`「異常觸發方式」。
- 相容性注意：同一時間只允許一種 `vendor_*` 異常；異常存在記憶體，重啟就清空；`DEBUG_API_ENABLED=false` 時 `/debug/*` 要維持回 404。

### 改每日額度規則

- 後端：
  - 建單時的額度檢查在 `backend/internal/usecase/cameo.go`（`ErrDailyLimitReached`）。
  - 上限的取值在 `backend/internal/usecase/service.go`（優先用執行期設定，否則用 `DailyLimit` 設定）；額度計算在 `backend/internal/domain/quota.go`。
  - `GET /v1/quota` 在 `backend/internal/adapter/http/server.go`。
  - 執行期設定 `/debug/config` 在 `backend/internal/adapter/http/config.go`（含上下限）。
- App：只顯示後端給的值，不寫死。額度模型在 `Models.kt` 的 `Quota`，取得在 `data/quota/QuotaRepositoryImpl.kt`，顯示在 `app/.../ui/Screens.kt`（`QuotaHeader`）與 `QuotaGate.kt`。
- 測試：`backend/internal/domain/cameo_test.go`、`backend/test/integration/config_test.go`、`backend/test/integration/cameo_test.go`。
- 文件：`spec-server.md` 第 2 章、`API.md` 的額度與執行期設定。
- 相容性注意：`display` 文字由後端決定，App 不依賴上限的數字；改規則不需要 App 發新版（見 `ARCHITECTURE.md` 第 6 節）。

### 新增資料庫 migration

- 後端：在 `backend/internal/adapter/postgres/migrations/` 新增下一個編號的檔案，命名 `NNNN_說明.sql`（目前最新是 `0007_seed_balance.sql`）。檔案由 `migrate.go` 內嵌（`go:embed migrations/*.sql`），依檔名排序，API 啟動時自動執行。
- 不要改已經發布的 migration：每個檔名執行過就記在 `schema_migrations`，之後不會再跑，改舊檔不會套用到已存在的資料庫。要改就新增一個 migration。
- 要能重複啟動：`Migrate` 會略過已記錄的版本，並鎖住版本表避免同時啟動；新 migration 本身在單一交易內執行。既有資料不符合新限制時，要像 `0004_ledger_integrity.sql` 一樣讓啟動失敗並在 log 說明原因，`backend/README.md` 的「升級注意」也要補上。
- 測試：整合測試用真實 Postgres（`backend/test/internal/testdb/testdb.go` 會建立獨立 schema 並跑 `Migrate`），改動要有整合測試涵蓋。
- 文件：`backend/README.md`（有升級注意時）、`spec-server.md`。
- 相容性注意：先想舊資料怎麼辦（預設值、回填），再想舊版 API 與新版資料庫能不能共存。

## 驗證

後端（整合測試需要 `TEST_DATABASE_URL`，說明見 [`backend/README.md`](backend/README.md)）：

```
cd backend
TEST_DATABASE_URL='postgres://cameo:cameo@127.0.0.1:5433/cameo?sslmode=disable' go test ./... -count=1
```

App（說明見 [`android/README.md`](android/README.md)）：

```
cd android
./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks
```

測試全綠不代表測到了。涉及點數與狀態的改動，建議做 mutation 抽查：故意改壞一處（例如把退點移到交易外、改錯退點金額），確認測試會失敗，再改回來。Gradle 要重跑測試用 `--rerun-tasks`，並確認輸出真的有執行。
