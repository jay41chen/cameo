package io.github.jay41chen.cameo.domain.usecase

import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository

enum class CameoStep { UPLOADING, SUBMITTING }

sealed interface AdvanceResult {
    data class Done(val order: CameoOrder) : AdvanceResult
    data class Expired(val order: CameoOrder) : AdvanceResult
    data class NeedPhoto(val clipId: String) : AdvanceResult
    data class NotCreated(val clipId: String) : AdvanceResult
    data object Gone : AdvanceResult
    data class Failed(val error: DomainError) : AdvanceResult
}

/**
 * 把已建單的草稿往前推：上傳照片、送出。每次呼叫都先 `getCameo`，由後端訂單狀態決定下一步，
 * 不看本機 `step`（本機可能落後，例如上傳回應遺失後當機）。
 * - 請求失敗且 code 為 `UPLOAD_EXPIRED`、`INVALID_STATE`、`SELFIE_NOT_UPLOADED`：重查訂單再判斷，
 *   整次呼叫最多重查 1 次，超過就記錄錯誤並回 [AdvanceResult.Failed]。
 * - `INVALID_SELFIE`、`SELFIE_TOO_LARGE`、`PHOTO_MISSING`：刪照片，回 [AdvanceResult.NeedPhoto]。
 * - `CAMEO_NOT_FOUND`：刪草稿，回 [AdvanceResult.Gone]。
 * - 其他：記錄錯誤、保留草稿與照片，回 [AdvanceResult.Failed]，由呼叫端看 `error.retryable`。
 * - 照片只在 `uploadSelfie` 成功之後才刪（或訂單已確定用不到：EXPIRED／已送出）。
 * - 取消直接往外丟，不動本機狀態。
 */
class AdvanceCameoUseCase(
    private val cameos: CameoRepository,
    private val pending: PendingCameoRepository,
) {
    suspend operator fun invoke(
        clientRequestId: String,
        onStep: (CameoStep) -> Unit = {},
    ): AdvanceResult {
        val row = pending.get(clientRequestId) ?: return AdvanceResult.Gone
        val cameoId = row.cameoId ?: return AdvanceResult.NotCreated(row.clipId)
        return Run(clientRequestId, row.clipId, cameoId, row.photoPath, onStep).execute()
    }

    /** 一次推進的執行狀態：每次 invoke 建一個，跑完就丟，所以同時多次呼叫不會共用狀態。 */
    private inner class Run(
        private val clientRequestId: String,
        private val clipId: String,
        private val cameoId: String,
        private var photoPath: String?,
        private val onStep: (CameoStep) -> Unit,
    ) {
        private var rechecks = 0
        private var uploaded = false
        private var submitted = false

        suspend fun execute(): AdvanceResult {
            var current: DomainResult<CameoOrder> = cameos.getCameo(cameoId)
            while (true) {
                // 失敗統一在這裡分流；成功才往下走狀態判斷
                val step = when (val r = current) {
                    is DomainResult.Success -> nextStep(r.value)
                    is DomainResult.Failure -> onFailure(r.error)
                }
                current = when (step) {
                    is Step.Call -> step.result
                    is Step.Stop -> return step.result
                }
            }
        }

        private suspend fun onFailure(error: DomainError): Step = when {
            error.code in RECHECK_CODES && rechecks < MAX_RECHECKS -> {
                rechecks++
                Step.Call(cameos.getCameo(cameoId))
            }
            error.code in PHOTO_REJECTED_CODES -> {
                pending.deletePhoto(clientRequestId)
                Step.Stop(AdvanceResult.NeedPhoto(clipId))
            }
            error.code == CAMEO_NOT_FOUND -> {
                pending.delete(clientRequestId)
                Step.Stop(AdvanceResult.Gone)
            }
            else -> {
                pending.recordError(clientRequestId, error.message)
                Step.Stop(AdvanceResult.Failed(error))
            }
        }

        private suspend fun nextStep(order: CameoOrder): Step = when (order.cameo.status) {
            CameoStatus.AWAITING_UPLOAD -> upload(order)
            CameoStatus.UPLOADED -> submit()
            CameoStatus.EXPIRED -> expire(order)
            CameoStatus.QUEUED,
            CameoStatus.GENERATING,
            CameoStatus.SUCCEEDED,
            CameoStatus.FAILED,
            -> finish(order)
            CameoStatus.UNKNOWN -> Step.Stop(AdvanceResult.Done(order))
        }

        private suspend fun upload(order: CameoOrder): Step {
            val uploadInfo = order.upload
            // 上傳過一次訂單卻仍是 AWAITING_UPLOAD：不再重傳，避免無限循環（要在缺照片判斷之前，上傳後 photoPath 已清空）
            if (uploaded) return unexpected()
            val path = photoPath
            if (path == null || uploadInfo == null) return Step.Stop(AdvanceResult.NeedPhoto(clipId))
            onStep(CameoStep.UPLOADING)
            val result = cameos.uploadSelfie(uploadInfo.url, path)
            if (result is DomainResult.Success) {
                pending.markUploaded(clientRequestId)
                photoPath = null
                uploaded = true
            }
            return Step.Call(result)
        }

        private suspend fun submit(): Step {
            if (submitted) return unexpected()
            if (photoPath != null) {
                // 上傳回應遺失後當機：後端已收到，本機照片用不到了
                pending.deletePhoto(clientRequestId)
                photoPath = null
            }
            onStep(CameoStep.SUBMITTING)
            val result = cameos.submitCameo(cameoId)
            if (result is DomainResult.Success) {
                pending.markSubmitted(clientRequestId)
                submitted = true
            }
            return Step.Call(result)
        }

        private suspend fun expire(order: CameoOrder): Step {
            pending.deletePhoto(clientRequestId)
            return Step.Stop(AdvanceResult.Expired(order))
        }

        private suspend fun finish(order: CameoOrder): Step {
            if (photoPath != null) pending.deletePhoto(clientRequestId)
            if (!submitted) pending.markSubmitted(clientRequestId)
            return Step.Stop(AdvanceResult.Done(order))
        }

        private suspend fun unexpected(): Step {
            val error = DomainError(message = null, retryable = true, code = UNEXPECTED_STATE)
            pending.recordError(clientRequestId, error.message)
            return Step.Stop(AdvanceResult.Failed(error))
        }
    }
}

private const val CAMEO_NOT_FOUND = "CAMEO_NOT_FOUND"
private const val UNEXPECTED_STATE = "UNEXPECTED_STATE"
private const val MAX_RECHECKS = 1
private val RECHECK_CODES = setOf("UPLOAD_EXPIRED", "INVALID_STATE", "SELFIE_NOT_UPLOADED")
private val PHOTO_REJECTED_CODES = setOf("INVALID_SELFIE", "SELFIE_TOO_LARGE", PHOTO_MISSING)

/** 每一步的結果：打了一次 API，拿結果進下一圈（[Call]）；或結束並回傳（[Stop]）。 */
private sealed interface Step {
    data class Call(val result: DomainResult<CameoOrder>) : Step
    data class Stop(val result: AdvanceResult) : Step
}

/** 上傳照片的結果：已附上，或需要重拍／草稿不存在。 */
sealed interface AttachResult {
    data object Attached : AttachResult
    data class NeedPhoto(val clipId: String) : AttachResult
    data object Gone : AttachResult
}

/** 把相機暫存檔附到既有草稿（重新拍照後接續上傳）。 */
class AttachCameoPhotoUseCase(private val pending: PendingCameoRepository) {
    suspend operator fun invoke(clientRequestId: String, sourcePath: String): AttachResult {
        val row = pending.get(clientRequestId) ?: return AttachResult.Gone
        if (pending.attachPhoto(clientRequestId, sourcePath)) return AttachResult.Attached
        // false 也可能是搬檔期間列被刪（認領）：再查一次，列不在就是 Gone
        return if (pending.get(clientRequestId) == null) AttachResult.Gone else AttachResult.NeedPhoto(row.clipId)
    }
}
