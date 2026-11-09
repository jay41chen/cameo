#!/usr/bin/env bash
# fault.sh：登記或清除後端的異常情境（/debug/*），免手打 curl。
#
# 用法：
#   fault.sh                      印出這份說明（同 help、-h、--help），不送請求
#   fault.sh <情境名>             送出 backend/faults/<情境名>.json（POST /debug/faults）
#   fault.sh <路徑>.json          送出自訂的 JSON 檔（POST /debug/faults）
#   fault.sh list                 看目前登記的異常（GET /debug/faults）
#   fault.sh clear                清除所有異常（DELETE /debug/faults）
#   fault.sh reset                清空訂單、明細、廠商事件、自拍與異常，餘額回初始值
#                                 （POST /debug/reset，不會再問確認）
#
# 情境（都是登記後「下一次」相關動作才觸發，預設只觸發 1 次）：
#   response-lost-after-commit  下一次「建立訂單」寫入成功後不回應（連線中斷，模擬回應遺失），
#                               用同一個 Idempotency-Key 重試應拿到同一筆
#   upload-interrupted          下一次上傳自拍，內容超過 1024 bytes 後連線中斷（訂單狀態不變）
#   upload-expired              下一次上傳自拍（或送出）時，該訂單的上傳期限視為已到（回 410，訂單 EXPIRED、釋放點數）
#   vendor-failed               下一個生成工作失敗（FAILED / VENDOR_FAILED），退點
#   vendor-duplicate-callback   下一個工作的完成回呼重複送 3 次 SUCCEEDED（驗冪等）
#   vendor-no-callback          下一個工作不回呼，等 GENERATION_TTL（compose 預設 2m）
#                               逾時轉 FAILED / TIMEOUT，退點
#   注意：vendor-* 同時只能登記一種，要換先 clear；其他情境可並存。
#
# 環境變數：
#   API_PORT   後端 port，預設 8080（base URL 為 http://localhost:$API_PORT）
#   API_BASE   完整 base URL，例如 http://localhost:18080；有設就優先於 API_PORT
#
# 範例：
#   ./backend/scripts/fault.sh vendor-failed
#   ./backend/scripts/fault.sh list
#   API_PORT=18080 ./backend/scripts/fault.sh reset
#   API_BASE=http://localhost:18080 ./backend/scripts/fault.sh clear
#   自訂檔案：存成 reject.json，內容為
#     {"name":"vendor_failed","params":{"reason":"CONTENT_REJECTED"}}
#   然後 ./backend/scripts/fault.sh ./reject.json
#
# 需要後端以 DEBUG_API_ENABLED=true 啟動（compose 預設為 true）；關閉時 /debug/* 回 404。
# 送出的 token 固定為 Authorization: Bearer test-token。
# 結束碼：0 成功（HTTP 2xx）；1 用法錯誤、連不上或非 2xx。

set -euo pipefail

FAULTS_DIR="$(cd "$(dirname "$0")/../faults" && pwd)"
TOKEN="test-token"

if [ -n "${API_BASE:-}" ]; then
  BASE="${API_BASE%/}"
else
  BASE="http://localhost:${API_PORT:-8080}"
fi

usage() {
  # 印出檔頭註解：從第 2 行起，連續以 # 開頭的行；遇到第一個非 # 開頭的行就停
  awk 'NR >= 2 { if ($0 !~ /^#/) exit; print }' "$0" | sed 's/^# \{0,1\}//'
}

# 不認得的指令或情境：stderr 錯誤＋情境清單，結束碼 1
unknown_cmd() {
  echo "錯誤：不認得的指令或情境 \"$1\"" >&2
  echo "可用情境：" >&2
  list_scenarios >&2
  echo "其他指令：list、clear、reset、help" >&2
  exit 1
}

list_scenarios() {
  local f
  for f in "$FAULTS_DIR"/*.json; do
    [ -e "$f" ] || continue
    f="$(basename "$f")"
    echo "  ${f%.json}"
  done
}

# send METHOD PATH [JSON檔]
send() {
  local method="$1" path="$2" file="${3:-}"
  local url="$BASE$path" out code rc=0
  echo "$method $url"
  out="$(mktemp)"
  if [ -n "$file" ]; then
    code="$(curl -sS -o "$out" -w '%{http_code}' -X "$method" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      --data-binary @"$file" "$url")" || rc=$?
  else
    code="$(curl -sS -o "$out" -w '%{http_code}' -X "$method" \
      -H "Authorization: Bearer $TOKEN" "$url")" || rc=$?
  fi
  if [ "$rc" -ne 0 ]; then
    rm -f "$out"
    echo "錯誤：curl 失敗（結束碼 ${rc}），連不上 $BASE 或連線中斷。請確認後端已啟動、API_PORT／API_BASE 正確。" >&2
    exit 1
  fi
  echo "HTTP $code"
  if [ -s "$out" ]; then
    cat "$out"
    echo
  fi
  rm -f "$out"
  case "$code" in
    2??) ;;
    *)
      if [ "$code" = "404" ]; then
        echo "提示：/debug/* 回 404，後端可能沒開 DEBUG_API_ENABLED=true。" >&2
      fi
      exit 1
      ;;
  esac
}

cmd="${1:-}"
case "$cmd" in
  ""|help|-h|--help)
    usage
    exit 0
    ;;
  list)
    send GET /debug/faults
    ;;
  clear)
    send DELETE /debug/faults
    ;;
  reset)
    send POST /debug/reset
    ;;
  *.json)
    if [ ! -f "$cmd" ]; then
      echo "錯誤：找不到檔案 $cmd" >&2
      echo "可用情境：" >&2
      list_scenarios >&2
      exit 1
    fi
    send POST /debug/faults "$cmd"
    ;;
  */*|*..*)
    # 情境名不可含 / 或 ..（避免跳出 faults 目錄）；自訂檔請以 .json 結尾
    unknown_cmd "$cmd"
    ;;
  *)
    if [ -f "$FAULTS_DIR/$cmd.json" ]; then
      send POST /debug/faults "$FAULTS_DIR/$cmd.json"
    else
      unknown_cmd "$cmd"
    fi
    ;;
esac
