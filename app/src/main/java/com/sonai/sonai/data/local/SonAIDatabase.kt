package com.sonai.sonai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sonai.sonai.data.local.dao.SessionDao
import com.sonai.sonai.data.local.entity.FocusSession
import com.sonai.sonai.data.local.entity.NoiseEvent

@Database(entities = [FocusSession::class, NoiseEvent::class], version = 1, exportSchema = false)
abstract class SonAIDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var INSTANCE: SonAIDatabase? = null

        fun getDatabase(context: Context): SonAIDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SonAIDatabase::class.java,
                    "sonai_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
