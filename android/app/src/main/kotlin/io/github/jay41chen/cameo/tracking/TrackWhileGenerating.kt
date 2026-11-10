package io.github.jay41chen.cameo.tracking

import io.github.jay41chen.cameo.domain.model.CameoStatus

/**
 * 訂單還在排隊、生成中，或是 App 不認得的狀態（UNKNOWN，視為非終態）才需要背景追蹤。
 * 送出成功時由程序層級的 `SubmissionRunner`（`io.github.jay41chen.cameo.submission`）呼叫 [SubmissionKeepAlive.track]（先 track 再釋放提交保持）。
 */
internal fun needsTracking(status: CameoStatus): Boolean =
    status == CameoStatus.QUEUED || status == CameoStatus.GENERATING || status == CameoStatus.UNKNOWN
