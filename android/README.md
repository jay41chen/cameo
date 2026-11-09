# Android App

需求：JDK 21（`android/gradle/gradle-daemon-jvm.properties` 要求 Gradle daemon 用 JDK 21，編譯用的 toolchain 也是 21；本機沒有時 Gradle 會透過 foojay 自動下載，需連網）、Android SDK 37（compileSdk 需要；targetSdk 為 36）。SDK 位置要設 `ANDROID_HOME` 環境變數，或在 `android/local.properties` 寫 `sdk.dir=/path/to/sdk`（此檔不進 git）。

1. 先啟動後端（見 [`../backend/README.md`](../backend/README.md)）。
2. 用 Android Studio 開啟 `android/` 資料夾並執行 `app`；或用 Gradle：

```
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.jay41chen.cameo/.MainActivity
```

- 先等模擬器開機完成（`adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'`）再安裝。要從頭驗收時，先 `adb uninstall io.github.jay41chen.cameo` 或用全新 AVD，否則已授予的權限和舊資料會保留，第一次按「＋客串」不會跳權限對話框。
- App 連 `http://10.0.2.2:8080`（模擬器看到的宿主機），測試 token 為 `test-token`，兩者由 `:app` 的 build config 提供；只有 debug build 允許對 `10.0.2.2` 明文 HTTP。`adb reverse` 不是必要步驟。
- 模擬器相機：AVD 啟動時加 `-camera-front emulated -camera-back virtualscene`（例如 `$ANDROID_HOME/emulator/emulator -avd Pixel_10_Pro -camera-front emulated -camera-back virtualscene`）；驗證時即用此參數，未測不加參數時的相機預覽 `[待確認]`。App 預設用前鏡頭，沒有前鏡頭用後鏡頭。

## 畫面

以下行為已在 API 37 模擬器實測，部分情境未涵蓋，見 [`../VERIFICATION.md`](../VERIFICATION.md)；完整規則見 [`../spec-mobile.md`](../spec-mobile.md) 第 2 章。

- 首頁「我的客串」：上方待處理區、下方已送出區；右上點數按鈕進點數明細，右下 FAB「＋客串」進選片段；已送出區分頁載入；待處理列在等待上傳或送出中時有「繼續」。
- 首頁更新：停在首頁時，可視的排隊中、生成中項目每 3 秒自動更新；回前景時刷新；一般情況位置不變，看得到列表頂端且有新訂單或待處理列增加時捲回頂端，列表頂端不在畫面上時不捲動、底部顯示「回到頂端」；第一頁和現有列表完全沒有重疊時整份取代，位置可能改變。
- 選片段：頂端顯示今日額度，額度用完時點片段會跳提示。
- 相機與預覽：點片段先進相機（被拒絕時顯示說明與「前往設定」），拍照後預覽、可重拍；確認支付顯示占用點數，建單成功後進進度頁。
- 進度頁：按確認支付就啟動前景服務，常駐通知「處理中，完成後會通知你」；離開頁面或 App，提交仍會跑完，完成時發通知；可重試的失敗有「重試」，照片有問題時要求重新拍照。
- 影片頁：已完成的列可點進，不自動播放，點影片出現控制列後可播放、暫停；分享開系統分享選單，取消分享前先確認。
- 點數明細：顯示可用與占用點數，明細分頁載入，回前景時保留閱讀位置。
- 權限：按 FAB 先檢查通知和相機權限，被拒絕時說明原因，系統不能再詢問時提供「前往設定」。

## 背景通知的限制

- 盡力在 App 位於背景時通知，無法百分之百保證；前景服務在強制停止或程序被殺時會消失，回到 App 會查詢並補發。
- 上傳或送出被中斷時，回到 App 接續（進度頁自動接續、失敗按「重試」，待處理列按「繼續」），只扣一次。
- 一直沒回來時不會生成、不會扣款，點數占到上傳期限才釋放（到期釋放未實測）。
- 例外：送出請求已到後端、只是 App 沒收到回應時，訂單仍會生成並扣款（API 37 模擬器實測 1 次）。
- 廠牌省電、低記憶體與真機都未測；完整說明見 [`spec-mobile.md` 第 4 章](../spec-mobile.md)。

## 單元測試

單元測試（不需啟動 App 或後端）：

```
cd android
./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks
```
