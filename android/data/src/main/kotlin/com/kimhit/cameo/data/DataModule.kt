package com.kimhit.cameo.data

import android.content.Context
import androidx.room.Room
import com.kimhit.cameo.data.cameos.CameoRepositoryImpl
import com.kimhit.cameo.data.clips.ClipRepositoryImpl
import com.kimhit.cameo.data.network.ApiService
import com.kimhit.cameo.data.network.DataConfig
import com.kimhit.cameo.data.network.createApiService
import com.kimhit.cameo.data.pending.PendingCameoDao
import com.kimhit.cameo.data.pending.PendingCameoRepositoryImpl
import com.kimhit.cameo.data.pending.PendingDatabase
import com.kimhit.cameo.data.points.PointsRepositoryImpl
import com.kimhit.cameo.data.quota.QuotaRepositoryImpl
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.repository.ClipRepository
import com.kimhit.cameo.domain.repository.PendingCameoRepository
import com.kimhit.cameo.domain.repository.PointsRepository
import com.kimhit.cameo.domain.repository.QuotaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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

    @Provides
    @Singleton
    internal fun provideQuotaRepository(api: ApiService): QuotaRepository = QuotaRepositoryImpl(api)

    @Provides
    @Singleton
    internal fun provideCameoRepository(api: ApiService): CameoRepository = CameoRepositoryImpl(api)

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
