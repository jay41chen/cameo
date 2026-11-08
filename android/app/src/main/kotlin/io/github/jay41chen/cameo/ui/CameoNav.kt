package io.github.jay41chen.cameo.ui

import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.PendingCameo
import java.net.URLEncoder

internal const val ROUTE_CLIPS = "clips"
internal const val ROUTE_POINTS = "points"
internal const val ROUTE_MY_CAMEOS = "my_cameos"
internal const val ROUTE_CAMERA = "camera/{clipId}?request={request}"
internal const val ROUTE_CONFIRM = "confirm/{clipId}?photo={photo}"
internal const val ROUTE_VIDEO = "video/{cameoId}"
internal const val ROUTE_CAMEO = "cameo/{clientRequestId}?photo={photo}"

internal fun videoRoute(cameoId: String): String = "video/$cameoId"

/** [request] 不為 null 表示替既有草稿重拍：「使用這張」後回進度畫面而不是確認頁。 */
internal fun cameraRoute(clipId: String, request: String? = null): String =
    if (request == null) "camera/$clipId" else "camera/$clipId?request=${urlEncode(request)}"

/** [photo] 為相機暫存檔路徑（URL 編碼）；沒帶表示用草稿已有的照片。 */
internal fun confirmRoute(clipId: String, photo: String? = null): String =
    if (photo == null) "confirm/$clipId" else "confirm/$clipId?photo=${urlEncode(photo)}"

/** 進度畫面（上傳、送出）。[photo] 為重拍後的相機暫存檔路徑。 */
internal fun cameoRoute(clientRequestId: String, photo: String? = null): String =
    if (photo == null) "cameo/$clientRequestId" else "cameo/$clientRequestId?photo=${urlEncode(photo)}"

/**
 * 未完成列的「繼續」：已建單的草稿進進度畫面（由後端狀態決定下一步）；
 * 尚未建單時，草稿已有照片直接去確認頁，沒有就先拍照。
 */
internal fun continueRoute(pending: PendingCameo): String = when {
    pending.cameoId != null -> cameoRoute(pending.clientRequestId)
    pending.photoPath != null -> confirmRoute(pending.clipId)
    else -> cameraRoute(pending.clipId)
}

/** 已建單的列，訂單還在等上傳或送出時顯示「繼續」。 */
internal fun canContinueOrder(order: CameoOrder?): Boolean =
    order?.cameo?.status == CameoStatus.AWAITING_UPLOAD || order?.cameo?.status == CameoStatus.UPLOADED

private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
