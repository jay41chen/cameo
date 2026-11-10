package io.github.jay41chen.cameo.ui

import androidx.lifecycle.SavedStateHandle
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoUpload
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import io.github.jay41chen.cameo.domain.model.UnfinishedCameo
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import io.github.jay41chen.cameo.domain.usecase.AdvanceCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.AdvanceResult
import io.github.jay41chen.cameo.domain.usecase.AttachCameoPhotoUseCase
import io.github.jay41chen.cameo.domain.usecase.ConfirmCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.GetClipsUseCase
import io.github.jay41chen.cameo.domain.usecase.GetQuotaUseCase
import io.github.jay41chen.cameo.submission.AdvanceRun
import io.github.jay41chen.cameo.submission.ConfirmRun
import io.github.jay41chen.cameo.submission.SubmissionRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val clipList = listOf(Clip("c1", "雨夜重逢", 10))
private fun clipsOk() = DomainResult.Success(clipList)

/** list() 每次呼叫取出一個 suspend lambda，用來控制回應先後。 */
private class QueuedListPending(
    val lists: ArrayDeque<suspend () -> List<PendingCameo>> = ArrayDeque(),
    delegate: PendingCameoRepository = FakePendingRepo(),
) : PendingCameoRepository by delegate {
    override suspend fun list(): List<PendingCameo> = lists.removeFirst()()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ClipsQuotaTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val quota = FakeQuota()

    private fun vm() = ClipsViewModel(
        GetClipsUseCase(FakeClips(ArrayDeque(listOf(clipsOk())))),
        GetQuotaUseCase(quota),
    )

    private fun quotaReturns(vararg r: DomainResult<io.github.jay41chen.cameo.domain.model.Quota>) {
        r.forEach { v -> quota.queue.addLast { v } }
    }

    @Test
    fun tryStart_blocksOnlyWhenRemainingIsZero_andSetsHint() = runTest {
        quotaReturns(DomainResult.Success(quotaOf(0)), DomainResult.Success(quotaOf(0)))
        val vm = vm()
        vm.onResume()
        advanceUntilIdle()
        assertFalse(vm.tryStart())
        assertEquals(quotaOf(0), vm.blockedHint.value)
        advanceUntilIdle()
        assertEquals(2, quota.calls) // 被擋時順便重查
        vm.dismissHint()
        assertNull(vm.blockedHint.value)
    }

    @Test
    fun tryStart_allowsWhenRemainingPositive() = runTest {
        quotaReturns(DomainResult.Success(quotaOf(2)))
        val vm = vm()
        vm.onResume()
        advanceUntilIdle()
        assertTrue(vm.tryStart())
        assertNull(vm.blockedHint.value)
    }

    @Test
    fun tryStart_allowsWhileQuotaLoading() = runTest {
        quotaReturns(DomainResult.Success(quotaOf(0)))
        val vm = vm()
        assertEquals(Load.Loading, vm.quota.value)
        assertTrue(vm.tryStart())
    }

    @Test
    fun tryStart_allowsWhenQuotaFailed() = runTest {
        quotaReturns(DomainResult.Failure(DomainError("x", true)))
        val vm = vm()
        vm.onResume()
        advanceUntilIdle()
        assertTrue(vm.quota.value is Load.Error)
        assertTrue(vm.tryStart())
    }

    @Test
    fun refreshQuota_doesNotFlashLoading_andFailureBecomesError() = runTest {
        quotaReturns(
            DomainResult.Success(quotaOf(2)),
            DomainResult.Success(quotaOf(1)),
            DomainResult.Failure(DomainError("壞了", true)),
        )
        val vm = vm()
        vm.onResume()
        advanceUntilIdle()
        assertEquals(Load.Success(quotaOf(2)), vm.quota.value)
        vm.onResume()
        assertEquals(Load.Success(quotaOf(2)), vm.quota.value) // 重查中不閃 Loading
        advanceUntilIdle()
        assertEquals(Load.Success(quotaOf(1)), vm.quota.value)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(Load.Error("壞了"), vm.quota.value) // 不沿用舊值
    }

    @Test
    fun refreshQuota_newerQueryCancelsOlder() = runTest {
        val slow = CompletableDeferred<DomainResult<io.github.jay41chen.cameo.domain.model.Quota>>()
        quota.queue.addLast { slow.await() }
        quota.queue.addLast { DomainResult.Success(quotaOf(1)) }
        val vm = vm()
        vm.onResume()
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        slow.complete(DomainResult.Success(quotaOf(5))) // 舊回應晚到，不可蓋掉新的
        advanceUntilIdle()
        assertEquals(Load.Success(quotaOf(1)), vm.quota.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MyCameosDraftTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepo()
    private val cameos = FakeCameoRepo()
    private val quota = FakeQuota()

    private fun vm() = myCameosVm(pending, cameos, quota)

    private fun quotaReturns(vararg r: DomainResult<io.github.jay41chen.cameo.domain.model.Quota>) {
        r.forEach { v -> quota.queue.addLast { v } }
    }

    private val expiredRow = UnfinishedCameo(
        PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null),
        testOrder(CameoStatus.EXPIRED),
    )

    @Test
    fun restart_doesNotDeleteDraftWhenQuotaZero() = runTest {
        pending.rows += expiredRow.pending
        cameos.gets["u1"] = DomainResult.Success(testOrder(CameoStatus.EXPIRED))
        quotaReturns(DomainResult.Success(quotaOf(0)), DomainResult.Success(quotaOf(0)))
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onResume()
        advanceUntilIdle()
        vm.onRestartClick(expiredRow, StartPermission.PROCEED)
        assertTrue(navs.isEmpty())
        advanceUntilIdle()
        assertEquals(1, pending.rows.size)
    }

    @Test
    fun restart_deletesDraftWhenAllowed() = runTest {
        pending.rows += expiredRow.pending
        cameos.gets["u1"] = DomainResult.Success(testOrder(CameoStatus.EXPIRED))
        quotaReturns(DomainResult.Success(quotaOf(1)))
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onResume()
        advanceUntilIdle()
        vm.onRestartClick(expiredRow, StartPermission.PROCEED)
        assertEquals(listOf<StartNavigation>(StartNavigation.OpenCamera("c1")), navs)
        advanceUntilIdle()
        assertTrue(pending.rows.isEmpty())
    }

    @Test
    fun remove_deletesDraftAndRefreshesUnfinished() = runTest {
        pending.rows += PendingCameo("k1", "c1", null, PendingStep.CREATE, "err")
        quotaReturns(DomainResult.Success(quotaOf(1)))
        val vm = vm()
        vm.onResume()
        advanceUntilIdle()
        assertEquals(1, vm.unfinished.value.size)
        vm.remove(vm.unfinished.value[0])
        advanceUntilIdle()
        assertTrue(vm.unfinished.value.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MyCameosUnfinishedTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun refreshUnfinished_newerQueryCancelsOlder() = runTest {
        val pending = QueuedListPending()
        val quota = FakeQuota()
        repeat(2) { quota.queue.addLast { DomainResult.Success(quotaOf(1)) } }
        val rowOld = PendingCameo("old", "c1", null, PendingStep.CREATE, null)
        val rowNew = PendingCameo("new", "c1", null, PendingStep.CREATE, null)
        val slow = CompletableDeferred<List<PendingCameo>>()
        pending.lists.addLast { slow.await() }
        pending.lists.addLast { listOf(rowNew) }
        val vm = myCameosVm(pending = pending, quota = quota)
        vm.onResume()
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        slow.complete(listOf(rowOld)) // 舊回應晚到，不可蓋掉新的
        advanceUntilIdle()
        assertEquals(listOf("new"), vm.unfinished.value.map { it.pending.clientRequestId })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        // 建單後 runner 會接著推進；訂單已送出（QUEUED），推進只會 track
        cameos.gets["u1"] = DomainResult.Success(testOrder(CameoStatus.QUEUED))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepo()
    private val cameos = FakeCameoRepo()
    private val gated = GatedCameos(cameos)
    private val keepAlive = FakeKeepAlive()

    /** runner 的 scope 跑在測試的 Main dispatcher（與 runTest 同一個 scheduler）。 */
    private val runner by lazy {
        SubmissionRunner(
            ConfirmCameoUseCase(gated, pending),
            AdvanceCameoUseCase(gated, pending),
            AttachCameoPhotoUseCase(pending),
            keepAlive,
            CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
    }

    private fun vm(
        clips: DomainResult<List<Clip>> = clipsOk(),
        clipId: String = "c1",
        /** true：草稿已有照片（相機頁「使用這張」之後）；false：草稿沒有照片。 */
        hasPhoto: Boolean = true,
        handle: SavedStateHandle = SavedStateHandle(mapOf("clipId" to clipId)),
    ): ConfirmViewModel {
        if (hasPhoto) pending.seedDraftWithPhoto(clipId)
        return ConfirmViewModel(
            handle,
            GetClipsUseCase(FakeClips(ArrayDeque(listOf(clips, clips)))),
            runner,
        )
    }

    @Test
    fun confirm_runsInRunner_acquireTrackRelease_andIsNotAcquiredOnLoad() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        assertTrue(keepAlive.events.isEmpty())
        vm.confirm()
        assertTrue(runner.confirmState("c1").value is ConfirmRun.Running) // 同步進入執行中
        advanceUntilIdle()
        assertEquals(listOf("acquire:submit:c1:1", "track:u1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun newViewModelForSameClip_whileFirstOrderAdvancing_canConfirmAgain() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder(id = "u1")))
        cameos.creates.addLast(DomainResult.Success(testOrder(id = "u2")))
        cameos.gets["u2"] = DomainResult.Success(testOrder(CameoStatus.QUEUED, id = "u2"))
        val vm1 = vm()
        advanceUntilIdle()
        // gets 以 QUEUED 回覆，推進只會 track；改用上傳卡住的訂單讓第一張停在推進階段
        cameos.gets["u1"] = DomainResult.Success(testOrder(CameoStatus.AWAITING_UPLOAD, id = "u1").copy(
            upload = CameoUpload("/v1/cameos/u1/selfie", "2026-11-07T10:30:00+08:00"),
        ))
        cameos.uploads.addLast(DomainResult.Success(testOrder(CameoStatus.UPLOADED, id = "u1")))
        cameos.submits.addLast(DomainResult.Success(testOrder(CameoStatus.QUEUED, id = "u1")))
        gated.uploadGate = CompletableDeferred()
        vm1.confirm()
        advanceUntilIdle()
        val first = (vm1.state.value as ConfirmUiState.Created).clientRequestId
        assertTrue(runner.isAdvancing(first))

        val vm2 = vm()
        advanceUntilIdle()
        assertEquals(ConfirmUiState.Ready(clipList[0], submitting = false), vm2.state.value)
        vm2.confirm()
        advanceUntilIdle()
        val second = (vm2.state.value as ConfirmUiState.Created).clientRequestId
        assertTrue(vm1.state.value is ConfirmUiState.Created)
        assertEquals(first, (vm1.state.value as ConfirmUiState.Created).clientRequestId)
        assertTrue(first != second)
        assertEquals(2, cameos.keys.size)
        gated.uploadGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm1.state.value is ConfirmUiState.Created)
        assertEquals(first, (vm1.state.value as ConfirmUiState.Created).clientRequestId)
        assertTrue(vm2.state.value is ConfirmUiState.Created)
        assertEquals(second, (vm2.state.value as ConfirmUiState.Created).clientRequestId)
    }

    @Test
    fun doubleTap_acquiresOnce() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(1, keepAlive.events.count { it.startsWith("acquire") })
    }

    @Test
    fun retryableFailure_releases_andRetryAcquiresAgain() = runTest {
        cameos.creates.addLast(DomainResult.Failure(DomainError(null, true)))
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("acquire:submit:c1:1", "release:submit:c1:1"), keepAlive.events)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(
            listOf(
                "acquire:submit:c1:1", "release:submit:c1:1",
                "acquire:submit:c1:2", "track:u1", "release:submit:c1:2",
            ),
            keepAlive.events,
        )
    }

    @Test
    fun nonRetryableFailure_releases() = runTest {
        cameos.creates.addLast(DomainResult.Failure(DomainError("x", false, "DAILY_LIMIT_REACHED")))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("acquire:submit:c1:1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun photoMissing_releases() = runTest {
        val vm = vm(hasPhoto = false)
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(ConfirmUiState.PhotoMissing, vm.state.value)
        assertEquals(listOf("acquire:submit:c1:1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun expired_releases() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder(CameoStatus.EXPIRED)))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Expired)
        assertEquals(listOf("acquire:submit:c1:1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun created_runnerReleasesAfterTrack_viewModelHoldsNothing() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Created)
        assertEquals(listOf("acquire:submit:c1:1", "track:u1", "release:submit:c1:1"), keepAlive.events)
        clearViewModel(vm)
        assertEquals(listOf("acquire:submit:c1:1", "track:u1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun clearingViewModelWhileCreating_runnerStillFinishesConfirmAndAdvance_releaseIsLast() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val gate = CompletableDeferred<Unit>()
        gated.createGate = gate
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        clearViewModel(vm) // 建單卡住時離開頁面
        advanceUntilIdle()
        assertEquals(listOf("acquire:submit:c1:1"), keepAlive.events)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue((runner.confirmState("c1").value as ConfirmRun.Finished).result is DomainResult.Success)
        val adv = runner.advanceState("key-1").value as AdvanceRun.Finished
        assertTrue(adv.result is AdvanceResult.Done)
        assertEquals(listOf("acquire:submit:c1:1", "track:u1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun leavingConfirmWhileCreating_newViewModelPicksUpCreated() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val gate = CompletableDeferred<Unit>()
        gated.createGate = gate
        val vm1 = vm()
        advanceUntilIdle()
        vm1.confirm()
        advanceUntilIdle()
        clearViewModel(vm1) // 建單卡住時離開確認頁
        val vm2 = vm()
        advanceUntilIdle()
        assertEquals(ConfirmUiState.Ready(clipList[0], submitting = true), vm2.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm2.state.value is ConfirmUiState.Created)
    }

    @Test
    fun staleFinishedFromEarlierRun_isIgnoredByNewViewModel() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        pending.seedDraftWithPhoto("c1")
        runner.confirm("c1")
        advanceUntilIdle()
        assertTrue((runner.confirmState("c1").value as ConfirmRun.Finished).result is DomainResult.Success)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ConfirmUiState.Ready(clipList[0]), vm.state.value)
    }

    @Test
    fun progressViewModelAfterCreated_joinsRunningAdvance_withoutStartingAnother() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val getGate = CompletableDeferred<Unit>()
        gated.getGate = getGate
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        val created = vm.state.value as ConfirmUiState.Created
        assertEquals(1, gated.getCalls) // 推進已在 runner 裡跑，卡在 getCameo
        val progress = CameoProgressViewModel(
            SavedStateHandle(mapOf("clientRequestId" to created.clientRequestId)),
            runner,
        )
        advanceUntilIdle()
        assertEquals(1, gated.getCalls)
        getGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, gated.getCalls)
        assertTrue(progress.state.value is ProgressUiState.Done)
        assertEquals(listOf("acquire:submit:c1:1", "track:u1", "release:submit:c1:1"), keepAlive.events)
    }

    @Test
    fun loadsClipIntoReady() = runTest {
        val vm = vm()
        assertEquals(ConfirmUiState.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(ConfirmUiState.Ready(clipList[0]), vm.state.value)
    }

    @Test
    fun clipLoadFailure_isLoadError_andRetryWorks() = runTest {
        val failing = FakeClips(ArrayDeque(listOf(DomainResult.Failure(DomainError("x", true)), clipsOk())))
        val vm = ConfirmViewModel(
            SavedStateHandle(mapOf("clipId" to "c1")),
            GetClipsUseCase(failing),
            runner,
        )
        advanceUntilIdle()
        assertEquals(ConfirmUiState.LoadError("x"), vm.state.value)
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Ready)
    }

    @Test
    fun unknownClip_isRejected() = runTest {
        val vm = vm(clipId = "nope")
        advanceUntilIdle()
        assertEquals(ConfirmUiState.Rejected("片段不存在"), vm.state.value)
    }

    @Test
    fun doubleTapPostsOnlyOnce() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(1, cameos.keys.size)
        assertTrue(vm.state.value is ConfirmUiState.Created)
    }

    @Test
    fun retryableFailureShowsFailed_andRetryUsesSameKey() = runTest {
        cameos.creates.addLast(DomainResult.Failure(DomainError(null, true)))
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        val ready = vm.state.value as ConfirmUiState.Ready
        assertTrue(ready.failed)
        assertFalse(ready.submitting)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("key-1", "key-1"), cameos.keys)
        assertTrue(vm.state.value is ConfirmUiState.Created)
    }

    @Test
    fun nonRetryableFailure_isRejectedWithBackendMessage() = runTest {
        cameos.creates.addLast(DomainResult.Failure(DomainError("今日客串次數已用完", false, "DAILY_LIMIT_REACHED")))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(ConfirmUiState.Rejected("今日客串次數已用完"), vm.state.value)
        assertTrue(pending.rows.isEmpty())
    }

    @Test
    fun unauthorized_isRejectedWithBackendMessage_andDraftKept() = runTest {
        cameos.creates.addLast(DomainResult.Failure(DomainError("請重新登入", false, "UNAUTHORIZED")))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(ConfirmUiState.Rejected("請重新登入"), vm.state.value)
        assertEquals(1, pending.rows.size)
        assertEquals("請重新登入", pending.rows[0].lastError)
    }

    @Test
    fun expired_thenNewScreenWithNewPhotoUsesNewKey() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder(CameoStatus.EXPIRED)))
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        val expired = vm.state.value as ConfirmUiState.Expired
        assertEquals("等待上傳", expired.title)
        assertTrue(pending.rows.isEmpty()) // 逾期後草稿已刪除，重新客串要走相機
        val vm2 = vm() // 相機 -> 確認頁（草稿已刪，重新有一份附照片的新草稿）
        advanceUntilIdle()
        vm2.confirm()
        advanceUntilIdle()
        assertEquals(listOf("key-1", "key-2"), cameos.keys)
        assertNotNull((vm2.state.value as ConfirmUiState.Created).order)
    }

    @Test
    fun created_carriesClientRequestId() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        val created = vm.state.value as ConfirmUiState.Created
        assertEquals("key-1", created.clientRequestId)
        assertEquals("key-1", pending.rows[0].clientRequestId)
    }

    @Test
    fun confirm_doesNotAttachAnyPhoto_usesDraftPhoto() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Created)
        assertTrue(pending.attached.isEmpty())
    }

    @Test
    fun retryAfterFailureReusesDraftPhotoAndKey() = runTest {
        cameos.creates.addLast(DomainResult.Failure(DomainError(null, true)))
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Created)
        assertEquals(listOf("key-1", "key-1"), cameos.keys)
        assertTrue(pending.attached.isEmpty())
    }

    @Test
    fun missingPhoto_showsPhotoMissing_noPost() = runTest {
        val vm = vm(hasPhoto = false)
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(ConfirmUiState.PhotoMissing, vm.state.value)
        assertTrue(cameos.keys.isEmpty())
        assertEquals(1, pending.rows.size) // 草稿保留
    }

    @Test
    fun draftHasPhoto_cameraTempFileGone_confirmStillPosts() = runTest {
        // 本 bug：程序在搬檔與 POST 之間被殺，確認頁還原後相機暫存檔已不在；照片早已在草稿裡，不可回 PHOTO_MISSING
        pending.missingSources += "/cache/captures/a.jpg"
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Created)
        assertEquals(listOf("key-1"), cameos.keys)
    }
}

class ClickThrottleTest {
    private var t = 0L
    private fun throttle() = ClickThrottle(1000L) { t }

    @Test
    fun firstClickPasses() {
        t = 5L
        assertTrue(throttle().tryAcquire())
    }

    @Test
    fun blockedWithinInterval_passesAtExactInterval() {
        val th = throttle()
        t = 100
        assertTrue(th.tryAcquire())
        t = 1099
        assertFalse(th.tryAcquire())
        t = 1100
        assertTrue(th.tryAcquire())
    }

    @Test
    fun afterPass_timerRestarts() {
        val th = throttle()
        t = 0
        assertTrue(th.tryAcquire())
        t = 1000
        assertTrue(th.tryAcquire())
        t = 1999
        assertFalse(th.tryAcquire())
        t = 2000
        assertTrue(th.tryAcquire())
    }

    @Test
    fun blockedClickDoesNotExtendTimer() {
        val th = throttle()
        t = 0
        assertTrue(th.tryAcquire())
        t = 900
        assertFalse(th.tryAcquire())
        t = 1000
        assertTrue(th.tryAcquire())
    }

    @Test
    fun guarded_skipsActionWhenThrottled() {
        val th = throttle()
        var n = 0
        t = 0
        th.guarded { n++ }
        t = 500
        th.guarded { n++ }
        assertEquals(1, n)
        t = 1000
        th.guarded { n++ }
        assertEquals(2, n)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ThrottledRestartTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun doubleTapRestart_deletesOnlyOnce() = runTest {
        val pending = FakePendingRepo()
        val row = PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null)
        pending.rows += row
        val quota = FakeQuota()
        val vm = myCameosVm(pending = pending, quota = quota)
        val unfinished = UnfinishedCameo(row, testOrder(CameoStatus.EXPIRED))
        var t = 0L
        val th = ClickThrottle(1000L) { t }
        val navs = collectStartNavigation(vm)
        var restartCalls = 0
        fun tap() = th.guarded {
            restartCalls++
            vm.onRestartClick(unfinished, StartPermission.PROCEED)
        }
        tap()
        t = 300
        tap()
        advanceUntilIdle()
        assertEquals(1, restartCalls)
        assertEquals(1, navs.size)
        assertTrue(pending.rows.isEmpty())
    }
}
