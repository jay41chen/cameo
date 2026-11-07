package com.kimhit.cameo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListTopPolicyTest {
    private fun top(topKey: Any?, firstId: String?, pending: Int = 0) = ListTop(topKey, firstId, pending)

    @Test
    fun `previous 為 null 時不捲`() {
        assertFalse(shouldScrollToTop(null, top("b", "b"), listOf(null, "b")))
    }

    @Test
    fun `第一筆 id 沒變時不捲`() {
        assertFalse(shouldScrollToTop(top("a", "a"), top("a", "a"), listOf("a")))
    }

    @Test
    fun `上一個頂端不可見時不捲`() {
        assertFalse(shouldScrollToTop(top("a", "a"), top("b", "b"), listOf("c", "d")))
    }

    @Test
    fun `current 的 firstId 為 null 時不捲`() {
        assertFalse(shouldScrollToTop(top("a", "a"), top(null, null), listOf("a")))
    }

    @Test
    fun `條件都成立才捲`() {
        assertTrue(shouldScrollToTop(top("a", "a"), top("b", "b"), listOf("a", "c")))
    }

    @Test
    fun `待處理列 0 到 1 且頂端可見會捲`() {
        assertTrue(shouldScrollToTop(top("a", "a", 0), top("pending_header", "a", 1), listOf("a")))
    }

    @Test
    fun `待處理列 1 到 2 且頂端可見會捲`() {
        val h = "pending_header"
        assertTrue(shouldScrollToTop(top(h, "a", 1), top(h, "a", 2), listOf(h, "p1")))
    }

    @Test
    fun `待處理列增加但頂端不可見不捲`() {
        val h = "pending_header"
        assertFalse(shouldScrollToTop(top("a", "a", 0), top(h, "a", 1), listOf("x", "y")))
        assertFalse(shouldScrollToTop(top(h, "a", 1), top(h, "a", 2), listOf("x", "y")))
    }

    @Test
    fun `待處理列減少不捲`() {
        val h = "pending_header"
        assertFalse(shouldScrollToTop(top(h, "a", 2), top(h, "a", 1), listOf(h)))
        assertFalse(shouldScrollToTop(top(h, "a", 1), top("a", "a", 0), listOf(h)))
    }

    @Test
    fun `有待處理列時新訂單出現且標題可見會捲`() {
        val h = "pending_header"
        assertTrue(shouldScrollToTop(top(h, "a", 1), top(h, "b", 1), listOf(h, "p1")))
    }

    @Test
    fun `第一次載入不捲`() {
        assertFalse(shouldScrollToTop(top("a", null, 0), top("a", "b", 0), listOf("a")))
    }
}
