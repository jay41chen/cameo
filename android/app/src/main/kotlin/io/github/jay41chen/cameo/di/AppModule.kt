package io.github.jay41chen.cameo.di

import io.github.jay41chen.cameo.BuildConfig
import io.github.jay41chen.cameo.data.network.DataConfig
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.repository.CameoShareRepository
import io.github.jay41chen.cameo.domain.repository.ClipRepository
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import io.github.jay41chen.cameo.domain.repository.PointsRepository
import io.github.jay41chen.cameo.domain.repository.QuotaRepository
import io.github.jay41chen.cameo.domain.usecase.AdvanceCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.AttachCameoPhotoUseCase
import io.github.jay41chen.cameo.domain.usecase.AttachDraftPhotoUseCase
import io.github.jay41chen.cameo.domain.usecase.ClaimFinishedCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.ConfirmCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.DeletePendingCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.GetCameoPageUseCase
import io.github.jay41chen.cameo.domain.usecase.GetCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.GetClipsUseCase
import io.github.jay41chen.cameo.domain.usecase.GetLedgerPageUseCase
import io.github.jay41chen.cameo.domain.usecase.GetPointsUseCase
import io.github.jay41chen.cameo.domain.usecase.GetQuotaUseCase
import io.github.jay41chen.cameo.domain.usecase.GetUnfinishedCameosUseCase
import io.github.jay41chen.cameo.domain.usecase.ResumeTrackingUseCase
import io.github.jay41chen.cameo.domain.usecase.ShareCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.UnshareCameoUseCase
import android.content.Context
import io.github.jay41chen.cameo.tracking.ServiceSubmissionKeepAlive
import io.github.jay41chen.cameo.tracking.SubmissionKeepAlive
import io.github.jay41chen.cameo.submission.SubmissionRunner
import io.github.jay41chen.cameo.ui.CaptureFiles
import io.github.jay41chen.cameo.ui.CaptureFilesImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun provideDataConfig(): DataConfig = DataConfig(
        BuildConfig.BASE_URL,
        BuildConfig.API_TOKEN,
        shareDebugBaseUrl = if (BuildConfig.DEBUG) BuildConfig.BASE_URL else null,
    )

    @Provides
    fun provideCaptureFiles(@ApplicationContext context: Context): CaptureFiles =
        CaptureFilesImpl(File(context.cacheDir, "captures"))

    @Provides
    fun provideSubmissionKeepAlive(impl: ServiceSubmissionKeepAlive): SubmissionKeepAlive = impl

    @Provides
    @Singleton
    fun provideSubmissionRunner(
        confirm: ConfirmCameoUseCase,
        advance: AdvanceCameoUseCase,
        attachPhoto: AttachCameoPhotoUseCase,
        keepAlive: SubmissionKeepAlive,
    ) = SubmissionRunner(
        confirm,
        advance,
        attachPhoto,
        keepAlive,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

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
    fun provideShareCameo(repository: CameoShareRepository) = ShareCameoUseCase(repository)

    @Provides
    fun provideUnshareCameo(repository: CameoShareRepository) = UnshareCameoUseCase(repository)

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
    fun provideAttachDraftPhoto(pending: PendingCameoRepository) = AttachDraftPhotoUseCase(pending)

    @Provides
    fun provideClaimFinishedCameo(pending: PendingCameoRepository) = ClaimFinishedCameoUseCase(pending)

    @Provides
    fun provideResumeTracking(pending: PendingCameoRepository, cameos: CameoRepository) =
        ResumeTrackingUseCase(pending, cameos)
}
