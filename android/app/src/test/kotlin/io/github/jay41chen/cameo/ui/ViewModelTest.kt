package io.github.jay41chen.cameo.ui

import io.github.jay41chen.cameo.domain.model.Cameo
import io.github.jay41chen.cameo.domain.model.CameoPage
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.LedgerEntry
import io.github.jay41chen.cameo.domain.model.LedgerPage
import io.github.jay41chen.cameo.domain.model.LedgerType
import io.github.jay41chen.cameo.domain.model.Points
import io.github.jay41chen.cameo.domain.model.Page
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.repository.ClipRepository
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import io.github.jay41chen.cameo.domain.repository.PointsRepository
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.usecase.GetCameoPageUseCase
import io.github.jay41chen.cameo.domain.usecase.GetCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.DeletePendingCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.GetClipsUseCase
import io.github.jay41chen.cameo.domain.usecase.GetLedgerPageUseCase
import io.github.jay41chen.cameo.domain.usecase.GetPointsUseCase
import io.github.jay41chen.cameo.domain.usecase.GetQuotaUseCase
import io.github.jay41chen.cameo.domain.usecase.GetUnfinishedCameosUseCase
import io.github.jay41chen.cameo.domain.usecase.AdvanceCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.AttachCameoPhotoUseCase
import io.github.jay41chen.cameo.domain.usecase.ConfirmCameoUseCase
import io.github.jay41chen.cameo.submission.SubmissionRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** 首頁點數用的可腳本化 fake：佇列有東西就照佇列；沒有就回固定成功值（不像 [FakePoints] 空了會丟例外）。 */
internal class ScriptedPoints(
    private val fallback: DomainResult<Points> = DomainResult.Success(Points(100, 0)),
) : PointsRepository {
    val queue = ArrayDeque<suspend () -> DomainResult<Points>>()
    override suspend fun getPoints(): DomainResult<Points> = queue.removeFirstOrNull()?.invoke() ?: fallback
    override suspend fun getLedger(cursor: String?): DomainResult<LedgerPage> = error("not used")
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

/** 收集 [MyCameosViewModel.startNavigation]（同 refreshErrors 測試的寫法）。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.collectStartNavigation(vm: MyCameosViewModel): MutableList<StartNavigation> {
    val events = mutableListOf<StartNavigation>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.startNavigation.collect { events += it } }
    return events
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
    points: PointsRepository = ScriptedPoints(),
    getCameo: (suspend (String) -> DomainResult<CameoOrder>)? = null,
    runner: SubmissionRunner = SubmissionRunner(
        ConfirmCameoUseCase(cameos, pending),
        AdvanceCameoUseCase(cameos, pending),
        AttachCameoPhotoUseCase(pending),
        FakeKeepAlive(),
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
    ),
): MyCameosViewModel {
    val repo = object : CameoRepository by cameos {
        override suspend fun listCameos(cursor: String?): DomainResult<CameoPage> = listCameos(cursor)
        override suspend fun getCameo(id: String): DomainResult<CameoOrder> =
            if (getCameo != null) getCameo(id) else cameos.getCameo(id)
    }
    return MyCameosViewModel(
        GetCameoPageUseCase(repo),
        GetQuotaUseCase(quota),
        GetPointsUseCase(points),
        GetClipsUseCase(clips),
        GetUnfinishedCameosUseCase(pending, cameos),
        DeletePendingCameoUseCase(pending),
        GetCameoUseCase(repo),
        runner,
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
    fun refreshLedger_whenIdle_mergesFirstPageAndKeepsLoadedItemsAndCursor() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(1, 0)))),
            ArrayDeque(
                listOf(
                    DomainResult.Success(LedgerPage(listOf(entry("l3"), entry("l2")), "cur1")),
                    DomainResult.Success(LedgerPage(listOf(entry("l1")), "cur2")),
                    DomainResult.Success(LedgerPage(listOf(entry("l4"), entry("l3")), "curX")),
                ),
            ),
        )
        val vm = pointsVm(fake)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        vm.refreshLedger()
        advanceUntilIdle()
        assertEquals(listOf("l4", "l3", "l2", "l1"), vm.ledger.value.items.map { it.id })
        assertEquals("cur2", vm.ledger.value.nextCursor)
        assertEquals(PagePhase.Idle, vm.ledger.value.phase)
        assertEquals(listOf<String?>(null, "cur1", null), fake.cursors)
    }

    @Test
    fun refreshLedger_noOverlap_replacesWholeListAndCursor() = runTest {
        val fake = FakePoints(
            ArrayDeque(listOf(DomainResult.Success(Points(1, 0)))),
            ArrayDeque(
                listOf(
                    DomainResult.Success(LedgerPage(listOf(entry("l2"), entry("l1")), "cur1")),
                    DomainResult.Success(LedgerPage(listOf(entry("n1")), "curN")),
                ),
            ),
        )
        val vm = pointsVm(fake)
        advanceUntilIdle()
        vm.refreshLedger()
        advanceUntilIdle()
        assertEquals(listOf("n1"), vm.ledger.value.items.map { it.id })
        assertEquals("curN", vm.ledger.value.nextCursor)
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

    // ---- MyCameosViewModel.onResume：列表載入失敗與刷新失敗 ----

    private fun quotaFor(resumes: Int) = FakeQuota().also { q ->
        repeat(resumes) { q.queue.addLast { DomainResult.Success(quotaOf(1)) } }
    }

    @Test
    fun myCameos_onResume_afterInitialLoadFailure_reloadsFirstPage() = runTest {
        val pages = ArrayDeque(
            listOf<DomainResult<CameoPage>>(failure, DomainResult.Success(Page(listOf(cameo("u1")), null))),
        )
        var calls = 0
        val vm = myCameosVm(quota = quotaFor(1), listCameos = { calls++; pages.removeFirst() })
        advanceUntilIdle()
        assertEquals(PagePhase.Error, vm.state.value.phase)
        assertEquals(1, calls)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(PagePhase.Idle, vm.state.value.phase)
        assertEquals(listOf("u1"), vm.state.value.items.map { it.id })
    }

    @Test
    fun myCameos_onResume_afterInitialLoadFailure_failsAgain_staysErrorWithoutRefreshEvent() = runTest {
        val pending = FakePendingRepo()
        pending.rows += draftRow
        var calls = 0
        val vm = myCameosVm(pending = pending, quota = quotaFor(1), listCameos = { calls++; failure })
        val events = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshErrors.collect { events += it } }
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(PagePhase.Error, vm.state.value.phase)
        assertEquals(listOf("k1"), vm.unfinished.value.map { it.pending.clientRequestId })
        assertTrue(events.isEmpty())
    }

    @Test
    fun myCameos_onResume_afterLoadMoreFailure_keepsItemsAndPhaseWithoutReload() = runTest {
        val pages = ArrayDeque(
            listOf<DomainResult<CameoPage>>(
                DomainResult.Success(Page(listOf(cameo("u1")), "cur1")),
                failure,
            ),
        )
        var calls = 0
        val vm = myCameosVm(quota = quotaFor(1), listCameos = { calls++; pages.removeFirst() })
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(PagePhase.LoadMoreError, vm.state.value.phase)
        assertEquals(2, calls)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(PagePhase.LoadMoreError, vm.state.value.phase)
        assertEquals(listOf("u1"), vm.state.value.items.map { it.id })
    }

    @Test
    fun myCameos_onResume_whenIdleWithItems_keepsItemsAndPhaseWhileQueryPending() = runTest {
        val stuck = CompletableDeferred<DomainResult<CameoPage>>()
        var calls = 0
        val vm = myCameosVm(quota = quotaFor(1), listCameos = {
            if (calls++ == 0) DomainResult.Success(Page(listOf(cameo("u1")), null)) else stuck.await()
        })
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(PagePhase.Idle, vm.state.value.phase)
        assertEquals(listOf("u1"), vm.state.value.items.map { it.id })
    }

    @Test
    fun myCameos_onResume_whenIdleWithItems_refreshFailureEmitsEventAndKeepsItems() = runTest {
        val pages = ArrayDeque(
            listOf<DomainResult<CameoPage>>(
                DomainResult.Success(Page(listOf(cameo("u1")), null)),
                failure,
                DomainResult.Success(Page(listOf(cameo("u1")), null)),
            ),
        )
        val vm = myCameosVm(quota = quotaFor(2), listCameos = { pages.removeFirst() })
        val events = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshErrors.collect { events += it } }
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        assertEquals(listOf<String?>("後端訊息"), events)
        assertEquals(listOf("u1"), vm.state.value.items.map { it.id })
        assertEquals(PagePhase.Idle, vm.state.value.phase)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(listOf<String?>("後端訊息"), events)
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
        val navs = collectStartNavigation(vm)
        advanceUntilIdle()
        assertTrue(vm.unfinished.value.isEmpty())
        assertEquals(listOf("u1"), vm.state.value.items.map { it.id })
        pending.rows += draftRow
        vm.onResume()
        advanceUntilIdle()
        assertEquals(listOf("k1"), vm.unfinished.value.map { it.pending.clientRequestId })
        assertEquals(listOf("u0", "u1"), vm.state.value.items.map { it.id })
        vm.onRestartClick(vm.unfinished.value[0], StartPermission.PROCEED) // 額度已刷新為 0
        assertTrue(navs.isEmpty())
        assertEquals(quotaOf(0), vm.blockedHint.value)
    }

    @Test
    fun myCameos_restart_whenQuotaNotLoaded_returnsTrueAndDeletesDraft() = runTest {
        val pending = FakePendingRepo()
        pending.rows += draftRow
        val quota = FakeQuota()
        val stuck = CompletableDeferred<DomainResult<io.github.jay41chen.cameo.domain.model.Quota>>()
        quota.queue.addLast { stuck.await() }
        val vm = myCameosVm(pending = pending, quota = quota)
        val navs = collectStartNavigation(vm)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(1, vm.unfinished.value.size)
        vm.onRestartClick(vm.unfinished.value[0], StartPermission.PROCEED)
        assertEquals(listOf<StartNavigation>(StartNavigation.OpenCamera("c1")), navs)
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
        val navs = collectStartNavigation(vm)
        vm.onResume()
        advanceUntilIdle()
        vm.onRestartClick(vm.unfinished.value[0], StartPermission.PROCEED)
        assertTrue(navs.isEmpty())
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

    @Test
    fun myCameos_submittedListFailure_keepsUnfinished() = runTest {
        val pending = FakePendingRepo()
        pending.rows += draftRow
        val vm = myCameosVm(
            pending = pending,
            quota = quotaOk(),
            listCameos = { failure },
        )
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        assertEquals(listOf("k1"), vm.unfinished.value.map { it.pending.clientRequestId })
        assertEquals(PagePhase.Error, vm.state.value.phase)
    }

    @Test
    fun myCameos_unfinishedLoaded_falseUntilQueryCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val base = FakePendingRepo()
        val pending = object : PendingCameoRepository by base {
            override suspend fun list(): List<PendingCameo> {
                gate.await()
                return base.list()
            }
        }
        val vm = myCameosVm(pending = pending, quota = quotaOk())
        assertFalse(vm.unfinishedLoaded.value)
        vm.onResume()
        advanceUntilIdle()
        assertFalse(vm.unfinishedLoaded.value) // 查詢卡住
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.unfinishedLoaded.value)
    }

    // ---- MyCameosViewModel：可用點數 ----

    private fun scriptedPoint(available: Int, held: Int = 0): suspend () -> DomainResult<Points> =
        { DomainResult.Success(Points(available, held)) }

    @Test
    fun myCameos_available_isAvailableNotHeld() = runTest {
        val points = ScriptedPoints()
        points.queue.addLast(scriptedPoint(90, 10))
        val vm = myCameosVm(quota = quotaOk(), points = points)
        assertNull(vm.available.value)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(90, vm.available.value)
    }

    @Test
    fun myCameos_available_firstFailureStaysNull_nextResumeLoads() = runTest {
        val points = ScriptedPoints()
        points.queue.addLast { failure }
        points.queue.addLast(scriptedPoint(70))
        val vm = myCameosVm(quota = quotaOk(), points = points)
        vm.onResume()
        advanceUntilIdle()
        assertNull(vm.available.value)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(70, vm.available.value)
    }

    @Test
    fun myCameos_available_failureKeepsOldValue() = runTest {
        val points = ScriptedPoints()
        points.queue.addLast(scriptedPoint(90))
        points.queue.addLast { failure }
        val vm = myCameosVm(quota = quotaOk(), points = points)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(90, vm.available.value)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(90, vm.available.value)
    }

    @Test
    fun myCameos_available_onResumeUpdatesToNewValue() = runTest {
        val points = ScriptedPoints()
        points.queue.addLast(scriptedPoint(90))
        points.queue.addLast(scriptedPoint(60))
        val vm = myCameosVm(quota = quotaOk(), points = points)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(90, vm.available.value)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(60, vm.available.value)
    }

    @Test
    fun myCameos_available_olderResponseDoesNotOverwriteNewer() = runTest {
        val first = CompletableDeferred<DomainResult<Points>>()
        val points = ScriptedPoints()
        points.queue.addLast { first.await() }
        points.queue.addLast(scriptedPoint(60))
        val vm = myCameosVm(quota = quotaOk(), points = points)
        vm.onResume() // 第一次查詢卡住
        advanceUntilIdle()
        vm.onResume() // 第二次較新，先完成
        advanceUntilIdle()
        assertEquals(60, vm.available.value)
        first.complete(DomainResult.Success(Points(10, 0))) // 較舊的才回來
        advanceUntilIdle()
        assertEquals(60, vm.available.value)
    }

    @Test
    fun myCameos_unfinishedLoaded_notSetWhenCancelledByNewerQuery() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val gates = ArrayDeque(listOf(gate1, gate2))
        val base = FakePendingRepo()
        val pending = object : PendingCameoRepository by base {
            override suspend fun list(): List<PendingCameo> {
                gates.removeFirst().await()
                return base.list()
            }
        }
        val vm = myCameosVm(pending = pending, quota = quotaOk())
        vm.onResume() // 第一次待處理查詢卡住
        advanceUntilIdle()
        vm.onResume() // 取消第一次，第二次也卡住
        advanceUntilIdle()
        assertFalse(vm.unfinishedLoaded.value)
        gate1.complete(Unit) // 被取消的第一次放行
        advanceUntilIdle()
        assertFalse(vm.unfinishedLoaded.value)
        gate2.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.unfinishedLoaded.value)
    }

    @Test
    fun myCameos_remove_whenQuotaZero_stillDeletesDraftAndRefreshes() = runTest {
        val pending = FakePendingRepo()
        pending.rows += draftRow
        val quota = FakeQuota()
        repeat(2) { quota.queue.addLast { DomainResult.Success(quotaOf(0)) } }
        val vm = myCameosVm(pending = pending, quota = quota)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(1, vm.unfinished.value.size)
        vm.remove(vm.unfinished.value[0])
        advanceUntilIdle()
        assertTrue(pending.rows.isEmpty())
        assertTrue(vm.unfinished.value.isEmpty())
    }
}
