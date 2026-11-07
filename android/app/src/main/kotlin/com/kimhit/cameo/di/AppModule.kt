package com.kimhit.cameo.di

import com.kimhit.cameo.BuildConfig
import com.kimhit.cameo.data.network.DataConfig
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.repository.ClipRepository
import com.kimhit.cameo.domain.repository.PendingCameoRepository
import com.kimhit.cameo.domain.repository.PointsRepository
import com.kimhit.cameo.domain.repository.QuotaRepository
import com.kimhit.cameo.domain.usecase.AdvanceCameoUseCase
import com.kimhit.cameo.domain.usecase.AttachCameoPhotoUseCase
import com.kimhit.cameo.domain.usecase.ClaimFinishedCameoUseCase
import com.kimhit.cameo.domain.usecase.ConfirmCameoUseCase
import com.kimhit.cameo.domain.usecase.DeletePendingCameoUseCase
import com.kimhit.cameo.domain.usecase.GetCameoPageUseCase
import com.kimhit.cameo.domain.usecase.GetCameoUseCase
import com.kimhit.cameo.domain.usecase.GetClipsUseCase
import com.kimhit.cameo.domain.usecase.GetLedgerPageUseCase
import com.kimhit.cameo.domain.usecase.GetPointsUseCase
import com.kimhit.cameo.domain.usecase.GetQuotaUseCase
import com.kimhit.cameo.domain.usecase.GetUnfinishedCameosUseCase
import com.kimhit.cameo.domain.usecase.ResumeTrackingUseCase
import android.content.Context
import com.kimhit.cameo.tracking.ServiceSubmissionKeepAlive
import com.kimhit.cameo.tracking.SubmissionKeepAlive
import com.kimhit.cameo.ui.CaptureFiles
import com.kimhit.cameo.ui.CaptureFilesImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun provideDataConfig(): DataConfig = DataConfig(BuildConfig.BASE_URL, BuildConfig.API_TOKEN)

    @Provides
    fun provideCaptureFiles(@ApplicationContext context: Context): CaptureFiles =
        CaptureFilesImpl(File(context.cacheDir, "captures"))

    @Provides
    fun provideSubmissionKeepAlive(impl: ServiceSubmissionKeepAlive): SubmissionKeepAlive = impl

    @Provides
    fun provideGetClips(repository: ClipRepository) = GetClipsUseCase(repository)

    @Provides
    fun provideGetPoints(repository: PointsRepository) = GetPointsUseCase(repository)

    @Provides
    fun provideGetLedgerPage(repository: PointsRepository) = GetLedgerPageUseCase(repository)

    @Provides
    fun provideGetCameoPage(repository: CameoRepository) = GetCameoPageUseCase(repository)

    @Provides
    fun provideGetCameo(repository: CameoRepository) = GetCameoUseCase(repository)

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

    @Provides
    fun provideAdvanceCameo(cameos: CameoRepository, pending: PendingCameoRepository) =
        AdvanceCameoUseCase(cameos, pending)

    @Provides
    fun provideAttachCameoPhoto(pending: PendingCameoRepository) = AttachCameoPhotoUseCase(pending)

    @Provides
    fun provideClaimFinishedCameo(pending: PendingCameoRepository) = ClaimFinishedCameoUseCase(pending)

    @Provides
    fun provideResumeTracking(pending: PendingCameoRepository, cameos: CameoRepository) =
        ResumeTrackingUseCase(pending, cameos)
}
