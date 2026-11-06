package com.kimhit.cameo.domain.usecase

import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.LedgerPage
import com.kimhit.cameo.domain.model.Points
import com.kimhit.cameo.domain.repository.ClipRepository
import com.kimhit.cameo.domain.repository.PointsRepository

class GetClipsUseCase(private val repository: ClipRepository) {
    suspend operator fun invoke(): DomainResult<List<Clip>> = repository.getClips()
}

class GetPointsUseCase(private val repository: PointsRepository) {
    suspend operator fun invoke(): DomainResult<Points> = repository.getPoints()
}

/** [cursor] 為 null 取第一頁；之後用上一頁的 [LedgerPage.nextCursor]。 */
class GetLedgerPageUseCase(private val repository: PointsRepository) {
    suspend operator fun invoke(cursor: String? = null): DomainResult<LedgerPage> =
        repository.getLedger(cursor)
}
