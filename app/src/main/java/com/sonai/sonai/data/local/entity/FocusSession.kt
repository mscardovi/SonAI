package com.sonai.sonai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "focus_sessions")
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val initialTimerMinutes: Int,
    val averageNoiseLevel: Double = 0.0,
    val focusIndex: Int = 0 // 0-100 score
)
