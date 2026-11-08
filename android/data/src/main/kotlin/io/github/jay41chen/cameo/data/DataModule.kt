package io.github.jay41chen.cameo.data

import android.content.Context
import androidx.room.Room
import io.github.jay41chen.cameo.data.cameos.CameoRepositoryImpl
import io.github.jay41chen.cameo.data.clips.ClipRepositoryImpl
import io.github.jay41chen.cameo.data.network.ApiService
import io.github.jay41chen.cameo.data.network.DataConfig
import io.github.jay41chen.cameo.data.network.UploadApi
import io.github.jay41chen.cameo.data.network.createApiService
import io.github.jay41chen.cameo.data.network.createMainClient
import io.github.jay41chen.cameo.data.network.createUploadApi
import io.github.jay41chen.cameo.data.pending.PendingCameoDao
import io.github.jay41chen.cameo.data.pending.PendingCameoRepositoryImpl
import io.github.jay41chen.cameo.data.pending.PendingDatabase
import io.github.jay41chen.cameo.data.points.PointsRepositoryImpl
import io.github.jay41chen.cameo.data.quota.QuotaRepositoryImpl
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.repository.CameoShareRepository
import io.github.jay41chen.cameo.domain.repository.ClipRepository
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import io.github.jay41chen.cameo.domain.repository.PointsRepository
import io.github.jay41chen.cameo.domain.repository.QuotaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** 需要 :app 提供 [DataConfig]。實作類別為 internal，只對外露出 domain 介面。 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    internal fun provideMainClient(config: DataConfig): OkHttpClient = createMainClient(config)

    @Provides
    @Singleton
    internal fun provideApi(config: DataConfig, client: OkHttpClient): ApiService = createApiService(config, client)

    /** 上傳用的獨立 client 由主 client 衍生，不改變主 client 的設定。 */
    @Provides
    @Singleton
    internal fun provideUploadApi(config: DataConfig, client: OkHttpClient): UploadApi = createUploadApi(config, client)

    @Provides
    @Singleton
    internal fun provideClipRepository(api: ApiService): ClipRepository = ClipRepositoryImpl(api)

    @Provides
    @Singleton
    internal fun providePointsRepository(api: ApiService): PointsRepository = PointsRepositoryImpl(api)

    @Provides
    @Singleton
    internal fun provideQuotaRepository(api: ApiService): QuotaRepository = QuotaRepositoryImpl(api)

    @Provides
    @Singleton
    internal fun provideCameoRepository(api: ApiService, uploadApi: UploadApi, config: DataConfig): CameoRepository =
        CameoRepositoryImpl(api, uploadApi, config)

    @Provides
    @Singleton
    internal fun provideCameoShareRepository(api: ApiService, uploadApi: UploadApi, config: DataConfig): CameoShareRepository =
        CameoRepositoryImpl(api, uploadApi, config)

    @Provides
    @Singleton
    internal fun providePendingDatabase(@ApplicationContext context: Context): PendingDatabase =
        Room.databaseBuilder(context, PendingDatabase::class.java, "pending.db").build()

    @Provides
    @Singleton
    internal fun providePendingDao(db: PendingDatabase): PendingCameoDao = db.pendingCameoDao()

    @Provides
    @Singleton
    internal fun providePendingCameoRepository(
        dao: PendingCameoDao,
        @ApplicationContext context: Context,
    ): PendingCameoRepository = PendingCameoRepositoryImpl(dao, File(context.filesDir, "selfies"))
}
