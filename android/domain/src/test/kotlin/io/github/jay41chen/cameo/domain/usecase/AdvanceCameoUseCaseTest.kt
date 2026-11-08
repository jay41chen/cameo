package io.github.jay41chen.cameo.domain.usecase

import io.github.jay41chen.cameo.domain.model.Cameo
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.CameoUpload
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val UPLOAD_URL = "/v1/cameos/u1/selfie"
private const val STORED = "/photos/k1.jpg"

private fun orderOf(status: CameoStatus, upload: Boolean = false) = CameoOrder(
    Cameo("u1", Clip("c1", "雨夜重逢", 10), status, "標題", "說明"),
    if (upload) CameoUpload(UPLOAD_URL, "2026-11-07T10:30:00+08:00") else null,
)

private fun ok(status: CameoStatus, upload: Boolean = false) = DomainResult.Success(orderOf(status, upload))

class AdvanceCameoUseCaseTest {
    private val pending = FakePending()
    private val cameos = FakeCameos(pending.events)
    private val advance = AdvanceCameoUseCase(cameos, pending)

    private fun row(photo: String? = STORED, cameoId: String? = "u1", step: PendingStep = PendingStep.UPLOAD) {
        pending.rows += PendingCameo("k1", "c1", cameoId, step, null, photo)
    }

    private fun serverIs(status: CameoStatus, upload: Boolean = status == CameoStatus.AWAITING_UPLOAD) {
        cameos.gets["u1"] = ok(status, upload)
    }

    private fun uploadsReturn(vararg r: DomainResult<CameoOrder>) = r.forEach { v -> cameos.uploads.addLast { v } }
    private fun submitsReturn(vararg r: DomainResult<CameoOrder>) = r.forEach { v -> cameos.submits.addLast { v } }

    private fun failed(r: AdvanceResult) = (r as AdvanceResult.Failed).error

    @Test
    fun awaitingUpload_uploadsThenSubmits_inOrder_withSteps() = runTest {
        row()
        serverIs(CameoStatus.AWAITING_UPLOAD)
        uploadsReturn(ok(CameoStatus.UPLOADED))
        submitsReturn(ok(CameoStatus.QUEUED))
        val steps = mutableListOf<CameoStep>()
        val r = advance("k1") { steps += it }
        assertEquals(CameoStatus.QUEUED, (r as AdvanceResult.Done).order.cameo.status)
        assertEquals(listOf(CameoStep.UPLOADING, CameoStep.SUBMITTING), steps)
        assertEquals(
            listOf("get", "upload:$UPLOAD_URL:$STORED", "uploaded:k1", "submit:u1", "submitted:k1"),
            pending.events,
        )
        assertEquals(PendingStep.TRACK, pending.rows[0].step)
        assertNull(pending.rows[0].photoPath)
    }

    @Test
    fun uploaded_resumesWithoutUploading() = runTest {
        row(photo = null, step = PendingStep.SUBMIT)
        serverIs(CameoStatus.UPLOADED)
        submitsReturn(ok(CameoStatus.QUEUED))
        val steps = mutableListOf<CameoStep>()
        assertTrue(advance("k1") { steps += it } is AdvanceResult.Done)
        assertEquals(listOf("get", "submit:u1", "submitted:k1"), pending.events)
        assertEquals(listOf(CameoStep.SUBMITTING), steps)
    }

    @Test
    fun uploaded_withLocalPhotoLeft_deletesPhotoBeforeSubmit() = runTest {
        row()
        serverIs(CameoStatus.UPLOADED)
        submitsReturn(ok(CameoStatus.QUEUED))
        advance("k1")
        assertEquals(listOf("get", "deletePhoto:k1", "submit:u1", "submitted:k1"), pending.events)
    }

    @Test
    fun queued_neitherUploadsNorSubmits() = runTest {
        row(photo = null)
        serverIs(CameoStatus.QUEUED)
        assertTrue(advance("k1") is AdvanceResult.Done)
        assertEquals(listOf("get", "submitted:k1"), pending.events)
    }

    @Test
    fun laterStatuses_withLocalPhoto_deletePhoto() = runTest {
        for (status in listOf(CameoStatus.QUEUED, CameoStatus.GENERATING, CameoStatus.SUCCEEDED, CameoStatus.FAILED)) {
            val p = FakePending()
            val c = FakeCameos(p.events, gets = mutableMapOf("u1" to ok(status)))
            p.rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null, STORED)
            val r = AdvanceCameoUseCase(c, p)("k1")
            assertEquals("$status", status, (r as AdvanceResult.Done).order.cameo.status)
            assertEquals("$status", listOf("get", "deletePhoto:k1", "submitted:k1"), p.events)
        }
    }

    @Test
    fun unknownStatus_doesNothing() = runTest {
        row()
        serverIs(CameoStatus.UNKNOWN)
        assertTrue(advance("k1") is AdvanceResult.Done)
        assertEquals(listOf("get"), pending.events)
        assertEquals(STORED, pending.rows[0].photoPath)
        assertEquals(PendingStep.UPLOAD, pending.rows[0].step)
    }

    @Test
    fun uploadRetryableFailure_keepsPhoto_thenNextCallResumes() = runTest {
        row()
        serverIs(CameoStatus.AWAITING_UPLOAD)
        uploadsReturn(fail(true), ok(CameoStatus.UPLOADED))
        submitsReturn(ok(CameoStatus.QUEUED))
        val e = failed(advance("k1"))
        assertTrue(e.retryable)
        assertEquals(STORED, pending.rows[0].photoPath)
        assertEquals("訊息", pending.rows[0].lastError)
        assertTrue(pending.events.none { it.startsWith("deletePhoto") || it.startsWith("uploaded") })
        assertTrue(advance("k1") is AdvanceResult.Done)
        assertEquals(PendingStep.TRACK, pending.rows[0].step)
        assertNull(pending.rows[0].lastError)
    }

    @Test
    fun submitResponseLost_nextCallSeesQueued_doesNotResubmit() = runTest {
        row(photo = null, step = PendingStep.SUBMIT)
        serverIs(CameoStatus.UPLOADED)
        submitsReturn(fail(true))
        assertTrue(failed(advance("k1")).retryable)
        serverIs(CameoStatus.QUEUED) // 請求其實到了，後端已送出
        assertTrue(advance("k1") is AdvanceResult.Done)
        assertEquals(1, pending.events.count { it.startsWith("submit:") })
        assertEquals(PendingStep.TRACK, pending.rows[0].step)
    }

    @Test
    fun uploadExpired_recheckSeesExpired_deletesPhoto() = runTest {
        row()
        cameos.getScript.addLast { ok(CameoStatus.AWAITING_UPLOAD, true) }
        cameos.getScript.addLast { ok(CameoStatus.EXPIRED) }
        uploadsReturn(fail(false, "UPLOAD_EXPIRED"))
        val r = advance("k1")
        assertEquals(CameoStatus.EXPIRED, (r as AdvanceResult.Expired).order.cameo.status)
        assertEquals(2, cameos.getCalls.size)
        assertNull(pending.rows[0].photoPath)
        assertEquals(1, pending.rows.size)
    }

    @Test
    fun rejectedPhoto_deletesPhoto_needsPhoto_noNewOrder() = runTest {
        for (code in listOf("INVALID_SELFIE", "SELFIE_TOO_LARGE", PHOTO_MISSING)) {
            val p = FakePending()
            val c = FakeCameos(p.events, gets = mutableMapOf("u1" to ok(CameoStatus.AWAITING_UPLOAD, true)))
            p.rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null, STORED)
            c.uploads.addLast { fail(false, code) }
            val r = AdvanceCameoUseCase(c, p)("k1")
            assertEquals(code, AdvanceResult.NeedPhoto("c1"), r)
            assertNull(code, p.rows[0].photoPath)
            assertEquals(code, 1, p.rows.size)
            assertEquals(code, "u1", p.rows[0].cameoId)
            assertTrue(code, c.keys.isEmpty())
            assertEquals(code, 1, c.getCalls.size) // 不重查
        }
    }

    @Test
    fun invalidState_recheckLeadsToRightNextStep() = runTest {
        row()
        cameos.getScript.addLast { ok(CameoStatus.AWAITING_UPLOAD, true) }
        cameos.getScript.addLast { ok(CameoStatus.UPLOADED) }
        uploadsReturn(fail(false, "INVALID_STATE"))
        submitsReturn(ok(CameoStatus.QUEUED))
        assertTrue(advance("k1") is AdvanceResult.Done)
        assertEquals(2, cameos.getCalls.size)
        assertEquals(1, pending.events.count { it.startsWith("upload:") })
        assertEquals(1, pending.events.count { it.startsWith("submit:") })
    }

    @Test
    fun selfieNotUploaded_onSubmit_recheckSeesQueued_isDone_withoutResubmit() = runTest {
        row(photo = null, step = PendingStep.SUBMIT)
        cameos.getScript.addLast { ok(CameoStatus.UPLOADED) }
        cameos.getScript.addLast { ok(CameoStatus.QUEUED) }
        submitsReturn(fail(false, "SELFIE_NOT_UPLOADED"))
        assertTrue(advance("k1") is AdvanceResult.Done)
        assertEquals(2, cameos.getCalls.size)
        assertEquals(1, pending.events.count { it.startsWith("submit:") })
    }

    @Test
    fun selfieNotUploaded_onSubmit_recheckSeesAwaitingUpload_withoutPhoto_needsPhoto() = runTest {
        row(photo = null, step = PendingStep.SUBMIT)
        cameos.getScript.addLast { ok(CameoStatus.UPLOADED) }
        cameos.getScript.addLast { ok(CameoStatus.AWAITING_UPLOAD, true) }
        submitsReturn(fail(false, "SELFIE_NOT_UPLOADED"))
        assertEquals(AdvanceResult.NeedPhoto("c1"), advance("k1"))
    }

    @Test
    fun uploadSucceedsButServerStaysAwaitingUpload_failsUnexpected_uploadsOnce() = runTest {
        row()
        serverIs(CameoStatus.AWAITING_UPLOAD)
        uploadsReturn(ok(CameoStatus.AWAITING_UPLOAD, upload = true))
        val r = advance("k1")
        assertTrue(r is AdvanceResult.Failed)
        val e = failed(r)
        assertEquals("UNEXPECTED_STATE", e.code)
        assertTrue(e.retryable)
        assertEquals(1, pending.events.count { it.startsWith("upload:") })
    }

    @Test
    fun recheckIsBounded_persistentConflictDoesNotLoop() = runTest {
        row()
        serverIs(CameoStatus.AWAITING_UPLOAD)
        repeat(10) { uploadsReturn(fail(false, "INVALID_STATE")) }
        val e = failed(advance("k1"))
        assertEquals("INVALID_STATE", e.code)
        assertEquals(2, cameos.getCalls.size) // 第一次查 + 最多重查 1 次
        assertEquals(2, pending.events.count { it.startsWith("upload:") })
        assertEquals("訊息", pending.rows[0].lastError)
        assertEquals(STORED, pending.rows[0].photoPath)
    }

    @Test
    fun cameoNotFound_onFirstGet_deletesDraft_gone() = runTest {
        row()
        cameos.gets["u1"] = fail(false, "CAMEO_NOT_FOUND")
        assertEquals(AdvanceResult.Gone, advance("k1"))
        assertTrue(pending.rows.isEmpty())
    }

    @Test
    fun cameoNotFound_onUpload_deletesDraft_gone() = runTest {
        row()
        serverIs(CameoStatus.AWAITING_UPLOAD)
        uploadsReturn(fail(false, "CAMEO_NOT_FOUND"))
        assertEquals(AdvanceResult.Gone, advance("k1"))
        assertTrue(pending.rows.isEmpty())
    }

    @Test
    fun unauthorized_failsNotRetryable_keepsDraftAndPhoto() = runTest {
        row()
        serverIs(CameoStatus.AWAITING_UPLOAD)
        uploadsReturn(fail(false, "UNAUTHORIZED"))
        val e = failed(advance("k1"))
        assertEquals(false, e.retryable)
        assertEquals("UNAUTHORIZED", e.code)
        assertEquals(1, pending.rows.size)
        assertEquals(STORED, pending.rows[0].photoPath)
    }

    @Test
    fun firstGetFailure_isRecordedAndKept() = runTest {
        row()
        cameos.gets["u1"] = fail(true)
        assertTrue(failed(advance("k1")).retryable)
        assertEquals("訊息", pending.rows[0].lastError)
        assertEquals(STORED, pending.rows[0].photoPath)
    }

    @Test
    fun noCameoId_isNotCreated_noApiCall() = runTest {
        row(cameoId = null, step = PendingStep.CREATE)
        assertEquals(AdvanceResult.NotCreated("c1"), advance("k1"))
        assertTrue(cameos.getCalls.isEmpty())
    }

    @Test
    fun awaitingUploadWithoutPhoto_needsPhoto_noUpload() = runTest {
        row(photo = null)
        serverIs(CameoStatus.AWAITING_UPLOAD)
        assertEquals(AdvanceResult.NeedPhoto("c1"), advance("k1"))
        assertTrue(pending.events.none { it.startsWith("upload:") })
    }

    @Test
    fun awaitingUploadWithoutUploadInfo_needsPhoto() = runTest {
        row()
        serverIs(CameoStatus.AWAITING_UPLOAD, upload = false)
        assertEquals(AdvanceResult.NeedPhoto("c1"), advance("k1"))
    }

    @Test
    fun missingRow_isGone() = runTest {
        assertEquals(AdvanceResult.Gone, advance("nope"))
        assertTrue(cameos.getCalls.isEmpty())
    }

    @Test
    fun expiredOnFirstGet_deletesPhoto_keepsRow() = runTest {
        row()
        serverIs(CameoStatus.EXPIRED)
        assertTrue(advance("k1") is AdvanceResult.Expired)
        assertNull(pending.rows[0].photoPath)
        assertEquals(1, pending.rows.size)
    }

    @Test
    fun backendKeepsReturningUploaded_afterSubmit_doesNotLoopForever() = runTest {
        row(photo = null)
        serverIs(CameoStatus.UPLOADED)
        repeat(5) { submitsReturn(ok(CameoStatus.UPLOADED)) }
        assertTrue(advance("k1") is AdvanceResult.Failed)
        assertEquals(1, pending.events.count { it.startsWith("submit:") })
    }

    @Test
    fun cancellation_propagates_keepsPhotoAndRow() = runTest {
        row()
        serverIs(CameoStatus.AWAITING_UPLOAD)
        cameos.uploads.addLast { throw CancellationException("left screen") }
        try {
            advance("k1")
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals(1, pending.rows.size)
        assertEquals(STORED, pending.rows[0].photoPath)
        assertNull(pending.rows[0].lastError)
        assertTrue(pending.events.none { it.startsWith("deletePhoto") || it.startsWith("error") })
    }

    @Test
    fun attachPhoto_usecase_results() = runTest {
        row(photo = null)
        val attach = AttachCameoPhotoUseCase(pending)
        assertEquals(AttachResult.Attached, attach("k1", "/cache/a.jpg"))
        pending.missingSources += "/cache/gone.jpg"
        assertEquals(AttachResult.NeedPhoto("c1"), attach("k1", "/cache/gone.jpg"))
        assertEquals(AttachResult.Gone, attach("ghost", "/cache/a.jpg"))
    }

    @Test
    fun attachPhoto_rowDeletedDuringAttach_returnsGone() = runTest {
        row(photo = null)
        // 搬檔期間列被認領刪除：attachPhoto 回 false，但 get 已查不到列
        val racing = object : PendingCameoRepository by pending {
            override suspend fun attachPhoto(clientRequestId: String, sourcePath: String): Boolean {
                pending.rows.clear()
                return false
            }
        }
        assertEquals(AttachResult.Gone, AttachCameoPhotoUseCase(racing)("k1", "/cache/a.jpg"))
    }
}
