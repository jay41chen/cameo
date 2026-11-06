package com.kimhit.cameo.ui

import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.Page
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
}
