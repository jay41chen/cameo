package io.github.jay41chen.cameo.domain.usecase

import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeTrackingUseCaseTest {
    private val events = mutableListOf<String>()
    private val pending = FakePending(events)
    private val cameos = FakeCameos(events)
    private val resume = ResumeTrackingUseCase(pending, cameos)
    private val notified = mutableListOf<String>()

    /** 回傳追蹤清單；完成的訂單 id 累積在 [notified]。 */
    private suspend fun run(enabled: Boolean = true): List<String> =
        resume(enabled) { notified += it.cameo.id }

    private fun row(key: String, cameoId: String?) {
        pending.rows += PendingCameo(key, "c1", cameoId, PendingStep.TRACK, null)
    }

    private fun server(id: String, status: CameoStatus) {
        cameos.gets[id] = io.github.jay41chen.cameo.domain.model.DomainResult.Success(order(status, id))
    }

    @Test
    fun finished_claimedThenReportedOnce() = runTest {
        row("k1", "u1")
        server("u1", CameoStatus.SUCCEEDED)
        val first = run()
        assertEquals(listOf("u1"), notified)
        assertTrue(first.isEmpty())
        assertTrue(pending.rows.isEmpty())
        // 第二次：草稿已刪，不會再通知
        run()
        assertEquals(listOf("u1"), notified)
    }

    @Test
    fun failedStatus_alsoNotified() = runTest {
        row("k1", "u1")
        server("u1", CameoStatus.FAILED)
        run()
        assertEquals(listOf("u1"), notified)
    }

    @Test
    fun claimFails_noNotification() = runTest {
        row("k1", "u1")
        server("u1", CameoStatus.SUCCEEDED)
        // 查完訂單之後、認領之前，別條路徑已經認領並刪了草稿
        cameos.getScript.addLast {
            pending.rows.clear()
            cameos.gets.getValue("u1")
        }
        val r = run()
        assertTrue(notified.isEmpty())
        assertTrue(r.isEmpty())
    }

    @Test
    fun finished_goesThroughClaim() = runTest {
        row("k1", "u1")
        server("u1", CameoStatus.SUCCEEDED)
        run()
        assertTrue("claim:u1" in events)
    }

    @Test
    fun notificationsDisabled_doesNotClaim_keepsDraft() = runTest {
        row("k1", "u1")
        server("u1", CameoStatus.SUCCEEDED)
        run(enabled = false)
        assertTrue(notified.isEmpty())
        assertEquals(1, pending.rows.size)
        assertFalse("claim:u1" in events)
    }

    @Test
    fun queuedAndGenerating_areTracked_notClaimed() = runTest {
        row("k1", "u1")
        row("k2", "u2")
        server("u1", CameoStatus.QUEUED)
        server("u2", CameoStatus.GENERATING)
        val r = run()
        assertEquals(listOf("u1", "u2"), r)
        assertTrue(notified.isEmpty())
        assertEquals(2, pending.rows.size)
    }

    @Test
    fun queryFailure_skipped_otherStatesUntouched() = runTest {
        row("k1", "u1")
        row("k2", "u2")
        row("k3", "u3")
        row("k4", null)
        cameos.gets["u1"] = fail(retryable = true, code = "NETWORK")
        server("u2", CameoStatus.AWAITING_UPLOAD)
        server("u3", CameoStatus.EXPIRED)
        val r = run()
        assertTrue(r.isEmpty())
        assertTrue(notified.isEmpty())
        assertEquals(4, pending.rows.size)
        assertEquals(listOf("u1", "u2", "u3"), cameos.getCalls)
    }

    @Test
    fun cancelledWhileQueryingSecond_firstStillNotifiedExactlyOnce() = runTest {
        row("k1", "u1")
        row("k2", "u2")
        server("u1", CameoStatus.SUCCEEDED)
        server("u2", CameoStatus.SUCCEEDED)
        var running: Job? = null
        // 第二筆的查詢開始時取消協程：第一筆已在迴圈內認領並通知
        cameos.getScript.addLast { cameos.gets.getValue("u1") }
        cameos.getScript.addLast {
            running!!.cancel()
            throw CancellationException("cancelled while querying u2")
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            running = coroutineContext[Job]
            run()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("u1"), notified)
        assertEquals(listOf("u1"), pending.claims)
        // 之後再回前景：u1 草稿已刪不會重發；u2 還在，這次才通知
        run()
        assertEquals(listOf("u1", "u2"), notified)
    }

    @Test
    fun cancelledDuringClaim_rowDeleted_stillNotifiedExactlyOnce() = runTest {
        row("k1", "u1")
        server("u1", CameoStatus.SUCCEEDED)
        var running: Job? = null
        // 認領進行中（已換到別的執行緒）呼叫端被取消，然後刪列並回傳 true
        val racing = object : PendingCameoRepository by pending {
            override suspend fun claimFinishedCameo(cameoId: String): Boolean =
                withContext(NonCancellable + Dispatchers.Default) {
                    running!!.cancel()
                    pending.rows.removeAll { it.cameoId == cameoId }
                    true
                }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            running = coroutineContext[Job]
            ResumeTrackingUseCase(racing, cameos)(true) { notified += it.cameo.id }
        }
        job.join()
        assertTrue(pending.rows.isEmpty())
        assertEquals(listOf("u1"), notified)
    }

    @Test
    fun claimUseCase_delegatesToRepository() = runTest {
        row("k1", "u1")
        val claim = ClaimFinishedCameoUseCase(pending)
        assertTrue(claim("u1"))
        assertFalse(claim("u1"))
    }
}
