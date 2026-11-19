package com.cape100.coach.scale

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import android.util.SparseArray
import kotlin.math.roundToInt

/**
 * Reads OKOK (Chipsea) bathroom scales directly over Bluetooth. These scales don't need pairing: while you stand on
 * them they broadcast the weight (and, on body-fat models, the impedance) in their Bluetooth advertisement.
 * Frame layouts follow the community documentation in the openScale project.
 */
object OkokProtocol {
    const val V20 = 0x20ca
    const val V11 = 0x11ca
    const val VF0 = 0xf0ff

    data class Frame(val weightKg: Double, val impedance: Double?, val stable: Boolean, val variant: String)

    fun isScaleName(name: String?): Boolean = name != null &&
        (name == "ADV" || name == "Chipsea-BLE" || name.startsWith("Yoda", true) || name.contains("OKOK", true))

    fun looksLikeScale(name: String?, m: SparseArray<ByteArray>): Boolean =
        isScaleName(name) || keys(m).any { it == V20 || it == V11 || it == VF0 || (name.isNullOrEmpty() && (it and 0xFF) == 0xC0) }

    fun parse(name: String?, m: SparseArray<ByteArray>): Frame? {
        m[V20]?.let { d -> return parseV20(d) }
        m[V11]?.let { d -> return parseV11(d) }
        m[VF0]?.let { d -> if (d.size >= 4) return Frame(u16(d[3], d[2]) / 10.0, null, true, "VF0") }
        if (name.isNullOrEmpty() || isScaleName(name)) {
            keys(m).firstOrNull { (it and 0xFF) == 0xC0 }?.let { k -> m[k]?.let { return parseC0(it) } }
        }
        return null
    }

    private fun parseV20(d: ByteArray): Frame? {
        if (d.size < 13) return null
        var x = 0x20
        for (i in 0 until 12) x = x xor (d[i].toInt() and 0xFF)
        if ((d[12].toInt() and 0xFF) != (x and 0xFF)) return null
        val flags = d[6].toInt() and 0xFF
        val divider = if (flags and 0x04 != 0) 100.0 else 10.0
        val imp = u16(d[10], d[11]) / 10.0
        return Frame(u16(d[8], d[9]) / divider, imp.takeIf { it > 0 }, flags and 0x01 != 0, "V20")
    }

    private fun parseV11(d: ByteArray): Frame? {
        if (d.size < 17) return null
        var x = 0xCA xor 0x11
        for (i in 0 until 16) x = x xor (d[i].toInt() and 0xFF)
        if ((d[16].toInt() and 0xFF) != (x and 0xFF)) return null
        val props = d[9].toInt() and 0xFF
        val kg = toKg(u16(d[3], d[4]), d[3], d[4], props) ?: return null
        return Frame(kg, null, true, "V11")
    }

    private fun parseC0(d: ByteArray): Frame? {
        if (d.size < 7) return null
        val attrib = d[6].toInt() and 0xFF
        val kg = toKg(u16(d[0], d[1]), d[0], d[1], attrib) ?: return null
        return Frame(kg, (u16(d[2], d[3]) / 10.0).takeIf { it > 0 }, attrib and 0x01 != 0, "C0")
    }

    /** Bits 1-2: resolution, bits 3-4: unit (kg, jin, lb, st:lb). */
    private fun toKg(raw: Int, msb: Byte, lsb: Byte, props: Int): Double? {
        val divider = when ((props shr 1) and 3) { 1 -> 1.0; 2 -> 100.0; else -> 10.0 }
        return when ((props shr 3) and 3) {
            0 -> raw / divider
            1 -> raw / divider / 2
            2 -> raw / divider / 2.204623
            3 -> (msb.toInt() and 0xFF) * 6.350293 + (lsb.toInt() and 0xFF) / divider * 0.453592
            else -> null
        }
    }

    private fun u16(msb: Byte, lsb: Byte) = ((msb.toInt() and 0xFF) shl 8) or (lsb.toInt() and 0xFF)
    private fun keys(m: SparseArray<ByteArray>) = (0 until m.size()).map { m.keyAt(it) }
    fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
}

/** Body composition from weight + impedance, using the OKOK vendor model (as documented by openScale). */
object BodyComposition {
    data class Result(
        val bodyFatPct: Double, val leanMassKg: Double, val waterPct: Double, val boneMassKg: Double,
        val muscleKg: Double, val visceralFat: Double, val bmrKcal: Double, val bodyAge: Int,
    )

    fun compute(weight: Double, impedance: Double, heightCm: Double, age: Int, male: Boolean): Result {
        val fatMass = if (male) heightCm * -0.3315 + weight * 0.6216 + age * 0.0183 + impedance * 0.0085 + 22.554
        else heightCm * -0.3332 + weight * 0.7509 + age * 0.0196 + impedance * 0.0072 + 22.7193
        val bodyFat = (fatMass / weight * 100).coerceIn(5.0, 45.0)
        val muscle = when {
            bodyFat > 45 -> weight * 0.95 - 4
            bodyFat < 5 -> weight * 0.95 - 1
            male -> heightCm * 0.2867 + weight * 0.3894 - age * 0.0408 - impedance * 0.01235 - 15.7665
            else -> heightCm * 0.3186 + weight * 0.1934 - age * 0.0206 - impedance * 0.0132 - 16.4556
        }.coerceIn(20.0, 70.0)
        val waterKg = if (male) heightCm * 0.0939 + weight * 0.3758 - age * 0.0032 - impedance * 0.006925 + 0.097
        else heightCm * 0.0877 + weight * 0.2973 - age * 0.0128 - impedance * 0.00603 + 0.5175
        val visceral = (if (male) heightCm * -0.2675 + weight * 0.42 + age * 0.1462 + impedance * 0.0123 + 13.9871
        else heightCm * -0.1651 + weight * 0.2628 + age * 0.0649 + impedance * 0.0024 + 12.3445).coerceIn(1.0, 60.0)
        val bmr = if (male) 66 + weight * 13.7 + heightCm * 5 - age * 6.8 else 655 + weight * 9.6 + heightCm * 1.8 - age * 4.7
        val bodyAge = (if (male) heightCm * -0.7471 + weight * 0.9161 + age * 0.4184 + impedance * 0.0517 + 54.2267
        else heightCm * -1.1165 + weight * 1.5784 + age * 0.4615 + impedance * 0.0415 + 83.2548).roundToInt().coerceIn(18, 80)
        return Result(
            bodyFatPct = bodyFat,
            leanMassKg = weight - weight * bodyFat / 100,
            waterPct = (waterKg / weight * 100).coerceIn(20.0, 85.0),
            boneMassKg = (weight - fatMass - muscle).coerceIn(1.0, 4.0),
            muscleKg = muscle,
            visceralFat = visceral,
            bmrKcal = bmr,
            bodyAge = bodyAge,
        )
    }
}

/** Listens for OKOK scale broadcasts. Caller must hold BLUETOOTH_SCAN (Android 12+) / location permission. */
class OkokScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var callback: ScanCallback? = null

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun start(onFrame: (OkokProtocol.Frame) -> Unit, onError: (String) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return onError("Bluetooth is off")
        stop()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val m = result.scanRecord?.manufacturerSpecificData ?: return
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (!OkokProtocol.looksLikeScale(name, m)) return
                // Raw frames go to logcat (tag OkokScan) to help decode unfamiliar scale models.
                for (i in 0 until m.size()) Log.d("OkokScan", "name=$name rssi=${result.rssi} id=%04X data=%s".format(m.keyAt(i), OkokProtocol.hex(m.valueAt(i))))
                OkokProtocol.parse(name, m)?.takeIf { it.weightKg in 5.0..300.0 }?.let(onFrame)
            }
            override fun onScanFailed(errorCode: Int) = onError("Bluetooth scan failed ($errorCode)")
        }
        callback = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        callback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        callback = null
    }
}
