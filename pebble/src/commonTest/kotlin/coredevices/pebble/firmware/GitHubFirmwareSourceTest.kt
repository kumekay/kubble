package coredevices.pebble.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubFirmwareSourceTest {

    private val assets = listOf(
        "normal_obelix_dvt_v4.33.1.pbz",
        "normal_obelix_dvt_v4.33.1_slot0.pbz",
        "normal_obelix_dvt_v4.33.1_slot1.pbz",
        "normal_obelix_pvt_v4.33.1.pbz",
        "normal_asterix_v4.33.1.pbz",
        "recovery_obelix_dvt_v4.33.1.pbz",
        "firmware_obelix_dvt_v4.33.1_slot0.bin",
    )
    private val urls = assets.associateWith { "https://example.com/$it" }

    @Test
    fun selectsFullNormalPbzForHardware() {
        assertEquals(
            "https://example.com/normal_obelix_dvt_v4.33.1.pbz",
            GitHubFirmwareSource.selectAsset(assets, urls, "obelix_dvt", "v4.33.1", isRecovery = false),
        )
        assertEquals(
            "https://example.com/normal_obelix_pvt_v4.33.1.pbz",
            GitHubFirmwareSource.selectAsset(assets, urls, "obelix_pvt", "v4.33.1", isRecovery = false),
        )
    }

    @Test
    fun selectsRecoveryPbzWhenRunningRecovery() {
        assertEquals(
            "https://example.com/recovery_obelix_dvt_v4.33.1.pbz",
            GitHubFirmwareSource.selectAsset(assets, urls, "obelix_dvt", "v4.33.1", isRecovery = true),
        )
    }

    @Test
    fun ignoresSlotSpecificAndRawBinaries() {
        // Only the exact full-bundle name matches; _slot0/_slot1 variants and
        // raw firmware binaries are never picked.
        val onlySlotAssets = listOf("normal_obelix_dvt_v4.33.1_slot0.pbz")
        assertNull(
            GitHubFirmwareSource.selectAsset(
                onlySlotAssets,
                onlySlotAssets.associateWith { it },
                "obelix_dvt",
                "v4.33.1",
                isRecovery = false,
            )
        )
    }

    @Test
    fun returnsNullWhenHardwareNotInRelease() {
        assertNull(
            GitHubFirmwareSource.selectAsset(assets, urls, "obelix_bb", "v4.33.1", isRecovery = false)
        )
    }

    @Test
    fun returnsNullWhenTagDoesNotMatch() {
        assertNull(
            GitHubFirmwareSource.selectAsset(assets, urls, "obelix_dvt", "v9.9.9", isRecovery = false)
        )
    }
}
