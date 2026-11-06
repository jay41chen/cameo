package com.kimhit.cameo.data.quota

import com.kimhit.cameo.data.network.ApiService
import com.kimhit.cameo.data.network.safeCall
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.Quota
import com.kimhit.cameo.domain.repository.QuotaRepository

internal class QuotaRepositoryImpl(private val api: ApiService) : QuotaRepository {
    override suspend fun getQuota(): DomainResult<Quota> =
        safeCall({ api.quota() }) { Quota(it.remaining, it.resetsAt, it.display) }
}
