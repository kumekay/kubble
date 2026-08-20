package coredevices.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "battery_stats", primaryKeys = ["serial", "timestamp"])
data class BatteryStatEntity(
    val serial: String,
    // epoch seconds, from the heartbeat record
    val timestamp: Long,
    val socPct: Double?,
    val socPctDrop: Double?,
    val socPctMin: Double?,
    val voltageV: Double?,
    val tteSeconds: Long?,
    val chargeTimeMs: Long?,
    val dischargeTimeMs: Long?,
    val tempC: Double?,
    val backlightMs: Long?,
    val hrmMs: Long?,
    val vibratorMs: Long?,
    val speakerMs: Long?,
    val bleConnectedMs: Long?,
    val cpuRunningPct: Double?,
    val appCpuPct: Double?,
    val watchfaceName: String?,
    val healthTrackingEnabled: Boolean?,
    val healthHrmEnabled: Boolean?,
    val healthHrmMeasurementInterval: Int?,
    val healthHrmActivityTrackingEnabled: Boolean?,
)

@Dao
interface BatteryStatDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: BatteryStatEntity)

    @Query("SELECT * FROM battery_stats WHERE serial = :serial ORDER BY timestamp ASC")
    fun observe(serial: String): Flow<List<BatteryStatEntity>>

    @Query("SELECT DISTINCT serial FROM battery_stats ORDER BY serial")
    fun observeSerials(): Flow<List<String>>

    @Query("DELETE FROM battery_stats WHERE timestamp < :cutoffEpochSeconds")
    suspend fun deleteOlderThan(cutoffEpochSeconds: Long)
}
