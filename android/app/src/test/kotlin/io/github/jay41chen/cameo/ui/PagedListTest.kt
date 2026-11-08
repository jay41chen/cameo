package io.github.jay41chen.cameo.ui

import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Result = DomainResult<Page<String>>

private fun ok(vararg items: String, next: String? = null): Result = DomainResult.Success(Page(items.toList(), next))
private fun fail(message: String = "失敗"): Result = DomainResult.Failure(DomainError(message, true))

/** 每次 fetch 依序取走一個可控制完成時機的 [CompletableDeferred]，並記錄傳入的 cursor。 */
private class Gates {
    val cursors = mutableListOf<String?>()
    private val queue = ArrayDeque<CompletableDeferred<Result>>()

    fun next(): CompletableDeferred<Result> = CompletableDeferred<Result>().also { queue.addLast(it) }

    suspend fun fetch(cursor: String?): Result {
        cursors += cursor
        return queue.removeFirst().await()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PagedListTest {
    private fun TestScope.list(g: Gates) = PagedList(CoroutineScope(StandardTestDispatcher(testScheduler))) { g.fetch(it) }

    /** 載入好第一頁（a，cursor c1），回到 Idle。 */
    private fun TestScope.loaded(g: Gates): PagedList<String> {
        val l = list(g)
        g.next().complete(ok("a", next = "c1"))
        l.loadFirstPage()
        advanceUntilIdle()
        return l
    }

    private fun TestScope.mergeList(g: Gates) =
        PagedList<String>(CoroutineScope(StandardTestDispatcher(testScheduler)), idOf = { it.substringBefore(":") }) { g.fetch(it) }

    /** 合併模式：第一頁 a:1、b:1（游標 c1），再 loadMore 取得 c:1、d:1（游標 c2）。 */
    private fun TestScope.mergeLoaded(g: Gates, firstCursor: String? = "c1"): PagedList<String> {
        val l = mergeList(g)
        g.next().complete(ok("a:1", "b:1", next = firstCursor))
        l.loadFirstPage()
        advanceUntilIdle()
        if (firstCursor != null) {
            g.next().complete(ok("c:1", "d:1", next = "c2"))
            l.loadMore()
            advanceUntilIdle()
        }
        return l
    }

    @Test
    fun loadFirstPage_successThenFailureResetsState() = runTest {
        val g = Gates()
        val l = list(g)
        val first = g.next()
        l.loadFirstPage()
        assertEquals(PagePhase.Loading, l.state.value.phase)
        first.complete(ok("a", next = "c1"))
        advanceUntilIdle()
        assertEquals(PagedUiState(listOf("a"), "c1", PagePhase.Idle), l.state.value)
        g.next().complete(fail("壞了"))
        l.loadFirstPage()
        assertEquals(PagedUiState<String>(phase = PagePhase.Loading), l.state.value)
        advanceUntilIdle()
        assertEquals(PagedUiState<String>(phase = PagePhase.Error, errorMessage = "壞了"), l.state.value)
    }

    @Test
    fun loadMore_appendsNewItemsAfterOld() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(ok("b", next = null))
        l.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), l.state.value.items)
        assertNull(l.state.value.nextCursor)
        assertEquals(PagePhase.Idle, l.state.value.phase)
    }

    @Test
    fun loadMore_usesCursorFromPreviousPage() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(ok("b", next = "c2"))
        l.loadMore()
        advanceUntilIdle()
        g.next().complete(ok("c"))
        l.loadMore()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null, "c1", "c2"), g.cursors)
    }

    @Test
    fun refresh_failureKeepsItemsAndSetsRefreshFailed() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(fail("更新失敗"))
        l.refresh()
        advanceUntilIdle()
        val s = l.state.value
        assertEquals(listOf("a"), s.items)
        assertEquals("c1", s.nextCursor)
        assertEquals(PagePhase.Idle, s.phase)
        assertTrue(s.refreshFailed)
        assertEquals("更新失敗", s.errorMessage)
    }

    @Test
    fun refresh_successClearsEarlierRefreshFailed() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(fail())
        l.refresh()
        advanceUntilIdle()
        assertTrue(l.state.value.refreshFailed)
        g.next().complete(ok("n", next = null))
        l.refresh()
        advanceUntilIdle()
        val s = l.state.value
        assertFalse(s.refreshFailed)
        assertNull(s.errorMessage)
        assertEquals(listOf("n"), s.items)
        assertNull(s.nextCursor)
    }

    @Test
    fun refresh_inFlightOnlySetsRefreshing() = runTest {
        val g = Gates()
        val l = loaded(g)
        val before = l.state.value
        assertFalse(before.refreshing)
        g.next()
        l.refresh()
        advanceUntilIdle()
        assertEquals(before.copy(refreshing = true), l.state.value)
    }

    @Test
    fun refresh_successClearsRefreshing() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(ok("n"))
        l.refresh()
        advanceUntilIdle()
        assertFalse(l.state.value.refreshing)
        assertEquals(listOf("n"), l.state.value.items)
    }

    @Test
    fun refresh_failureClearsRefreshingAndSetsRefreshFailed() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(fail("更新失敗"))
        l.refresh()
        advanceUntilIdle()
        assertFalse(l.state.value.refreshing)
        assertTrue(l.state.value.refreshFailed)
    }

    @Test
    fun refresh_retryAfterFailureShowsRefreshingWithRefreshFailedStillSet() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(fail("更新失敗"))
        l.refresh()
        advanceUntilIdle()
        g.next()
        l.refresh()
        advanceUntilIdle()
        assertTrue(l.state.value.refreshFailed)
        assertTrue(l.state.value.refreshing)
    }

    @Test
    fun loadMore_duringRefresh_clearsRefreshing() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next()
        g.next()
        l.refresh()
        advanceUntilIdle()
        assertTrue(l.state.value.refreshing)
        l.loadMore()
        advanceUntilIdle()
        assertFalse(l.state.value.refreshing)
        assertEquals(PagePhase.LoadingMore, l.state.value.phase)
    }

    @Test
    fun loadFirstPage_duringRefresh_clearsRefreshing() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next()
        val fresh = g.next()
        l.refresh()
        advanceUntilIdle()
        assertTrue(l.state.value.refreshing)
        l.loadFirstPage()
        advanceUntilIdle()
        assertFalse(l.state.value.refreshing)
        fresh.complete(ok("x"))
        advanceUntilIdle()
        assertFalse(l.state.value.refreshing)
        assertEquals(listOf("x"), l.state.value.items)
    }

    @Test
    fun loadMore_afterRefreshFailed_clearsRefreshFailedOnStart() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(fail("更新失敗"))
        l.refresh()
        advanceUntilIdle()
        assertTrue(l.state.value.refreshFailed)
        g.next()
        l.loadMore()
        advanceUntilIdle()
        assertEquals(PagePhase.LoadingMore, l.state.value.phase)
        assertFalse(l.state.value.refreshFailed)
        assertNull(l.state.value.errorMessage)
    }

    @Test
    fun refresh_olderResponseDoesNotOverwriteNewer() = runTest {
        val g = Gates()
        val l = loaded(g)
        val a = g.next()
        val b = g.next()
        l.refresh()
        advanceUntilIdle()
        l.refresh()
        advanceUntilIdle()
        b.complete(ok("b"))
        advanceUntilIdle()
        a.complete(ok("a-late"))
        advanceUntilIdle()
        assertEquals(listOf("b"), l.state.value.items)
    }

    @Test
    fun loadFirstPage_oldLoadMoreArrivingLateDoesNotOverwrite() = runTest {
        val g = Gates()
        val l = loaded(g)
        val more = g.next()
        val fresh = g.next()
        l.loadMore()
        advanceUntilIdle()
        l.loadFirstPage()
        advanceUntilIdle()
        fresh.complete(ok("x"))
        advanceUntilIdle()
        more.complete(ok("old"))
        advanceUntilIdle()
        assertEquals(listOf("x"), l.state.value.items)
        assertEquals(PagePhase.Idle, l.state.value.phase)
    }

    @Test
    fun loadMore_whileLoadingMore_fetchesOnlyOnce() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next()
        l.loadMore()
        advanceUntilIdle()
        l.loadMore()
        advanceUntilIdle()
        l.loadMore()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null, "c1"), g.cursors)
    }

    @Test
    fun loadMore_startedDuringRefresh_lateRefreshDoesNotOverwrite() = runTest {
        val g = Gates()
        val l = loaded(g)
        val refresh = g.next()
        val more = g.next()
        l.refresh()
        advanceUntilIdle()
        l.loadMore()
        advanceUntilIdle()
        assertEquals(PagePhase.LoadingMore, l.state.value.phase)
        refresh.complete(ok("r"))
        advanceUntilIdle()
        assertEquals(listOf("a"), l.state.value.items)
        assertEquals(PagePhase.LoadingMore, l.state.value.phase)
        more.complete(ok("b"))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), l.state.value.items)
        assertEquals(PagePhase.Idle, l.state.value.phase)
    }

    @Test
    fun refresh_whileLoading_doesNotFetch() = runTest {
        val g = Gates()
        val l = list(g)
        g.next()
        l.loadFirstPage()
        advanceUntilIdle()
        l.refresh()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), g.cursors)
    }

    @Test
    fun refresh_whileLoadMoreErrorOrError_doesNotFetch() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(fail())
        l.loadMore()
        advanceUntilIdle()
        assertEquals(PagePhase.LoadMoreError, l.state.value.phase)
        l.refresh()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null, "c1"), g.cursors)
        assertFalse(l.state.value.refreshing)

        val g2 = Gates()
        val l2 = list(g2)
        g2.next().complete(fail("壞了"))
        l2.loadFirstPage()
        advanceUntilIdle()
        assertEquals(PagePhase.Error, l2.state.value.phase)
        l2.refresh()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), g2.cursors)
        assertFalse(l2.state.value.refreshing)
    }

    @Test
    fun loadMore_failureKeepsItems_thenRetrySucceeds() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(fail("分頁失敗"))
        l.loadMore()
        advanceUntilIdle()
        val s = l.state.value
        assertEquals(PagePhase.LoadMoreError, s.phase)
        assertEquals(listOf("a"), s.items)
        assertEquals("分頁失敗", s.errorMessage)
        g.next().complete(ok("b"))
        l.loadMore()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null, "c1", "c1"), g.cursors)
        assertEquals(listOf("a", "b"), l.state.value.items)
        assertEquals(PagePhase.Idle, l.state.value.phase)
        assertNull(l.state.value.errorMessage)
    }

    @Test
    fun loadMore_withoutNextCursor_doesNothing() = runTest {
        val g = Gates()
        val l = list(g)
        g.next().complete(ok("a"))
        l.loadFirstPage()
        advanceUntilIdle()
        l.loadMore()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), g.cursors)
    }

    @Test
    fun merge_overlap_newFirst_existingUpdatedInPlace_laterPagesAndCursorKept() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        g.next().complete(ok("n:1", "b:2", next = "other"))
        l.refresh()
        advanceUntilIdle()
        val s = l.state.value
        assertEquals(listOf("n:1", "a:1", "b:2", "c:1", "d:1"), s.items)
        assertEquals("c2", s.nextCursor)
        assertFalse(s.refreshing)
        assertFalse(s.refreshFailed)
    }

    @Test
    fun merge_multipleNewKeepFirstPageOrder() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        g.next().complete(ok("n2:1", "n1:1", "a:2", next = "x"))
        l.refresh()
        advanceUntilIdle()
        assertEquals(listOf("n2:1", "n1:1", "a:2", "b:1", "c:1", "d:1"), l.state.value.items)
    }

    @Test
    fun merge_allExistingKeepsOrderAndCount() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        g.next().complete(ok("b:2", "a:2", next = "x"))
        l.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a:2", "b:2", "c:1", "d:1"), l.state.value.items)
    }

    @Test
    fun merge_noOverlap_replacesAllIncludingCursor() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        g.next().complete(ok("x:1", "y:1", next = "cx"))
        l.refresh()
        advanceUntilIdle()
        assertEquals(listOf("x:1", "y:1"), l.state.value.items)
        assertEquals("cx", l.state.value.nextCursor)
    }

    @Test
    fun merge_emptyPage_clearsListAndTakesEmptyPageCursor() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        g.next().complete(ok())
        l.refresh()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), l.state.value.items)
        assertNull(l.state.value.nextCursor)
    }

    @Test
    fun merge_nullCursorStaysNullAfterOverlapRefresh() = runTest {
        val g = Gates()
        val l = mergeLoaded(g, firstCursor = null)
        assertNull(l.state.value.nextCursor)
        g.next().complete(ok("n:1", "a:2", next = "surprise"))
        l.refresh()
        advanceUntilIdle()
        assertEquals(listOf("n:1", "a:2", "b:1"), l.state.value.items)
        assertNull(l.state.value.nextCursor)
    }

    @Test
    fun merge_loadMoreAfterRefreshUsesPreservedCursor() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        g.next().complete(ok("n:1", "a:2", next = "other"))
        l.refresh()
        advanceUntilIdle()
        g.next().complete(ok("e:1"))
        l.loadMore()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null, "c1", null, "c2"), g.cursors)
    }

    @Test
    fun merge_refreshFailureKeepsListAndCursor() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        g.next().complete(fail("更新失敗"))
        l.refresh()
        advanceUntilIdle()
        val s = l.state.value
        assertEquals(listOf("a:1", "b:1", "c:1", "d:1"), s.items)
        assertEquals("c2", s.nextCursor)
        assertTrue(s.refreshFailed)
    }

    @Test
    fun noIdOf_overlapStillReplacesAllIncludingCursor() = runTest {
        val g = Gates()
        val l = loaded(g)
        g.next().complete(ok("a", "n", next = "c9"))
        l.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a", "n"), l.state.value.items)
        assertEquals("c9", l.state.value.nextCursor)
    }

    // ---- onRefreshFailed 回呼 ----

    private fun TestScope.callbackList(g: Gates, calls: MutableList<String?>) = PagedList<String>(
        CoroutineScope(StandardTestDispatcher(testScheduler)),
        onRefreshFailed = { calls += it },
    ) { g.fetch(it) }

    private fun TestScope.callbackLoaded(g: Gates, calls: MutableList<String?>): PagedList<String> {
        val l = callbackList(g, calls)
        g.next().complete(ok("a", next = "c1"))
        l.loadFirstPage()
        advanceUntilIdle()
        return l
    }

    @Test
    fun onRefreshFailed_calledOncePerFailedRefreshWithMessage() = runTest {
        val g = Gates()
        val calls = mutableListOf<String?>()
        val l = callbackLoaded(g, calls)
        g.next().complete(fail("第一次"))
        l.refresh()
        advanceUntilIdle()
        assertEquals(listOf<String?>("第一次"), calls)
        g.next().complete(fail("第二次"))
        l.refresh()
        advanceUntilIdle()
        assertEquals(listOf<String?>("第一次", "第二次"), calls)
    }

    @Test
    fun onRefreshFailed_notCalledOnRefreshSuccess() = runTest {
        val g = Gates()
        val calls = mutableListOf<String?>()
        val l = callbackLoaded(g, calls)
        g.next().complete(ok("a", "b", next = "c1"))
        l.refresh()
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun onRefreshFailed_notCalledOnLoadFirstPageOrLoadMoreFailure() = runTest {
        val g = Gates()
        val calls = mutableListOf<String?>()
        val l = callbackList(g, calls)
        g.next().complete(fail("首頁失敗"))
        l.loadFirstPage()
        advanceUntilIdle()
        g.next().complete(ok("a", next = "c1"))
        l.loadFirstPage()
        advanceUntilIdle()
        g.next().complete(fail("分頁失敗"))
        l.loadMore()
        advanceUntilIdle()
        assertEquals(PagePhase.LoadMoreError, l.state.value.phase)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun onRefreshFailed_notCalledWhenRefreshCancelledByNewAction() = runTest {
        val g = Gates()
        val calls = mutableListOf<String?>()
        val l = callbackLoaded(g, calls)
        val stuck = g.next()
        l.refresh()
        advanceUntilIdle()
        g.next().complete(ok("a", "b", next = "c2"))
        l.loadMore()
        advanceUntilIdle()
        stuck.complete(fail("遲到的失敗"))
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun updateItem_replacesInPlaceKeepingOrderAndCursor() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        val before = l.state.value
        l.updateItem("b:2")
        assertEquals(listOf("a:1", "b:2", "c:1", "d:1"), l.state.value.items)
        assertEquals(before.copy(items = listOf("a:1", "b:2", "c:1", "d:1")), l.state.value)
        assertEquals("c2", l.state.value.nextCursor)
        assertEquals(PagePhase.Idle, l.state.value.phase)
    }

    @Test
    fun updateItem_unknownIdLeavesStateUnchanged() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        val before = l.state.value
        l.updateItem("zzz:1")
        assertEquals(before, l.state.value)
    }

    @Test
    fun updateItem_withoutIdOfLeavesStateUnchanged() = runTest {
        val g = Gates()
        val l = loaded(g)
        val before = l.state.value
        l.updateItem("a")
        assertEquals(before, l.state.value)
    }

    @Test
    fun updateItem_afterLoadMoreFailure_keepsPhaseErrorAndCursor() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        g.next().complete(fail("載入更多壞了"))
        l.loadMore()
        advanceUntilIdle()
        val before = l.state.value
        assertEquals(PagePhase.LoadMoreError, before.phase)
        assertEquals("載入更多壞了", before.errorMessage)
        assertEquals("c2", before.nextCursor)
        l.updateItem("b:2")
        val after = l.state.value
        assertEquals(PagePhase.LoadMoreError, after.phase)
        assertEquals("載入更多壞了", after.errorMessage)
        assertEquals("c2", after.nextCursor)
        assertEquals(listOf("a:1", "b:2", "c:1", "d:1"), after.items)
        assertEquals(before.copy(items = listOf("a:1", "b:2", "c:1", "d:1")), after)
    }

    @Test
    fun updateItem_afterRefreshFailure_keepsRefreshFailedAndError() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        g.next().complete(fail("刷新壞了"))
        l.refresh()
        advanceUntilIdle()
        val before = l.state.value
        assertTrue(before.refreshFailed)
        assertEquals("刷新壞了", before.errorMessage)
        l.updateItem("c:2")
        val after = l.state.value
        assertTrue(after.refreshFailed)
        assertEquals("刷新壞了", after.errorMessage)
        assertEquals(PagePhase.Idle, after.phase)
        assertEquals(listOf("a:1", "b:1", "c:2", "d:1"), after.items)
        assertEquals(before.copy(items = listOf("a:1", "b:1", "c:2", "d:1")), after)
    }

    @Test
    fun updateItem_doesNotCancelLoadMore() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        val gate = g.next()
        l.loadMore()
        advanceUntilIdle()
        assertEquals(PagePhase.LoadingMore, l.state.value.phase)
        l.updateItem("a:9")
        assertEquals(PagePhase.LoadingMore, l.state.value.phase)
        gate.complete(ok("e:1", next = null))
        advanceUntilIdle()
        assertEquals(PagePhase.Idle, l.state.value.phase)
        assertEquals(listOf("a:9", "b:1", "c:1", "d:1", "e:1"), l.state.value.items)
    }

    @Test
    fun updateItem_doesNotCancelRefresh() = runTest {
        val g = Gates()
        val l = mergeLoaded(g)
        val gate = g.next()
        l.refresh()
        advanceUntilIdle()
        assertTrue(l.state.value.refreshing)
        l.updateItem("c:9")
        assertTrue(l.state.value.refreshing)
        gate.complete(ok("z:1", "a:1", "b:1", next = "c1"))
        advanceUntilIdle()
        assertFalse(l.state.value.refreshing)
        assertEquals(listOf("z:1", "a:1", "b:1", "c:9", "d:1"), l.state.value.items)
    }
}
