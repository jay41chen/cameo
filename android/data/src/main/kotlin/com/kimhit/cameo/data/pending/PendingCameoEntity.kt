package com.kimhit.cameo.data.pending

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 對應 spec.md「Room pending_cameos」。目前由確認支付流程寫入 CREATE／UPLOAD 兩個 step，其餘 step 與 [photoPath] 尚未使用。 */
@Entity(tableName = "pending_cameos")
internal data class PendingCameoEntity(
    @PrimaryKey @ColumnInfo(name = "client_request_id") val clientRequestId: String,
    @ColumnInfo(name = "clip_id") val clipId: String,
    @ColumnInfo(name = "cameo_id") val cameoId: String?,
    /** `CREATE`、`UPLOAD`、`SUBMIT`、`TRACK` */
    val step: String,
    @ColumnInfo(name = "photo_path") val photoPath: String?,
    @ColumnInfo(name = "last_error") val lastError: String?,
    /** epoch millis `[待確認]` */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
