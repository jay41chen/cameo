package com.kimhit.cameo.ui

import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.LedgerPage
import com.kimhit.cameo.domain.model.Page
import com.kimhit.cameo.domain.model.Points
import com.kimhit.cameo.domain.repository.PointsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun cameoOf(id: String, status: CameoStatus, title: String = "標題-$id") =
    Cameo(id, Clip("c1", "雨夜重逢", 10), status, title, "訊息")

class PollTargetsTest {
    private val items = listOf(
        cameoOf("q", CameoStatus.QUEUED),
        cameoOf("g", CameoStatus.GENERATING),
        cameoOf("s", CameoStatus.SUCCEEDED),
        cameoOf("f", CameoStatus.FAILED),
        cameoOf("a", CameoStatus.AWAITING_UPLOAD),
        cameoOf("u", CameoStatus.UPLOADED),
        cameoOf("e", CameoStatus.EXPIRED),
        cameoOf("x", CameoStatus.UNKNOWN),
    )
    private val all: Set<Any> = items.map { it.id }.toSet()

    @Test
    fun onlyVisibleQueuedAndGeneratingAreTargets() {
        assertEquals(listOf("q", "g"), pollTargets(items, all))
    }

    @Test
    fun invisibleItemsAreNotTargets() {
        assertEquals(listOf("g"), pollTargets(items, setOf("g", "s")))
        assertEquals(emptyList<String>(), pollTargets(items, emptySet()))
    }

    @Test
    fun finishedAndNonTrackedStatusesAreNeverTargets() {
        assertEquals(emptyList<String>(), pollTargets(items, setOf("s", "f", "a", "u", "e", "x")))
    }

    @Test
    fun foreignKeysDoNotAffectTargets() {
        val keys: Set<Any> = setOf("q", "pending-client-request-id", "pending_header", 42)
        assertEquals(listOf("q"), pollTargets(items, keys))
    }
}

private class PollEnv(var listed: List<Cameo>) {
    val calls = mutableListOf<String>()
    val replies = mutableMapOf<String, ArrayDeque<DomainResult<CameoOrder>>>()
    val stuck = mutableMapOf<String, CompletableDeferred<Unit>>()
    val points = CountingPoints()

    suspend fun get(id: String): DomainResult<CameoOrder> {
        calls += id
        stuck[id]?.await()
        return replies[id]?.removeFirstOrNull()
            ?: DomainResult.Success(CameoOrder(listed.first { it.id == id }, null))
    }

    fun reply(id: String, vararg results: DomainResult<CameoOrder>) {
        replies[id] = ArrayDeque(results.toList())
    }
}

private class CountingPoints : PointsRepository {
    var calls = 0
    override suspend fun getPoints(): DomainResult<Points> {
        calls++
        return DomainResult.Success(Points(77, 0))
    }

    override suspend fun getLedger(cursor: String?): DomainResult<LedgerPage> = error("not used")
}

private fun order(id: String, status: CameoStatus, title: String = "標題-$id") =
    DomainResult.Success(CameoOrder(cameoOf(id, status, title), null))

@OptIn(ExperimentalCoroutinesApi::class)
class PollVisibleTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(env: PollEnv): MyCameosViewModel =
        myCameosVm(
            listCameos = { DomainResult.Success(Page(env.listed, null)) },
            points = env.points,
            getCameo = { env.get(it) },
        ).also { advanceUntilIdle() }

    private fun TestScope.startPoll(vm: MyCameosViewModel, keys: Flow<Set<Any>>) =
        backgroundScope.launch { vm.pollVisible(keys) }.also { runCurrent() }

    private fun TestScope.advanceBy(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun keysOf(vararg k: Any) = MutableStateFlow<Set<Any>>(k.toSet())

    private val q = { id: String -> cameoOf(id, CameoStatus.QUEUED) }

    @Test
    fun firstQueryAfterDebounceThenEveryInterval() = runTest {
        val env = PollEnv(listOf(q("a")))
        startPoll(vm(env), keysOf("a"))
        advanceBy(299)
        assertEquals(0, env.calls.size)
        advanceBy(1)
        assertEquals(1, env.calls.size)
        advanceBy(2999)
        assertEquals(1, env.calls.size)
        advanceBy(1)
        assertEquals(2, env.calls.size)
        advanceBy(3000)
        assertEquals(3, env.calls.size)
    }

    @Test
    fun onlyVisibleItemsAreQueried() = runTest {
        val env = PollEnv(listOf(q("a"), cameoOf("b", CameoStatus.GENERATING)))
        val keys = keysOf("a")
        startPoll(vm(env), keys)
        advanceBy(300)
        assertEquals(listOf("a"), env.calls)
        keys.value = emptySet()
        advanceBy(10_000)
        assertEquals(listOf("a"), env.calls)
    }

    @Test
    fun finishedItemsAreNotQueried() = runTest {
        val env = PollEnv(
            listOf(
                cameoOf("s", CameoStatus.SUCCEEDED),
                cameoOf("f", CameoStatus.FAILED),
                cameoOf("w", CameoStatus.AWAITING_UPLOAD),
                q("d"),
            ),
        )
        startPoll(vm(env), keysOf("s", "f", "w", "d"))
        advanceBy(300)
        assertEquals(listOf("d"), env.calls)
    }

    @Test
    fun itemBecomingSucceededIsNotQueriedAgain() = runTest {
        val env = PollEnv(listOf(q("e")))
        env.reply("e", order("e", CameoStatus.SUCCEEDED))
        val vm = vm(env)
        startPoll(vm, keysOf("e"))
        advanceBy(300)
        assertEquals(CameoStatus.SUCCEEDED, vm.state.value.items.single().status)
        advanceBy(10_000)
        assertEquals(listOf("e"), env.calls)
    }

    @Test
    fun noTargetsMeansNoRequestsUntilTargetAppears() = runTest {
        val env = PollEnv(listOf(cameoOf("a", CameoStatus.SUCCEEDED), q("b")))
        val keys = keysOf("a")
        startPoll(vm(env), keys)
        advanceBy(10_000)
        assertEquals(0, env.calls.size)
        keys.value = setOf("a", "b")
        advanceBy(299)
        assertEquals(0, env.calls.size)
        advanceBy(1)
        assertEquals(listOf("b"), env.calls)
    }

    @Test
    fun scrollingRestartsDebounceAndInterval() = runTest {
        val env = PollEnv(listOf(q("a"), q("b"), q("c")))
        val keys = keysOf("a")
        startPoll(vm(env), keys)
        advanceBy(200)
        keys.value = setOf("b")
        advanceBy(200) // t=400：a 的 300ms 已過，但被取消；b 要到 500
        assertEquals(0, env.calls.size)
        keys.value = setOf("c")
        advanceBy(299) // t=699
        assertEquals(0, env.calls.size)
        advanceBy(1) // t=700
        assertEquals(listOf("c"), env.calls)
        advanceBy(1300) // t=2000
        keys.value = setOf("a")
        advanceBy(299) // t=2299：舊循環下一次是 3700，新的要 2300
        assertEquals(listOf("c"), env.calls)
        advanceBy(1) // t=2300
        assertEquals(listOf("c", "a"), env.calls)
        advanceBy(2999) // t=5299
        assertEquals(2, env.calls.size)
        advanceBy(1) // t=5300
        assertEquals(listOf("c", "a", "a"), env.calls)
    }

    @Test
    fun failedQueryChangesNothingAndNextRoundRetries() = runTest {
        val env = PollEnv(listOf(q("a")))
        env.reply(
            "a",
            DomainResult.Failure(DomainError("網路錯誤", true)),
            order("a", CameoStatus.SUCCEEDED),
        )
        val vm = vm(env)
        val events = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshErrors.collect { events += it } }
        val before = vm.state.value
        startPoll(vm, keysOf("a"))
        advanceBy(300)
        assertEquals(1, env.calls.size)
        assertEquals(before, vm.state.value)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.refreshFailed)
        assertTrue(events.isEmpty())
        advanceBy(3000)
        assertEquals(2, env.calls.size)
        assertEquals(CameoStatus.SUCCEEDED, vm.state.value.items.single().status)
        assertTrue(events.isEmpty())
    }

    @Test
    fun successUpdatesRowInPlaceKeepingOrder() = runTest {
        val env = PollEnv(listOf(q("a"), q("b"), q("c")))
        env.reply("b", order("b", CameoStatus.GENERATING, title = "新標題"))
        val vm = vm(env)
        startPoll(vm, keysOf("a", "b", "c"))
        advanceBy(300)
        val items = vm.state.value.items
        assertEquals(listOf("a", "b", "c"), items.map { it.id })
        assertEquals(cameoOf("b", CameoStatus.GENERATING, "新標題"), items[1])
        assertEquals(CameoStatus.QUEUED, items[0].status)
    }

    @Test
    fun becomingFailedRefreshesPointsImmediately() = runTest {
        val env = PollEnv(listOf(q("a")))
        env.reply("a", order("a", CameoStatus.FAILED))
        val vm = vm(env)
        assertNull(vm.available.value)
        startPoll(vm, keysOf("a"))
        advanceBy(299)
        assertEquals(0, env.points.calls)
        advanceBy(1)
        assertEquals(1, env.points.calls)
        assertEquals(77, vm.available.value)
        assertEquals(CameoStatus.FAILED, vm.state.value.items.single().status)
    }

    @Test
    fun becomingSucceededDoesNotRefreshPoints() = runTest {
        val env = PollEnv(listOf(q("a")))
        env.reply("a", order("a", CameoStatus.SUCCEEDED))
        val vm = vm(env)
        startPoll(vm, keysOf("a"))
        advanceBy(300)
        assertEquals(CameoStatus.SUCCEEDED, vm.state.value.items.single().status)
        assertEquals(0, env.points.calls)
        assertNull(vm.available.value)
    }

    @Test
    fun alreadyFailedRowDoesNotRefreshPointsAgain() = runTest {
        val env = PollEnv(listOf(q("a")))
        env.stuck["a"] = CompletableDeferred()
        env.reply("a", order("a", CameoStatus.FAILED))
        val vm = vm(env)
        startPoll(vm, keysOf("a"))
        advanceBy(300)
        assertEquals(1, env.calls.size) // 查詢進行中
        env.listed = listOf(cameoOf("a", CameoStatus.FAILED)) // 同時列表刷新已把它換成 FAILED
        vm.refresh()
        advanceBy(0)
        assertEquals(CameoStatus.FAILED, vm.state.value.items.single().status)
        env.stuck.getValue("a").complete(Unit)
        advanceBy(0)
        assertEquals(0, env.points.calls)
    }

    @Test
    fun cancelStopsPollingAndAbortsInFlightQuery() = runTest {
        val env = PollEnv(listOf(q("a")))
        val vm = vm(env)
        val job = startPoll(vm, keysOf("a"))
        advanceBy(300)
        assertEquals(1, env.calls.size)
        job.cancel()
        advanceBy(20_000)
        assertEquals(1, env.calls.size)

        // 進行中的查詢被取消：不更新列、不查點數
        val env2 = PollEnv(listOf(q("a")))
        env2.stuck["a"] = CompletableDeferred()
        env2.reply("a", order("a", CameoStatus.FAILED))
        val vm2 = vm(env2)
        val job2 = startPoll(vm2, keysOf("a"))
        advanceBy(300)
        assertEquals(1, env2.calls.size)
        job2.cancel()
        env2.stuck.getValue("a").complete(Unit)
        advanceBy(20_000)
        assertEquals(CameoStatus.QUEUED, vm2.state.value.items.single().status)
        assertEquals(0, env2.points.calls)
        assertEquals(1, env2.calls.size)
    }

    @Test
    fun callingAgainAfterCancelRestartsWithDebounce() = runTest {
        val env = PollEnv(listOf(q("a")))
        val vm = vm(env)
        val keys = keysOf("a")
        val job = startPoll(vm, keys)
        advanceBy(300)
        assertEquals(1, env.calls.size)
        job.cancel()
        advanceBy(5000)
        startPoll(vm, keys)
        advanceBy(299)
        assertEquals(1, env.calls.size)
        advanceBy(1)
        assertEquals(2, env.calls.size)
    }

    @Test
    fun stuckQueryDoesNotBlockOtherRowsInSameRound() = runTest {
        val env = PollEnv(listOf(q("a"), q("b")))
        env.stuck["a"] = CompletableDeferred()
        env.reply("b", order("b", CameoStatus.GENERATING, title = "b 更新"))
        val vm = vm(env)
        startPoll(vm, keysOf("a", "b"))
        advanceBy(300)
        assertEquals(listOf("a", "b"), env.calls)
        assertEquals("b 更新", vm.state.value.items[1].displayTitle)
        advanceBy(10_000) // a 還卡著，這輪沒結束，不會開始下一輪
        assertEquals(2, env.calls.size)
        env.reply("a", order("a", CameoStatus.SUCCEEDED))
        env.stuck.getValue("a").complete(Unit)
        advanceBy(0)
        assertEquals(CameoStatus.SUCCEEDED, vm.state.value.items[0].status)
    }
}
