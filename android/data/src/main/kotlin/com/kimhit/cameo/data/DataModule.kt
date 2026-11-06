package com.kimhit.cameo.data

import com.kimhit.cameo.data.clips.ClipRepositoryImpl
import com.kimhit.cameo.data.network.ApiService
import com.kimhit.cameo.data.network.DataConfig
import com.kimhit.cameo.data.network.createApiService
import com.kimhit.cameo.data.points.PointsRepositoryImpl
import com.kimhit.cameo.domain.repository.ClipRepository
import com.kimhit.cameo.domain.repository.PointsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 需要 :app 提供 [DataConfig]。實作類別為 internal，只對外露出 domain 介面。 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    internal fun provideApi(config: DataConfig): ApiService = createApiService(config)

    @Provides
    @Singleton
    internal fun provideClipRepository(api: ApiService): ClipRepository = ClipRepositoryImpl(api)

    @Provides
    @Singleton
    internal fun providePointsRepository(api: ApiService): PointsRepository = PointsRepositoryImpl(api)
}
