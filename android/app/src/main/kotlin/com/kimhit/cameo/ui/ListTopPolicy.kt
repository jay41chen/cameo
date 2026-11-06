package com.kimhit.cameo.ui

/**
 * 回前景或資料更新後，列表是否要自動捲到新的第一筆。
 *
 * 只在「上一個第一筆還看得到」時才捲。這裡查上一個第一筆的 key 是否可見，不查 index：
 * 新訂單插入後 LazyColumn 會改 index，副作用與量測的先後不一定；[待確認]
 * 上一個第一筆是否可見，在一般情況下量測前後答案相同（在畫面上的會被錨定而仍可見，
 * 不在畫面上的前後都不可見）。例外：兩次查詢之間新增超過一頁（目前 20 筆）時，
 * 上一個第一筆不在新資料內，此時可能不捲。[待確認]
 */
internal fun shouldScrollToTop(
    previousFirstId: String?,
    currentFirstId: String?,
    visibleKeys: Collection<Any?>,
): Boolean =
    previousFirstId != null &&
        currentFirstId != null &&
        currentFirstId != previousFirstId &&
        previousFirstId in visibleKeys
