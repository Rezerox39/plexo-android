@file:OptIn(ExperimentalMaterial3Api::class)

package app.getplexo.mobile.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.getplexo.mobile.core.DataPeriod
import app.getplexo.mobile.core.Link
import app.getplexo.mobile.core.LinkState
import app.getplexo.mobile.core.Schedule
import app.getplexo.mobile.core.SpeedUnit
import app.getplexo.mobile.core.ThemeMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private val NetworkColors = listOf(
    0xFF4EA89A,
    0xFFD8A44C,
    0xFF7E93BD,
    0xFFD98A9A,
    0xFF9C8FD6,
    0xFF9DBE6C,
    0xFF6DB8C9,
    0xFFEDA3A3,
)

@Composable
fun SettingsScreen(
    vm: PlexoViewModel,
    states: List<Pair<Link, LinkState>>,
    slowMode: Boolean,
    onSlowMode: (Boolean) -> Unit,
) {
    val settings = vm.settings
    val context = LocalContext.current

    var theme by remember { mutableStateOf(settings.theme) }
    var speedUnit by remember { mutableStateOf(settings.speedUnit) }
    var streams by remember { mutableIntStateOf(settings.streamsPerLink) }
    var chunk by remember { mutableIntStateOf(settings.chunkMb) }
    var atOnce by remember { mutableIntStateOf(settings.downloadsAtOnce) }
    var autoRetry by remember { mutableStateOf(settings.autoRetry) }
    var limit by remember { mutableIntStateOf(settings.speedLimitMbps) }
    var slowLimit by remember { mutableIntStateOf(settings.slowModeMbps) }
    var schedule by remember { mutableStateOf(settings.schedule) }
    var editing by remember { mutableStateOf<Link?>(null) }

    val freeBytes = remember {
        runCatching {
            StatFs(Environment.getDataDirectory().path).availableBytes
        }.getOrDefault(0L)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PanelCard {
            SectionLabel("Appearance")
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    ChoiceChip(mode.label, theme == mode) {
                        theme = mode
                        settings.theme = mode
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed units", style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                ChoiceChip("MB/s", speedUnit == SpeedUnit.MEGABYTES) {
                    speedUnit = SpeedUnit.MEGABYTES
                    settings.speedUnit = speedUnit
                }
                Spacer(Modifier.width(6.dp))
                ChoiceChip("Mbps", speedUnit == SpeedUnit.MEGABITS) {
                    speedUnit = SpeedUnit.MEGABITS
                    settings.speedUnit = speedUnit
                }
            }
            Text(
                "System follows the phone's light/dark setting. Pure black is made for OLED screens.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        PanelCard {
            SectionLabel("Download engine", trailing = "${formatBytes(freeBytes)} FREE")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Downloads at once", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("Desktop default is 2; each file uses every selected link.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
                listOf(1, 2, 3, 4).forEach {
                    ChoiceChip(it.toString(), atOnce == it) {
                        atOnce = it
                        settings.downloadsAtOnce = it
                    }
                    Spacer(Modifier.width(5.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            PlexoSlider(
                title = "Connections per link",
                value = "$streams",
                valueFloat = streams.toFloat(),
                range = 1f..16f,
                steps = 14,
                onChange = {
                    streams = it.roundToInt()
                    settings.streamsPerLink = streams
                },
            )
            Text(
                "Each stream is its own TCP connection. 8 is a good default; 16+ helps very high-latency links but adds overhead.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
            )
            Spacer(Modifier.height(12.dp))
            PlexoSlider(
                title = "Maximum range size",
                value = "$chunk MB",
                valueFloat = chunk.toFloat(),
                range = 16f..256f,
                steps = 14,
                onChange = {
                    chunk = it.roundToInt()
                    settings.chunkMb = chunk
                },
            )
            Text(
                "Each stream downloads one equal range of the file. Large ranges mean fewer requests and less throttling; lower this only if a flaky link keeps restarting a big range.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep waiting for a link", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("Hold the job for 30 s through a handover instead of failing it.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
                PlexoSwitch(autoRetry) {
                    autoRetry = it
                    settings.autoRetry = it
                }
            }
        }

        PanelCard {
            SectionLabel("Speed", trailing = if (slowMode) "SLOW MODE" else null, trailingColor = Amber)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Slow mode", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("One switch for a quiet background download.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
                PlexoSwitch(slowMode, onSlowMode)
            }
            if (slowMode) {
                Spacer(Modifier.height(8.dp))
                PlexoSlider(
                    title = "Slow mode cap",
                    value = formatLimit(slowLimit, speedUnit),
                    valueFloat = slowLimit.toFloat(),
                    range = 1f..20f,
                    steps = 18,
                    accent = Mint,
                    onChange = {
                        slowLimit = it.roundToInt()
                        settings.slowModeMbps = slowLimit
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            PlexoSlider(
                title = "Total speed limit",
                value = if (limit == 0) "Unlimited" else formatLimit(limit, speedUnit),
                valueFloat = limit.toFloat(),
                range = 0f..200f,
                steps = 199,
                onChange = {
                    limit = it.roundToInt()
                    settings.speedLimitMbps = limit
                },
            )
            Text(
                "The limit is shared by every stream and every file, so 10 MB/s means 10 MB/s in total.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
            )
        }

        PanelCard {
            SectionLabel("Schedule", trailing = if (schedule.enabled) "ON" else "OFF", trailingColor = if (schedule.enabled) Mint else TextFaint)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Only download in a window", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text(
                        if (schedule.enabled && !schedule.isOpen()) "The window is closed; new downloads wait." else "Queued files start when the window opens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (schedule.enabled && !schedule.isOpen()) Amber else TextFaint,
                    )
                }
                PlexoSwitch(schedule.enabled) {
                    schedule = schedule.copy(enabled = it)
                    settings.saveSchedule(schedule)
                }
            }
            if (schedule.enabled) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("From", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Spacer(Modifier.width(8.dp))
                    TimeChip(formatMinute(schedule.startMinute)) {
                        TimePickerDialog(
                            context,
                            { _, h, m ->
                                schedule = schedule.copy(startMinute = h * 60 + m)
                                settings.saveSchedule(schedule)
                            },
                            schedule.startMinute / 60,
                            schedule.startMinute % 60,
                            true,
                        ).show()
                    }
                    Spacer(Modifier.weight(1f))
                    Text("To", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Spacer(Modifier.width(8.dp))
                    TimeChip(formatMinute(schedule.endMinute)) {
                        TimePickerDialog(
                            context,
                            { _, h, m ->
                                schedule = schedule.copy(endMinute = h * 60 + m)
                                settings.saveSchedule(schedule)
                            },
                            schedule.endMinute / 60,
                            schedule.endMinute % 60,
                            true,
                        ).show()
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(7) { index ->
                        val selected = schedule.days and (1 shl index) != 0
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) Amber.copy(alpha = 0.16f) else Color.Transparent)
                                .border(1.dp, if (selected) Amber else StrokeStrong, RoundedCornerShape(6.dp))
                                .clickable {
                                    val days = schedule.days xor (1 shl index)
                                    schedule = schedule.copy(days = days and Schedule.EVERY_DAY)
                                    settings.saveSchedule(schedule)
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                weekdayShort(index),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) Amber else TextFaint,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (schedule.endEpochDay > 0) {
                            "Ends ${LocalDate.ofEpochDay(schedule.endEpochDay)}"
                        } else {
                            "No end date"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    SmallAction("Set end date", {
                        val initial = if (schedule.endEpochDay > 0) {
                            LocalDate.ofEpochDay(schedule.endEpochDay)
                        } else {
                            LocalDate.now()
                        }
                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                schedule = schedule.copy(endEpochDay = LocalDate.of(y, m + 1, d).toEpochDay())
                                settings.saveSchedule(schedule)
                            },
                            initial.year,
                            initial.monthValue - 1,
                            initial.dayOfMonth,
                        ).show()
                    }, Amber)
                    if (schedule.endEpochDay > 0) {
                        SmallAction("Clear", {
                            schedule = schedule.copy(endEpochDay = 0)
                            settings.saveSchedule(schedule)
                        }, TextMuted)
                    }
                }
            }
        }

        PanelCard {
            SectionLabel("Networks", trailing = "${states.count { it.second.connected }} CONNECTED")
            Spacer(Modifier.height(8.dp))
            states.forEachIndexed { index, (link, state) ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(StrokeSubtle))
                }
                NetworkSettingsRow(
                    link = link,
                    state = state,
                    settings = settings,
                    onToggle = { vm.setLinkEnabled(link.id, it) },
                    onEdit = { editing = link },
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Android does not let an app switch a radio on. The switch here selects a link for Plexo; the system panel owns the radio itself.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
            )
        }

        PanelCard {
            SectionLabel("About")
            Spacer(Modifier.height(8.dp))
            Text("Plexo for Android 0.4.2", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                "A direct port of the desktop Plexo download engine. No account, no server, no VPN: the app asks Android for each radio separately and binds its own sockets to them.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Bullet("HTTP/HTTPS downloads with byte ranges, resume-safe retries and per-link streams.")
            Bullet("Torrent and NZB transfers are not implemented yet. The desktop app can; this port does not pretend to.")
            Bullet("One Wi-Fi radio joins one network at a time. Two Wi-Fi networks at once is not possible on stock Android.")
            Bullet("What can bond: Wi-Fi + mobile data + Bluetooth PAN + USB/OTG Ethernet, each carrying different ranges.")
            Bullet("Android restricts turning radios on/off; Plexo offers a shortcut to the panel that can.", Coral)
            Spacer(Modifier.height(10.dp))
            SmallAction("Desktop project on GitHub", {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/anmolkapil/plexo")),
                    )
                }
            }, Amber)
            Spacer(Modifier.height(30.dp))
        }
    }

    editing?.let { link ->
        NetworkEditDialog(
            link = link,
            state = states.firstOrNull { it.first.id == link.id }?.second,
            settings = settings,
            onDismiss = { editing = null },
            onRename = { vm.renameLink(link.id, it) },
            onColor = { vm.colorLink(link.id, it) },
        )
    }
}

@Composable
private fun NetworkSettingsRow(
    link: Link,
    state: LinkState,
    settings: app.getplexo.mobile.core.Settings,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val speedCap = settings.linkSpeedLimitMbps(link.id)
    val chosenStreams = settings.linkStreams(link.id, settings.streamsPerLink)
    val period = settings.linkDataPeriod(link.id)
    val used = settings.linkDataUsed(link.id, period)
    val cap = settings.linkDataLimitMb(link.id)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Color(state.color).copy(alpha = if (state.connected) 1f else 0.32f)),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.displayName.ifBlank { link.title },
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Text(
                    when {
                        !state.enabled -> "Off"
                        state.connected && state.validated -> if (state.metered) "Connected · metered" else "Connected"
                        state.connected -> "Connected · no internet"
                        else -> state.note ?: "Waiting for a network…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                    maxLines = 2,
                )
            }
            PlexoSwitch(state.enabled, onToggle)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append(if (speedCap > 0) formatLimit(speedCap, settings.speedUnit) else "No link cap")
                    append(" · ")
                    append("$chosenStreams streams")
                    append(" · ")
                    append(formatBytes(used))
                    if (cap > 0) append(" / ${cap} MB")
                    append(" ${period.label.lowercase()}")
                },
                style = MonoSmall,
                color = TextFaint,
                modifier = Modifier.weight(1f),
            )
            SmallAction("Edit", onEdit, Amber)
        }
        if (cap > 0 && used >= cap.toLong() * 1024L * 1024L) {
            Text(
                "Plexo's data cap for this link is reached; it will not start new ranges until the ${period.label.lowercase()} resets.",
                style = MaterialTheme.typography.bodySmall,
                color = Coral,
            )
        }
    }
}

@Composable
private fun NetworkEditDialog(
    link: Link,
    state: LinkState?,
    settings: app.getplexo.mobile.core.Settings,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onColor: (Long) -> Unit,
) {
    var name by remember(link.id) { mutableStateOf(state?.displayName ?: link.title) }
    var color by remember(link.id) { mutableStateOf(state?.color ?: 0xFF4EA89A) }
    var streams by remember(link.id) { mutableIntStateOf(settings.linkStreamsOverride(link.id)) }
    var speed by remember(link.id) { mutableStateOf(settings.linkSpeedLimitMbps(link.id).toString()) }
    var dataCap by remember(link.id) { mutableStateOf(settings.linkDataLimitMb(link.id).toString()) }
    var period by remember(link.id) { mutableStateOf(settings.linkDataPeriod(link.id)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted,
        title = { Text("Edit ${link.title.lowercase()}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = plexoFieldColors(),
                    shape = RoundedCornerShape(8.dp),
                )
                Text("Colour", style = MaterialTheme.typography.labelSmall, color = TextFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    NetworkColors.forEach { swatch ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(swatch))
                                .border(
                                    if (swatch == color) 2.dp else 0.dp,
                                    if (swatch == color) TextPrimary else Color.Transparent,
                                    CircleShape,
                                )
                                .clickable {
                                    color = swatch
                                    onColor(swatch)
                                },
                        )
                    }
                }
                Text("Streams", style = MaterialTheme.typography.labelSmall, color = TextFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 4, 8, 16, 32).forEach { option ->
                        ChoiceChip(if (option == 0) "Auto" else option.toString(), streams == option) {
                            streams = option
                            settings.setLinkStreams(link.id, option)
                        }
                    }
                }
                OutlinedTextField(
                    value = speed,
                    onValueChange = { speed = it.filter(Char::isDigit).take(4) },
                    label = { Text("Link speed cap (MB/s, 0 = total limit)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = plexoFieldColors(),
                    shape = RoundedCornerShape(8.dp),
                )
                OutlinedTextField(
                    value = dataCap,
                    onValueChange = { dataCap = it.filter(Char::isDigit).take(7) },
                    label = { Text("Data cap in Plexo (MB, 0 = none)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = plexoFieldColors(),
                    shape = RoundedCornerShape(8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DataPeriod.entries.forEach { p ->
                        ChoiceChip(p.label, period == p) { period = p }
                    }
                }
                Text(
                    "Plexo counts only the bytes it carries, so this is an app cap, not a system data counter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onRename(name)
                settings.setLinkSpeedLimitMbps(link.id, speed.toIntOrNull() ?: 0)
                settings.setLinkDataLimitMb(link.id, dataCap.toIntOrNull() ?: 0)
                settings.setLinkDataPeriod(link.id, period)
                onDismiss()
            }) { Text("Save", color = Amber) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
        },
    )
}

@Composable
private fun PlexoSlider(
    title: String,
    value: String,
    valueFloat: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    accent: Color = Amber,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelMedium, color = accent)
        }
        Slider(
            value = valueFloat,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Track,
            ),
        )
    }
}

@Composable
private fun TimeChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, StrokeStrong, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(label, style = MonoSmall, color = Amber)
    }
}
