package com.kimhit.cameo.ui

import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.LedgerEntry
import com.kimhit.cameo.domain.model.LedgerPage
import com.kimhit.cameo.domain.model.LedgerType
import com.kimhit.cameo.domain.model.Points
import com.kimhit.cameo.domain.repository.ClipRepository
import com.kimhit.cameo.domain.repository.PointsRepository
import com.kimhit.cameo.domain.usecase.GetClipsUseCase
import com.kimhit.cameo.domain.usecase.GetLedgerPageUseCase
import com.kimhit.cameo.domain.usecase.GetPointsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class FakeClips(val results: ArrayDeque<DomainResult<List<Clip>>>) : ClipRepository {
    override suspend fun getClips() = results.removeFirst()
}

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
        val vm = ClipsViewModel(GetClipsUseCase(FakeClips(ArrayDeque(listOf(DomainResult.Success(clips))))))
        assertEquals(Load.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(Load.Success(clips), vm.state.value)
    }

    @Test
    fun clips_failureThenRetrySuccess() = runTest {
        val clips = listOf(Clip("c1", "雨夜重逢", 10))
        val vm = ClipsViewModel(
            GetClipsUseCase(FakeClips(ArrayDeque(listOf(failure, DomainResult.Success(clips))))),
        )
        advanceUntilIdle()
        assertEquals(Load.Error("後端訊息"), vm.state.value)
        vm.load()
        assertEquals(Load.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(Load.Success(clips), vm.state.value)
    }

    @Test
    fun clips_failureWithoutMessageKeepsNullMessage() = runTest {
        val vm = ClipsViewModel(
            GetClipsUseCase(FakeClips(ArrayDeque(listOf(DomainResult.Failure(DomainError(null, false)))))),
        )
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
        assertEquals(LedgerPhase.Loading, vm.ledger.value.phase)
        advanceUntilIdle()
        assertEquals(Load.Success(Points(100, 0)), vm.points.value)
        assertEquals(LedgerUiState(phase = LedgerPhase.Idle), vm.ledger.value)
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
        assertEquals(LedgerPhase.Error, vm.ledger.value.phase)
        assertEquals("後端訊息", vm.ledger.value.errorMessage)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(Load.Success(Points(90, 10)), vm.points.value)
        assertEquals(listOf(entry("l1")), vm.ledger.value.items)
        assertEquals(LedgerPhase.Idle, vm.ledger.value.phase)
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
        assertEquals(LedgerPhase.LoadingMore, vm.ledger.value.phase)
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
        assertEquals(LedgerPhase.LoadMoreError, vm.ledger.value.phase)
        assertEquals(listOf("l1"), vm.ledger.value.items.map { it.id })
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("l1", "l2"), vm.ledger.value.items.map { it.id })
        assertEquals(LedgerPhase.Idle, vm.ledger.value.phase)
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
}
