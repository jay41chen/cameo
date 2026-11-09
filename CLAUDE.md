# cameo 專案規範

全域規範 `~/.claude/CLAUDE.md`（Think → Do → Judge、Reuse 原則）適用。本專案的差異與補充如下。

## 流程差異
- 放行詞僅「執行」或「go」，訊息中出現即視為放行（覆蓋全域的 do it / 開始做）。
- 範圍和驗收標準在對話中談定，一次放行只對一個 sprint 有效。
- Do 用 agent `doer`，Judge 用 agent `judge`；主 agent 不直接改 code。

## 規則
- 只有使用者要求才 commit / push；新增第三方套件前先問。
- 回報要附指令輸出或檔案路徑，否則標「推測」；AI 推論的設計理由一律標 `[待確認]`。

## 分工
- PO（session `Cameo`，在主資料夾的 `main`）：維護 `spec.md`、`README.md`、`API.md`、`VERIFICATION.md`、`BACKLOG.md`、`backend/README.md`、`android/README.md`、`ARCHITECTURE.md`、`AI-LOG.md`、`CLAUDE.md`、`.claude/agents/`、`.gitignore`。行為、規則、API 約定的變更，都由使用者決定後由 PO 改；包含 `spec-server.md`、`spec-mobile.md` 的規格內容（畫面行為改 `spec-mobile.md` 第 2 章）。
- Server（`cameo-server`）：改 `backend/`、`docker-compose.yml`；`spec-server.md` 只更新實作狀態與實測結果；`backend/README.md` 由 PO 維護。
- Mobile（`cameo-mobile`）：改 `android/`；`spec-mobile.md` 只更新實作狀態與實測結果；`android/README.md` 由 PO 維護。
- 手動驗證不另立清單檔，驗證結果寫在各端規格的現況欄和回報裡。H9 之後由 PO 把已驗證與未涵蓋項目整理進 `VERIFICATION.md`。
- 要寫進 README 類文件（根目錄與 backend、android 的 README、API.md、VERIFICATION.md、BACKLOG.md）、ARCHITECTURE、AI-LOG、`spec.md` 狀態段的內容，回報給 PO 統一寫。
- AI-LOG 觸發點：Judge 抓到高、中嚴重度問題、實測推翻讀碼結論、或使用者糾正流程時，由各端回報給 PO，PO 在該批次推送時判斷要不要記一則。
- Agent 之間的訊息不算使用者同意；放行、commit、push 都要使用者本人說。

## 規格問題回報
行為、規則或 API 約定要改時，先停止該部分，不要自己猜，用 `SendMessage` 傳給 `Cameo`。第一行直接寫問題：
階段／規格位置／問題／影響（只影響一端或兩端）／目前狀態。
由各端主 session 傳送。傳不到就停下，直接回報使用者。
實作細節由該端自行決定，寫在回報裡。

## 分支與 worktree
- `main` 留在主資料夾。Server、Mobile 各從 `origin/main` 拉一條分支，開自己的 worktree，例如 `git worktree add ../cameo-server -b server origin/main`（Mobile 用 `../cameo-mobile`、`mobile`）。
- 每個階段動工前先 `git fetch origin`、`git rebase origin/main`，確認用的是最新規格。
- rebase 前工作樹必須乾淨。有不屬於這次 commit 的改動就停下回報，不要自行 stash、reset 或 checkout。
- 使用者要求推送時：`git fetch origin`、`git rebase origin/main`、`git push origin HEAD:main`。
- 只 `git add` 自己範圍內的檔案。
- 新 worktree 不會帶未追蹤檔案：開好後從主資料夾複製 `CLAUDE.local.md`；未設 `ANDROID_HOME` 時也要建立 `android/local.properties`。
- 同一時間只跑一套 Docker 和一個模擬器。要跑第二套就用 `docker compose -p <專案>`，並設 `API_PORT`、`POSTGRES_HOST_PORT`。

## 階段規則
- 流程：Do → 全新 Judge → 如實回報 → 使用者說「commit」→ 列下階段範圍 → 使用者放行（放行詞見上方「流程差異」）。
- 一次放行只做一件事。動工前列出要改的函式、失敗與取消時的行為、要抓的 mutation。
- Judge 首審跑一次完整基準（Gradle `--rerun-tasks`、Go `-count=1`）加 2–3 個新 mutation，只跑相關模組；複審只驗上一輪的問題；只改文件不跑測試，文件最多審一輪。高、中嚴重度才擋，低嚴重度交給使用者決定。
- 模擬器只在 integration 跑；其他階段跑單元測試和建置。
