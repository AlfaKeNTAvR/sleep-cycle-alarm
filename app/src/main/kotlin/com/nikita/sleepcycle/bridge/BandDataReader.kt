package com.nikita.sleepcycle.bridge

// File purpose: copies the Gadgetbridge database export into cache, verifies it, and reads sleep data and
// the band's alarm table from it in the same pass.

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import com.nikita.sleepcycle.engine.SleepSegment
import java.io.File
import java.time.Instant

private const val CACHE_FILE_NAME = "gadgetbridge_export_copy.db"
private const val TABLE_ACTIVITY_SAMPLE = "HUAWEI_ACTIVITY_SAMPLE"
private const val TABLE_DEVICE = "DEVICE"
private const val TABLE_ALARM = "ALARM"

sealed interface BandDataResult {
    data class Success(
        val segments: List<SleepSegment>,
        val newestSampleAt: Instant?,
        val bandAlarms: List<BandAlarmSlot>,
        val exportFileModifiedAt: Instant?
    ) : BandDataResult
    data class Failure(val step: String, val cause: String) : BandDataResult
}

/**
 * Copies the exported database at [exportUri] into cache, runs a quick integrity check, finds the device row
 * for [deviceMac], and reads sleep segments at or after [since] plus the current band alarm table. The cache
 * copy is always deleted afterwards, whether reading succeeded or not.
 */
fun readBandData(context: Context, exportUri: Uri, deviceMac: String, since: Instant): BandDataResult {
    val exportFileModifiedAt = queryDocumentLastModified(context, exportUri)
    val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
    try {
        copyUriToFile(context, exportUri, cacheFile)
    } catch (error: Exception) {
        return BandDataResult.Failure("copy", error.message ?: error.toString())
    }

    return try {
        SQLiteDatabase.openDatabase(cacheFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            readFromOpenDatabase(database, deviceMac, since, exportFileModifiedAt)
        }
    } catch (error: Exception) {
        BandDataResult.Failure("read", error.message ?: error.toString())
    } finally {
        cacheFile.delete()
    }
}

private fun readFromOpenDatabase(
    database: SQLiteDatabase,
    deviceMac: String,
    since: Instant,
    exportFileModifiedAt: Instant?
): BandDataResult {
    if (!databaseIsHealthy(database)) {
        return BandDataResult.Failure("quick_check", "PRAGMA quick_check did not return ok")
    }
    val deviceId = findDeviceId(database, deviceMac)
        ?: return BandDataResult.Failure("device_lookup", "no DEVICE row for MAC $deviceMac")
    val samples = readRawActivitySamples(database, deviceId, since)
    val segments = mapRawSamplesToSleepSegments(samples, deviceId)
    val newestSampleAt = newestHeartRateSampleAt(samples, deviceId)
    val alarmRows = readRawBandAlarmRows(database, deviceId)
    val bandAlarms = mapRawBandAlarmRowsToSlots(alarmRows, deviceId)
    return BandDataResult.Success(segments, newestSampleAt, bandAlarms, exportFileModifiedAt)
}

private fun copyUriToFile(context: Context, uri: Uri, destination: File) {
    val input = context.contentResolver.openInputStream(uri) ?: error("cannot open export uri $uri")
    input.use { source -> destination.outputStream().use { target -> source.copyTo(target) } }
}

/** The export document's last-modified time, used to tell "Gadgetbridge exported nothing new" from a fresh export. Null if the provider does not report it. */
private fun queryDocumentLastModified(context: Context, uri: Uri): Instant? =
    try {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) Instant.ofEpochMilli(cursor.getLong(0)) else null }
    } catch (error: Exception) {
        null
    }

private fun databaseIsHealthy(database: SQLiteDatabase): Boolean =
    database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
        cursor.moveToFirst() && cursor.getString(0) == "ok"
    }

private fun findDeviceId(database: SQLiteDatabase, deviceMac: String): Int? =
    database.rawQuery("SELECT _id FROM $TABLE_DEVICE WHERE IDENTIFIER = ?", arrayOf(deviceMac)).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else null
    }

private fun readRawActivitySamples(database: SQLiteDatabase, deviceId: Int, since: Instant): List<RawActivitySample> {
    val query = """
        SELECT TIMESTAMP, OTHER_TIMESTAMP, RAW_KIND, SOURCE, DEVICE_ID
        FROM $TABLE_ACTIVITY_SAMPLE
        WHERE DEVICE_ID = ? AND SOURCE IN ($SOURCE_HEART_RATE, $SOURCE_SLEEP) AND TIMESTAMP >= ?
    """.trimIndent()
    database.rawQuery(query, arrayOf(deviceId.toString(), since.epochSecond.toString())).use { cursor ->
        val samples = mutableListOf<RawActivitySample>()
        while (cursor.moveToNext()) {
            samples += RawActivitySample(
                timestampSeconds = cursor.getLong(0),
                otherTimestampSeconds = cursor.getLong(1),
                rawKind = cursor.getInt(2),
                source = cursor.getInt(3),
                deviceId = cursor.getInt(4)
            )
        }
        return samples
    }
}

private fun readRawBandAlarmRows(database: SQLiteDatabase, deviceId: Int): List<RawBandAlarmRow> {
    val query = """
        SELECT DEVICE_ID, POSITION, ENABLED, HOUR, MINUTE, TITLE, SMART_WAKEUP, REPETITION, SMART_WAKEUP_INTERVAL
        FROM $TABLE_ALARM
        WHERE DEVICE_ID = ?
    """.trimIndent()
    database.rawQuery(query, arrayOf(deviceId.toString())).use { cursor ->
        val rows = mutableListOf<RawBandAlarmRow>()
        while (cursor.moveToNext()) {
            rows += RawBandAlarmRow(
                deviceId = cursor.getInt(0),
                position = cursor.getInt(1),
                enabled = cursor.getInt(2) != 0,
                hour = cursor.getInt(3),
                minute = cursor.getInt(4),
                title = if (cursor.isNull(5)) null else cursor.getString(5),
                smartWakeup = cursor.getInt(6) != 0,
                repetition = cursor.getInt(7),
                smartWakeupWindowMinutes = if (cursor.isNull(8)) null else cursor.getInt(8)
            )
        }
        return rows
    }
}
