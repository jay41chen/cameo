package io.github.jay41chen.cameo.data.points

import io.github.jay41chen.cameo.data.network.ApiService
import io.github.jay41chen.cameo.data.network.safeCall
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.LedgerEntry
import io.github.jay41chen.cameo.domain.model.LedgerPage
import io.github.jay41chen.cameo.domain.model.LedgerType
import io.github.jay41chen.cameo.domain.model.Points
import io.github.jay41chen.cameo.domain.repository.PointsRepository

internal class PointsRepositoryImpl(private val api: ApiService) : PointsRepository {
    override suspend fun getPoints(): DomainResult<Points> =
        safeCall({ api.points() }) { Points(it.available, it.held) }

    override suspend fun getLedger(cursor: String?): DomainResult<LedgerPage> =
        safeCall({ api.ledger(cursor) }) { body ->
            LedgerPage(
                items = body.items.map {
                    LedgerEntry(
                        id = it.id,
                        type = runCatching { LedgerType.valueOf(it.type) }.getOrDefault(LedgerType.UNKNOWN),
                        amount = it.amount,
                        cameoId = it.cameoId,
                        clipTitle = it.clipTitle,
                        createdAt = it.createdAt,
                    )
                },
                nextCursor = body.nextCursor,
            )
        }
}
