package io.github.jay41chen.cameo.submission

import android.util.Log
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.usecase.AdvanceCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.AdvanceResult
import io.github.jay41chen.cameo.domain.usecase.AttachCameoPhotoUseCase
import io.github.jay41chen.cameo.domain.usecase.AttachResult
import io.github.jay41chen.cameo.domain.usecase.CameoStep
import io.github.jay41chen.cameo.domain.usecase.ConfirmCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.CreatedCameo
import io.github.jay41chen.cameo.tracking.SubmissionKeepAlive
import io.github.jay41chen.cameo.tracking.needsTracking
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ConfirmRun {
    data object Running : ConfirmRun
    data class Finished(val result: DomainResult<CreatedCameo>) : ConfirmRun
}

sealed interface AdvanceRun {
    data class Working(val step: CameoStep?) : AdvanceRun
    data class Finished(val result: AdvanceResult) : AdvanceRun
}

private const val RUNNER_TAG = "SubmissionRunner"

/**
 * 程序層級的提交執行者：建單、上傳、送出、轉交追蹤都跑在注入的 [scope]，不依賴畫面或 ViewModel，
 * App 在背景時流程也不會停。畫面只觀察 [confirmState]、[advanceState]。
 *
 * - 同一個 clipId 的 confirm、同一個 clientRequestId 的推進，各自最多一條進行中工作（登記在 [lock] 內原子完成）。
 * - confirm 成功（非 EXPIRED）後在同一條工作裡直接推進，並把該工作登記為該 clientRequestId 的推進工作。
 * - 工作結束只移除「進行中」登記，最後的狀態保留給之後才來觀察的畫面。
 * - keepAlive 的 release 一定在 `finally`，且在推進與 track 之後。
 * - [finished] 在 confirm、advance 各自那條工作結束時發一次（成功、失敗、例外、取消都發，在鎖外）；
 *   confirm 接著推進的整條工作只發一次；重複呼叫被擋掉與 scope 已取消的提早 return 不發。
 */
class SubmissionRunner(
    private val confirmCameo: ConfirmCameoUseCase,
    private val advanceCameo: AdvanceCameoUseCase,
    private val attachPhoto: AttachCameoPhotoUseCase,
    private val keepAlive: SubmissionKeepAlive,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val confirmFlows = mutableMapOf<String, MutableStateFlow<ConfirmRun?>>()
    private val advanceFlows = mutableMapOf<String, MutableStateFlow<AdvanceRun?>>()
    private val confirmJobs = mutableMapOf<String, Job>()
    private val advanceJobs = mutableMapOf<String, Job>()

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 每次提交工作結束時發一次，供首頁刷新已送出區與待處理區。 */
    val finished: SharedFlow<Unit> = _finished

    fun confirmState(clipId: String): StateFlow<ConfirmRun?> = synchronized(lock) { confirmFlow(clipId) }

    fun advanceState(clientRequestId: String): StateFlow<AdvanceRun?> =
        synchronized(lock) { advanceFlow(clientRequestId) }

    fun isAdvancing(clientRequestId: String): Boolean = synchronized(lock) { clientRequestId in advanceJobs }

    // 呼叫端必須持有 lock
    private fun confirmFlow(clipId: String) = confirmFlows.getOrPut(clipId) { MutableStateFlow(null) }
    private fun advanceFlow(id: String) = advanceFlows.getOrPut(id) { MutableStateFlow(null) }

    fun confirm(clipId: String, photoSourcePath: String?) {
        synchronized(lock) {
            if (clipId in confirmJobs) return
            // scope 已取消時 launch 不會執行 body、finally 也不會跑，所以不登記，直接寫可重試的失敗
            if (!scope.isActive) {
                confirmFlow(clipId).value = ConfirmRun.Finished(retryableConfirmFailure())
                return
            }
            // 在鎖內 launch 並登記；工作開頭先取一次鎖，確保登記完成後才往下，不會在登記前就結束
            val job = scope.launch {
                synchronized(lock) { } // 等外層登記完成
                val self = coroutineContext[Job]!!
                val key = "submit:$clipId"
                var ownedCrid: String? = null
                try {
                    keepAlive.acquire(key)
                    val r = runCatchingResult(
                        { confirmCameo(clipId, photoSourcePath) },
                        { retryableConfirmFailure() },
                    )
                    // 同一個鎖區塊：先登記推進，最後才寫 Finished；觀察者看到 Finished(success) 時推進已登記
                    synchronized(lock) {
                        if (r is DomainResult.Success && r.value.order.cameo.status != CameoStatus.EXPIRED) {
                            val crid = r.value.clientRequestId
                            if (crid !in advanceJobs) {
                                advanceJobs[crid] = self
                                advanceFlow(crid).value = AdvanceRun.Working(null)
                                ownedCrid = crid
                            }
                        }
                        confirmFlow(clipId).value = ConfirmRun.Finished(r)
                    }
                    ownedCrid?.let { runAdvance(it, null) }
                } finally {
                    keepAlive.release(key)
                    synchronized(lock) {
                        // 被取消或遇到沒被 catch 的 Error 時，狀態仍停在進行中，改寫成可重試的失敗
                        val cf = confirmFlow(clipId)
                        if (cf.value is ConfirmRun.Running) cf.value = ConfirmRun.Finished(retryableConfirmFailure())
                        ownedCrid?.let { crid ->
                            val af = advanceFlow(crid)
                            if (af.value is AdvanceRun.Working) af.value = AdvanceRun.Finished(retryableAdvanceFailure())
                            if (advanceJobs[crid] === self) advanceJobs.remove(crid)
                        }
                        if (confirmJobs[clipId] === self) confirmJobs.remove(clipId)
                    }
                    _finished.tryEmit(Unit)
                }
            }
            confirmJobs[clipId] = job
            // Running 同步寫入，呼叫端 confirm() 回來就能觀察到 [待確認]
            confirmFlow(clipId).value = ConfirmRun.Running
        }
    }

    fun advance(clientRequestId: String, photoSourcePath: String?) {
        synchronized(lock) {
            if (clientRequestId in advanceJobs) return
            if (!scope.isActive) {
                advanceFlow(clientRequestId).value = AdvanceRun.Finished(retryableAdvanceFailure())
                return
            }
            val job = scope.launch {
                synchronized(lock) { } // 等外層登記完成
                val self = coroutineContext[Job]!!
                val key = "advance:$clientRequestId"
                try {
                    keepAlive.acquire(key)
                    runAdvance(clientRequestId, photoSourcePath)
                } finally {
                    keepAlive.release(key)
                    synchronized(lock) {
                        val af = advanceFlow(clientRequestId)
                        if (af.value is AdvanceRun.Working) af.value = AdvanceRun.Finished(retryableAdvanceFailure())
                        if (advanceJobs[clientRequestId] === self) advanceJobs.remove(clientRequestId)
                    }
                    _finished.tryEmit(Unit)
                }
            }
            advanceJobs[clientRequestId] = job
            advanceFlow(clientRequestId).value = AdvanceRun.Working(null)
        }
    }

    /**
     * confirm 與 advance 共用：附照片（有的話）、推進、track、寫入最後狀態。
     * track 在寫入 Finished 之前完成；release 由呼叫端的 finally 負責（在這之後）。
     */
    private suspend fun runAdvance(clientRequestId: String, photoSourcePath: String?) {
        val flow = synchronized(lock) { advanceFlow(clientRequestId) }
        val result = runCatchingResult(
            {
                val attached = photoSourcePath?.let { attachPhoto(clientRequestId, it) } ?: AttachResult.Attached
                when (attached) {
                    is AttachResult.NeedPhoto -> AdvanceResult.NeedPhoto(attached.clipId)
                    AttachResult.Gone -> AdvanceResult.Gone
                    AttachResult.Attached -> {
                        val r = advanceCameo(clientRequestId) { step -> flow.value = AdvanceRun.Working(step) }
                        if (r is AdvanceResult.Done && needsTracking(r.order.cameo.status)) {
                            keepAlive.track(r.order.cameo.id)
                        }
                        r
                    }
                }
            },
            { retryableAdvanceFailure() },
        )
        flow.value = AdvanceRun.Finished(result)
    }

    private fun retryableConfirmFailure() = DomainResult.Failure(DomainError(message = null, retryable = true))
    private fun retryableAdvanceFailure() = AdvanceResult.Failed(DomainError(message = null, retryable = true))

    /** use case 正常回傳 DomainResult；萬一丟例外就記 log 並轉成 [fallback]，取消照常往外丟。 */
    private suspend inline fun <T> runCatchingResult(block: () -> T, fallback: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 單元測試的 android.util.Log 未 mock 時會丟例外，記 log 不能影響流程
        runCatching { Log.w(RUNNER_TAG, "use case threw", e) }
        fallback()
    }
}
