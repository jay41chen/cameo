package io.github.jay41chen.cameo.ui

import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CameraCatchingTest {
    @Test
    fun success_returnsValue() = runTest {
        assertEquals("ok", cameraCatching { "ok" })
    }

    @Test
    fun ordinaryExceptions_becomeNull() = runTest {
        assertNull(cameraCatching<String> { throw IllegalStateException("bind failed") })
        assertNull(cameraCatching<String> { throw IllegalArgumentException("no camera") })
        assertNull(cameraCatching<String> { throw java.util.concurrent.ExecutionException(RuntimeException()) })
    }

    @Test
    fun cancellation_isRethrown() = runTest {
        try {
            cameraCatching<String> { throw CancellationException("cancelled") }
            fail("should rethrow")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }
}

class PendingNoOrderTextTest {
    private fun p(photo: String?, err: String?) =
        PendingCameo("k", "c", null, PendingStep.CREATE, err, photo)

    @Test
    fun noPhotoNoError_saysPhotoMissing() = assertEquals("照片無法使用，請重新拍照", pendingNoOrderText(p(null, null)))

    @Test
    fun error_wins() {
        assertEquals("網路錯誤", pendingNoOrderText(p(null, "網路錯誤")))
        assertEquals("網路錯誤", pendingNoOrderText(p("/p.jpg", "網路錯誤")))
    }

    @Test
    fun photoNoError_saysUnconfirmed() =
        assertEquals("尚未確認是否建立成功", pendingNoOrderText(p("/p.jpg", null)))
}
