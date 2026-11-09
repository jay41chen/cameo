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

## 文件導覽

| 文件 | 內容 |
|---|---|
| [`backend/README.md`](backend/README.md) | 後端啟動、設定、重設與測試 |
| [`android/README.md`](android/README.md) | App 建置、執行、畫面與背景通知的限制 |
| [`API.md`](API.md) | 後端 API、Debug 異常 API 與異常觸發方式 |
| [`VERIFICATION.md`](VERIFICATION.md) | 已驗證與未涵蓋的項目 |
| [`BACKLOG.md`](BACKLOG.md) | MVP 之後的待做項目 |
| [`spec.md`](spec.md) | 規格 |
| [`spec-server.md`](spec-server.md) | 後端規格與實作狀態 |
| [`spec-mobile.md`](spec-mobile.md) | App 規格與實作狀態 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 架構與取捨 |
| [`AI-LOG.md`](AI-LOG.md) | AI 協作紀錄 |
