package com.sonai.sonai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.sonai.sonai.data.local.entity.FocusSession
import com.sonai.sonai.data.local.entity.NoiseEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: FocusSession): Long

    @Update
    suspend fun updateSession(session: FocusSession)

    @Insert
    suspend fun insertNoiseEvent(event: NoiseEvent)

    @Query("SELECT * FROM focus_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<FocusSession>>

    @Query("SELECT * FROM noise_events WHERE sessionId = :sessionId")
    suspend fun getNoiseEventsForSession(sessionId: Long): List<NoiseEvent>
    
    @Query("SELECT COUNT(*) FROM focus_sessions")
    suspend fun getSessionCount(): Int
}
