package com.kimhit.cameo.data.clips

import com.kimhit.cameo.data.network.ApiService
import com.kimhit.cameo.data.network.safeCall
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.repository.ClipRepository

internal class ClipRepositoryImpl(private val api: ApiService) : ClipRepository {
    override suspend fun getClips(): DomainResult<List<Clip>> =
        safeCall({ api.clips() }) { body -> body.items.map { Clip(it.id, it.title, it.cost) } }
}
