package com.cape100.coach.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.cape100.coach.data.BodyReading
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/**
 * Reads body-composition data from Android Health Connect. The OKOK International app syncs its scale readings
 * to Google Fit, and Google Fit shares them with Health Connect, where this app reads them (read-only).
 */
class HealthConnectReader(private val context: Context) {

    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(BoneMassRecord::class),
            HealthPermission.getReadPermission(BodyWaterMassRecord::class),
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
            HealthPermission.getReadPermission(HeightRecord::class),
        )
        private val APP_NAMES = mapOf(
            "com.chipsea.btcontrol.en" to "OKOK",
            "com.google.android.apps.fitness" to "Google Fit",
            "com.sec.android.app.shealth" to "Samsung Health",
            "com.garmin.android.apps.connectmobile" to "Garmin",
        )
        fun appName(pkg: String) = APP_NAMES[pkg] ?: pkg.substringAfterLast('.')
    }

    val available: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        available && client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS.filter { "WEIGHT" in it }.toSet())

    data class Result(val readings: List<BodyReading>, val heightM: Double?)

    /** All weigh-ins in the last [days] days, newest first. Measurements taken within 3 minutes are merged into one reading. */
    suspend fun readBody(days: Long = 365): Result {
        val range = TimeRangeFilter.after(Instant.now().minus(days, ChronoUnit.DAYS))
        val zone = ZoneId.systemDefault()

        val weights = read(WeightRecord::class, range)
        val fats = read(BodyFatRecord::class, range)
        val lean = read(LeanBodyMassRecord::class, range)
        val bone = read(BoneMassRecord::class, range)
        val water = read(BodyWaterMassRecord::class, range)
        val bmr = read(BasalMetabolicRateRecord::class, range)
        val height = read(HeightRecord::class, TimeRangeFilter.after(Instant.now().minus(3650, ChronoUnit.DAYS)))
            .maxByOrNull { it.time }?.height?.inMeters

        fun <T> near(list: List<T>, t: Instant, time: (T) -> Instant) = list.firstOrNull { kotlin.math.abs(ChronoUnit.SECONDS.between(time(it), t)) <= 180 }

        val readings = weights.sortedByDescending { it.time }.map { w ->
            BodyReading(
                time = w.time.toString(),
                date = w.time.atZone(zone).toLocalDate().toString(),
                weightKg = w.weight.inKilograms,
                bodyFatPct = near(fats, w.time) { it.time }?.percentage?.value,
                leanMassKg = near(lean, w.time) { it.time }?.mass?.inKilograms,
                boneMassKg = near(bone, w.time) { it.time }?.mass?.inKilograms,
                waterKg = near(water, w.time) { it.time }?.mass?.inKilograms,
                bmrKcal = near(bmr, w.time) { it.time }?.basalMetabolicRate?.inKilocaloriesPerDay,
                source = appName(w.metadata.dataOrigin.packageName),
            )
        }
        return Result(readings, height)
    }

    private suspend fun <T : Record> read(type: KClass<T>, range: TimeRangeFilter): List<T> = runCatching {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(type, range, pageToken = token))
            out += resp.records
            token = resp.pageToken
        } while (token != null && out.size < 5000)
        out
    }.getOrDefault(emptyList())
}
