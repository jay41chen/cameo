package io.github.jay41chen.cameo.submission

import io.github.jay41chen.cameo.domain.model.Cameo
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.CameoUpload
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.usecase.AdvanceCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.AdvanceResult
import io.github.jay41chen.cameo.domain.usecase.AttachCameoPhotoUseCase
import io.github.jay41chen.cameo.domain.usecase.CameoStep
import io.github.jay41chen.cameo.domain.usecase.ConfirmCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.PHOTO_MISSING
import io.github.jay41chen.cameo.tracking.SubmissionKeepAlive
import io.github.jay41chen.cameo.ui.FakeCameoRepo
import io.github.jay41chen.cameo.ui.FakeKeepAlive
import io.github.jay41chen.cameo.ui.FakePendingRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CAMERA = "/cache/captures/a.jpg"

private fun orderWith(status: CameoStatus, upload: Boolean = false, id: String = "u1") = CameoOrder(
    Cameo(id, Clip("c1", "雨夜重逢", 10), status, "標題", "說明"),
    if (upload) CameoUpload("/v1/cameos/$id/selfie", "2026-11-07T10:30:00+08:00") else null,
)

private fun ok(status: CameoStatus, upload: Boolean = false, id: String = "u1") =
    DomainResult.Success(orderWith(status, upload, id))

private fun fail(retryable: Boolean, code: String? = null) =
    DomainResult.Failure(DomainError(message = "壞了", retryable = retryable, code = code))

/** 可卡住 createCameo、uploadSelfie；測試觀察進行中的狀態與不重入。 */
private class Gated(val delegate: FakeCameoRepo) : CameoRepository by delegate {
    var createGate: CompletableDeferred<Unit>? = null
    var uploadGate: CompletableDeferred<Unit>? = null
    var submitGate: CompletableDeferred<Unit>? = null
    var uploadCalls = 0

    override suspend fun submitCameo(cameoId: String): DomainResult<CameoOrder> {
        submitGate?.await()
        return delegate.submitCameo(cameoId)
    }

    override suspend fun createCameo(clientRequestId: String, clipId: String): DomainResult<CameoOrder> {
        createGate?.await()
        return delegate.createCameo(clientRequestId, clipId)
    }

    override suspend fun uploadSelfie(url: String, photoPath: String): DomainResult<CameoOrder> {
        uploadCalls++
        uploadGate?.await()
        return delegate.uploadSelfie(url, photoPath)
    }
}

/** 在 track 當下取樣 advanceState，驗證 track 早於 Finished。 */
private class SamplingKeepAlive(val delegate: FakeKeepAlive) : SubmissionKeepAlive by delegate {
    var sampleAtTrack: () -> AdvanceRun? = { null }
    var atTrack: AdvanceRun? = AdvanceRun.Working(CameoStep.SUBMITTING)

    override fun track(cameoId: String) {
        atTrack = sampleAtTrack()
        delegate.track(cameoId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SubmissionRunnerTest {
    private val pending = FakePendingRepo()
    private val repo = FakeCameoRepo()
    private val gated = Gated(repo)
    private val keepAlive = FakeKeepAlive()
    private val sampling = SamplingKeepAlive(keepAlive)

    private fun runner(scope: CoroutineScope) = SubmissionRunner(
        ConfirmCameoUseCase(gated, pending),
        AdvanceCameoUseCase(gated, pending),
        AttachCameoPhotoUseCase(pending),
        sampling,
        scope,
    )

    /** 建單成功後上傳、送出都順利，最後訂單為 GENERATING。 */
    private fun stubHappyPath() {
        repo.creates += ok(CameoStatus.AWAITING_UPLOAD, upload = true)
        repo.gets["u1"] = ok(CameoStatus.AWAITING_UPLOAD, upload = true)
        repo.uploads += ok(CameoStatus.UPLOADED)
        repo.submits += ok(CameoStatus.GENERATING)
    }

    /** 跑在同一個 scheduler 的獨立 scope；backgroundScope 的工作不會被 advanceUntilIdle 推進。 */
    private fun TestScope.bg() = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private fun draftRow(photo: String? = "/photos/k1.jpg") {
        pending.rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null, photo)
    }

    @Test
    fun `confirm success advances without anyone calling advance`() = runTest {
        stubHappyPath()
        val r = runner(bg())
        sampling.sampleAtTrack = { r.advanceState("key-1").value }
        r.confirm("c1", CAMERA)
        advanceUntilIdle()

        val state = r.advanceState("key-1").value as AdvanceRun.Finished
        assertEquals(CameoStatus.GENERATING, (state.result as AdvanceResult.Done).order.cameo.status)
        assertTrue(r.confirmState("c1").value is ConfirmRun.Finished)
        assertEquals(listOf("acquire:submit:c1:1", "track:u1", "release:submit:c1:1"), keepAlive.events)
        assertEquals(listOf("upload", "submit"), repo.calls.filter { it != "get" })
        assertFalse(r.isAdvancing("key-1"))
    }

    @Test
    fun `confirm failure retryable and not retryable do not advance`() = runTest {
        for (retryable in listOf(true, false)) {
            val p = FakePendingRepo()
            val rp = FakeCameoRepo()
            val ka = FakeKeepAlive()
            rp.creates += fail(retryable)
            val r = SubmissionRunner(
                ConfirmCameoUseCase(rp, p), AdvanceCameoUseCase(rp, p), AttachCameoPhotoUseCase(p), ka, bg(),
            )
            r.confirm("c1", CAMERA)
            advanceUntilIdle()
            val s = r.confirmState("c1").value as ConfirmRun.Finished
            assertEquals(retryable, (s.result as DomainResult.Failure).error.retryable)
            assertTrue(rp.calls.isEmpty())
            assertEquals(listOf("acquire:submit:c1:1", "release:submit:c1:1"), ka.events)
            assertNull(r.advanceState("key-1").value)
        }
    }

    @Test
    fun `confirm success but expired does not advance`() = runTest {
        repo.creates += ok(CameoStatus.EXPIRED)
        val r = runner(bg())
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertTrue(r.confirmState("c1").value is ConfirmRun.Finished)
        assertNull(r.advanceState("key-1").value)
        assertTrue(repo.calls.isEmpty())
        assertEquals(listOf("acquire:submit:c1:1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun `photo missing does not create or advance`() = runTest {
        val r = runner(bg())
        r.confirm("c1", null)
        advanceUntilIdle()
        val s = r.confirmState("c1").value as ConfirmRun.Finished
        assertEquals(PHOTO_MISSING, (s.result as DomainResult.Failure).error.code)
        assertTrue(repo.keys.isEmpty())
        assertTrue(repo.calls.isEmpty())
        assertEquals(listOf("acquire:submit:c1:1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun `second confirm for same clip while running creates once`() = runTest {
        stubHappyPath()
        gated.createGate = CompletableDeferred()
        val r = runner(bg())
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertEquals(ConfirmRun.Running, r.confirmState("c1").value)
        r.confirm("c1", CAMERA)
        gated.createGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repo.keys.size)
        assertEquals(1, keepAlive.events.count { it.startsWith("acquire") })
    }

    /** 同一個片段兩張訂單（u1、u2）的 stub：建單、上傳、送出各兩次。 */
    private fun stubTwoOrders() {
        for (id in listOf("u1", "u2")) {
            repo.creates += ok(CameoStatus.AWAITING_UPLOAD, upload = true, id = id)
            repo.gets[id] = ok(CameoStatus.AWAITING_UPLOAD, upload = true, id = id)
            repo.uploads += ok(CameoStatus.UPLOADED, id = id)
            repo.submits += ok(CameoStatus.GENERATING, id = id)
        }
    }

    @Test
    fun `second confirm for same clip during advance creates a second order`() = runTest {
        stubTwoOrders()
        gated.uploadGate = CompletableDeferred()
        val r = runner(bg())
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertTrue(r.isAdvancing("key-1"))
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertEquals(2, repo.keys.size)
        assertEquals(listOf("key-1", "key-2"), repo.keys)
        assertTrue(r.isAdvancing("key-2"))
        gated.uploadGate!!.complete(Unit)
        advanceUntilIdle()
        for (k in listOf("key-1", "key-2")) {
            val f = r.advanceState(k).value as AdvanceRun.Finished
            assertTrue(f.result is AdvanceResult.Done)
        }
    }

    @Test
    fun `first job ending does not overwrite second confirm running`() = runTest {
        stubTwoOrders()
        gated.uploadGate = CompletableDeferred()
        val r = runner(bg())
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertTrue(r.isAdvancing("key-1"))
        gated.createGate = CompletableDeferred()
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertEquals(ConfirmRun.Running, r.confirmState("c1").value)
        gated.uploadGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue((r.advanceState("key-1").value as AdvanceRun.Finished).result is AdvanceResult.Done)
        assertEquals(ConfirmRun.Running, r.confirmState("c1").value)
        // 第二張還在建單：第三次 confirm 應被同片段的建單去重擋下
        val keysBefore = repo.keys.size
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertEquals(keysBefore, repo.keys.size)
        assertEquals(2, keepAlive.events.count { it.startsWith("acquire:") })
        gated.createGate!!.complete(Unit)
        advanceUntilIdle()
        val f = r.confirmState("c1").value as ConfirmRun.Finished
        assertTrue(f.result is DomainResult.Success)
        assertTrue((r.advanceState("key-2").value as AdvanceRun.Finished).result is AdvanceResult.Done)
    }

    @Test
    fun `keep alive keys are per submission`() = runTest {
        stubTwoOrders()
        gated.uploadGate = CompletableDeferred()
        val r = runner(bg())
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        gated.createGate = CompletableDeferred()
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        val acquired = keepAlive.events.filter { it.startsWith("acquire:") }.map { it.removePrefix("acquire:") }
        assertEquals(2, acquired.size)
        assertEquals(2, acquired.toSet().size)
        val (k1, k2) = acquired
        gated.uploadGate!!.complete(Unit)
        advanceUntilIdle()
        // 第一張結束：它的 key 已放掉，第二張的 key 仍在保持中
        assertEquals(1, keepAlive.events.count { it == "release:$k1" })
        assertEquals(0, keepAlive.events.count { it == "release:$k2" })
        gated.createGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, keepAlive.events.count { it == "release:$k1" })
        assertEquals(1, keepAlive.events.count { it == "release:$k2" })
        assertEquals(4, keepAlive.events.count { it.startsWith("acquire:") || it.startsWith("release:") })
    }

    @Test
    fun `advance during confirm driven advance does not start another`() = runTest {
        stubHappyPath()
        gated.uploadGate = CompletableDeferred()
        val r = runner(bg())
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertTrue(r.isAdvancing("key-1"))
        r.advance("key-1", null)
        gated.uploadGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, gated.uploadCalls)
        assertTrue((r.advanceState("key-1").value as AdvanceRun.Finished).result is AdvanceResult.Done)
        assertEquals(listOf("acquire:submit:c1:1", "track:u1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun `concurrent confirm calls from other threads start one job`() = runTest {
        stubHappyPath()
        val r = runner(bg())
        val threads = List(8) { Thread { r.confirm("c1", CAMERA) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        advanceUntilIdle()
        assertEquals(1, repo.keys.size)
    }

    @Test
    fun `advance normal emits steps and tracks before finished`() = runTest {
        draftRow()
        repo.gets["u1"] = ok(CameoStatus.AWAITING_UPLOAD, upload = true)
        repo.uploads += ok(CameoStatus.UPLOADED)
        repo.submits += ok(CameoStatus.GENERATING)
        val r = runner(bg())
        sampling.sampleAtTrack = { r.advanceState("k1").value }
        gated.uploadGate = CompletableDeferred()
        gated.submitGate = CompletableDeferred()
        r.advance("k1", null)
        advanceUntilIdle()
        assertEquals(AdvanceRun.Working(CameoStep.UPLOADING), r.advanceState("k1").value)
        gated.uploadGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(AdvanceRun.Working(CameoStep.SUBMITTING), r.advanceState("k1").value)
        assertTrue(keepAlive.events.none { it.startsWith("track") })
        gated.submitGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue((r.advanceState("k1").value as AdvanceRun.Finished).result is AdvanceResult.Done)
        assertEquals(AdvanceRun.Working(CameoStep.SUBMITTING), sampling.atTrack)
        assertEquals(listOf("acquire:advance:k1", "track:u1", "release:advance:k1"), keepAlive.events)
    }

    @Test
    fun `advance retryable failure finishes failed without track`() = runTest {
        draftRow()
        repo.gets["u1"] = ok(CameoStatus.AWAITING_UPLOAD, upload = true)
        repo.uploads += fail(retryable = true)
        val r = runner(bg())
        r.advance("k1", null)
        advanceUntilIdle()
        val s = (r.advanceState("k1").value as AdvanceRun.Finished).result as AdvanceResult.Failed
        assertTrue(s.error.retryable)
        assertEquals(listOf("acquire:advance:k1", "release:advance:k1"), keepAlive.events)
    }

    @Test
    fun `advance with photo finishes early on NeedPhoto and Gone`() = runTest {
        draftRow(photo = null)
        pending.missingSources += "/cache/gone.jpg"
        val r = runner(bg())
        r.advance("k1", "/cache/gone.jpg")
        advanceUntilIdle()
        assertEquals(AdvanceRun.Finished(AdvanceResult.NeedPhoto("c1")), r.advanceState("k1").value)
        assertEquals(listOf("acquire:advance:k1", "release:advance:k1"), keepAlive.events)
        assertTrue(repo.calls.isEmpty())

        keepAlive.events.clear()
        r.advance("nope", CAMERA)
        advanceUntilIdle()
        assertEquals(AdvanceRun.Finished(AdvanceResult.Gone), r.advanceState("nope").value)
        assertEquals(listOf("acquire:advance:nope", "release:advance:nope"), keepAlive.events)
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun `advance with photo attaches then continues`() = runTest {
        draftRow(photo = null)
        repo.gets["u1"] = ok(CameoStatus.AWAITING_UPLOAD, upload = true)
        repo.uploads += ok(CameoStatus.UPLOADED)
        repo.submits += ok(CameoStatus.GENERATING)
        val r = runner(bg())
        r.advance("k1", CAMERA)
        advanceUntilIdle()
        assertTrue((r.advanceState("k1").value as AdvanceRun.Finished).result is AdvanceResult.Done)
        assertEquals(listOf("k1" to CAMERA), pending.attached)
    }

    @Test
    fun `use case throwing finishes failed and releases`() = runTest {
        draftRow()
        // gets 沒有 stub，FakeCameoRepo.getCameo 會丟 IllegalStateException
        val r = runner(bg())
        r.advance("k1", null)
        advanceUntilIdle()
        val s = (r.advanceState("k1").value as AdvanceRun.Finished).result as AdvanceResult.Failed
        assertTrue(s.error.retryable)
        assertEquals(listOf("acquire:advance:k1", "release:advance:k1"), keepAlive.events)
        assertFalse(r.isAdvancing("k1"))
    }

    @Test
    fun `confirm use case throwing finishes failure and releases`() = runTest {
        // creates 佇列為空，createCameo 會丟 NoSuchElementException
        val r = runner(bg())
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        val s = r.confirmState("c1").value as ConfirmRun.Finished
        assertTrue((s.result as DomainResult.Failure).error.retryable)
        assertEquals(listOf("acquire:submit:c1:1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun `cancelling the scope still releases`() = runTest {
        stubHappyPath()
        gated.uploadGate = CompletableDeferred()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val r = runner(scope)
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertTrue(r.isAdvancing("key-1"))
        scope.cancel()
        advanceUntilIdle()
        assertEquals(listOf("acquire:submit:c1:1", "release:submit:c1:1"), keepAlive.events)
        assertFalse(r.isAdvancing("key-1"))
        // confirm 已成功，被取消的是推進：停在進行中的推進狀態改寫成可重試失敗
        assertTrue((r.confirmState("c1").value as ConfirmRun.Finished).result is DomainResult.Success)
        assertRetryableAdvanceFailure(r.advanceState("key-1").value)
    }

    @Test
    fun `cancelling the scope during confirm finishes confirm as retryable failure`() = runTest {
        stubHappyPath()
        gated.createGate = CompletableDeferred()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val r = runner(scope)
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertEquals(ConfirmRun.Running, r.confirmState("c1").value)
        scope.cancel()
        advanceUntilIdle()
        val s = r.confirmState("c1").value as ConfirmRun.Finished
        val e = (s.result as DomainResult.Failure).error
        assertTrue(e.retryable)
        assertNull(e.message)
        assertNull(r.advanceState("key-1").value)
        assertEquals(listOf("acquire:submit:c1:1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun `cancelling the scope during advance releases and finishes as retryable failure`() = runTest {
        draftRow()
        repo.gets["u1"] = ok(CameoStatus.AWAITING_UPLOAD, upload = true)
        repo.uploads += ok(CameoStatus.UPLOADED)
        gated.uploadGate = CompletableDeferred()
        val scope = bg()
        val r = runner(scope)
        r.advance("k1", null)
        advanceUntilIdle()
        assertTrue(r.isAdvancing("k1"))
        scope.cancel()
        advanceUntilIdle()
        assertEquals(listOf("acquire:advance:k1", "release:advance:k1"), keepAlive.events)
        assertFalse(r.isAdvancing("k1"))
        assertRetryableAdvanceFailure(r.advanceState("k1").value)
    }

    @Test
    fun `advance is already registered when confirm becomes Finished success`() = runTest {
        stubHappyPath()
        gated.uploadGate = CompletableDeferred()
        val r = runner(bg())
        var advancingAtFinished: Boolean? = null
        // Unconfined：寫入 Finished 的當下同步取樣（同執行緒、鎖可重入）
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            r.confirmState("c1").collect {
                if (it is ConfirmRun.Finished && it.result is DomainResult.Success && advancingAtFinished == null) {
                    advancingAtFinished = r.isAdvancing("key-1")
                }
            }
        }
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertEquals(true, advancingAtFinished)
        gated.uploadGate!!.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `cancelled scope leaves no registration and no keep alive`() = runTest {
        val scope = bg()
        scope.cancel()
        val r = runner(scope)
        r.confirm("c1", CAMERA)
        r.advance("k1", null)
        advanceUntilIdle()

        val c = r.confirmState("c1").value as ConfirmRun.Finished
        assertTrue((c.result as DomainResult.Failure).error.retryable)
        assertRetryableAdvanceFailure(r.advanceState("k1").value)
        assertFalse(r.isAdvancing("k1"))
        assertTrue(keepAlive.events.isEmpty())
        assertTrue(repo.calls.isEmpty())
        // 登記已清掉：再呼叫仍會立刻得到失敗，不會被「已在進行中」擋掉
        r.confirm("c1", CAMERA)
        assertTrue(r.confirmState("c1").value is ConfirmRun.Finished)
    }

    /** 收集 [SubmissionRunner.finished]，回傳收到的次數清單（Unconfined，發出當下就記）。 */
    private fun TestScope.collectFinished(r: SubmissionRunner): MutableList<Unit> {
        val got = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.finished.collect { got += it } }
        return got
    }

    @Test
    fun `finished emits once when confirm succeeds and advance completes`() = runTest {
        stubHappyPath()
        val r = runner(bg())
        val got = collectFinished(r)
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertTrue((r.advanceState("key-1").value as AdvanceRun.Finished).result is AdvanceResult.Done)
        assertEquals(1, got.size)
    }

    @Test
    fun `finished emits once when advance alone completes`() = runTest {
        draftRow()
        repo.gets["u1"] = ok(CameoStatus.AWAITING_UPLOAD, upload = true)
        repo.uploads += ok(CameoStatus.UPLOADED)
        repo.submits += ok(CameoStatus.GENERATING)
        val r = runner(bg())
        val got = collectFinished(r)
        r.advance("k1", null)
        advanceUntilIdle()
        assertTrue((r.advanceState("k1").value as AdvanceRun.Finished).result is AdvanceResult.Done)
        assertEquals(1, got.size)
    }

    @Test
    fun `finished emits once when confirm returns failure`() = runTest {
        repo.creates += fail(retryable = true)
        val r = runner(bg())
        val got = collectFinished(r)
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertTrue((r.confirmState("c1").value as ConfirmRun.Finished).result is DomainResult.Failure)
        assertEquals(1, got.size)
    }

    @Test
    fun `finished emits once when the use case throws`() = runTest {
        // creates 佇列為空，createCameo 會丟 NoSuchElementException
        val r = runner(bg())
        val got = collectFinished(r)
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        assertTrue((r.confirmState("c1").value as ConfirmRun.Finished).result is DomainResult.Failure)
        assertEquals(1, got.size)
    }

    @Test
    fun `finished emits once in total when the same clip is confirmed again while running`() = runTest {
        stubHappyPath()
        gated.createGate = CompletableDeferred()
        val r = runner(bg())
        val got = collectFinished(r)
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        r.confirm("c1", CAMERA)
        gated.createGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repo.keys.size)
        assertEquals(1, got.size)
    }

    private fun assertRetryableAdvanceFailure(state: AdvanceRun?) {
        val f = (state as AdvanceRun.Finished).result as AdvanceResult.Failed
        assertTrue(f.error.retryable)
        assertNull(f.error.message)
    }

    @Test
    fun `state is kept after the job ends for late observers`() = runTest {
        stubHappyPath()
        val r = runner(bg())
        r.confirm("c1", CAMERA)
        advanceUntilIdle()
        val late = r.advanceState("key-1").value
        assertTrue(late is AdvanceRun.Finished)
        assertTrue((late as AdvanceRun.Finished).result is AdvanceResult.Done)
        assertTrue(r.confirmState("c1").value is ConfirmRun.Finished)
    }
}
