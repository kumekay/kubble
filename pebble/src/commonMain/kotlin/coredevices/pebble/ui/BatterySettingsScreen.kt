package coredevices.pebble.ui

import CommonApiConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.database.BatteryStatDao
import coredevices.database.BatteryStatEntity
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.ui.SettingsKeys.KEY_ENABLE_MEMFAULT_UPLOADS
import coredevices.ui.PebbleElevatedButton
import coredevices.ui.PebbleWebview
import coredevices.ui.PebbleWebviewNavigator
import coredevices.ui.PebbleWebviewUrlInterceptor
import coredevices.ui.SignInDialog
import coredevices.util.emailOrNull
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import io.ktor.http.encodeURLParameter
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import org.koin.compose.koinInject

private const val MOBILE_BATTERY_PATH = "/m/battery"

@Composable
fun BatterySettingsScreen(navBarNav: NavBarNav, topBarParams: TopBarParams) {
    val apiConfig = koinInject<CommonApiConfig>()
    val settings = koinInject<Settings>()
    val analyticsEnabled = settings.getBoolean(KEY_ENABLE_MEMFAULT_UPLOADS, true)
    val accountEmail by Firebase.auth.idTokenChanged
        .map { it?.emailOrNull }
        .distinctUntilChanged()
        .collectAsState(Firebase.auth.currentUser?.emailOrNull)

    var showSignInDialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.title("Battery")
    }

    if (apiConfig.bugUrl.isNullOrBlank()) {
        LocalBatteryContent()
        LaunchedEffect(Unit) {
            topBarParams.actions { }
        }
        return
    }

    LaunchedEffect(accountEmail) {
        val email = accountEmail ?: run {
            url = null
            return@LaunchedEffect
        }
        val baseUrl = apiConfig.bugUrl
        if (baseUrl.isNullOrBlank()) {
            url = null
            loadError = "Battery analytics service is not configured"
            return@LaunchedEffect
        }
        val idToken = try {
            Firebase.auth.currentUser?.getIdToken(false)
        } catch (e: Exception) {
            Logger.withTag("BatterySettingsScreen").e(e) { "Failed to mint id token" }
            null
        }
        if (idToken == null) {
            // Drop the previous URL too, otherwise a stale (still-rendered)
            // WebView would hide the new error from the user.
            url = null
            loadError = "Sign in to view your battery analytics"
            return@LaunchedEffect
        }
        loadError = null
        url = buildBatteryUrl(baseUrl, email = email, idToken = idToken)
    }

    if (showSignInDialog) {
        SignInDialog(onDismiss = { showSignInDialog = false })
    }

    if (accountEmail == null) {
        SignedOutBatteryContent(onSignIn = { showSignInDialog = true })
        LaunchedEffect(Unit) {
            topBarParams.actions { }
        }
        return
    }

    if (!analyticsEnabled) {
        AnalyticsDisabledBatteryContent(
            onOpenSettings = {
                navBarNav.navigateTo(
                    PebbleNavBarRoutes.WatchSettingsCategoryRoute(
                        section = Section.Diagnostics.name,
                        topLevelType = TopLevelType.Phone.name,
                    ),
                )
            },
        )
        LaunchedEffect(Unit) {
            topBarParams.actions { }
        }
        return
    }

    val currentUrl = url
    val currentError = loadError
    if (currentUrl == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (currentError != null) {
                Text(
                    currentError,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        LaunchedEffect(Unit) {
            topBarParams.actions { }
        }
        return
    }

    var pageError by remember { mutableStateOf<String?>(null) }
    // Clear any prior failure when the target URL changes (e.g. sign-in or token refresh).
    LaunchedEffect(currentUrl) { pageError = null }

    if (pageError != null) {
        Logger.withTag("BatterySettingsScreen").w { "Battery page failed to load: $pageError" }
        BatteryLoadErrorContent(onRetry = { pageError = null })
        LaunchedEffect(Unit) {
            topBarParams.actions { }
        }
        return
    }

    val interceptor = remember {
        object : PebbleWebviewUrlInterceptor {
            override var navigator: PebbleWebviewNavigator? = null
            override fun onIntercept(url: String, navigator: PebbleWebviewNavigator) = true
        }
    }
    LaunchedEffect(interceptor) {
        topBarParams.actions {
            IconButton(onClick = { interceptor.navigator?.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        PebbleWebview(
            url = currentUrl,
            interceptor = interceptor,
            modifier = Modifier.fillMaxSize(),
            onPageError = { pageError = it },
        )
    }
}

private fun buildBatteryUrl(
    baseUrl: String,
    email: String,
    idToken: String,
): String {
    // bugUrl is configured as `<host>/api`; the mobile battery page is at the host root.
    val root = baseUrl.trimEnd('/').removeSuffix("/api")
    return buildString {
        append(root)
        append(MOBILE_BATTERY_PATH)
        append("?email=").append(email.encodeURLParameter())
        append("&googleIdToken=").append(idToken.encodeURLParameter())
    }
}

@Composable
private fun LocalBatteryContent() {
    val libPebble = rememberLibPebble()
    val batteryStatDao = koinInject<BatteryStatDao>()
    val watches by libPebble.watches.collectAsState()
    val connected = remember(watches) {
        watches.mapNotNull { watch ->
            (watch as? ConnectedPebble.Battery)?.let {
                ConnectedBattery(
                    name = watch.displayName(),
                    serial = (watch as? KnownPebbleDevice)?.serial,
                    level = it.batteryLevel,
                )
            }
        }
    }
    var selectedSerial by remember { mutableStateOf<String?>(null) }
    val chartSerial = selectedSerial ?: connected.firstNotNullOfOrNull { it.serial }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (connected.isEmpty()) {
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Connect a watch to see its battery level.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
        connected.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = item.serial != null) {
                        selectedSerial = item.serial
                    },
            ) {
                item.level?.let {
                    Icon(
                        imageVector = it.batteryIcon(),
                        contentDescription = "Battery",
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    item.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    item.level?.let { "$it%" } ?: "…",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        chartSerial?.let { serial ->
            val stats by batteryStatDao.observe(serial).collectAsState(initial = emptyList())
            BatteryHistoryChart(stats)
            ComponentActivityBreakdown(stats)
        }
    }
}

private data class ConnectedBattery(
    val name: String,
    val serial: String?,
    val level: Int?,
)

@Composable
private fun BatteryHistoryChart(stats: List<BatteryStatEntity>) {
    val points = remember(stats) {
        stats.mapNotNull { s -> s.socPct?.let { soc -> s.timestamp to soc } }
    }
    if (points.size < 2) {
        Text(
            "Battery history appears after a few heartbeats.",
            style = MaterialTheme.typography.bodySmall,
            color = AxisLabelColor,
        )
        return
    }
    val t0 = points.first().first
    val t1 = points.last().first
    val minSoc = points.minOf { it.second }
    val maxSoc = points.maxOf { it.second }
    Text(
        "${formatEpoch(t0)} – ${formatEpoch(t1)} · ${minSoc.toInt()}–${maxSoc.toInt()}%",
        style = MaterialTheme.typography.bodySmall,
        color = AxisLabelColor,
    )
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        drawRect(ChartOverlayColor, Offset.Zero, Size(size.width, size.height))
        for (grid in listOf(25f, 50f, 75f)) {
            val y = size.height * (1 - grid / 100)
            drawLine(
                AxisLabelColor,
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
        }
        val span = maxOf(1L, t1 - t0)
        val inset = 4.dp.toPx()
        val pts = points.map { (t, soc) ->
            Offset(
                inset + ((t - t0).toFloat() / span) * (size.width - 2 * inset),
                size.height * (1 - soc.toFloat() / 100),
            )
        }
        drawPath(smoothLinePath(pts), ActivityFillColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun ComponentActivityBreakdown(stats: List<BatteryStatEntity>) {
    val cutoff = Clock.System.now().minus(24.hours).epochSeconds
    val recent = remember(stats) { stats.filter { it.timestamp >= cutoff } }
    if (recent.size < 2) return
    val windowMs = (recent.last().timestamp - recent.first().timestamp) * 1000
    if (windowMs <= 0) return
    val cpuMs = recent.mapNotNull { it.cpuRunningPct }.average().let { pct ->
        (pct / 100 * windowMs).toLong()
    }
    val items = listOf(
        "Bluetooth" to recent.sumOf { it.bleConnectedMs ?: 0 },
        "CPU" to cpuMs,
        "Heart rate" to recent.sumOf { it.hrmMs ?: 0 },
        "Backlight" to recent.sumOf { it.backlightMs ?: 0 },
        "Vibrator" to recent.sumOf { it.vibratorMs ?: 0 },
        "Speaker" to recent.sumOf { it.speakerMs ?: 0 },
    ).filter { it.second > 0 }.sortedByDescending { it.second }
    if (items.isEmpty()) return
    val max = items.first().second
    Text(
        "Active time · last 24 h",
        style = MaterialTheme.typography.bodySmall,
        color = AxisLabelColor,
    )
    items.forEach { (name, ms) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                name,
                modifier = Modifier.width(72.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(ChartOverlayColor),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((ms.toFloat() / max).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(ActivityFillColor),
                )
            }
            Text(
                "${formatDuration(ms)} · ${(ms * 100 / windowMs).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = AxisLabelColor,
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${s % 3600 / 60}m"
    }
}

private fun formatEpoch(seconds: Long): String {
    val dt = kotlinx.datetime.Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = dt.month.name.lowercase().take(3)
    val hh = dt.hour.toString().padStart(2, '0')
    val mm = dt.minute.toString().padStart(2, '0')
    return "${dt.dayOfMonth} $month $hh:$mm"
}

@Composable
private fun SignedOutBatteryContent(onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "You must be signed into your Pebble account to view your Battery usage.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.height(12.dp))
        PebbleElevatedButton(
            onClick = onSignIn,
            text = "Sign in",
            primaryColor = true,
        )
    }
}

@Composable
private fun BatteryLoadErrorContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Couldn't load your Battery usage. Check your connection and try again.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.height(12.dp))
        PebbleElevatedButton(
            onClick = onRetry,
            text = "Retry",
            primaryColor = true,
        )
    }
}

@Composable
private fun AnalyticsDisabledBatteryContent(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "You need 'Send watch analytics' enabled to view your Battery usage.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.height(12.dp))
        PebbleElevatedButton(
            onClick = onOpenSettings,
            text = "Open settings",
            primaryColor = true,
        )
    }
}
