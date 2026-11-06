package com.kimhit.cameo.data.pending

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface PendingCameoDao {
    @Upsert
    suspend fun upsert(entity: PendingCameoEntity)

    @Query("SELECT * FROM pending_cameos WHERE client_request_id = :id")
    suspend fun get(id: String): PendingCameoEntity?

    @Query("DELETE FROM pending_cameos WHERE client_request_id = :id")
    suspend fun delete(id: String)
}
