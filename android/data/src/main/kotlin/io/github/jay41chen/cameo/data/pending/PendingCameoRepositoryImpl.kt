package io.github.jay41chen.cameo.data.pending

import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class PendingCameoRepositoryImpl(
    private val dao: PendingCameoDao,
    /** 照片的正式目錄；只有這裡知道正式路徑，檔名固定為 `<clientRequestId>.jpg`。 */
    private val photoDir: File,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) : PendingCameoRepository {
    override suspend fun getOrCreateDraft(clipId: String): PendingCameo =
        dao.getOrInsertDraft(
            PendingCameoEntity(
                clientRequestId = newId(),
                clipId = clipId,
                cameoId = null,
                step = PendingStep.DRAFT.name,
                photoPath = null,
                lastError = null,
                updatedAt = now(),
            ),
        ).toDomain()

    override suspend fun markConfirming(clientRequestId: String) {
        dao.markConfirming(clientRequestId, now())
    }

    override suspend fun markCreated(clientRequestId: String, cameoId: String) {
        dao.markCreated(clientRequestId, cameoId, now())
    }

    override suspend fun recordError(clientRequestId: String, message: String?) {
        dao.updateError(clientRequestId, message, now())
    }

    override suspend fun delete(clientRequestId: String) {
        // 先刪列再刪檔，且整段不可取消，避免只刪一半留下孤兒列或孤兒照片；刪檔結果不影響回傳
        withContext(NonCancellable + Dispatchers.IO) {
            dao.delete(clientRequestId)
            photoFile(clientRequestId)?.delete()
            tempFile(clientRequestId)?.delete()
        }
    }

    override suspend fun claimFinishedCameo(cameoId: String): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            // 先記下 key（刪列後就查不到），再用 DELETE 的回傳筆數當唯一的認領依據
            val key = dao.findByCameoId(cameoId)?.clientRequestId
            val claimed = dao.deleteByCameoId(cameoId) >= 1
            if (claimed && key != null) {
                photoFile(key)?.delete()
                tempFile(key)?.delete()
            }
            claimed
        }

    override suspend fun deletePhoto(clientRequestId: String) {
        withContext(Dispatchers.IO) {
            photoFile(clientRequestId)?.delete()
            tempFile(clientRequestId)?.delete()
        }
        dao.clearPhotoPath(clientRequestId, now())
    }

    override suspend fun attachPhoto(clientRequestId: String, sourcePath: String): Boolean {
        if (dao.get(clientRequestId) == null) return false
        val target = photoFile(clientRequestId) ?: return false
        val temp = tempFile(clientRequestId) ?: return false
        val source = File(sourcePath)
        val moved = withContext(Dispatchers.IO) {
            if (source.absoluteFile == target.absoluteFile) return@withContext target.isFile
            if (!source.isFile) return@withContext false
            try {
                photoDir.mkdirs()
                // 先寫暫名再改名，舊照片在最後一步前都不動
                if (!source.renameTo(temp)) {
                    source.copyTo(temp, overwrite = true)
                    source.delete()
                }
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) throw IOException("rename failed")
                }
                true
            } catch (e: IOException) {
                temp.delete()
                false
            }
        }
        if (!moved) return false
        // 條件式 UPDATE：搬檔期間列若被刪（認領），不重建列，並清掉剛搬進來的照片
        if (dao.updatePhotoPath(clientRequestId, target.path, now()) == 0) {
            withContext(Dispatchers.IO) {
                target.delete()
                temp.delete()
            }
            return false
        }
        return true
    }

    override suspend fun list(): List<PendingCameo> = dao.list().map { it.toDomain() }

    override suspend fun get(clientRequestId: String): PendingCameo? = dao.get(clientRequestId)?.toDomain()

    override suspend fun markUploaded(clientRequestId: String) {
        if (dao.get(clientRequestId) == null) return
        withContext(Dispatchers.IO) {
            photoFile(clientRequestId)?.delete()
            tempFile(clientRequestId)?.delete()
        }
        dao.markUploaded(clientRequestId, now())
    }

    override suspend fun markSubmitted(clientRequestId: String) {
        dao.markSubmitted(clientRequestId, now())
    }

    /** key 是 App 自己產生的 UUID；仍只接受安全字元，固定檔名組合，不讓路徑穿越。 */
    private fun photoFile(id: String): File? = if (SAFE_KEY.matches(id)) File(photoDir, "$id.jpg") else null

    private fun tempFile(id: String): File? = if (SAFE_KEY.matches(id)) File(photoDir, "$id.jpg.tmp") else null

    private companion object {
        val SAFE_KEY = Regex("[A-Za-z0-9_-]+")
    }
}

private fun PendingCameoEntity.toDomain() = PendingCameo(
    clientRequestId = clientRequestId,
    clipId = clipId,
    cameoId = cameoId,
    step = runCatching { PendingStep.valueOf(step) }
        .getOrDefault(if (cameoId != null) PendingStep.UPLOAD else PendingStep.CREATE),
    lastError = lastError,
    photoPath = photoPath?.takeIf { File(it).isFile },
)
