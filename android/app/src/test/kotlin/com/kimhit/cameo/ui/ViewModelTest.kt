package com.kimhit.cameo.ui

import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoPage
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.LedgerEntry
import com.kimhit.cameo.domain.model.LedgerPage
import com.kimhit.cameo.domain.model.LedgerType
import com.kimhit.cameo.domain.model.Points
import com.kimhit.cameo.domain.model.Page
import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.PendingStep
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.repository.ClipRepository
import com.kimhit.cameo.domain.repository.PendingCameoRepository
import com.kimhit.cameo.domain.repository.PointsRepository
import com.kimhit.cameo.domain.usecase.GetCameoPageUseCase
import com.kimhit.cameo.domain.usecase.DeletePendingCameoUseCase
import com.kimhit.cameo.domain.usecase.GetClipsUseCase
import com.kimhit.cameo.domain.usecase.GetLedgerPageUseCase
import com.kimhit.cameo.domain.usecase.GetPointsUseCase
import com.kimhit.cameo.domain.usecase.GetQuotaUseCase
import com.kimhit.cameo.domain.usecase.GetUnfinishedCameosUseCase
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakePoints(
    val points: ArrayDeque<DomainResult<Points>> = ArrayDeque(),
    val pages: ArrayDeque<DomainResult<LedgerPage>> = ArrayDeque(),
) : PointsRepository {
    val cursors = mutableListOf<String?>()
    override suspend fun getPoints() = points.removeFirst()
    override suspend fun getLedger(cursor: String?): DomainResult<LedgerPage> {
        cursors += cursor
        return pages.removeFirst()
    }
}

private fun entry(id: String) =
    LedgerEntry(id, LedgerType.CHARGE, 10, null, "雨夜重逢", "2026-11-07T10:01:00+08:00")

/** 這幾個測試只驗片段清單，額度與草稿相關的依賴用不會被呼叫的空 fake。 */
internal fun clipsVm(clips: FakeClips) = ClipsViewModel(
    GetClipsUseCase(clips),
    GetQuotaUseCase(FakeQuota()),
)

private class CountingClips(val results: ArrayDeque<DomainResult<List<Clip>>>) : ClipRepository {
    var calls = 0
    override suspend fun getClips(): DomainResult<List<Clip>> {
        calls++
        return results.removeFirst()
    }
}

/**
 * MyCameosViewModel 的測試組裝。init 會呼叫 listCameos，而 FakeCameoRepo.listCameos 會丟錯，
 * 所以只覆寫 listCameos，其餘委派給同一個 [cameos] 實例。
 */
internal fun myCameosVm(
    pending: PendingCameoRepository = FakePendingRepo(),
    cameos: FakeCameoRepo = FakeCameoRepo(),
    quota: FakeQuota = FakeQuota(),
    clips: ClipRepository = FakeClips(ArrayDeque(listOf(DomainResult.Success(listOf(Clip("c1", "雨夜重逢", 10)))))),
    listCameos: suspend (String?) -> DomainResult<CameoPage> = { DomainResult.Success(Page(emptyList(), null)) },
): MyCameosViewModel {
    val repo = object : CameoRepository by cameos {
        override suspend fun listCameos(cursor: String?): DomainResult<CameoPage> = listCameos(cursor)
    }
    return MyCameosViewModel(
        GetCameoPageUseCase(repo),
        GetQuotaUseCase(quota),
        GetClipsUseCase(clips),
        GetUnfinishedCameosUseCase(pending, cameos),
        DeletePendingCameoUseCase(pending),
    )
}

private val failure = DomainResult.Failure(DomainError("後端訊息", true))

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun clips_loadingThenSuccess() = runTest {
        val clips = listOf(Clip("c1", "雨夜重逢", 10))
        val vm = clipsVm(FakeClips(ArrayDeque(listOf(DomainResult.Success(clips)))))
        assertEquals(Load.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(Load.Success(clips), vm.state.value)
    }

    @Test
    fun clips_failureThenRetrySuccess() = runTest {
        val clips = listOf(Clip("c1", "雨夜重逢", 10))
        val vm = clipsVm(FakeClips(ArrayDeque(listOf(failure, DomainResult.Success(clips)))))
        advanceUntilIdle()
        assertEquals(Load.Error("後端訊息"), vm.state.value)
        vm.load()
        assertEquals(Load.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(Load.Success(clips), vm.state.value)
    }

    @Test
    fun clips_failureWithoutMessageKeepsNullMessage() = runTest {
        val vm = clipsVm(FakeClips(ArrayDeque(listOf(DomainResult.Failure(DomainError(null, false))))))
        advanceUntilIdle()
        assertEquals(Load.Error(null), vm.state.value)
    }

    private fun pointsVm(fake: FakePoints) =
        PointsViewModel(GetPointsUseCase(fake), GetLedgerPageUseCase(fake))

    @Test
    fun points_loadingThenSuccess_withEmptyLedger() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(100, 0)))),
            ArrayDeque(listOf(DomainResult.Success(LedgerPage(emptyList(), null)))),
        )
        val vm = pointsVm(fake)
        assertEquals(Load.Loading, vm.points.value)
        assertEquals(PagePhase.Loading, vm.ledger.value.phase)
        advanceUntilIdle()
        assertEquals(Load.Success(Points(100, 0)), vm.points.value)
        assertEquals(PagedUiState<LedgerEntry>(phase = PagePhase.Idle), vm.ledger.value)
    }

    @Test
    fun points_failureThenRefreshSuccess() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(failure, DomainResult.Success(Points(90, 10)))),
            ArrayDeque(listOf(failure, DomainResult.Success(LedgerPage(listOf(entry("l1")), null)))),
        )
        val vm = pointsVm(fake)
        advanceUntilIdle()
        assertEquals(Load.Error("後端訊息"), vm.points.value)
        assertEquals(PagePhase.Error, vm.ledger.value.phase)
        assertEquals("後端訊息", vm.ledger.value.errorMessage)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(Load.Success(Points(90, 10)), vm.points.value)
        assertEquals(listOf(entry("l1")), vm.ledger.value.items)
        assertEquals(PagePhase.Idle, vm.ledger.value.phase)
    }

    @Test
    fun ledger_loadMoreAppendsAndStopsAtLastPage() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(1, 0)))),
            ArrayDeque(
                listOf(
                    DomainResult.Success(LedgerPage(listOf(entry("l1"), entry("l2")), "cur1")),
                    DomainResult.Success(LedgerPage(listOf(entry("l3")), null)),
                ),
            ),
        )
        val vm = pointsVm(fake)
        advanceUntilIdle()
        vm.loadMore()
        assertEquals(PagePhase.LoadingMore, vm.ledger.value.phase)
        advanceUntilIdle()
        assertEquals(listOf("l1", "l2", "l3"), vm.ledger.value.items.map { it.id })
        assertEquals(null, vm.ledger.value.nextCursor)
        vm.loadMore() // 沒有下一頁，不應再請求
        advanceUntilIdle()
        assertEquals(listOf(null, "cur1"), fake.cursors)
    }

    @Test
    fun ledger_loadMoreFailureKeepsItemsAndRetrySucceeds() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(1, 0)))),
            ArrayDeque(
                listOf(
                    DomainResult.Success(LedgerPage(listOf(entry("l1")), "cur1")),
                    failure,
                    DomainResult.Success(LedgerPage(listOf(entry("l2")), null)),
                ),
            ),
        )
        val vm = pointsVm(fake)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(PagePhase.LoadMoreError, vm.ledger.value.phase)
        assertEquals(listOf("l1"), vm.ledger.value.items.map { it.id })
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("l1", "l2"), vm.ledger.value.items.map { it.id })
        assertEquals(PagePhase.Idle, vm.ledger.value.phase)
        assertEquals(listOf(null, "cur1", "cur1"), fake.cursors)
    }

    @Test
    fun ledger_loadMoreIgnoredWhileLoadingMore() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(1, 0)))),
            ArrayDeque(
                listOf(
                    DomainResult.Success(LedgerPage(listOf(entry("l1")), "cur1")),
                    DomainResult.Success(LedgerPage(listOf(entry("l2")), null)),
                ),
            ),
        )
        val vm = pointsVm(fake)
        advanceUntilIdle()
        vm.loadMore()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(null, "cur1"), fake.cursors)
    }

    @Test
    fun refreshPoints_keepsSuccessWhileReloading_andDoesNotReloadLedger() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(100, 0)), DomainResult.Success(Points(90, 10)))),
            ArrayDeque(listOf(DomainResult.Success(LedgerPage(listOf(entry("l1")), null)))),
        )
        val vm = pointsVm(fake)
        advanceUntilIdle()
        vm.refreshPoints()
        assertEquals(Load.Success(Points(100, 0)), vm.points.value)
        advanceUntilIdle()
        assertEquals(Load.Success(Points(90, 10)), vm.points.value)
        assertEquals(listOf<String?>(null), fake.cursors)
        assertEquals(listOf("l1"), vm.ledger.value.items.map { it.id })
    }

    @Test
    fun refreshPoints_failureBecomesError() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(100, 0)), failure)),
            ArrayDeque(listOf(DomainResult.Success(LedgerPage(emptyList(), null)))),
        )
        val vm = pointsVm(fake)
        advanceUntilIdle()
        vm.refreshPoints()
        advanceUntilIdle()
        assertEquals(Load.Error("後端訊息"), vm.points.value)
    }

    private val pointsOf = { a: Int -> DomainResult.Success(Points(a, 0)) }

    /** 以可控制完成時機的 fake 驗證舊回應不能蓋掉新回應。 */
    private class GatedPoints(val gates: ArrayDeque<CompletableDeferred<DomainResult<Points>>>) : PointsRepository {
        override suspend fun getPoints() = gates.removeFirst().await()
        override suspend fun getLedger(cursor: String?): DomainResult<LedgerPage> =
            DomainResult.Success(LedgerPage(emptyList(), null))
    }

    private fun gatedVm(vararg g: CompletableDeferred<DomainResult<Points>>): PointsViewModel {
        val fake = GatedPoints(ArrayDeque(g.toList()))
        return PointsViewModel(GetPointsUseCase(fake), GetLedgerPageUseCase(fake))
    }

    @Test
    fun loadPoints_olderResponseDoesNotOverwriteRefreshPoints() = runTest {
        val a = CompletableDeferred<DomainResult<Points>>()
        val b = CompletableDeferred<DomainResult<Points>>()
        val vm = gatedVm(a, b) // a 由 init 的 loadPoints 使用
        advanceUntilIdle()
        vm.refreshPoints()
        advanceUntilIdle()
        b.complete(pointsOf(90))
        advanceUntilIdle()
        a.complete(pointsOf(100)) // 舊回應晚到
        advanceUntilIdle()
        assertEquals(Load.Success(Points(90, 0)), vm.points.value)
    }

    @Test
    fun refreshPoints_olderResponseDoesNotOverwriteLoadPoints() = runTest {
        val first = CompletableDeferred<DomainResult<Points>>()
        val a = CompletableDeferred<DomainResult<Points>>()
        val b = CompletableDeferred<DomainResult<Points>>()
        val vm = gatedVm(first, a, b)
        first.complete(pointsOf(1))
        advanceUntilIdle()
        vm.refreshPoints() // a
        advanceUntilIdle()
        vm.loadPoints() // b
        advanceUntilIdle()
        b.complete(pointsOf(90))
        advanceUntilIdle()
        a.complete(pointsOf(100)) // 舊回應晚到
        advanceUntilIdle()
        assertEquals(Load.Success(Points(90, 0)), vm.points.value)
    }

    // ---- refreshLedger ----

    @Test
    fun refreshLedger_whenIdle_replacesFirstPageAndCursor() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(1, 0)))),
            ArrayDeque(
                listOf(
                    DomainResult.Success(LedgerPage(listOf(entry("l1")), "cur1")),
                    DomainResult.Success(LedgerPage(listOf(entry("l2"), entry("l1")), "cur2")),
                ),
            ),
        )
        val vm = pointsVm(fake)
        advanceUntilIdle()
        vm.refreshLedger()
        advanceUntilIdle()
        assertEquals(listOf("l2", "l1"), vm.ledger.value.items.map { it.id })
        assertEquals("cur2", vm.ledger.value.nextCursor)
        assertEquals(PagePhase.Idle, vm.ledger.value.phase)
        assertEquals(listOf<String?>(null, null), fake.cursors)
    }

    @Test
    fun refreshLedger_failureKeepsItemsAndState() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(1, 0)))),
            ArrayDeque(listOf(DomainResult.Success(LedgerPage(listOf(entry("l1")), "cur1")), failure)),
        )
        val vm = pointsVm(fake)
        advanceUntilIdle()
        val before = vm.ledger.value
        vm.refreshLedger()
        advanceUntilIdle()
        assertEquals(before.copy(refreshFailed = true, errorMessage = "後端訊息"), vm.ledger.value)
        assertEquals(PagePhase.Idle, vm.ledger.value.phase)
    }

    @Test
    fun refreshLedger_doesNotRunWhileLoadingMoreOrLoadingOrError() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(1, 0)))),
            ArrayDeque(
                listOf(
                    DomainResult.Success(LedgerPage(listOf(entry("l1")), "cur1")),
                    DomainResult.Success(LedgerPage(listOf(entry("l2")), null)),
                ),
            ),
        )
        val vm = pointsVm(fake)
        vm.refreshLedger() // 第一頁還在載入
        advanceUntilIdle()
        vm.loadMore()
        assertEquals(PagePhase.LoadingMore, vm.ledger.value.phase)
        vm.refreshLedger()
        advanceUntilIdle()
        assertEquals(listOf(null, "cur1"), fake.cursors)
        assertEquals(listOf("l1", "l2"), vm.ledger.value.items.map { it.id })
    }

    private class GatedLedger(val gates: ArrayDeque<CompletableDeferred<DomainResult<LedgerPage>>>) : PointsRepository {
        override suspend fun getPoints() = DomainResult.Success(Points(1, 0))
        override suspend fun getLedger(cursor: String?): DomainResult<LedgerPage> = gates.removeFirst().await()
    }

    @Test
    fun refreshLedger_olderResponseDoesNotOverwriteNewer() = runTest {
        val first = CompletableDeferred<DomainResult<LedgerPage>>()
        val a = CompletableDeferred<DomainResult<LedgerPage>>()
        val b = CompletableDeferred<DomainResult<LedgerPage>>()
        val fake = GatedLedger(ArrayDeque(listOf(first, a, b)))
        val vm = PointsViewModel(GetPointsUseCase(fake), GetLedgerPageUseCase(fake))
        first.complete(DomainResult.Success(LedgerPage(listOf(entry("l0")), null)))
        advanceUntilIdle()
        vm.refreshLedger() // a
        advanceUntilIdle()
        vm.refreshLedger() // b，取消 a
        advanceUntilIdle()
        b.complete(DomainResult.Success(LedgerPage(listOf(entry("lb")), null)))
        advanceUntilIdle()
        a.complete(DomainResult.Success(LedgerPage(listOf(entry("la")), null))) // 舊回應晚到
        advanceUntilIdle()
        assertEquals(listOf("lb"), vm.ledger.value.items.map { it.id })
    }

    @Test
    fun refreshLedger_responseArrivingDuringLoadMore_isDropped_andLoadMoreKeepsItems() = runTest {
        val first = CompletableDeferred<DomainResult<LedgerPage>>()
        val refresh = CompletableDeferred<DomainResult<LedgerPage>>()
        val more = CompletableDeferred<DomainResult<LedgerPage>>()
        val fake = GatedLedger(ArrayDeque(listOf(first, refresh, more)))
        val vm = PointsViewModel(GetPointsUseCase(fake), GetLedgerPageUseCase(fake))
        first.complete(DomainResult.Success(LedgerPage(listOf(entry("l1")), "cur1")))
        advanceUntilIdle()
        vm.refreshLedger() // 進行中
        advanceUntilIdle()
        vm.loadMore()
        assertEquals(PagePhase.LoadingMore, vm.ledger.value.phase)
        refresh.complete(DomainResult.Success(LedgerPage(listOf(entry("lr")), null))) // refresh 先回來
        advanceUntilIdle()
        assertTrue(vm.ledger.value.phase != PagePhase.Idle)
        assertEquals(listOf("l1"), vm.ledger.value.items.map { it.id })
        more.complete(DomainResult.Success(LedgerPage(listOf(entry("l2")), null)))
        advanceUntilIdle()
        assertEquals(PagePhase.Idle, vm.ledger.value.phase)
        assertEquals(listOf("l1", "l2"), vm.ledger.value.items.map { it.id })
    }

    // ---- MyCameosViewModel.refresh ----

    private fun cameo(id: String, status: CameoStatus = CameoStatus.SUCCEEDED) =
        Cameo(id, Clip("c1", "雨夜重逢", 10), status, "標題", "說明")

    @Test
    fun myCameos_refreshMergesById_keepsLoadedItemsAndCursor() = runTest {
        val pages = ArrayDeque(
            listOf<DomainResult<CameoPage>>(
                DomainResult.Success(Page(listOf(cameo("u1", CameoStatus.GENERATING), cameo("u2")), "cur1")),
                DomainResult.Success(Page(listOf(cameo("u3")), "cur2")),
                DomainResult.Success(Page(listOf(cameo("u0"), cameo("u1")), "other")),
            ),
        )
        val vm = myCameosVm(listCameos = { pages.removeFirst() })
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("u0", "u1", "u2", "u3"), s.items.map { it.id })
        assertEquals(CameoStatus.SUCCEEDED, s.items.first { it.id == "u1" }.status)
        assertEquals("cur2", s.nextCursor)
        assertEquals(PagePhase.Idle, s.phase)
    }

    // ---- MyCameosViewModel：待處理區、額度、片段名稱 ----

    private val draftRow = PendingCameo("k1", "c1", null, PendingStep.CREATE, "err")

    @Test
    fun myCameos_onResume_refreshesUnfinishedQuotaAndList() = runTest {
        val pages = ArrayDeque(
            listOf<DomainResult<CameoPage>>(
                DomainResult.Success(Page(listOf(cameo("u1")), null)),
                DomainResult.Success(Page(listOf(cameo("u0"), cameo("u1")), null)),
            ),
        )
        val pending = FakePendingRepo()
        val quota = FakeQuota()
        repeat(2) { quota.queue.addLast { DomainResult.Success(quotaOf(0)) } }
        val vm = myCameosVm(pending = pending, quota = quota, listCameos = { pages.removeFirst() })
        advanceUntilIdle()
        assertTrue(vm.unfinished.value.isEmpty())
        assertEquals(listOf("u1"), vm.state.value.items.map { it.id })
        pending.rows += draftRow
        vm.onResume()
        advanceUntilIdle()
        assertEquals(listOf("k1"), vm.unfinished.value.map { it.pending.clientRequestId })
        assertEquals(listOf("u0", "u1"), vm.state.value.items.map { it.id })
        assertFalse(vm.restart(vm.unfinished.value[0])) // 額度已刷新為 0
        assertEquals(quotaOf(0), vm.blockedHint.value)
    }

    @Test
    fun myCameos_restart_whenQuotaNotLoaded_returnsTrueAndDeletesDraft() = runTest {
        val pending = FakePendingRepo()
        pending.rows += draftRow
        val quota = FakeQuota()
        val stuck = CompletableDeferred<DomainResult<com.kimhit.cameo.domain.model.Quota>>()
        quota.queue.addLast { stuck.await() }
        val vm = myCameosVm(pending = pending, quota = quota)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(1, vm.unfinished.value.size)
        assertTrue(vm.restart(vm.unfinished.value[0]))
        advanceUntilIdle()
        assertTrue(pending.rows.isEmpty())
        assertNull(vm.blockedHint.value)
    }

    @Test
    fun myCameos_restart_whenQuotaZero_setsHint_andDismissClearsIt() = runTest {
        val pending = FakePendingRepo()
        pending.rows += draftRow
        val quota = FakeQuota()
        repeat(2) { quota.queue.addLast { DomainResult.Success(quotaOf(0)) } }
        val vm = myCameosVm(pending = pending, quota = quota)
        vm.onResume()
        advanceUntilIdle()
        assertFalse(vm.restart(vm.unfinished.value[0]))
        assertEquals(quotaOf(0), vm.blockedHint.value)
        assertEquals(1, pending.rows.size)
        vm.dismissHint()
        assertNull(vm.blockedHint.value)
    }

    private val clipList = listOf(Clip("c1", "雨夜重逢", 10))

    private fun quotaOk() = FakeQuota().also { q -> repeat(3) { q.queue.addLast { DomainResult.Success(quotaOf(1)) } } }

    @Test
    fun myCameos_clips_loadedOnResume() = runTest {
        val clips = CountingClips(ArrayDeque(listOf(DomainResult.Success(clipList))))
        val vm = myCameosVm(quota = quotaOk(), clips = clips)
        advanceUntilIdle()
        assertTrue(vm.clips.value.isEmpty())
        vm.onResume()
        advanceUntilIdle()
        assertEquals(clipList, vm.clips.value)
    }

    @Test
    fun myCameos_clips_failureStaysEmpty_andNextResumeRetries() = runTest {
        val clips = CountingClips(ArrayDeque(listOf(failure, DomainResult.Success(clipList))))
        val vm = myCameosVm(quota = quotaOk(), clips = clips)
        vm.onResume()
        advanceUntilIdle()
        assertTrue(vm.clips.value.isEmpty())
        assertEquals(1, clips.calls)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(2, clips.calls)
        assertEquals(clipList, vm.clips.value)
    }

    @Test
    fun myCameos_clips_notRequeriedOnceLoaded() = runTest {
        val clips = CountingClips(ArrayDeque(listOf(DomainResult.Success(clipList))))
        val vm = myCameosVm(quota = quotaOk(), clips = clips)
        vm.onResume()
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        assertEquals(1, clips.calls)
        assertEquals(clipList, vm.clips.value)
    }
}
