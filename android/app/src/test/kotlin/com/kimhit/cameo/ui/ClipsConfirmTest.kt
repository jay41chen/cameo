package com.kimhit.cameo.ui

import androidx.lifecycle.SavedStateHandle
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.PendingStep
import com.kimhit.cameo.domain.model.UnfinishedCameo
import com.kimhit.cameo.domain.repository.PendingCameoRepository
import com.kimhit.cameo.domain.usecase.ConfirmCameoUseCase
import com.kimhit.cameo.domain.usecase.GetClipsUseCase
import com.kimhit.cameo.domain.usecase.GetQuotaUseCase
import kotlinx.coroutines.CompletableDeferred
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

    private fun quotaReturns(vararg r: DomainResult<com.kimhit.cameo.domain.model.Quota>) {
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
        val slow = CompletableDeferred<DomainResult<com.kimhit.cameo.domain.model.Quota>>()
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

    private fun quotaReturns(vararg r: DomainResult<com.kimhit.cameo.domain.model.Quota>) {
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
        vm.onResume()
        advanceUntilIdle()
        assertFalse(vm.restart(expiredRow))
        advanceUntilIdle()
        assertEquals(1, pending.rows.size)
    }

    @Test
    fun restart_deletesDraftWhenAllowed() = runTest {
        pending.rows += expiredRow.pending
        cameos.gets["u1"] = DomainResult.Success(testOrder(CameoStatus.EXPIRED))
        quotaReturns(DomainResult.Success(quotaOf(1)))
        val vm = vm()
        vm.onResume()
        advanceUntilIdle()
        assertTrue(vm.restart(expiredRow))
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
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepo()
    private val cameos = FakeCameoRepo()

    private fun vm(
        clips: DomainResult<List<Clip>> = clipsOk(),
        clipId: String = "c1",
        photo: String? = "/cache/captures/a.jpg",
    ) = ConfirmViewModel(
        SavedStateHandle(mapOf("clipId" to clipId, "photo" to photo)),
        GetClipsUseCase(FakeClips(ArrayDeque(listOf(clips, clips)))),
        ConfirmCameoUseCase(cameos, pending),
    )

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
            SavedStateHandle(mapOf("clipId" to "c1", "photo" to "/cache/captures/a.jpg")),
            GetClipsUseCase(failing),
            ConfirmCameoUseCase(cameos, pending),
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
        val vm2 = vm(photo = "/cache/captures/b.jpg") // 相機 -> 確認頁
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
    fun passesPhotoPathToUseCase() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm(photo = "/cache/captures/x.jpg")
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("key-1" to "/cache/captures/x.jpg"), pending.attached)
    }

    @Test
    fun retryAfterFailureUsesDraftPhoto_notTheMovedSource() = runTest {
        cameos.creates.addLast(DomainResult.Failure(DomainError(null, true)))
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val handle = SavedStateHandle(mapOf("clipId" to "c1", "photo" to "/cache/captures/a.jpg"))
        val vm = ConfirmViewModel(
            handle,
            GetClipsUseCase(FakeClips(ArrayDeque(listOf(clipsOk())))),
            ConfirmCameoUseCase(cameos, pending),
        )
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        pending.missingSources += "/cache/captures/a.jpg" // 來源已被移走
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Created)
        assertEquals(listOf("key-1", "key-1"), cameos.keys)
        assertEquals(1, pending.attached.size)
        assertNull(handle.get<String>("photo"))
    }

    @Test
    fun missingPhoto_showsPhotoMissing_noPost() = runTest {
        pending.missingSources += "/cache/captures/a.jpg"
        val vm = vm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(ConfirmUiState.PhotoMissing, vm.state.value)
        assertTrue(cameos.keys.isEmpty())
        assertEquals(1, pending.rows.size) // 草稿保留
    }

    @Test
    fun noPhotoArgAndDraftWithoutPhoto_showsPhotoMissing() = runTest {
        val vm = vm(photo = null)
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(ConfirmUiState.PhotoMissing, vm.state.value)
        assertTrue(cameos.keys.isEmpty())
    }

    @Test
    fun noPhotoArgButDraftHasPhoto_posts() = runTest {
        pending.rows += PendingCameo("key-1", "c1", null, PendingStep.CREATE, null, "/photos/key-1.jpg")
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm(photo = null)
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
        var navigations = 0
        var restartCalls = 0
        fun tap() = th.guarded {
            restartCalls++
            if (vm.restart(unfinished)) navigations++
        }
        tap()
        t = 300
        tap()
        advanceUntilIdle()
        assertEquals(1, restartCalls)
        assertEquals(1, navigations)
        assertTrue(pending.rows.isEmpty())
    }
}
