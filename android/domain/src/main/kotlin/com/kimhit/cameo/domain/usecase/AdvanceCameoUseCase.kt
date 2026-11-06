package com.kimhit.cameo.domain.usecase

import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.repository.PendingCameoRepository

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
        var photoPath = row.photoPath
        var rechecks = 0
        var uploaded = false
        var submitted = false
        var current: DomainResult<CameoOrder> = cameos.getCameo(cameoId)

        while (true) {
            // 失敗統一在這裡分流；成功才往下走狀態判斷
            val order = when (val r = current) {
                is DomainResult.Success -> r.value
                is DomainResult.Failure -> {
                    val error = r.error
                    when {
                        error.code in RECHECK_CODES && rechecks < MAX_RECHECKS -> {
                            rechecks++
                            current = cameos.getCameo(cameoId)
                            continue
                        }
                        error.code in PHOTO_REJECTED_CODES -> {
                            pending.deletePhoto(clientRequestId)
                            return AdvanceResult.NeedPhoto(row.clipId)
                        }
                        error.code == CAMEO_NOT_FOUND -> {
                            pending.delete(clientRequestId)
                            return AdvanceResult.Gone
                        }
                        else -> {
                            pending.recordError(clientRequestId, error.message)
                            return AdvanceResult.Failed(error)
                        }
                    }
                }
            }

            when (order.cameo.status) {
                CameoStatus.AWAITING_UPLOAD -> {
                    val upload = order.upload
                    // 上傳過一次訂單卻仍是 AWAITING_UPLOAD：不再重傳，避免無限循環（要在缺照片判斷之前，上傳後 photoPath 已清空）
                    if (uploaded) return unexpected(clientRequestId)
                    if (photoPath == null || upload == null) return AdvanceResult.NeedPhoto(row.clipId)
                    onStep(CameoStep.UPLOADING)
                    current = cameos.uploadSelfie(upload.url, photoPath)
                    if (current is DomainResult.Success) {
                        pending.markUploaded(clientRequestId)
                        photoPath = null
                        uploaded = true
                    }
                }
                CameoStatus.UPLOADED -> {
                    if (submitted) return unexpected(clientRequestId)
                    if (photoPath != null) {
                        // 上傳回應遺失後當機：後端已收到，本機照片用不到了
                        pending.deletePhoto(clientRequestId)
                        photoPath = null
                    }
                    onStep(CameoStep.SUBMITTING)
                    current = cameos.submitCameo(cameoId)
                    if (current is DomainResult.Success) {
                        pending.markSubmitted(clientRequestId)
                        submitted = true
                    }
                }
                CameoStatus.EXPIRED -> {
                    pending.deletePhoto(clientRequestId)
                    return AdvanceResult.Expired(order)
                }
                CameoStatus.QUEUED,
                CameoStatus.GENERATING,
                CameoStatus.SUCCEEDED,
                CameoStatus.FAILED,
                -> {
                    if (photoPath != null) pending.deletePhoto(clientRequestId)
                    if (!submitted) pending.markSubmitted(clientRequestId)
                    return AdvanceResult.Done(order)
                }
                CameoStatus.UNKNOWN -> return AdvanceResult.Done(order)
            }
        }
    }

    private suspend fun unexpected(clientRequestId: String): AdvanceResult.Failed {
        val error = DomainError(message = null, retryable = true, code = UNEXPECTED_STATE)
        pending.recordError(clientRequestId, error.message)
        return AdvanceResult.Failed(error)
    }
}

private const val CAMEO_NOT_FOUND = "CAMEO_NOT_FOUND"
private const val UNEXPECTED_STATE = "UNEXPECTED_STATE"
private const val MAX_RECHECKS = 1
private val RECHECK_CODES = setOf("UPLOAD_EXPIRED", "INVALID_STATE", "SELFIE_NOT_UPLOADED")
private val PHOTO_REJECTED_CODES = setOf("INVALID_SELFIE", "SELFIE_TOO_LARGE", PHOTO_MISSING)

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
        return if (pending.attachPhoto(clientRequestId, sourcePath)) {
            AttachResult.Attached
        } else {
            AttachResult.NeedPhoto(row.clipId)
        }
    }
}
