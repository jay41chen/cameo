package io.github.jay41chen.cameo.data.pending

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PendingCameoEntity::class], version = 1, exportSchema = false)
internal abstract class PendingDatabase : RoomDatabase() {
    abstract fun pendingCameoDao(): PendingCameoDao
}
