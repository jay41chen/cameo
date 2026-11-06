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

    @Query("SELECT * FROM pending_cameos WHERE cameo_id = :cameoId LIMIT 1")
    suspend fun findByCameoId(cameoId: String): PendingCameoEntity?

    /** 原子刪除；回傳實際刪掉的列數。 */
    @Query("DELETE FROM pending_cameos WHERE cameo_id = :cameoId")
    suspend fun deleteByCameoId(cameoId: String): Int

    @Query("SELECT * FROM pending_cameos WHERE step = 'CREATE' AND clip_id = :clipId ORDER BY updated_at DESC LIMIT 1")
    suspend fun findDraft(clipId: String): PendingCameoEntity?

    @Query("SELECT * FROM pending_cameos ORDER BY updated_at DESC")
    suspend fun list(): List<PendingCameoEntity>

    // 以下寫入都是條件式 UPDATE：列不存在（例如已被認領刪除）時回 0、不會重建列。

    @Query(
        "UPDATE pending_cameos SET step = 'UPLOAD', cameo_id = :cameoId, last_error = NULL, updated_at = :now " +
            "WHERE client_request_id = :id",
    )
    suspend fun markCreated(id: String, cameoId: String, now: Long): Int

    @Query("UPDATE pending_cameos SET last_error = :message, updated_at = :now WHERE client_request_id = :id")
    suspend fun updateError(id: String, message: String?, now: Long): Int

    @Query("UPDATE pending_cameos SET photo_path = :path, updated_at = :now WHERE client_request_id = :id")
    suspend fun updatePhotoPath(id: String, path: String?, now: Long): Int

    /** 只在確實有照片路徑時才寫（重複清除不改 updated_at）。 */
    @Query(
        "UPDATE pending_cameos SET photo_path = NULL, updated_at = :now " +
            "WHERE client_request_id = :id AND photo_path IS NOT NULL",
    )
    suspend fun clearPhotoPath(id: String, now: Long): Int

    @Query(
        "UPDATE pending_cameos SET step = 'SUBMIT', photo_path = NULL, last_error = NULL, updated_at = :now " +
            "WHERE client_request_id = :id",
    )
    suspend fun markUploaded(id: String, now: Long): Int

    @Query("UPDATE pending_cameos SET step = 'TRACK', last_error = NULL, updated_at = :now WHERE client_request_id = :id")
    suspend fun markSubmitted(id: String, now: Long): Int

    /** 同片段已有 CREATE 草稿就回傳它，否則寫入並回傳 [fresh]；在同一個交易內完成。 */
    @Transaction
    suspend fun getOrInsertDraft(fresh: PendingCameoEntity): PendingCameoEntity {
        findDraft(fresh.clipId)?.let { return it }
        upsert(fresh)
        return fresh
    }
}
