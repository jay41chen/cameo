package com.kimhit.cameo.ui

/** 已送出區要顯示什麼。待處理區不受影響，永遠照常顯示。 */
internal enum class SubmittedSection { Loading, Error, AllEmpty, Content }

internal fun submittedSection(
    phase: PagePhase,
    hasSubmitted: Boolean,
    unfinishedLoaded: Boolean,
    hasUnfinished: Boolean,
): SubmittedSection = when {
    phase == PagePhase.Loading -> SubmittedSection.Loading
    phase == PagePhase.Error -> SubmittedSection.Error
    !hasSubmitted && unfinishedLoaded && !hasUnfinished -> SubmittedSection.AllEmpty
    else -> SubmittedSection.Content
}

/** 首頁右上的點數文字。還沒取得或第一次就失敗時不區分，都顯示 `- 點`。 */
internal fun pointsLabel(available: Int?): String = if (available == null) "- 點" else "$available 點"
