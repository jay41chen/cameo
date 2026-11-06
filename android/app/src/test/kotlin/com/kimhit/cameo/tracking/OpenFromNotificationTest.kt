package com.kimhit.cameo.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFromNotificationTest {
    @Test
    fun `有 extra 非歷史非還原時跳頁`() {
        assertTrue(shouldOpenMyCameos(hasExtra = true, launchedFromHistory = false, restored = false))
    }

    @Test
    fun `沒有 extra 不跳頁`() {
        assertFalse(shouldOpenMyCameos(hasExtra = false, launchedFromHistory = false, restored = false))
    }

    @Test
    fun `從最近使用清單開啟不跳頁`() {
        assertFalse(shouldOpenMyCameos(hasExtra = true, launchedFromHistory = true, restored = false))
    }

    @Test
    fun `Activity 還原不跳頁`() {
        assertFalse(shouldOpenMyCameos(hasExtra = true, launchedFromHistory = false, restored = true))
    }
}
