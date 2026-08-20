package coredevices.heartbeat

import kotlin.time.Instant

// Decoder for the PebbleOS `native_heartbeat_record` (src/fw/services/analytics/native.c),
// whose field layout is X-macro-expanded from include/pbl/services/analytics/analytics.def.
// The firmware memcpys the packed struct into datalogging (tag 87), so all fields are
// little-endian. Field order below must match analytics.def exactly.

const val NATIVE_HEARTBEAT_RECORD_VERSION = 3

private const val BUILD_ID_LEN = 20
private const val HEADER_SIZE = 1 + 8 + BUILD_ID_LEN

internal enum class Kind { U, I, SU, SI, T, STR }

internal data class Field(val name: String, val kind: Kind, val len: Int = 0) {
    val size: Int
        get() = when (kind) {
            Kind.U, Kind.I, Kind.T -> 4
            Kind.SU, Kind.SI -> 6
            Kind.STR -> len + 1
        }
}

// Transcribed from analytics.def (record version 3). SU/SI carry a u16 scale after the value.
internal val HEARTBEAT_V3_FIELDS: List<Field> = listOf(
    // OS
    Field("memory_pct_max", Kind.U),
    Field("memory_largest_free_pct", Kind.U),
    Field("stack_free_kernel_main_bytes", Kind.U),
    Field("stack_free_kernel_background_bytes", Kind.U),
    Field("stack_free_newtimers_bytes", Kind.U),
    Field("stack_free_app_syscall_bytes", Kind.U),
    Field("stack_free_worker_syscall_bytes", Kind.U),
    Field("utc_offset_s", Kind.I),
    Field("fw_version", Kind.STR, 32),
    Field("last_reboot_reason", Kind.U),
    Field("uptime_s", Kind.U),
    // Battery & Power
    Field("battery_soc_pct", Kind.SU),
    Field("battery_soc_pct_drop", Kind.SU),
    Field("battery_voltage", Kind.SU),
    Field("battery_voltage_delta", Kind.SI),
    Field("battery_tte_s", Kind.U),
    Field("battery_charge_time_ms", Kind.T),
    Field("battery_discharge_duration_ms", Kind.T),
    // Hardware I/O
    Field("backlight_on_time_ms", Kind.T),
    Field("backlight_avg_intensity_pct", Kind.U),
    Field("vibrator_on_time_ms", Kind.T),
    Field("vibrator_avg_strength_pct", Kind.U),
    Field("speaker_on_time_ms", Kind.T),
    Field("speaker_play_count", Kind.U),
    Field("speaker_avg_volume_pct", Kind.U),
    Field("speaker_preempted_count", Kind.U),
    Field("speaker_stream_underrun_count", Kind.U),
    Field("hrm_on_time_ms", Kind.T),
    Field("button_pressed_count", Kind.U),
    Field("touch_event_count", Kind.U),
    Field("gesture_tap_count", Kind.U),
    Field("gesture_double_tap_count", Kind.U),
    Field("touch_driver_wake_cnt", Kind.U),
    // CPU usage
    Field("cpu_running_pct", Kind.SU),
    Field("cpu_sleep0_pct", Kind.SU),
    Field("cpu_sleep1_pct", Kind.SU),
    Field("cpu_sleep2_pct", Kind.SU),
    Field("sifli_ipc_not_idle_count", Kind.U),
    Field("task_cpu_kernel_main_pct", Kind.SU),
    Field("task_cpu_kernel_background_pct", Kind.SU),
    Field("task_cpu_worker_pct", Kind.SU),
    Field("task_cpu_app_pct", Kind.SU),
    Field("task_cpu_bt_host_pct", Kind.SU),
    Field("task_cpu_bt_controller_pct", Kind.SU),
    Field("task_cpu_bt_hci_pct", Kind.SU),
    Field("task_cpu_new_timers_pct", Kind.SU),
    Field("task_cpu_pulse_pct", Kind.SU),
    Field("task_cpu_idle_pct", Kind.SU),
    // Accelerometer
    Field("accel_sample_count", Kind.U),
    Field("accel_shake_count", Kind.U),
    Field("accel_double_tap_count", Kind.U),
    Field("accel_peek_count", Kind.U),
    // Notifications, phone calls
    Field("notification_received_count", Kind.U),
    Field("notification_received_dnd_count", Kind.U),
    Field("phone_call_incoming_count", Kind.U),
    Field("phone_call_time_ms", Kind.T),
    // Modes
    Field("low_power_time_ms", Kind.T),
    Field("stationary_time_ms", Kind.T),
    // Watchface
    Field("watchface_time_ms", Kind.T),
    Field("watchface_name", Kind.STR, 32),
    Field("watchface_uuid", Kind.STR, 39),
    Field("watchface_crash_count", Kind.U),
    Field("watchface_crash_revert_count", Kind.U),
    // File system
    Field("pfs_space_free_kb", Kind.U),
    // NOR Flash
    Field("flash_spi_write_bytes", Kind.U),
    Field("flash_spi_erase_bytes", Kind.U),
    // BLE
    Field("ble_adv_short_intvl_time_ms", Kind.T),
    Field("ble_adv_long_intvl_time_ms", Kind.T),
    Field("ble_conn_itvl_min_time_ms", Kind.T),
    Field("ble_conn_itvl_mid_time_ms", Kind.T),
    Field("ble_conn_itvl_max_time_ms", Kind.T),
    Field("ble_disconnect_conn_spvn_tmo_count", Kind.U),
    Field("ble_disconnect_rem_user_term_count", Kind.U),
    Field("ble_disconnect_conn_term_local_count", Kind.U),
    Field("ble_disconnect_lmp_ll_rsp_tmo_count", Kind.U),
    Field("ble_disconnect_conn_establishment_count", Kind.U),
    Field("ble_disconnect_other_count", Kind.U),
    Field("ppog_reversed", Kind.U),
    // Settings
    Field("settings_health_tracking_enabled", Kind.U),
    Field("settings_health_hrm_enabled", Kind.U),
    Field("settings_health_hrm_measurement_interval", Kind.U),
    Field("settings_health_hrm_activity_tracking_enabled", Kind.U),
    Field("settings_motion_sensitivity", Kind.U),
    Field("settings_backlight_intensity_pct", Kind.U),
    Field("settings_backlight_timeout_s", Kind.U),
    Field("settings_touch_enabled", Kind.U),
    // Application
    Field("app_message_sent_count", Kind.U),
    Field("app_message_received_count", Kind.U),
    Field("app_tick_timer_second_subscribed", Kind.U),
    // Connectivity
    Field("connectivity_connected_time_ms", Kind.T),
    Field("connectivity_expected_time_ms", Kind.T),
    // Appended in record version 3
    Field("ble_conn_slave_lat0_time_ms", Kind.T),
    Field("ble_conn_param_update_count", Kind.U),
    Field("accel_stream_recovery_count", Kind.U),
    Field("unexpected_reboot_count", Kind.U),
    Field("battery_temp_c", Kind.SI),
    Field("i2c_transfer_error_count", Kind.U),
    Field("ble_conn_itvl_other_time_ms", Kind.T),
    Field("drv_init_fail_flags", Kind.U),
    Field("battery_soc_pct_min", Kind.SU),
    Field("touch_gated_touchdown_count", Kind.U),
)

data class NativeHeartbeat(
    val timestamp: Instant,
    val fwVersion: String?,
    val batterySocPct: Double?,
    val batterySocPctDrop: Double?,
    val batterySocPctMin: Double?,
    val batteryVoltageV: Double?,
    val batteryVoltageDeltaV: Double?,
    val batteryTteSeconds: Long?,
    val batteryChargeTimeMs: Long?,
    val batteryDischargeTimeMs: Long?,
    val batteryTempC: Double?,
    // Per-heartbeat-window component activity
    val backlightOnTimeMs: Long?,
    val hrmOnTimeMs: Long?,
    val vibratorOnTimeMs: Long?,
    val speakerOnTimeMs: Long?,
    val bleConnectedTimeMs: Long?,
    val cpuRunningPct: Double?,
    val appCpuPct: Double?,
    val watchfaceName: String?,
    val healthTrackingEnabled: Boolean?,
    val healthHrmEnabled: Boolean?,
    val healthHrmMeasurementInterval: Int?,
    val healthHrmActivityTrackingEnabled: Boolean?,
)

fun parseNativeHeartbeat(data: ByteArray): NativeHeartbeat? {
    if (data.size < HEADER_SIZE) return null
    val version = data[0].toInt() and 0xFF
    if (version != NATIVE_HEARTBEAT_RECORD_VERSION) return null
    val timestamp = data.u64Le(1)

    var fwVersion: String? = null
    var socPct: Double? = null
    var socPctDrop: Double? = null
    var socPctMin: Double? = null
    var voltageV: Double? = null
    var voltageDeltaV: Double? = null
    var tteSeconds: Long? = null
    var chargeTimeMs: Long? = null
    var dischargeTimeMs: Long? = null
    var tempC: Double? = null
    var backlightMs: Long? = null
    var hrmMs: Long? = null
    var vibratorMs: Long? = null
    var speakerMs: Long? = null
    var bleConnectedMs: Long? = null
    var cpuRunningPct: Double? = null
    var appCpuPct: Double? = null
    var watchfaceName: String? = null
    var healthTrackingEnabled: Boolean? = null
    var healthHrmEnabled: Boolean? = null
    var healthHrmMeasurementInterval: Int? = null
    var healthHrmActivityTrackingEnabled: Boolean? = null

    var o = HEADER_SIZE
    for (field in HEARTBEAT_V3_FIELDS) {
        if (o + field.size > data.size) break
        when (field.name) {
            "fw_version" -> fwVersion = data.cString(o, field.len)
            "battery_soc_pct" -> socPct = data.scaledU(o)
            "battery_soc_pct_drop" -> socPctDrop = data.scaledU(o)
            "battery_soc_pct_min" -> socPctMin = data.scaledU(o)
            "battery_voltage" -> voltageV = data.scaledU(o)
            "battery_voltage_delta" -> voltageDeltaV = data.scaledI(o)
            "battery_tte_s" -> tteSeconds = data.u32Le(o)
            "battery_charge_time_ms" -> chargeTimeMs = data.u32Le(o)
            "battery_discharge_duration_ms" -> dischargeTimeMs = data.u32Le(o)
            "battery_temp_c" -> tempC = data.scaledI(o)
            "backlight_on_time_ms" -> backlightMs = data.u32Le(o)
            "hrm_on_time_ms" -> hrmMs = data.u32Le(o)
            "vibrator_on_time_ms" -> vibratorMs = data.u32Le(o)
            "speaker_on_time_ms" -> speakerMs = data.u32Le(o)
            "connectivity_connected_time_ms" -> bleConnectedMs = data.u32Le(o)
            "cpu_running_pct" -> cpuRunningPct = data.scaledU(o)
            "task_cpu_app_pct" -> appCpuPct = data.scaledU(o)
            "watchface_name" -> watchfaceName = data.cString(o, field.len).ifBlank { null }
            "settings_health_tracking_enabled" -> healthTrackingEnabled = data.u32Le(o) != 0L
            "settings_health_hrm_enabled" -> healthHrmEnabled = data.u32Le(o) != 0L
            "settings_health_hrm_measurement_interval" -> healthHrmMeasurementInterval = data.u32Le(o).toInt()
            "settings_health_hrm_activity_tracking_enabled" -> healthHrmActivityTrackingEnabled =
                data.u32Le(o) != 0L
        }
        o += field.size
    }

    return NativeHeartbeat(
        timestamp = Instant.fromEpochSeconds(timestamp),
        fwVersion = fwVersion,
        batterySocPct = socPct,
        batterySocPctDrop = socPctDrop,
        batterySocPctMin = socPctMin,
        batteryVoltageV = voltageV,
        batteryVoltageDeltaV = voltageDeltaV,
        batteryTteSeconds = tteSeconds,
        batteryChargeTimeMs = chargeTimeMs,
        batteryDischargeTimeMs = dischargeTimeMs,
        batteryTempC = tempC,
        backlightOnTimeMs = backlightMs,
        hrmOnTimeMs = hrmMs,
        vibratorOnTimeMs = vibratorMs,
        speakerOnTimeMs = speakerMs,
        bleConnectedTimeMs = bleConnectedMs,
        cpuRunningPct = cpuRunningPct,
        appCpuPct = appCpuPct,
        watchfaceName = watchfaceName,
        healthTrackingEnabled = healthTrackingEnabled,
        healthHrmEnabled = healthHrmEnabled,
        healthHrmMeasurementInterval = healthHrmMeasurementInterval,
        healthHrmActivityTrackingEnabled = healthHrmActivityTrackingEnabled,
    )
}

private fun ByteArray.u16Le(o: Int): Int =
    (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32Le(o: Int): Long =
    (this[o].toLong() and 0xFF) or
        ((this[o + 1].toLong() and 0xFF) shl 8) or
        ((this[o + 2].toLong() and 0xFF) shl 16) or
        ((this[o + 3].toLong() and 0xFF) shl 24)

private fun ByteArray.u64Le(o: Int): Long {
    var v = 0L
    for (i in 7 downTo 0) {
        v = (v shl 8) or (this[o + i].toLong() and 0xFF)
    }
    return v
}

private fun ByteArray.scaledU(o: Int): Double? {
    val scale = u16Le(o + 4)
    if (scale == 0) return null
    return u32Le(o).toDouble() / scale
}

private fun ByteArray.scaledI(o: Int): Double? {
    val scale = u16Le(o + 4)
    if (scale == 0) return null
    return u32Le(o).toInt().toDouble() / scale
}

private fun ByteArray.cString(o: Int, len: Int): String {
    val end = (o until o + len + 1).firstOrNull { this[it] == 0.toByte() } ?: (o + len + 1)
    return copyOfRange(o, end).decodeToString()
}
