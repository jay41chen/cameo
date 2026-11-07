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
