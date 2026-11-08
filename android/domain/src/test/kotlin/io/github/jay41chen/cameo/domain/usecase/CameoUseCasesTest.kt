package io.github.jay41chen.cameo.domain.usecase

import io.github.jay41chen.cameo.domain.model.Cameo
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoPage
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

internal class FakePending(val events: MutableList<String> = mutableListOf()) : PendingCameoRepository {
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

    val claims = mutableListOf<String>()

    override suspend fun claimFinishedCameo(cameoId: String): Boolean {
        events += "claim:$cameoId"
        claims += cameoId
        val removed = rows.removeAll { it.cameoId == cameoId }
        return removed
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

    override suspend fun get(clientRequestId: String): PendingCameo? =
        rows.firstOrNull { it.clientRequestId == clientRequestId }

    override suspend fun markUploaded(clientRequestId: String) {
        events += "uploaded:$clientRequestId"
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i >= 0) rows[i] = rows[i].copy(step = PendingStep.SUBMIT, photoPath = null, lastError = null)
    }

    override suspend fun markSubmitted(clientRequestId: String) {
        events += "submitted:$clientRequestId"
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i >= 0) rows[i] = rows[i].copy(step = PendingStep.TRACK, lastError = null)
    }
}

internal const val PHOTO = "/cache/captures/a.jpg"

internal class FakeCameos(
    val events: MutableList<String>,
    val creates: ArrayDeque<() -> DomainResult<CameoOrder>> = ArrayDeque(),
    val gets: MutableMap<String, DomainResult<CameoOrder>> = mutableMapOf(),
) : CameoRepository {
    val keys = mutableListOf<String>()
    val getCalls = mutableListOf<String>()

    /** getCameo 的腳本：有就依序取出，沒有才用 [gets] 的固定值。 */
    val getScript = ArrayDeque<() -> DomainResult<CameoOrder>>()
    val uploads = ArrayDeque<() -> DomainResult<CameoOrder>>()
    val submits = ArrayDeque<() -> DomainResult<CameoOrder>>()

    override suspend fun createCameo(clientRequestId: String, clipId: String): DomainResult<CameoOrder> {
        events += "post:$clientRequestId"
        keys += clientRequestId
        return creates.removeFirst()()
    }

    override suspend fun getCameo(id: String): DomainResult<CameoOrder> {
        getCalls += id
        events += "get"
        return if (getScript.isNotEmpty()) getScript.removeFirst()() else gets.getValue(id)
    }

    override suspend fun uploadSelfie(url: String, photoPath: String): DomainResult<CameoOrder> {
        events += "upload:$url:$photoPath"
        return uploads.removeFirst()()
    }

    override suspend fun submitCameo(cameoId: String): DomainResult<CameoOrder> {
        events += "submit:$cameoId"
        return submits.removeFirst()()
    }

    override suspend fun listCameos(cursor: String?): DomainResult<CameoPage> = error("listCameos not stubbed")
}

internal fun order(status: CameoStatus = CameoStatus.AWAITING_UPLOAD, id: String = "u1", clip: String = "c1") =
    CameoOrder(Cameo(id, Clip(clip, "雨夜重逢", 10), status, "等待上傳", "已占用 10 點"), null)

internal fun fail(retryable: Boolean, code: String? = null) =
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
        assertEquals(CameoStatus.EXPIRED, r.value.order.cameo.status)
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

    private fun row(key: String = "k1", cameoId: String = "u1", step: PendingStep = PendingStep.UPLOAD, photo: String? = "/photos/k1.jpg") =
        PendingCameo(key, "c1", cameoId, step, null, photo)

    @Test
    fun unfinished_submittedStatusesAreNotListedAndDraftKept() = runTest {
        for (st in listOf(CameoStatus.QUEUED, CameoStatus.GENERATING, CameoStatus.SUCCEEDED, CameoStatus.FAILED)) {
            val p = FakePending()
            p.rows += row()
            val c = FakeCameos(p.events, gets = mutableMapOf("u1" to DomainResult.Success(order(st))))
            val out = GetUnfinishedCameosUseCase(p, c)()
            assertEquals("$st", 1, c.getCalls.size)
            assertTrue("$st", out.isEmpty())
            assertEquals("$st", 1, p.rows.size)
            assertEquals("$st", "/photos/k1.jpg", p.rows[0].photoPath)
            assertTrue("$st", p.events.none { it.startsWith("deletePhoto") })
            assertTrue("$st", p.events.none { it.startsWith("delete:") })
        }
    }

    @Test
    fun unfinished_trackWithFetchFailureIsNotListedAndDraftKept() = runTest {
        for (f in listOf(fail(true), fail(false, "UNAUTHORIZED"))) {
            val p = FakePending()
            p.rows += row(step = PendingStep.TRACK)
            val c = FakeCameos(p.events, gets = mutableMapOf("u1" to f))
            val out = GetUnfinishedCameosUseCase(p, c)()
            assertTrue(out.isEmpty())
            assertEquals(1, p.rows.size)
            assertTrue(p.events.none { it.startsWith("delete") })
        }
    }

    @Test
    fun unfinished_trackWithCameoNotFoundDeletesRow() = runTest {
        pending.rows += row(step = PendingStep.TRACK)
        val c = FakeCameos(pending.events, gets = mutableMapOf("u1" to fail(false, "CAMEO_NOT_FOUND")))
        val out = GetUnfinishedCameosUseCase(pending, c)()
        assertTrue(out.isEmpty())
        assertTrue(pending.rows.isEmpty())
    }

    @Test
    fun unfinished_unsubmittedStatusesStillListedAtTrackStep() = runTest {
        for (st in listOf(CameoStatus.AWAITING_UPLOAD, CameoStatus.UPLOADED, CameoStatus.EXPIRED, CameoStatus.UNKNOWN)) {
            val p = FakePending()
            p.rows += row(step = PendingStep.TRACK)
            val c = FakeCameos(p.events, gets = mutableMapOf("u1" to DomainResult.Success(order(st))))
            val out = GetUnfinishedCameosUseCase(p, c)()
            assertEquals("$st", 1, out.size)
            assertEquals("$st", st, out[0].order?.cameo?.status)
            assertEquals("$st", 1, p.rows.size)
        }
    }

    @Test
    fun unfinished_mixedRowsKeepOnlyPendingAndKeepExcludedDrafts() = runTest {
        pending.rows += row("k1", "u1", PendingStep.TRACK)
        pending.rows += row("k2", "u2", PendingStep.UPLOAD)
        pending.rows += row("k3", "u3", PendingStep.TRACK)
        pending.rows += row("k4", "u4", PendingStep.TRACK)
        pending.rows += row("k5", "u5", PendingStep.SUBMIT)
        val c = FakeCameos(
            pending.events,
            gets = mutableMapOf(
                "u1" to DomainResult.Success(order(CameoStatus.SUCCEEDED, "u1")),
                "u2" to DomainResult.Success(order(CameoStatus.AWAITING_UPLOAD, "u2")),
                "u3" to fail(true),
                "u4" to DomainResult.Success(order(CameoStatus.GENERATING, "u4")),
                "u5" to fail(true),
            ),
        )
        val out = GetUnfinishedCameosUseCase(pending, c)()
        assertEquals(listOf("k2", "k5"), out.map { it.pending.clientRequestId })
        assertNull(out[1].order)
        assertEquals(listOf("k1", "k2", "k3", "k4", "k5"), pending.rows.map { it.clientRequestId })
    }

    @Test
    fun unfinished_cancellationAtTrackRowPropagatesAndKeepsDraft() = runTest {
        pending.rows += row("k1", "u1", PendingStep.UPLOAD)
        pending.rows += row("k2", "u2", PendingStep.TRACK)
        val c = FakeCameos(pending.events, gets = mutableMapOf("u1" to DomainResult.Success(order(CameoStatus.AWAITING_UPLOAD))))
        c.getScript += { DomainResult.Success(order(CameoStatus.AWAITING_UPLOAD)) }
        c.getScript += { throw CancellationException("left screen") }
        try {
            GetUnfinishedCameosUseCase(pending, c)()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals(listOf("k1", "k2"), pending.rows.map { it.clientRequestId })
        assertTrue(pending.events.none { it.startsWith("delete") })
    }
}
