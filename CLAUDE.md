# cameo 專案規範

全域規範 `~/.claude/CLAUDE.md`（Think → Do → Judge、Reuse 原則）適用。本專案的差異與補充如下。

## 流程差異
- 放行詞僅「執行」或「go」，且整則訊息就是這個詞（覆蓋全域的 do it / 開始做）。
- 範圍和驗收標準在對話中談定，一次放行只對一個 sprint 有效。
- Do 用 agent `doer`，Judge 用 agent `judge`；主 agent 不直接改 code。

## 規則
- 只有使用者要求才 commit / push；新增第三方套件前先問。
- 回報要附指令輸出或檔案路徑，否則標「推測」；AI 推論的設計理由一律標 `[待確認]`。
