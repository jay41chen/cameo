package com.kimhit.cameo.di

import com.kimhit.cameo.BuildConfig
import com.kimhit.cameo.data.network.DataConfig
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.repository.ClipRepository
import com.kimhit.cameo.domain.repository.PendingCameoRepository
import com.kimhit.cameo.domain.repository.PointsRepository
import com.kimhit.cameo.domain.repository.QuotaRepository
import com.kimhit.cameo.domain.usecase.ConfirmCameoUseCase
import com.kimhit.cameo.domain.usecase.DeletePendingCameoUseCase
import com.kimhit.cameo.domain.usecase.GetClipsUseCase
import com.kimhit.cameo.domain.usecase.GetLedgerPageUseCase
import com.kimhit.cameo.domain.usecase.GetPointsUseCase
import com.kimhit.cameo.domain.usecase.GetQuotaUseCase
import com.kimhit.cameo.domain.usecase.GetUnfinishedCameosUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun provideDataConfig(): DataConfig = DataConfig(BuildConfig.BASE_URL, BuildConfig.API_TOKEN)

    @Provides
    fun provideGetClips(repository: ClipRepository) = GetClipsUseCase(repository)

    @Provides
    fun provideGetPoints(repository: PointsRepository) = GetPointsUseCase(repository)

    @Provides
    fun provideGetLedgerPage(repository: PointsRepository) = GetLedgerPageUseCase(repository)

    @Provides
    fun provideGetQuota(repository: QuotaRepository) = GetQuotaUseCase(repository)

    @Provides
    fun provideConfirmCameo(cameos: CameoRepository, pending: PendingCameoRepository) =
        ConfirmCameoUseCase(cameos, pending)

    @Provides
    fun provideGetUnfinishedCameos(pending: PendingCameoRepository, cameos: CameoRepository) =
        GetUnfinishedCameosUseCase(pending, cameos)

    @Provides
    fun provideDeletePendingCameo(pending: PendingCameoRepository) = DeletePendingCameoUseCase(pending)
}
