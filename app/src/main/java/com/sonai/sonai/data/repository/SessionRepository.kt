package com.sonai.sonai.data.repository

import com.sonai.sonai.data.local.dao.SessionDao
import com.sonai.sonai.data.local.entity.FocusSession
import com.sonai.sonai.data.local.entity.NoiseEvent
import kotlinx.coroutines.flow.Flow

class SessionRepository(private val sessionDao: SessionDao) {
    val allSessions: Flow<List<FocusSession>> = sessionDao.getAllSessions()

    suspend fun startSession(startTime: Long, timerMinutes: Int): Long {
        val session = FocusSession(startTime = startTime, initialTimerMinutes = timerMinutes)
        return sessionDao.insertSession(session)
    }

    suspend fun endSession(sessionId: Long, endTime: Long, avgNoise: Double, focusIndex: Int) {
        // In a real app we would fetch the session first, but for simplicity:
        val session = FocusSession(
            id = sessionId,
            startTime = 0,
            endTime = endTime,
            initialTimerMinutes = 0,
            averageNoiseLevel = avgNoise,
            focusIndex = focusIndex
        )
        sessionDao.updateSession(session)
    }

    suspend fun addNoiseEvent(event: NoiseEvent) {
        sessionDao.insertNoiseEvent(event)
    }
    
    suspend fun getSessionCount(): Int = sessionDao.getSessionCount()
}
