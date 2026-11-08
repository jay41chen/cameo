package io.github.jay41chen.cameo.data.quota

import io.github.jay41chen.cameo.data.network.ApiService
import io.github.jay41chen.cameo.data.network.safeCall
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.Quota
import io.github.jay41chen.cameo.domain.repository.QuotaRepository

internal class QuotaRepositoryImpl(private val api: ApiService) : QuotaRepository {
    override suspend fun getQuota(): DomainResult<Quota> =
        safeCall({ api.quota() }) { Quota(it.remaining, it.resetsAt, it.display) }
}
