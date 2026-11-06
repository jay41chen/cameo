package com.kimhit.cameo.data.points

import com.kimhit.cameo.data.network.ApiService
import com.kimhit.cameo.data.network.safeCall
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.LedgerEntry
import com.kimhit.cameo.domain.model.LedgerPage
import com.kimhit.cameo.domain.model.LedgerType
import com.kimhit.cameo.domain.model.Points
import com.kimhit.cameo.domain.repository.PointsRepository

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
