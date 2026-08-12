package coredevices.pebble.firmware

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Firmware source backed by the public coredevices/PebbleOS GitHub releases.
 * Used for Core devices when no Memfault token is configured (e.g. self-built forks),
 * since Rebble's cohorts server only serves classic Pebbles.
 */
class GitHubFirmwareSource(
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    },
) {
    private val logger = Logger.withTag("GitHubFirmwareSource")

    suspend fun getLatestFirmware(watch: WatchInfo): FirmwareUpdateCheckResult = try {
        fetchLatestFirmware(watch)
    } catch (e: Exception) {
        logger.w(e) { "GitHub firmware check failed: ${e.message}" }
        FirmwareUpdateCheckResult.UpdateCheckFailed("GitHub check failed: ${e.message}")
    }

    private suspend fun fetchLatestFirmware(watch: WatchInfo): FirmwareUpdateCheckResult {
        val release = httpClient.get(LATEST_RELEASE_URL) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "Kubble")
            timeout { requestTimeoutMillis = 15_000 }
        }
        if (!release.status.isSuccess()) {
            logger.w { "GitHub returned ${release.status}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("GitHub returned ${release.status}")
        }
        val response = release.body<GitHubRelease>()

        val assetUrl = selectAsset(
            assetNames = response.assets.map { it.name },
            assetUrlByName = response.assets.associate { it.name to it.browserDownloadUrl },
            hardware = watch.platform.revision,
            tag = response.tagName,
            isRecovery = watch.runningFwVersion.isRecovery,
        )
        if (assetUrl == null) {
            logger.i { "No ${watch.platform.revision} firmware in release ${response.tagName}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed(
                "No firmware for ${watch.platform.revision} in release ${response.tagName}"
            )
        }

        val latestVersion = FirmwareVersion.from(
            tag = response.tagName,
            isRecovery = watch.runningFwVersion.isRecovery,
            gitHash = "",
            timestamp = runCatching { Instant.parse(response.publishedAt) }
                .getOrDefault(Instant.DISTANT_PAST),
            isDualSlot = false, // Resolved from the pbz manifest during install.
            isSlot0 = false,
        )
        if (latestVersion == null) {
            logger.e { "Couldn't parse firmware version from tag ${response.tagName}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Couldn't parse version ${response.tagName}")
        }

        return if (watch.runningFwVersion.isRecovery || latestVersion > watch.runningFwVersion) {
            FirmwareUpdateCheckResult.FoundUpdate(
                version = latestVersion,
                url = assetUrl,
                notes = response.body.orEmpty(),
            )
        } else {
            FirmwareUpdateCheckResult.FoundNoUpdate
        }
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/coredevices/PebbleOS/releases/latest"

        /**
         * Pick the full (non-slot-specific) pbz for the hardware, e.g.
         * `normal_obelix_dvt_v4.33.1.pbz`. The updater selects the target slot
         * from the pbz manifest itself.
         */
        internal fun selectAsset(
            assetNames: List<String>,
            assetUrlByName: Map<String, String>,
            hardware: String,
            tag: String,
            isRecovery: Boolean,
        ): String? {
            val kind = if (isRecovery) "recovery" else "normal"
            val name = "${kind}_${hardware}_${tag}.pbz"
            return if (name in assetNames) assetUrlByName[name] else null
        }
    }
}

@Serializable
private data class GitHubRelease(
    @kotlinx.serialization.SerialName("tag_name")
    val tagName: String,
    @kotlinx.serialization.SerialName("published_at")
    val publishedAt: String = "",
    val body: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @kotlinx.serialization.SerialName("browser_download_url")
    val browserDownloadUrl: String,
)
