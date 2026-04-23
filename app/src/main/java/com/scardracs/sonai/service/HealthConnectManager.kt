package com.scardracs.sonai.service

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata as HealthMetadata
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) {
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    companion object {
        fun requestPermissionActivityContract() = PermissionController.createRequestPermissionResultContract()
    }

    val permissions = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class)
    )

    suspend fun hasPermissions(): Boolean {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return granted.containsAll(permissions)
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun writeHeartRate(bpm: Int) {
        if (!hasPermissions()) return

        val now = Instant.now()
        val offset = ZoneOffset.systemDefault().rules.getOffset(now)
        val record = HeartRateRecord(
            startTime = now.minusSeconds(1),
            startZoneOffset = offset,
            endTime = now,
            endZoneOffset = offset,
            samples = listOf(
                HeartRateRecord.Sample(
                    time = now,
                    beatsPerMinute = bpm.toLong()
                )
            ),
            metadata = HealthMetadata.unknownRecordingMethod()
        )
        try {
            healthConnectClient.insertRecords(listOf(record))
        } catch (e: Exception) {
            Log.e("HealthConnect", "Error writing heart rate record", e)
        }
    }
}
