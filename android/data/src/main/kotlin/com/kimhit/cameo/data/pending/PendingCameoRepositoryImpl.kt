package com.kimhit.cameo.data.pending

import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.PendingStep
import com.kimhit.cameo.domain.repository.PendingCameoRepository
import java.util.UUID

internal class PendingCameoRepositoryImpl(
    private val dao: PendingCameoDao,
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

    override suspend fun delete(clientRequestId: String) = dao.delete(clientRequestId)

    override suspend fun list(): List<PendingCameo> = dao.list().map { it.toDomain() }
}

private fun PendingCameoEntity.toDomain() = PendingCameo(
    clientRequestId = clientRequestId,
    clipId = clipId,
    cameoId = cameoId,
    step = runCatching { PendingStep.valueOf(step) }.getOrDefault(PendingStep.CREATE),
    lastError = lastError,
)
