package io.github.jay41chen.cameo.ui

import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.Page
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import io.github.jay41chen.cameo.domain.usecase.AdvanceCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.AttachCameoPhotoUseCase
import io.github.jay41chen.cameo.domain.usecase.ConfirmCameoUseCase
import io.github.jay41chen.cameo.submission.ConfirmRun
import io.github.jay41chen.cameo.submission.SubmissionRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 計算待處理區查詢（pending.list()）次數。 */
private class CountingListPending(
    val delegate: FakePendingRepo = FakePendingRepo(),
) : PendingCameoRepository by delegate {
    var listCalls = 0
    override suspend fun list(): List<PendingCameo> {
        listCalls++
        return delegate.list()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WatchSubmissionsTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = CountingListPending()
    private val cameos = FakeCameoRepo()
    private var pageCalls = 0

    private fun TestScope.runner() = SubmissionRunner(
        ConfirmCameoUseCase(cameos, pending),
        AdvanceCameoUseCase(cameos, pending),
        AttachCameoPhotoUseCase(pending),
        FakeKeepAlive(),
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
    )

    private fun TestScope.vm(runner: SubmissionRunner) = myCameosVm(
        pending = pending,
        cameos = cameos,
        listCameos = {
            pageCalls++
            DomainResult.Success(Page(emptyList(), null))
        },
        runner = runner,
    ).also { advanceUntilIdle() }

    private fun TestScope.finishOneSubmission(runner: SubmissionRunner) {
        cameos.creates += DomainResult.Failure(DomainError(message = "壞了", retryable = true))
        pending.delegate.seedDraftWithPhoto("c1")
        runner.confirm("c1")
        advanceUntilIdle()
        assertTrue(runner.confirmState("c1").value is ConfirmRun.Finished)
    }

    @Test
    fun `watching refreshes both sections once when a submission finishes`() = runTest {
        val runner = runner()
        val vm = vm(runner)
        // Unconfined：收聽立刻開始，不依賴背景工作被排程（advanceUntilIdle 不推進只剩背景的工作）
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.watchSubmissions() }
        advanceUntilIdle()
        val pagesBefore = pageCalls
        val listBefore = pending.listCalls

        finishOneSubmission(runner)

        assertEquals(pagesBefore + 1, pageCalls)
        assertEquals(listBefore + 1, pending.listCalls)
    }

    @Test
    fun `not watching does not refresh when a submission finishes`() = runTest {
        val runner = runner()
        vm(runner)
        val pagesBefore = pageCalls
        val listBefore = pending.listCalls

        finishOneSubmission(runner)

        assertEquals(pagesBefore, pageCalls)
        assertEquals(listBefore, pending.listCalls)
    }
}
