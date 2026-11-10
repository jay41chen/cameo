package io.github.jay41chen.cameo.domain.usecase

import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.DomainResult

/** 背景追蹤一筆訂單時，每次查詢後的下一步。 */
enum class TrackDecision {
    /** 繼續查。 */
    CONTINUE,

    /** 訂單到結束狀態（`SUCCEEDED`、`FAILED`）：發完成通知並結束追蹤。 */
    NOTIFY_AND_STOP,

    /** 訂單不會再有完成結果（例如 `EXPIRED`），或到達追蹤上限：不發通知並結束追蹤。 */
    STOP,
}

/**
 * 追蹤上限：35 分鐘，單位毫秒。來源：後端生成期限 `GENERATION_TTL` 預設 30 分鐘
 * （`docker-compose.yml`），再多 5 分鐘讓逾時轉失敗的掃描與退點有時間完成。
 */
const val TRACK_LIMIT_MS: Long = 35L * 60 * 1000

/**
 * 依一次查詢結果與已追蹤時間 [elapsedMs] 決定下一步。純函式，不碰時鐘。
 * - `SUCCEEDED`、`FAILED`：[TrackDecision.NOTIFY_AND_STOP]（即使剛好超過上限，結果已到就通知）。
 * - `QUEUED`、`GENERATING`、`UNKNOWN`（App 不認得的狀態，視為非終態）與查詢失敗：[TrackDecision.CONTINUE]，但 [elapsedMs] 已達 [TRACK_LIMIT_MS] 就 [TrackDecision.STOP]。
 * - 其他狀態（`EXPIRED`、`AWAITING_UPLOAD`、`UPLOADED`）：[TrackDecision.STOP]。
 */
fun decideTracking(result: DomainResult<CameoOrder>, elapsedMs: Long): TrackDecision {
    val status = (result as? DomainResult.Success)?.value?.cameo?.status
    return when (status) {
        CameoStatus.SUCCEEDED, CameoStatus.FAILED -> TrackDecision.NOTIFY_AND_STOP
        null, CameoStatus.QUEUED, CameoStatus.GENERATING, CameoStatus.UNKNOWN ->
            if (elapsedMs >= TRACK_LIMIT_MS) TrackDecision.STOP else TrackDecision.CONTINUE
        CameoStatus.EXPIRED, CameoStatus.AWAITING_UPLOAD, CameoStatus.UPLOADED ->
            TrackDecision.STOP
    }
}
