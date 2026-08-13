package coredevices.heartbeat

import co.touchlab.kermit.Logger
import coredevices.database.BatteryStatDao
import coredevices.database.BatteryStatEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class BatteryStatStore(
    private val dao: BatteryStatDao,
) {
    private val logger = Logger.withTag("BatteryStatStore")
    private val scope = CoroutineScope(Dispatchers.IO)

    // Non-suspending: called from the datalogging path for every analytics heartbeat.
    fun record(serial: String, payload: ByteArray) {
        val heartbeat = parseNativeHeartbeat(payload)
        if (heartbeat == null) {
            logger.v { "unparseable heartbeat from $serial (${payload.size} bytes)" }
            return
        }
        val row = BatteryStatEntity(
            serial = serial,
            timestamp = heartbeat.timestamp.epochSeconds,
            socPct = heartbeat.batterySocPct,
            socPctMin = heartbeat.batterySocPctMin,
            voltageV = heartbeat.batteryVoltageV,
            tteSeconds = heartbeat.batteryTteSeconds,
            chargeTimeMs = heartbeat.batteryChargeTimeMs,
            dischargeTimeMs = heartbeat.batteryDischargeTimeMs,
            tempC = heartbeat.batteryTempC,
            backlightMs = heartbeat.backlightOnTimeMs,
            hrmMs = heartbeat.hrmOnTimeMs,
            vibratorMs = heartbeat.vibratorOnTimeMs,
            speakerMs = heartbeat.speakerOnTimeMs,
            bleConnectedMs = heartbeat.bleConnectedTimeMs,
            cpuRunningPct = heartbeat.cpuRunningPct,
        )
        scope.launch {
            dao.insert(row)
            dao.deleteOlderThan(Clock.System.now().minus(RETENTION).epochSeconds)
        }
    }

    companion object {
        private val RETENTION = 30.days
    }
}
