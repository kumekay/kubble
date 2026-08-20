package coredevices.pebble.ui

import coredevices.database.BatteryStatEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatterySettingsScreenTest {
    @Test
    fun contributionsUseRemainingWindowForSystem() {
        val contributions = batteryContributions(
            listOf(
                stat(timestamp = 100, vibratorMs = 5_000, hrmMs = 10_000, bleMs = 25_000),
                stat(timestamp = 200),
            ),
        )

        assertEquals(listOf("System", "Vibrator", "Heart Rate Monitor", "Bluetooth"), contributions.map { it.name })
        assertEquals(0.6, contributions[0].fraction, 0.001)
        assertEquals(0.05, contributions[1].fraction, 0.001)
        assertEquals(0.1, contributions[2].fraction, 0.001)
        assertEquals(0.25, contributions[3].fraction, 0.001)
        assertEquals(1.0, contributions.sumOf { it.fraction }, 0.001)
    }

    @Test
    fun contributionsNeedAWindow() {
        assertTrue(batteryContributions(emptyList()).isEmpty())
        assertTrue(batteryContributions(listOf(stat(timestamp = 100))).isEmpty())
    }

    private fun stat(
        timestamp: Long,
        vibratorMs: Long? = null,
        hrmMs: Long? = null,
        bleMs: Long? = null,
    ) = BatteryStatEntity(
        serial = "serial",
        timestamp = timestamp,
        socPct = null,
        socPctDrop = null,
        socPctMin = null,
        voltageV = null,
        tteSeconds = null,
        chargeTimeMs = null,
        dischargeTimeMs = null,
        tempC = null,
        backlightMs = null,
        hrmMs = hrmMs,
        vibratorMs = vibratorMs,
        speakerMs = null,
        bleConnectedMs = bleMs,
        cpuRunningPct = null,
        appCpuPct = null,
        watchfaceName = null,
        healthTrackingEnabled = null,
        healthHrmEnabled = null,
        healthHrmMeasurementInterval = null,
        healthHrmActivityTrackingEnabled = null,
    )
}
