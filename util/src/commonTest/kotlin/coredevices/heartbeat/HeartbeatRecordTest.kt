package coredevices.heartbeat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class HeartbeatRecordTest {

    private val headerSize = 1 + 8 + 20

    private fun encodeRecord(
        version: Int = NATIVE_HEARTBEAT_RECORD_VERSION,
        timestamp: Long = 1_750_000_000L,
        ints: Map<String, Long> = emptyMap(),
        scaled: Map<String, Pair<Long, Int>> = emptyMap(),
        strings: Map<String, String> = emptyMap(),
        trailingBytes: Int = 0,
    ): ByteArray {
        val total = headerSize + HEARTBEAT_V3_FIELDS.sumOf { it.size } + trailingBytes
        val out = ByteArray(total)
        out[0] = version.toByte()
        for (i in 0 until 8) {
            out[1 + i] = (timestamp shr (8 * i)).toByte()
        }
        var o = headerSize
        for (field in HEARTBEAT_V3_FIELDS) {
            when (field.kind) {
                Kind.STR -> {
                    val s = strings[field.name]?.encodeToByteArray() ?: ByteArray(0)
                    s.copyInto(out, o, 0, minOf(s.size, field.len))
                }
                Kind.SU, Kind.SI -> {
                    val (raw, scale) = scaled[field.name] ?: (0L to 0)
                    writeU32(out, o, raw)
                    writeU16(out, o + 4, scale)
                }
                Kind.U, Kind.I, Kind.T -> writeU32(out, o, ints[field.name] ?: 0L)
            }
            o += field.size
        }
        return out
    }

    private fun writeU32(out: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) out[o + i] = (v shr (8 * i)).toByte()
    }

    private fun writeU16(out: ByteArray, o: Int, v: Int) {
        for (i in 0 until 2) out[o + i] = (v shr (8 * i)).toByte()
    }

    @Test
    fun schemaSizeMatchesFirmwareRecord() {
        // sizeof(struct native_heartbeat_record) for version 3, per native.c's _Static_assert
        assertEquals(567, headerSize + HEARTBEAT_V3_FIELDS.sumOf { it.size })
    }

    @Test
    fun parsesBatteryFields() {
        val data = encodeRecord(
            timestamp = 1_750_000_000L,
            scaled = mapOf(
                "battery_soc_pct" to (8_750L to 100),
                "battery_soc_pct_min" to (8_000L to 100),
                "battery_voltage" to (4_123L to 1000),
                "battery_voltage_delta" to (-50L to 1000),
                "battery_temp_c" to (-500L to 1000),
                "cpu_running_pct" to (12_50L to 100),
            ),
            ints = mapOf(
                "battery_tte_s" to 123_456L,
                "battery_charge_time_ms" to 3_600_000L,
                "battery_discharge_duration_ms" to 7_200_000L,
                "backlight_on_time_ms" to 60_000L,
                "hrm_on_time_ms" to 120_000L,
                "vibrator_on_time_ms" to 1_500L,
                "speaker_on_time_ms" to 2_500L,
                "connectivity_connected_time_ms" to 3_000_000L,
            ),
            strings = mapOf("fw_version" to "v4.5.0"),
        )

        val rec = parseNativeHeartbeat(data)

        assertNotNull(rec)
        assertEquals(Instant.fromEpochSeconds(1_750_000_000L), rec.timestamp)
        assertEquals("v4.5.0", rec.fwVersion)
        assertEquals(87.5, rec.batterySocPct)
        assertEquals(80.0, rec.batterySocPctMin)
        assertEquals(4.123, rec.batteryVoltageV)
        assertEquals(-0.05, rec.batteryVoltageDeltaV)
        assertEquals(123_456L, rec.batteryTteSeconds)
        assertEquals(3_600_000L, rec.batteryChargeTimeMs)
        assertEquals(7_200_000L, rec.batteryDischargeTimeMs)
        assertEquals(-0.5, rec.batteryTempC)
        assertEquals(12.5, rec.cpuRunningPct)
        assertEquals(60_000L, rec.backlightOnTimeMs)
        assertEquals(120_000L, rec.hrmOnTimeMs)
        assertEquals(1_500L, rec.vibratorOnTimeMs)
        assertEquals(2_500L, rec.speakerOnTimeMs)
        assertEquals(3_000_000L, rec.bleConnectedTimeMs)
    }

    @Test
    fun wrongVersionReturnsNull() {
        assertNull(parseNativeHeartbeat(encodeRecord(version = 2)))
        assertNull(parseNativeHeartbeat(encodeRecord(version = 4)))
    }

    @Test
    fun tooShortReturnsNull() {
        assertNull(parseNativeHeartbeat(ByteArray(10)))
        assertNull(parseNativeHeartbeat(ByteArray(0)))
    }

    @Test
    fun truncatedRecordParsesAvailablePrefix() {
        val full = encodeRecord(
            scaled = mapOf(
                "battery_soc_pct" to (5_000L to 100),
                "battery_temp_c" to (2_500L to 1000),
            ),
        )
        val cutoff = headerSize + HEARTBEAT_V3_FIELDS
            .takeWhile { it.name != "battery_tte_s" }
            .sumOf { it.size }

        val rec = parseNativeHeartbeat(full.copyOfRange(0, cutoff))

        assertNotNull(rec)
        assertEquals(50.0, rec.batterySocPct)
        assertNull(rec.batteryTempC)
    }

    @Test
    fun trailingBytesTolerated() {
        val rec = parseNativeHeartbeat(
            encodeRecord(scaled = mapOf("battery_soc_pct" to (10_000L to 100)), trailingBytes = 8),
        )
        assertNotNull(rec)
        assertEquals(100.0, rec.batterySocPct)
    }
}
