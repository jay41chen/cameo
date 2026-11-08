package com.kimhit.cameo.tracking

import com.kimhit.cameo.domain.model.CameoStatus

/**
 * 訂單還在排隊或生成中才需要背景追蹤。
 * 送出成功時由程序層級的 `SubmissionRunner`（`com.kimhit.cameo.submission`）呼叫 [SubmissionKeepAlive.track]（先 track 再釋放提交保持）。
 */
internal fun needsTracking(status: CameoStatus): Boolean =
    status == CameoStatus.QUEUED || status == CameoStatus.GENERATING
