package app.getplexo.mobile.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getplexo.mobile.core.DownloadSnapshot
import app.getplexo.mobile.core.DownloadStatus
import app.getplexo.mobile.core.Link
import app.getplexo.mobile.core.LinkProgress
import app.getplexo.mobile.core.LinkState
import java.util.Locale
import kotlin.math.roundToInt

private const val VERSION = "0.3.0"

@Composable
fun HomeScreen(
    vm: PlexoViewModel,
    initialUrl: String?,
    onInitialUrlUsed: () -> Unit,
) {
    val download by vm.download.collectAsStateWithLifecycle()
    val links = vm.links
    val states = links.map { it to it.state.collectAsStateWithLifecycle().value }
    val context = LocalContext.current

    // A link that landed on an interface another link already uses adds no bandwidth.
    // Mark it so the panel does not claim bonding that is not real.
    val duplicateIds = buildSet {
        val seen = HashSet<String>()
        states.forEach { (link, state) ->
            if (!state.enabled) return@forEach
            val key = state.networkKey ?: return@forEach
            if (!seen.add(key)) add(link.id)
        }
    }

    var url by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            url = initialUrl
            onInitialUrlUsed()
        }
    }

    val busy = download?.status?.let {
        it == DownloadStatus.PREPARING || it == DownloadStatus.RUNNING || it == DownloadStatus.SAVING
    } ?: false

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header()

        LinkPanel(
            rows = states,
            duplicates = duplicateIds,
            download = download,
            onToggle = { id, on ->
                vm.setLinkEnabled(id, on)
                // If the radio is known to be off, a switch alone cannot bring it up:
                // take the user straight to the panel that can. The inline button stays
                // available for cases the app cannot detect (mobile data, Bluetooth 12+).
                val radioOff = on && links.firstOrNull { it.id == id }?.state?.value?.radioOff == true
                if (radioOff) openLinkSettings(context, id)
            },
            onOpenLinkSettings = { id -> openLinkSettings(context, id) },
        )

        NewDownloadPanel(
            url = url,
            onUrlChange = {
                url = it
                error = null
            },
            busy = busy,
            error = error,
            onStart = {
                val result = vm.start(url)
                error = result
                if (result == null) url = ""
            },
        )

        download?.let { snapshot ->
            DownloadPanel(
                snapshot = snapshot,
                links = links,
                onCancel = vm::cancel,
                onDismiss = vm::dismiss,
            )
        }

        TuningPanel(vm)
        LimitsPanel()

        Spacer(Modifier.height(4.dp))
        Text(
            text = "Plexo $VERSION · open source, no servers, no VPN",
            style = MonoSmall,
            color = TextFaint,
        )
    }
}

/** The desktop app's mark: three network lines merging into one node and arrow. */
@Composable
private fun PlexoMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.55f
        val cy = h * 0.5f
        val stroke = w * 0.075f

        fun merge(color: Color, at: Float) {
            val path = Path().apply {
                moveTo(w * 0.05f, h * at)
                cubicTo(w * 0.32f, h * at, w * 0.34f, cy, cx, cy)
            }
            drawPath(path, color, style = StrokeStyle(width = stroke, cap = StrokeCap.Round))
        }

        merge(Mint, 0.27f)
        drawLine(
            color = Amber,
            start = Offset(w * 0.05f, cy),
            end = Offset(cx, cy),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        merge(Steel, 0.73f)

        drawCircle(
            color = TextPrimary,
            radius = w * 0.17f,
            center = Offset(cx, cy),
            style = StrokeStyle(width = stroke * 0.5f),
        )
        val arrow = Path().apply {
            moveTo(cx, cy - h * 0.14f)
            lineTo(w * 0.94f, cy)
            lineTo(cx, cy + h * 0.14f)
            close()
        }
        drawPath(arrow, TextPrimary)
    }
}

@Composable
private fun Header() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlexoMark(Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Plexo", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Text(
                    text = "One file, every link at once",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                )
            }
            Text(VERSION, style = MonoSmall, color = TextFaint)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Stroke),
        )
    }
}

@Composable
private fun LinkPanel(
    rows: List<Pair<Link, LinkState>>,
    duplicates: Set<String>,
    download: DownloadSnapshot?,
    onToggle: (String, Boolean) -> Unit,
    onOpenLinkSettings: (String) -> Unit,
) {
    val active = rows.count { (link, state) ->
        state.enabled && state.connected && link.id !in duplicates
    }
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "LINKS",
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (active > 1) "$active BONDED" else "$active ACTIVE",
                style = MaterialTheme.typography.labelSmall,
                color = if (active > 1) Mint else TextFaint,
            )
        }

        Spacer(Modifier.height(4.dp))

        rows.forEachIndexed { index, (link, state) ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(StrokeSubtle),
                )
            }
            val progress = download?.links?.firstOrNull { it.id == link.id }
            LinkRow(
                link = link,
                state = state,
                progress = progress,
                duplicate = link.id in duplicates,
                onToggle = { onToggle(link.id, it) },
                onOpenLinkSettings = { onOpenLinkSettings(link.id) },
            )
        }
    }
}

@Composable
private fun LinkRow(
    link: Link,
    state: LinkState,
    progress: LinkProgress?,
    duplicate: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenLinkSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(link.color).copy(alpha = if (state.connected) 1f else 0.32f)),
            )
            Spacer(Modifier.width(11.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = link.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    if (progress != null && progress.bytes > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatRate(progress.speedBps),
                            style = MonoSmall,
                            color = Color(link.color),
                        )
                    }
                }
                Text(
                    text = statusText(state, duplicate),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(10.dp))
            Switch(
                checked = state.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1B1408),
                    checkedTrackColor = Amber,
                    uncheckedThumbColor = TextFaint,
                    uncheckedTrackColor = PanelRaised,
                    uncheckedBorderColor = StrokeStrong,
                ),
            )
        }

        // Android has no API that lets an app turn a radio on. Offer the system panel
        // instead, which is the closest the platform allows.
        val action = settingsAction(link.id)
        if (action != null && state.enabled && !state.connected) {
            TextButton(
                onClick = onOpenLinkSettings,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(
                    text = action,
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
            }
        }
    }
}

@Composable
private fun NewDownloadPanel(
    url: String,
    onUrlChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onStart: () -> Unit,
) {
    PanelCard {
        Text(
            text = "NEW DOWNLOAD",
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Paste a direct link. Plexo splits the file into byte ranges and fetches them from every link that is on.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = {
                Text(
                    "https://example.com/file.iso",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextFaint,
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Amber,
                focusedBorderColor = Amber,
                unfocusedBorderColor = StrokeStrong,
                focusedContainerColor = PanelRaised,
                unfocusedContainerColor = PanelRaised,
                focusedPlaceholderColor = TextFaint,
                unfocusedPlaceholderColor = TextFaint,
            ),
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = Coral,
            )
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onStart,
            enabled = !busy && url.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Amber,
                contentColor = Color(0xFF1B1408),
                disabledContainerColor = PanelRaised,
                disabledContentColor = TextFaint,
            ),
        ) {
            Text(
                text = if (busy) "Download running" else "Start download",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun DownloadPanel(
    snapshot: DownloadSnapshot,
    links: List<Link>,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = !snapshot.status.finished
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "DOWNLOAD",
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint,
            )
            Spacer(Modifier.weight(1f))
            if (running) {
                Text(
                    text = "${(snapshot.fraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Amber,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = snapshot.name.ifBlank { "Preparing…" },
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = statusLine(snapshot),
            style = MonoSmall,
            color = TextFaint,
        )

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Text(
                    text = "SPEED",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = speedValue(snapshot.speedBps),
                        style = MaterialTheme.typography.displaySmall,
                        color = if (snapshot.speedBps > 0) Amber else TextMuted,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = speedUnit(snapshot.speedBps),
                        style = MonoSmall,
                        color = TextFaint,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "DONE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint,
                )
                Text(
                    text = formatBytes(snapshot.bytesDone),
                    style = MonoMedium,
                    color = TextPrimary,
                )
                if (snapshot.totalBytes > 0) {
                    Text(
                        text = "of ${formatBytes(snapshot.totalBytes)}",
                        style = MonoSmall,
                        color = TextFaint,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        snapshot.etaSeconds?.let { eta ->
            Text(
                text = "ETA ${formatEta(eta)}" +
                    if (snapshot.links.isNotEmpty()) " · ${snapshot.links.size} links" else "",
                style = MonoSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (snapshot.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { snapshot.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Amber,
                trackColor = Track,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Amber,
                trackColor = Track,
            )
        }

        if (snapshot.links.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SplitBar(snapshot = snapshot, links = links)
            Spacer(Modifier.height(10.dp))
            snapshot.links.forEach { progress ->
                val link = links.firstOrNull { it.id == progress.id }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(link?.color ?: 0xFFFFFFFF)),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = link?.title ?: progress.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${formatBytes(progress.bytes)}  ·  ${formatRate(progress.speedBps)}",
                        style = MonoSmall,
                        color = TextPrimary,
                    )
                }
            }
        }

        snapshot.savedTo?.let { location ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Saved to $location",
                style = MaterialTheme.typography.bodySmall,
                color = Mint,
            )
        }

        if (snapshot.message != null && snapshot.status != DownloadStatus.DONE) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = snapshot.message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (snapshot.status == DownloadStatus.ERROR) Coral else TextMuted,
            )
        }

        if (!snapshot.parallel && snapshot.status == DownloadStatus.RUNNING) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "This server does not allow partial downloads, so only one link can be used.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }

        Spacer(Modifier.height(12.dp))
        if (running) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, StrokeStrong),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
            ) {
                Text("Cancel", style = MaterialTheme.typography.titleSmall)
            }
        } else {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Dismiss", style = MaterialTheme.typography.titleSmall, color = TextMuted)
            }
        }
    }
}

@Composable
private fun SplitBar(snapshot: DownloadSnapshot, links: List<Link>) {
    val segments = snapshot.links.filter { it.bytes > 0 }
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Track),
    ) {
        segments.forEach { progress ->
            val color = links.firstOrNull { it.id == progress.id }?.color
            Box(
                Modifier
                    .weight(progress.bytes.toFloat())
                    .fillMaxHeight()
                    .background(Color(color ?: 0xFFFFFFFF)),
            )
        }
    }
}

@Composable
private fun TuningPanel(vm: PlexoViewModel) {
    var streams by rememberSaveable { mutableIntStateOf(vm.settings.streamsPerLink) }
    var chunk by rememberSaveable { mutableIntStateOf(vm.settings.chunkMb) }

    PanelCard {
        Text(
            text = "TUNING",
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint,
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Connections per link",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$streams",
                style = MaterialTheme.typography.labelMedium,
                color = Amber,
            )
        }
        Slider(
            value = streams.toFloat(),
            onValueChange = {
                streams = it.roundToInt()
                vm.settings.streamsPerLink = streams
            },
            valueRange = 1f..16f,
            steps = 14,
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Track,
            ),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Maximum chunk size",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$chunk MB",
                style = MaterialTheme.typography.labelMedium,
                color = Amber,
            )
        }
        Slider(
            value = chunk.toFloat(),
            onValueChange = {
                chunk = it.roundToInt()
                vm.settings.chunkMb = chunk
            },
            valueRange = 1f..32f,
            steps = 30,
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Track,
            ),
        )

        Text(
            text = "Plexo sizes each request from the link's own measured speed: fast radios get big ranges, slow ones get small ones, so a Bluetooth link cannot hold the tail of the file. The slider sets the upper limit.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun LimitsPanel() {
    PanelCard {
        Text(
            text = "WHAT ANDROID ALLOWS",
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint,
        )
        Spacer(Modifier.height(10.dp))
        Bullet("Android does not let an app switch Wi-Fi or mobile data on or off. The switches here pick which links Plexo may use; when a radio is off, Plexo shows a shortcut to the system panel that owns it.")
        Bullet("One Wi-Fi radio can only be joined to one network at a time. Two Wi-Fi networks at once is not possible on stock Android.")
        Bullet("What can run at once: Wi-Fi + mobile data + Bluetooth PAN + a wired/USB adapter, each radio carrying different chunks of the same file.")
        Bullet("Bluetooth PAN needs a paired device that shares its connection. Android does not let an app start that itself, so pair it and allow internet access in Bluetooth settings. Expect only about 1–2 Mbps.")
        Bullet("Android cannot consume USB tethering from a laptop — the phone is only ever the tethering host. What the wired link does use is a USB-C dock or a USB/OTG Ethernet adapter.")
        Bullet("Mobile data and Bluetooth are off by default here. Switch one on to add its bandwidth.")
        Bullet("If mobile data drops while Wi-Fi is on, enable Developer options › Mobile data always active.")
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(3.dp)
                .clip(CircleShape)
                .background(TextFaint),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .border(1.dp, Stroke, RoundedCornerShape(8.dp))
            .padding(16.dp),
        content = content,
    )
}

/** Label for the system shortcut on a link that is on but has no network yet. */
private fun settingsAction(id: String): String? = when (id) {
    "wifi" -> "Turn on Wi-Fi"
    "cellular" -> "Turn on mobile data"
    "bluetooth" -> "Open Bluetooth settings"
    else -> null
}

/**
 * Opens the system panel that owns a radio. Android 10+ has inline quick panels
 * (Wi-Fi, internet); older OEM builds only have the full settings screen, so each
 * candidate is tried in order until one opens.
 */
private fun openLinkSettings(context: Context, id: String) {
    val candidates = when (id) {
        "wifi" -> listOf(
            Intent(Settings.Panel.ACTION_WIFI),
            Intent(Settings.ACTION_WIFI_SETTINGS),
        )
        "cellular" -> listOf(
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
            Intent(Settings.ACTION_DATA_USAGE_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        "bluetooth" -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        else -> return
    }
    for (intent in candidates) {
        // From an Activity the panel should stay in the same task so Back returns here.
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

private fun statusText(state: LinkState, duplicate: Boolean = false): String = when {
    duplicate -> "Same network as another link · adds no speed"
    !state.enabled -> "Off"
    state.connected && state.validated -> if (state.metered) "Connected · metered" else "Connected"
    state.connected -> "Connected · no internet"
    state.note != null -> state.note
    else -> "Looking for a network…"
}

private fun statusLine(snapshot: DownloadSnapshot): String = when (snapshot.status) {
    DownloadStatus.PREPARING -> "Measuring the file and links"
    DownloadStatus.RUNNING -> {
        val streams = snapshot.links.sumOf { it.streams }
        if (snapshot.parallel) {
            "${snapshot.links.size} links · $streams streams"
        } else {
            "Single connection"
        }
    }

    DownloadStatus.SAVING -> "Writing to Downloads/Plexo"
    DownloadStatus.DONE -> "Complete"
    DownloadStatus.ERROR -> "Failed"
    DownloadStatus.CANCELLED -> "Cancelled"
}

/** "12.3 MB/s" -> "12.3" and "MB/s", so the number can be set in the display face. */
private fun speedValue(bytesPerSecond: Long): String =
    formatBytes(bytesPerSecond).substringBeforeLast(' ')

private fun speedUnit(bytesPerSecond: Long): String =
    formatBytes(bytesPerSecond).substringAfterLast(' ', "B") + "/s"

fun formatBytes(value: Long): String {
    if (value <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) {
        size /= 1024
        unit++
    }
    return if (unit == 0) {
        "${size.toInt()} B"
    } else {
        String.format(Locale.US, "%.1f %s", size, units[unit])
    }
}

fun formatRate(bytesPerSecond: Long): String = if (bytesPerSecond <= 0) "0 B/s" else "${formatBytes(bytesPerSecond)}/s"

fun formatEta(seconds: Long): String = when {
    seconds <= 0 -> "0s"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}
