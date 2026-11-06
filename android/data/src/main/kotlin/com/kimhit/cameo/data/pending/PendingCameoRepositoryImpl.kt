package com.kimhit.cameo.data.pending

import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.PendingStep
import com.kimhit.cameo.domain.repository.PendingCameoRepository
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
                step = PendingStep.CREATE.name,
                photoPath = null,
                lastError = null,
                updatedAt = now(),
            ),
        ).toDomain()

    override suspend fun markCreated(clientRequestId: String, cameoId: String) {
        val row = dao.get(clientRequestId) ?: return
        dao.upsert(
            row.copy(
                step = PendingStep.UPLOAD.name,
                cameoId = cameoId,
                lastError = null,
                updatedAt = now(),
            ),
        )
    }

    override suspend fun recordError(clientRequestId: String, message: String?) {
        val row = dao.get(clientRequestId) ?: return
        dao.upsert(row.copy(lastError = message, updatedAt = now()))
    }

    override suspend fun delete(clientRequestId: String) {
        // 先刪列再刪檔，且整段不可取消，避免只刪一半留下孤兒列或孤兒照片；刪檔結果不影響回傳
        withContext(NonCancellable + Dispatchers.IO) {
            dao.delete(clientRequestId)
            photoFile(clientRequestId)?.delete()
            tempFile(clientRequestId)?.delete()
        }
    }

    override suspend fun deletePhoto(clientRequestId: String) {
        withContext(Dispatchers.IO) {
            photoFile(clientRequestId)?.delete()
            tempFile(clientRequestId)?.delete()
        }
        val row = dao.get(clientRequestId) ?: return
        if (row.photoPath != null) dao.upsert(row.copy(photoPath = null, updatedAt = now()))
    }

    override suspend fun attachPhoto(clientRequestId: String, sourcePath: String): Boolean {
        val row = dao.get(clientRequestId) ?: return false
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
        if (row.photoPath != target.path) {
            dao.upsert(row.copy(photoPath = target.path, updatedAt = now()))
        }
        return true
    }

    override suspend fun list(): List<PendingCameo> = dao.list().map { it.toDomain() }

    override suspend fun get(clientRequestId: String): PendingCameo? = dao.get(clientRequestId)?.toDomain()

    override suspend fun markUploaded(clientRequestId: String) {
        val row = dao.get(clientRequestId) ?: return
        withContext(Dispatchers.IO) {
            photoFile(clientRequestId)?.delete()
            tempFile(clientRequestId)?.delete()
        }
        dao.upsert(
            row.copy(
                step = PendingStep.SUBMIT.name,
                photoPath = null,
                lastError = null,
                updatedAt = now(),
            ),
        )
    }

    override suspend fun markSubmitted(clientRequestId: String) {
        val row = dao.get(clientRequestId) ?: return
        dao.upsert(row.copy(step = PendingStep.TRACK.name, lastError = null, updatedAt = now()))
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
