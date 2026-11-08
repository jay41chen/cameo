package io.github.jay41chen.cameo.data.clips

import io.github.jay41chen.cameo.data.network.ApiService
import io.github.jay41chen.cameo.data.network.safeCall
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.repository.ClipRepository

internal class ClipRepositoryImpl(private val api: ApiService) : ClipRepository {
    override suspend fun getClips(): DomainResult<List<Clip>> =
        safeCall({ api.clips() }) { body -> body.items.map { Clip(it.id, it.title, it.cost) } }
}
