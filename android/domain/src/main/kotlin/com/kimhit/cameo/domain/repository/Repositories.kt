package com.kimhit.cameo.domain.repository

import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.LedgerPage
import com.kimhit.cameo.domain.model.Points

interface ClipRepository {
    suspend fun getClips(): DomainResult<List<Clip>>
}

interface PointsRepository {
    suspend fun getPoints(): DomainResult<Points>
    suspend fun getLedger(cursor: String?): DomainResult<LedgerPage>
}
