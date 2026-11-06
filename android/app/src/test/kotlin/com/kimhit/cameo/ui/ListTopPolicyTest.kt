package com.kimhit.cameo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListTopPolicyTest {
    @Test
    fun `previous 為 null 時不捲`() {
        assertFalse(shouldScrollToTop(null, "b", listOf(null, "b")))
    }

    @Test
    fun `第一筆 id 沒變時不捲`() {
        assertFalse(shouldScrollToTop("a", "a", listOf("a")))
    }

    @Test
    fun `上一個第一筆不可見時不捲`() {
        assertFalse(shouldScrollToTop("a", "b", listOf("c", "d")))
    }

    @Test
    fun `current 為 null 時不捲`() {
        assertFalse(shouldScrollToTop("a", null, listOf("a")))
    }

    @Test
    fun `條件都成立才捲`() {
        assertTrue(shouldScrollToTop("a", "b", listOf("a", "c")))
    }
}
