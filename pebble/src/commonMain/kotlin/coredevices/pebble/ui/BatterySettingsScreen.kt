package coredevices.pebble.ui

import CommonApiConfig
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.roundToInt
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
        LocalBatteryContent(navBarNav)
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
private fun LocalBatteryContent(navBarNav: NavBarNav) {
    val libPebble = rememberLibPebble()
    val batteryStatDao = koinInject<BatteryStatDao>()
    val watches by libPebble.watches.collectAsState()
    val storedSerials by batteryStatDao.observeSerials().collectAsState(initial = emptyList())
    val batteryWatches = remember(watches, storedSerials) {
        val knownWatches = watches.filterIsInstance<KnownPebbleDevice>()
        (knownWatches.map { it.serial } + storedSerials).distinct().map { serial ->
            val watch = knownWatches.firstOrNull { it.serial == serial }
            BatteryWatch(
                serial = serial,
                label = watch?.let { "${it.displayName()} - $serial" } ?: serial,
                liveLevel = (watch as? ConnectedPebble.Battery)?.batteryLevel,
            )
        }
    }
    var selectedSerial by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(batteryWatches) {
        if (selectedSerial !in batteryWatches.map { it.serial }) {
            selectedSerial = batteryWatches.firstOrNull { it.liveLevel != null }?.serial
                ?: batteryWatches.firstOrNull()?.serial
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        if (batteryWatches.isEmpty()) {
            Text(
                "Connect a watch to see its battery usage.",
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            batteryWatches.forEach { watch ->
                val selected = watch.serial == selectedSerial
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .then(
                            if (selected) {
                                Modifier.background(DashboardTabColor)
                            } else {
                                Modifier.border(1.dp, Color.White, RoundedCornerShape(24.dp))
                            },
                        )
                        .clickable { selectedSerial = watch.serial }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        watch.label,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
        selectedSerial?.let { serial ->
            val stats by batteryStatDao.observe(serial).collectAsState(initial = emptyList())
            val watch = batteryWatches.firstOrNull { it.serial == serial }
            BatteryDashboard(
                stats = stats,
                liveLevel = watch?.liveLevel,
                onOpenHealthSettings = {
                    navBarNav.navigateTo(
                        PebbleNavBarRoutes.WatchSettingsCategoryRoute(
                            section = Section.Health.name,
                            topLevelType = TopLevelType.Watch.name,
                        ),
                    )
                },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
            )
        }
    }
}

private data class BatteryWatch(
    val serial: String,
    val label: String,
    val liveLevel: Int?,
)

private enum class HourlyTag { Drop, HeavyApp }

private data class BatteryPoint(
    val stat: BatteryStatEntity,
    val soc: Double,
)

internal data class BatteryContribution(
    val name: String,
    val durationMs: Long,
    val fraction: Double,
)

internal fun batteryContributions(stats: List<BatteryStatEntity>): List<BatteryContribution> {
    if (stats.size < 2) return emptyList()
    val windowMs = (stats.last().timestamp - stats.first().timestamp) * 1000
    if (windowMs <= 0) return emptyList()
    val components = listOf(
        "Vibrator" to stats.sumOf { it.vibratorMs ?: 0L },
        "Heart Rate Monitor" to stats.sumOf { it.hrmMs ?: 0L },
        "Bluetooth" to stats.sumOf { it.bleConnectedMs ?: 0L },
        "Backlight" to stats.sumOf { it.backlightMs ?: 0L },
        "Speaker" to stats.sumOf { it.speakerMs ?: 0L },
    ).filter { it.second > 0L }
    val systemMs = (windowMs - components.sumOf { it.second }).coerceAtLeast(0L)
    val durations = listOf("System" to systemMs) + components
    val total = durations.sumOf { it.second }.coerceAtLeast(1L)
    return durations.filter { it.second > 0L }.map { (name, duration) ->
        BatteryContribution(name, duration, duration.toDouble() / total)
    }
}

@Composable
private fun BatteryDashboard(
    stats: List<BatteryStatEntity>,
    liveLevel: Int?,
    onOpenHealthSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val periodStats = remember(stats) {
        val end = stats.lastOrNull()?.timestamp ?: 0L
        stats.filter { it.timestamp >= end - DASHBOARD_PERIOD.inWholeSeconds }
    }
    val latest = periodStats.lastOrNull()
    val level = liveLevel?.toDouble() ?: latest?.socPct
    val tteDays = latest?.tteSeconds?.takeIf { it > 0L }?.toDouble()?.div(86_400)
    val fullChargeDays = if (level != null && level > 0.0 && tteDays != null) {
        tteDays * 100 / level
    } else {
        null
    }
    var selectedTags by remember(stats) { mutableStateOf(emptySet<HourlyTag>()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DashboardBodyColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DashboardHeaderColor)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    level?.let { "${it.roundToInt()}%" } ?: "—",
                    color = Color.White,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 40.sp,
                )
                latest?.let {
                    Text(
                        formatWatchTimestamp(it.timestamp),
                        color = DashboardSecondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row {
                    Text(
                        "Watchface: ",
                        color = DashboardSecondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        latest?.watchfaceName ?: "Unknown",
                        color = DashboardSecondaryText,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Full charge → 0%: ${formatDays(fullChargeDays)}",
                    color = DashboardSecondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Empty in: ${formatDays(tteDays)}",
                    color = DashboardSecondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        BatteryHistoryChart(periodStats, selectedTags)

        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "Hourly details",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Each of these contributes to higher power consumption. Tap a tag to highlight " +
                    "matching hours on the chart. Tap again to clear.",
                color = DashboardSecondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardTag(
                    label = "% drop",
                    color = DropTagColor,
                    selected = HourlyTag.Drop in selectedTags,
                    onClick = { selectedTags = selectedTags.toggle(HourlyTag.Drop) },
                )
                DashboardTag(
                    label = "Heavy app",
                    color = HeavyAppTagColor,
                    selected = HourlyTag.HeavyApp in selectedTags,
                    onClick = { selectedTags = selectedTags.toggle(HourlyTag.HeavyApp) },
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Here’s what affected your battery life during this period",
                color = DashboardSecondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            BatteryContributionChart(batteryContributions(periodStats))
            BatteryAdvice(latest, onOpenHealthSettings)
        }
    }
}

@Composable
private fun BatteryHistoryChart(
    stats: List<BatteryStatEntity>,
    selectedTags: Set<HourlyTag>,
) {
    val points = remember(stats) {
        stats.mapNotNull { stat -> stat.socPct?.let { BatteryPoint(stat, it) } }
    }
    if (points.size < 2) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(DashboardChartColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Battery history appears after a few heartbeats.",
                modifier = Modifier.padding(24.dp),
                color = DashboardSecondaryText,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val t0 = points.first().stat.timestamp
    val t1 = points.last().stat.timestamp
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(DashboardChartColor),
    ) {
        for (fraction in listOf(0.2f, 0.55f, 0.87f)) {
            val x = size.width * fraction
            drawLine(
                DashboardGridColor,
                Offset(x, 0f),
                Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())),
            )
        }
        val span = maxOf(1L, t1 - t0)
        val pointsOnCanvas = points.map { point ->
            Offset(
                ((point.stat.timestamp - t0).toFloat() / span) * size.width,
                size.height * (1 - point.soc.toFloat().coerceIn(0f, 100f) / 100),
            )
        }
        drawPath(smoothFilledPath(pointsOnCanvas, size.height), BatteryAreaColor)
        drawPath(
            smoothLinePath(pointsOnCanvas),
            BatteryLineColor,
            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
        )
        points.forEachIndexed { index, point ->
            val previous = points.getOrNull(index - 1)
            val drop = (point.stat.socPctDrop ?: 0.0) > 0.0 ||
                (previous != null && previous.soc > point.soc)
            val heavyApp = (point.stat.appCpuPct ?: 0.0) >= HEAVY_APP_CPU_THRESHOLD
            val color = when {
                HourlyTag.Drop in selectedTags && drop -> DropTagColor
                HourlyTag.HeavyApp in selectedTags && heavyApp -> HeavyAppTagColor
                else -> null
            }
            color?.let { drawCircle(it, 4.dp.toPx(), pointsOnCanvas[index]) }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashboardChartColor)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(formatChartDate(t0), color = DashboardSecondaryText, style = MaterialTheme.typography.bodySmall)
        Text(
            formatChartDate(t0 + (t1 - t0) / 2),
            color = DashboardSecondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(formatChartDate(t1), color = DashboardSecondaryText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DashboardTag(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, DashboardTagBorder, RoundedCornerShape(7.dp))
            .background(if (selected) color.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BatteryContributionChart(contributions: List<BatteryContribution>) {
    if (contributions.isEmpty()) {
        Text(
            "Battery impact details appear after a few heartbeats.",
            color = DashboardSecondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Canvas(Modifier.size(120.dp)) {
            var start = -90f
            contributions.forEach { item ->
                val sweep = (item.fraction * 360).toFloat()
                drawArc(
                    contributionColor(item.name),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = true,
                )
                start += sweep
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            contributions.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(contributionColor(item.name)),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        item.name,
                        modifier = Modifier.weight(1f),
                        color = DashboardSecondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${(item.fraction * 100.0).format(1)}%",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryAdvice(
    latest: BatteryStatEntity?,
    onOpenHealthSettings: () -> Unit,
) {
    val advice = buildList {
        if (latest?.healthTrackingEnabled == true) {
            add(
                "Health tracking is on" to
                    "Health tracking keeps motion and heart-rate sensors active throughout the day.",
            )
        }
        if (latest?.healthHrmActivityTrackingEnabled == true) {
            add(
                "Heart rate during activities" to
                    "Continuous heart-rate tracking during auto-detected walks and runs increases battery use.",
            )
        }
        val interval = if (latest?.healthHrmEnabled == true) {
            when (latest.healthHrmMeasurementInterval) {
                0 -> "10 minutes"
                1 -> "30 minutes"
                2 -> "hour"
                else -> null
            }
        } else {
            null
        }
        interval?.let {
            add(
                "Heart rate monitor sampling every $it" to
                    "Background heart-rate samples every $it use more battery than longer intervals.",
            )
        }
    }
    if (advice.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    advice.forEach { (title, description) ->
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            description,
            color = DashboardSecondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
    }
    Text(
        "Change these settings to reduce power consumption",
        modifier = Modifier.clickable(onClick = onOpenHealthSettings),
        color = DashboardSecondaryText,
        fontStyle = FontStyle.Italic,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun contributionColor(name: String): Color = when (name) {
    "System" -> Color(0xFFFF9694)
    "Vibrator" -> Color(0xFF68B8AF)
    "Heart Rate Monitor" -> Color(0xFFE85155)
    "Bluetooth" -> Color(0xFF5081AF)
    "Backlight" -> Color(0xFFF2CA61)
    else -> Color(0xFFA883D8)
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

private fun formatWatchTimestamp(seconds: Long): String {
    val dt = kotlin.time.Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = dt.hour.toString().padStart(2, '0')
    val minute = dt.minute.toString().padStart(2, '0')
    return "${dt.month.ordinal + 1}/${dt.day} $hour:$minute"
}

private fun formatChartDate(seconds: Long): String {
    val zone = TimeZone.currentSystemDefault()
    val date = kotlin.time.Instant.fromEpochSeconds(seconds).toLocalDateTime(zone).date
    val now = Clock.System.now().toLocalDateTime(zone).date
    val yesterday = Clock.System.now().minus(24.hours).toLocalDateTime(zone).date
    return when (date) {
        now -> "Today"
        yesterday -> "Yesterday"
        else -> "${date.month.name.lowercase().take(3).replaceFirstChar { it.uppercase() }} ${date.day}"
    }
}

private fun formatDays(days: Double?): String = days?.let { "${it.format(1)} days" } ?: "—"

private val DASHBOARD_PERIOD = 72.hours
private const val HEAVY_APP_CPU_THRESHOLD = 10.0
private val DashboardTabColor = Color(0xFF008E7B)
private val DashboardHeaderColor = Color(0xFF008B78)
private val DashboardChartColor = Color(0xFF08685C)
private val DashboardBodyColor = Color(0xFF087466)
private val DashboardSecondaryText = Color.White.copy(alpha = 0.68f)
private val DashboardGridColor = Color.White.copy(alpha = 0.26f)
private val DashboardTagBorder = Color.White.copy(alpha = 0.26f)
private val BatteryLineColor = Color(0xFF91F572)
private val BatteryAreaColor = Color(0xFF6FA66D).copy(alpha = 0.45f)
private val DropTagColor = Color(0xFF39B9EB)
private val HeavyAppTagColor = Color(0xFF9A7BE7)

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
