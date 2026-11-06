package com.kimhit.cameo.data.pending

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal interface PendingCameoDao {
    @Upsert
    suspend fun upsert(entity: PendingCameoEntity)

    @Query("SELECT * FROM pending_cameos WHERE client_request_id = :id")
    suspend fun get(id: String): PendingCameoEntity?

    @Query("DELETE FROM pending_cameos WHERE client_request_id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM pending_cameos WHERE step = 'CREATE' AND clip_id = :clipId ORDER BY updated_at DESC LIMIT 1")
    suspend fun findDraft(clipId: String): PendingCameoEntity?

    @Query("SELECT * FROM pending_cameos ORDER BY updated_at DESC")
    suspend fun list(): List<PendingCameoEntity>

    /** 同片段已有 CREATE 草稿就回傳它，否則寫入並回傳 [fresh]；在同一個交易內完成。 */
    @Transaction
    suspend fun getOrInsertDraft(fresh: PendingCameoEntity): PendingCameoEntity {
        findDraft(fresh.clipId)?.let { return it }
        upsert(fresh)
        return fresh
    }
}
