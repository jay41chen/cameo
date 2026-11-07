package com.kimhit.cameo.ui

/** 列表狀態的快照。[topKey] 是列表實際第一個項目的 key；[firstId] 是第一筆已送出；[pendingCount] 是待處理列數。 */
internal data class ListTop(val topKey: Any?, val firstId: String?, val pendingCount: Int)

/**
 * 回前景或資料更新後，列表是否要自動捲回頂端。
 *
 * 只在「上一個頂端項目還看得到」時才捲（列表頂端可見，只露出一部分也算），
 * 且有新訂單（[ListTop.firstId] 變了）或待處理列增加時才捲。
 * 這裡查上一個頂端 [ListTop.topKey] 是否可見，不查 index：
 * 新項目插入後 LazyColumn 會改 index，副作用與量測的先後不一定；[待確認]
 * 上一個頂端是否可見，在一般情況下量測前後答案相同（在畫面上的會被錨定而仍可見，
 * 不在畫面上的前後都不可見）。例外：兩次查詢之間新增超過一頁（目前 20 筆）時，
 * 上一個第一筆不在新資料內，此時可能不捲。[待確認]
 *
 * 時序：`animateScrollToItem(0)` 要在這次資料更新排版之後才有效；若在排版前執行，
 * 舊排版裡 index 0 可見、距離 0，會變成空動作，之後錨定把列表留在原項目。
 * 目前依賴 effect 在排版後才跑。[待確認]
 */
internal fun shouldScrollToTop(previous: ListTop?, current: ListTop, visibleKeys: Collection<Any?>): Boolean {
    if (previous == null || previous.topKey == null || previous.topKey !in visibleKeys) return false
    val newOrder = previous.firstId != null && current.firstId != null && current.firstId != previous.firstId
    return newOrder || current.pendingCount > previous.pendingCount
}
