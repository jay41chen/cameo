package com.kimhit.cameo.ui

import com.kimhit.cameo.domain.model.PendingCameo
import java.net.URLEncoder

internal const val ROUTE_CLIPS = "clips"
internal const val ROUTE_POINTS = "points"
internal const val ROUTE_CAMERA = "camera/{clipId}"
internal const val ROUTE_CONFIRM = "confirm/{clipId}?photo={photo}"

internal fun cameraRoute(clipId: String) = "camera/$clipId"

/** [photo] 為相機暫存檔路徑（URL 編碼）；沒帶表示用草稿已有的照片。 */
internal fun confirmRoute(clipId: String, photo: String? = null): String =
    if (photo == null) "confirm/$clipId" else "confirm/$clipId?photo=${urlEncode(photo)}"

/** 未完成列的「繼續」：草稿已有照片直接去確認頁，沒有就先拍照。 */
internal fun continueRoute(pending: PendingCameo): String =
    if (pending.photoPath != null) confirmRoute(pending.clipId) else cameraRoute(pending.clipId)

private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
