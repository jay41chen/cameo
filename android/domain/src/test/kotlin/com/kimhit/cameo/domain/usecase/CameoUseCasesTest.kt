package com.kimhit.cameo.domain.usecase

import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.PendingStep
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.repository.PendingCameoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakePending(val events: MutableList<String> = mutableListOf()) : PendingCameoRepository {
    val rows = mutableListOf<PendingCameo>()
    private var next = 1

    override suspend fun getOrCreateDraft(clipId: String): PendingCameo {
        events += "draft:$clipId"
        rows.firstOrNull { it.clipId == clipId && it.step == PendingStep.CREATE }?.let { return it }
        return PendingCameo("key-${next++}", clipId, null, PendingStep.CREATE, null).also { rows += it }
    }

    override suspend fun markCreated(clientRequestId: String, cameoId: String) {
        events += "created:$clientRequestId"
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i >= 0) rows[i] = rows[i].copy(cameoId = cameoId, step = PendingStep.UPLOAD, lastError = null)
    }

    override suspend fun recordError(clientRequestId: String, message: String?) {
        events += "error:$clientRequestId"
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i >= 0) rows[i] = rows[i].copy(lastError = message)
    }

    override suspend fun delete(clientRequestId: String) {
        events += "delete:$clientRequestId"
        rows.removeAll { it.clientRequestId == clientRequestId }
    }

    override suspend fun deletePhoto(clientRequestId: String) {
        events += "deletePhoto:$clientRequestId"
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i >= 0) rows[i] = rows[i].copy(photoPath = null)
    }

    /** 這些來源路徑視為不存在，attachPhoto 回 false。 */
    val missingSources = mutableSetOf<String>()

    override suspend fun attachPhoto(clientRequestId: String, sourcePath: String): Boolean {
        events += "attach:$clientRequestId"
        if (sourcePath in missingSources) return false
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i < 0) return false
        rows[i] = rows[i].copy(photoPath = "/photos/$clientRequestId.jpg")
        return true
    }

    override suspend fun list(): List<PendingCameo> = rows.toList()
}

private const val PHOTO = "/cache/captures/a.jpg"

private class FakeCameos(
    val events: MutableList<String>,
    val creates: ArrayDeque<() -> DomainResult<CameoOrder>> = ArrayDeque(),
    val gets: MutableMap<String, DomainResult<CameoOrder>> = mutableMapOf(),
) : CameoRepository {
    val keys = mutableListOf<String>()
    val getCalls = mutableListOf<String>()

    override suspend fun createCameo(clientRequestId: String, clipId: String): DomainResult<CameoOrder> {
        events += "post:$clientRequestId"
        keys += clientRequestId
        return creates.removeFirst()()
    }

    override suspend fun getCameo(id: String): DomainResult<CameoOrder> {
        getCalls += id
        return gets.getValue(id)
    }
}

private fun order(status: CameoStatus = CameoStatus.AWAITING_UPLOAD, id: String = "u1", clip: String = "c1") =
    CameoOrder(Cameo(id, Clip(clip, "雨夜重逢", 10), status, "等待上傳", "已占用 10 點"), null)

private fun fail(retryable: Boolean, code: String? = null) =
    DomainResult.Failure(DomainError("訊息", retryable, code))

class CameoUseCasesTest {
    private val pending = FakePending()
    private fun cameos(vararg r: () -> DomainResult<CameoOrder>) =
        FakeCameos(pending.events, ArrayDeque(r.toList()))

    private fun confirm(c: FakeCameos) = ConfirmCameoUseCase(c, pending)

    @Test
    fun draftIsWrittenBeforePost() = runTest {
        val c = cameos({ DomainResult.Success(order()) })
        confirm(c)("c1", PHOTO)
        assertEquals(listOf("draft:c1", "attach:key-1", "post:key-1", "created:key-1"), pending.events)
    }

    @Test
    fun retryableFailureKeepsDraftAndSecondAttemptReusesKey() = runTest {
        val c = cameos({ fail(true) }, { DomainResult.Success(order()) })
        val uc = confirm(c)
        uc("c1", PHOTO)
        assertEquals(1, pending.rows.size)
        assertEquals("訊息", pending.rows[0].lastError)
        uc("c1", PHOTO)
        assertEquals(listOf("key-1", "key-1"), c.keys)
    }

    @Test
    fun lostResponseThenRetryReusesKey() = runTest {
        // 網路例外在 data 層表現為 retryable 且無 message
        val c = cameos({ DomainResult.Failure(DomainError(null, true)) }, { DomainResult.Success(order()) })
        val uc = confirm(c)
        uc("c1", PHOTO)
        uc("c1", PHOTO)
        assertEquals(listOf("key-1", "key-1"), c.keys)
    }

    @Test
    fun afterSuccessSameClipGetsNewKey() = runTest {
        val c = cameos({ DomainResult.Success(order()) }, { DomainResult.Success(order(id = "u2")) })
        val uc = confirm(c)
        uc("c1", PHOTO)
        assertEquals(PendingStep.UPLOAD, pending.rows[0].step)
        assertEquals("u1", pending.rows[0].cameoId)
        uc("c1", PHOTO)
        assertEquals(listOf("key-1", "key-2"), c.keys)
    }

    @Test
    fun noOrderCodesDeleteDraftAndNextUsesNewKey() = runTest {
        val codes = listOf("INSUFFICIENT_POINTS", "DAILY_LIMIT_REACHED", "CLIP_NOT_FOUND")
        val c = cameos(*codes.map { code -> { fail(false, code) } }.toTypedArray())
        val uc = confirm(c)
        for (code in codes) {
            val r = uc("c1", PHOTO)
            assertTrue(code, r is DomainResult.Failure)
            assertTrue(code, pending.rows.isEmpty())
        }
        assertEquals(listOf("key-1", "key-2", "key-3"), c.keys)
    }

    @Test
    fun otherNonRetryableFailuresKeepDraftRecordErrorAndReuseKey() = runTest {
        for (code in listOf("UNAUTHORIZED", "IDEMPOTENCY_CONFLICT", null)) {
            val p = FakePending()
            val c = FakeCameos(p.events, ArrayDeque(listOf({ fail(false, code) }, { fail(false, code) })))
            val uc = ConfirmCameoUseCase(c, p)
            uc("c1", PHOTO)
            assertEquals("$code", 1, p.rows.size)
            assertEquals("$code", "訊息", p.rows[0].lastError)
            uc("c1", PHOTO)
            assertEquals("$code", listOf("key-1", "key-1"), c.keys)
            assertEquals("$code", 1, p.rows.size)
        }
    }

    @Test
    fun lostResponseThenUnauthorizedThenConfirmKeepsSameKey() = runTest {
        val c = cameos(
            { fail(true) },
            { fail(false, "UNAUTHORIZED") },
            { DomainResult.Success(order()) },
        )
        val uc = confirm(c)
        uc("c1", PHOTO)
        uc("c1", PHOTO)
        uc("c1", PHOTO)
        assertEquals(listOf("key-1", "key-1", "key-1"), c.keys)
    }

    @Test
    fun expiredResponseDeletesDraftAndNextUsesNewKey() = runTest {
        val c = cameos(
            { DomainResult.Success(order(CameoStatus.EXPIRED)) },
            { DomainResult.Success(order()) },
        )
        val uc = confirm(c)
        val r = uc("c1", PHOTO) as DomainResult.Success
        assertEquals(CameoStatus.EXPIRED, r.value.cameo.status)
        assertTrue(pending.rows.isEmpty())
        uc("c1", PHOTO)
        assertEquals(listOf("key-1", "key-2"), c.keys)
    }

    @Test
    fun differentClipsUseDifferentKeys() = runTest {
        val c = cameos({ fail(true) }, { fail(true) })
        val uc = confirm(c)
        uc("c1", PHOTO)
        uc("c2", PHOTO)
        assertNotEquals(c.keys[0], c.keys[1])
        assertEquals(2, pending.rows.size)
    }

    @Test
    fun attachFailureReturnsPhotoMissing_noPost_draftKept() = runTest {
        val c = cameos({ DomainResult.Success(order()) })
        pending.missingSources += PHOTO
        val r = confirm(c)("c1", PHOTO) as DomainResult.Failure
        assertEquals(PHOTO_MISSING, r.error.code)
        assertNull(r.error.message)
        assertEquals(false, r.error.retryable)
        assertTrue(c.keys.isEmpty())
        assertEquals(listOf("draft:c1", "attach:key-1"), pending.events)
        assertEquals(1, pending.rows.size)
        assertNull(pending.rows[0].photoPath)
    }

    @Test
    fun noSourceAndNoDraftPhoto_returnsPhotoMissing_noPost_noAttach() = runTest {
        val c = cameos({ DomainResult.Success(order()) })
        val r = confirm(c)("c1", null) as DomainResult.Failure
        assertEquals(PHOTO_MISSING, r.error.code)
        assertTrue(c.keys.isEmpty())
        assertEquals(listOf("draft:c1"), pending.events)
        assertEquals(1, pending.rows.size)
    }

    @Test
    fun noSourceButDraftHasPhoto_postsWithoutAttach() = runTest {
        val c = cameos({ fail(true) }, { DomainResult.Success(order()) })
        val uc = confirm(c)
        uc("c1", PHOTO) // 照片已附上，POST 失敗
        pending.events.clear()
        uc("c1", null)
        assertEquals(listOf("draft:c1", "post:key-1", "created:key-1"), pending.events)
        assertEquals(listOf("key-1", "key-1"), c.keys)
    }

    @Test
    fun retryWithNewPhotoKeepsSameKey() = runTest {
        val c = cameos({ fail(true) }, { DomainResult.Success(order()) })
        val uc = confirm(c)
        uc("c1", PHOTO)
        uc("c1", "/cache/captures/b.jpg")
        assertEquals(listOf("key-1", "key-1"), c.keys)
    }

    @Test
    fun photoMissingThenRetakeKeepsSameKeyAndPosts() = runTest {
        val c = cameos({ DomainResult.Success(order()) })
        val uc = confirm(c)
        pending.missingSources += PHOTO
        uc("c1", PHOTO)
        uc("c1", "/cache/captures/b.jpg")
        assertEquals(listOf("key-1"), c.keys)
        assertEquals(PendingStep.UPLOAD, pending.rows[0].step)
    }

    @Test
    fun cancellationPropagatesAndKeepsDraft() = runTest {
        val c = cameos({ throw CancellationException("left screen") })
        try {
            confirm(c)("c1", PHOTO)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals(1, pending.rows.size)
        assertEquals(listOf("draft:c1", "attach:key-1", "post:key-1"), pending.events)
    }

    @Test
    fun unfinished_cameoNotFoundDeletesRow() = runTest {
        pending.rows += PendingCameo("k1", "c1", "gone", PendingStep.UPLOAD, null)
        val c = FakeCameos(pending.events, gets = mutableMapOf("gone" to fail(false, "CAMEO_NOT_FOUND")))
        val out = GetUnfinishedCameosUseCase(pending, c)()
        assertTrue(out.isEmpty())
        assertTrue(pending.rows.isEmpty())
    }

    @Test
    fun unfinished_retryableFailureKeepsRowWithNullOrder() = runTest {
        pending.rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null)
        val c = FakeCameos(pending.events, gets = mutableMapOf("u1" to fail(true)))
        val out = GetUnfinishedCameosUseCase(pending, c)()
        assertEquals(1, out.size)
        assertNull(out[0].order)
        assertEquals(1, pending.rows.size)
    }

    @Test
    fun unfinished_successAttachesOrder_andNoCameoIdSkipsApi() = runTest {
        pending.rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null)
        pending.rows += PendingCameo("k2", "c2", null, PendingStep.CREATE, "err")
        val o = order()
        val c = FakeCameos(pending.events, gets = mutableMapOf("u1" to DomainResult.Success(o)))
        val out = GetUnfinishedCameosUseCase(pending, c)()
        assertEquals(listOf("u1"), c.getCalls)
        assertEquals(o, out[0].order)
        assertNull(out[1].order)
    }

    @Test
    fun unfinished_nonRetryableUnauthorizedKeepsRowWithNullOrder() = runTest {
        pending.rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null)
        val c = FakeCameos(pending.events, gets = mutableMapOf("u1" to fail(false, "UNAUTHORIZED")))
        val out = GetUnfinishedCameosUseCase(pending, c)()
        assertEquals(1, out.size)
        assertNull(out[0].order)
        assertEquals(1, pending.rows.size)
    }

    @Test
    fun unfinished_expiredDeletesPhotoButKeepsRow() = runTest {
        pending.rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null, "/photos/k1.jpg")
        val c = FakeCameos(pending.events, gets = mutableMapOf("u1" to DomainResult.Success(order(CameoStatus.EXPIRED))))
        val out = GetUnfinishedCameosUseCase(pending, c)()
        assertTrue("deletePhoto:k1" in pending.events)
        assertEquals(1, pending.rows.size)
        assertNull(pending.rows[0].photoPath)
        assertEquals(1, out.size)
        assertNull(out[0].pending.photoPath)
        assertEquals(CameoStatus.EXPIRED, out[0].order?.cameo?.status)
    }

    @Test
    fun unfinished_nonExpiredKeepsPhoto() = runTest {
        pending.rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null, "/photos/k1.jpg")
        val c = FakeCameos(pending.events, gets = mutableMapOf("u1" to DomainResult.Success(order(CameoStatus.AWAITING_UPLOAD))))
        val out = GetUnfinishedCameosUseCase(pending, c)()
        assertTrue(pending.events.none { it.startsWith("deletePhoto") })
        assertEquals("/photos/k1.jpg", pending.rows[0].photoPath)
        assertEquals("/photos/k1.jpg", out[0].pending.photoPath)
    }

    @Test
    fun unfinished_fetchFailureDoesNotDeletePhoto() = runTest {
        pending.rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null, "/photos/k1.jpg")
        val c = FakeCameos(pending.events, gets = mutableMapOf("u1" to fail(true)))
        val out = GetUnfinishedCameosUseCase(pending, c)()
        assertNull(out[0].order)
        assertTrue(pending.events.none { it.startsWith("deletePhoto") })
        assertEquals("/photos/k1.jpg", pending.rows[0].photoPath)
    }
}
