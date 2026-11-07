package com.kimhit.cameo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSectionsTest {
    @Test
    fun `Loading 階段顯示載入`() {
        assertEquals(
            SubmittedSection.Loading,
            submittedSection(PagePhase.Loading, hasSubmitted = false, unfinishedLoaded = true, hasUnfinished = false),
        )
    }

    @Test
    fun `Error 階段顯示錯誤，沒有資料時也不是空狀態`() {
        assertEquals(
            SubmittedSection.Error,
            submittedSection(PagePhase.Error, hasSubmitted = false, unfinishedLoaded = true, hasUnfinished = false),
        )
    }

    @Test
    fun `兩區都載入完且都沒資料是空狀態`() {
        assertEquals(
            SubmittedSection.AllEmpty,
            submittedSection(PagePhase.Idle, hasSubmitted = false, unfinishedLoaded = true, hasUnfinished = false),
        )
    }

    @Test
    fun `只有待處理區有資料是 Content`() {
        assertEquals(
            SubmittedSection.Content,
            submittedSection(PagePhase.Idle, hasSubmitted = false, unfinishedLoaded = true, hasUnfinished = true),
        )
    }

    @Test
    fun `只有已送出區有資料是 Content`() {
        assertEquals(
            SubmittedSection.Content,
            submittedSection(PagePhase.Idle, hasSubmitted = true, unfinishedLoaded = true, hasUnfinished = false),
        )
    }

    @Test
    fun `待處理區還沒載入且沒有資料是 Content`() {
        assertEquals(
            SubmittedSection.Content,
            submittedSection(PagePhase.Idle, hasSubmitted = false, unfinishedLoaded = false, hasUnfinished = false),
        )
    }

    @Test
    fun `pointsLabel 未取得顯示 - 點，0 與一般值照數字`() {
        assertEquals("- 點", pointsLabel(null))
        assertEquals("0 點", pointsLabel(0))
        assertEquals("90 點", pointsLabel(90))
    }
}
