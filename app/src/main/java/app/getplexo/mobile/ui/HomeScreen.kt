@file:OptIn(ExperimentalMaterial3Api::class)

package app.getplexo.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getplexo.mobile.core.DownloadEntry
import app.getplexo.mobile.core.DownloadSnapshot
import app.getplexo.mobile.core.DownloadStatus
import app.getplexo.mobile.core.Link
import app.getplexo.mobile.core.LinkState
import app.getplexo.mobile.core.SpeedUnit
import java.util.Locale

private const val VERSION = "0.4.2"

private enum class DownloadFilter(val label: String) {
    ALL("All"),
    ACTIVE("Downloading"),
    QUEUED("Queued"),
    ATTENTION("Needs attention"),
    FINISHED("Finished"),
}

@Composable
fun HomeScreen(
    vm: PlexoViewModel,
    initialUrl: String?,
    onInitialUrlUsed: () -> Unit,
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val links = vm.links
    val states = links.map { link -> link to link.state.collectAsStateWithLifecycle().value }
    val settings = vm.settings

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showNew by remember { mutableStateOf(false) }
    var seedUrl by remember { mutableStateOf<String?>(null) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var slowMode by remember { mutableStateOf(settings.slowMode) }

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            seedUrl = initialUrl
            showNew = true
            onInitialUrlUsed()
        }
    }

    val active = entries.filter { it.isActive }
    val queued = entries.filter { it.isQueued }
    val speed = active.sumOf { it.snapshot.speedBps }
    val detail = entries.firstOrNull { it.id == detailId }

    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PlexoTopBar(
                activeCount = active.size + queued.size,
                speed = speed,
                speedUnit = settings.speedUnit,
                slowMode = slowMode,
                onSlowMode = {
                    slowMode = !slowMode
                    settings.slowMode = slowMode
                },
                onSettings = { tab = 1 },
            )
        },
        bottomBar = {
            PlexoNav(tab = tab, onTab = { tab = it }, active = active.size)
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                0 -> DownloadsScreen(
                    entries = entries,
                    links = links,
                    states = states,
                    speedUnit = settings.speedUnit,
                    speed = speed,
                    onNew = {
                        seedUrl = null
                        showNew = true
                    },
                    onOpen = { detailId = it.id },
                    onCancel = vm::cancel,
                    onRetry = vm::retry,
                    onAgain = vm::again,
                    onRemove = vm::remove,
                    onClearFinished = vm::clearFinished,
                    slowMode = slowMode,
                )

                else -> SettingsScreen(
                    vm = vm,
                    states = states,
                    slowMode = slowMode,
                    onSlowMode = {
                        slowMode = it
                        settings.slowMode = it
                    },
                )
            }
        }
    }

    if (showNew) {
        NewDownloadSheet(
            initialUrl = seedUrl,
            states = states,
            speedUnit = settings.speedUnit,
            scheduleOpen = settings.schedule.isOpen(),
            onDismiss = {
                showNew = false
                seedUrl = null
            },
            onStart = { url, name, networks, streams ->
                val error = vm.start(url, name, networks, streams)
                if (error == null) {
                    showNew = false
                    seedUrl = null
                }
                error
            },
        )
    }

    if (detail != null) {
        DownloadDetailSheet(
            entry = detail,
            links = links,
            speedUnit = settings.speedUnit,
            onDismiss = { detailId = null },
            onCancel = { vm.cancel(detail.id) },
            onRetry = { vm.retry(detail.id) },
            onAgain = { vm.again(detail.id) },
            onRemove = {
                vm.remove(detail.id)
                detailId = null
            },
        )
    }
}

@Composable
private fun PlexoTopBar(
    activeCount: Int,
    speed: Long,
    speedUnit: SpeedUnit,
    slowMode: Boolean,
    onSlowMode: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlexoMark(Modifier.size(32.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Plexo", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Text(
                    text = "One file, every link at once",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                    maxLines = 1,
                )
            }
            if (activeCount > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatRate(speed, speedUnit),
                        style = MonoMedium,
                        color = Amber,
                    )
                    Text(
                        text = if (activeCount == 1) "1 download" else "$activeCount downloads",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextFaint,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (slowMode) Amber.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(onClick = onSlowMode)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "SLOW",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (slowMode) Amber else TextFaint,
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onSettings)
                    .padding(horizontal = 7.dp, vertical = 6.dp),
            ) {
                Text("SET", style = MaterialTheme.typography.labelSmall, color = TextFaint)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Stroke),
        )
    }
}

@Composable
private fun PlexoNav(tab: Int, onTab: (Int) -> Unit, active: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Stroke))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            NavItem("DOWNLOADS", active, tab == 0, Modifier.weight(1f)) { onTab(0) }
            NavItem("SETTINGS", 0, tab == 1, Modifier.weight(1f)) { onTab(1) }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    badge: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) Amber else TextFaint,
            )
            if (badge > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(if (selected) Amber else StrokeStrong)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) OnAmber else TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen(
    entries: List<DownloadEntry>,
    links: List<Link>,
    states: List<Pair<Link, LinkState>>,
    speedUnit: SpeedUnit,
    speed: Long,
    onNew: () -> Unit,
    onOpen: (DownloadEntry) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onAgain: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
    slowMode: Boolean,
) {
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    val active = entries.filter { it.isActive }
    val queued = entries.filter { it.isQueued }
    val attention = entries.filter { it.snapshot.status == DownloadStatus.ERROR || it.snapshot.status == DownloadStatus.CANCELLED }
    val finished = entries.filter { it.snapshot.status == DownloadStatus.DONE }

    val filtered = when (filter) {
        DownloadFilter.ALL -> entries
        DownloadFilter.ACTIVE -> active
        DownloadFilter.QUEUED -> queued
        DownloadFilter.ATTENTION -> attention
        DownloadFilter.FINISHED -> finished
    }.sortedWith(
        compareBy(
            { it.snapshot.status.ordinal },
            { -it.createdAt },
        ),
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ThroughputHeader(
                entries = active,
                states = states,
                speed = speed,
                speedUnit = speedUnit,
                slowMode = slowMode,
                queued = queued.size,
            )
        }

        item {
            Button(
                onClick = onNew,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = OnAmber,
                ),
            ) {
                Text("New download", style = MaterialTheme.typography.titleSmall)
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                FilterChip("All", entries.size, filter == DownloadFilter.ALL) { filter = DownloadFilter.ALL }
                FilterChip("Downloading", active.size, filter == DownloadFilter.ACTIVE) { filter = DownloadFilter.ACTIVE }
                FilterChip("Queued", queued.size, filter == DownloadFilter.QUEUED) { filter = DownloadFilter.QUEUED }
                FilterChip("Needs attention", attention.size, filter == DownloadFilter.ATTENTION) { filter = DownloadFilter.ATTENTION }
                FilterChip("Finished", finished.size, filter == DownloadFilter.FINISHED) { filter = DownloadFilter.FINISHED }
            }
        }

        if (filtered.isEmpty()) {
            item {
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlexoMark(Modifier.size(38.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = when (filter) {
                                    DownloadFilter.ALL -> "No downloads yet"
                                    DownloadFilter.ACTIVE -> "Nothing downloading"
                                    DownloadFilter.QUEUED -> "The queue is empty"
                                    DownloadFilter.ATTENTION -> "Nothing needs attention"
                                    DownloadFilter.FINISHED -> "No finished downloads"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                            )
                            Text(
                                text = if (filter == DownloadFilter.ALL || filter == DownloadFilter.ACTIVE) {
                                    "Paste a link, pick the networks, and Plexo splits the file across them."
                                } else {
                                    "Switch back to All to see the rest of the list."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                    }
                }
            }
        }

        items(filtered, key = { it.id }) { entry ->
            DownloadCard(
                entry = entry,
                links = links,
                speedUnit = speedUnit,
                onOpen = { onOpen(entry) },
                onCancel = { onCancel(entry.id) },
                onRetry = { onRetry(entry.id) },
                onAgain = { onAgain(entry.id) },
                onRemove = { onRemove(entry.id) },
            )
        }

        if (finished.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "History is kept on this phone; files live in Downloads/Plexo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextFaint,
                        modifier = Modifier.weight(1f),
                    )
                    SmallAction("Clear finished", onClearFinished, TextMuted)
                }
            }
        }

        item {
            Text(
                text = "Plexo $VERSION · open source, no servers, no VPN",
                style = MonoSmall,
                color = TextFaint,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    ChoiceChip(label = if (count > 0) "$label $count" else label, selected = selected, onClick = onClick)
}

@Composable
private fun ThroughputHeader(
    entries: List<DownloadEntry>,
    states: List<Pair<Link, LinkState>>,
    speed: Long,
    speedUnit: SpeedUnit,
    slowMode: Boolean,
    queued: Int,
) {
    val connected = states.count { (link, state) -> state.enabled && state.connected && link.id !in duplicateIds(states) }
    val history = aggregateHistory(entries)
    PanelCard {
        SectionLabel(
            text = "Throughput",
            trailing = when {
                slowMode -> "SLOW MODE"
                connected > 1 -> "$connected BONDED"
                connected == 1 -> "1 LINK"
                else -> "NO LINK"
            },
            trailingColor = when {
                slowMode -> Amber
                connected > 1 -> Mint
                else -> TextFaint
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = speedNumber(speed, speedUnit),
                style = MaterialTheme.typography.displaySmall,
                color = if (speed > 0) Amber else TextMuted,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = speedSuffix(speed, speedUnit),
                style = MonoSmall,
                color = TextFaint,
                modifier = Modifier.padding(bottom = 5.dp),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (entries.isEmpty()) "IDLE" else "${entries.size} ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint,
                )
                if (queued > 0) {
                    Text(
                        text = "$queued QUEUED",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        ThroughputChart(history = history, height = 64)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            states.filter { it.second.enabled }.forEach { (link, state) ->
                val progress = entries.mapNotNull { entry -> entry.snapshot.links.firstOrNull { it.id == link.id } }
                val linkSpeed = progress.sumOf { it.speedBps }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinkDot(state.color, state.connected, 7)
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = state.displayName.ifBlank { link.title },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.connected) TextPrimary else TextFaint,
                        )
                        Text(
                            text = if (state.connected) formatRate(linkSpeed, speedUnit) else "offline",
                            style = MonoSmall,
                            color = if (state.connected && linkSpeed > 0) Color(state.color) else TextFaint,
                        )
                    }
                }
            }
        }
        if (entries.isEmpty() && states.none { it.second.enabled }) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Switch on a link in Settings, then add a download.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }
    }
}

private fun duplicateIds(states: List<Pair<Link, LinkState>>): Set<String> {
    val seen = HashSet<String>()
    val dupes = HashSet<String>()
    states.forEach { (link, state) ->
        val key = state.networkKey ?: return@forEach
        if (!state.enabled) return@forEach
        if (!seen.add(key)) dupes.add(link.id)
    }
    return dupes
}

private fun aggregateHistory(entries: List<DownloadEntry>): List<Long> {
    if (entries.isEmpty()) return emptyList()
    val size = entries.maxOf { it.history.size }.coerceAtMost(60)
    return (0 until size).map { index ->
        entries.sumOf { entry ->
            val offset = entry.history.size - size
            entry.history.getOrNull(index + offset) ?: 0L
        }
    }
}

@Composable
private fun DownloadCard(
    entry: DownloadEntry,
    links: List<Link>,
    speedUnit: SpeedUnit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onAgain: () -> Unit,
    onRemove: () -> Unit,
) {
    val snapshot = entry.snapshot
    val statusColor = statusColor(snapshot.status)
    val context = LocalContext.current
    PanelCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = snapshot.name.ifBlank { "Preparing…" },
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLine(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snapshot.status.active && snapshot.totalBytes > 0) {
                Text(
                    text = "${(snapshot.fraction * 100).toInt()}%",
                    style = MonoMedium,
                    color = Amber,
                )
            } else if (snapshot.status == DownloadStatus.DONE) {
                Text("DONE", style = MaterialTheme.typography.labelSmall, color = Mint)
            } else if (snapshot.status == DownloadStatus.ERROR) {
                Text("FAILED", style = MaterialTheme.typography.labelSmall, color = Coral)
            }
        }

        if (snapshot.status.active || snapshot.status == DownloadStatus.QUEUED) {
            Spacer(Modifier.height(10.dp))
            if (snapshot.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { snapshot.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = statusColor,
                    trackColor = Track,
                )
            } else if (snapshot.status.active) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = statusColor,
                    trackColor = Track,
                )
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatRate(snapshot.speedBps, speedUnit),
                    style = MonoMedium,
                    color = if (snapshot.speedBps > 0) Amber else TextFaint,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = buildString {
                        append(formatBytes(snapshot.bytesDone))
                        if (snapshot.totalBytes > 0) append(" / ").append(formatBytes(snapshot.totalBytes))
                    },
                    style = MonoSmall,
                    color = TextMuted,
                )
                Spacer(Modifier.weight(1f))
                snapshot.etaSeconds?.let {
                    Text("ETA ${formatEta(it)}", style = MonoSmall, color = TextFaint)
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append(formatBytes(snapshot.bytesDone))
                    append(" · ")
                    append(formatDate(entry.createdAt))
                },
                style = MonoSmall,
                color = TextFaint,
            )
            snapshot.savedTo?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Mint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (snapshot.links.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            SplitBarColors(snapshot = snapshot, links = links)
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                snapshot.links.forEach { progress ->
                    val link = links.firstOrNull { it.id == progress.id }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinkDot(link?.state?.value?.color ?: 0xFFFFFFFF, progress.bytes > 0, 7)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "${link?.state?.value?.displayName ?: link?.title ?: progress.id} ${formatRate(progress.speedBps, speedUnit)}",
                            style = MonoSmall,
                            color = TextMuted,
                        )
                    }
                }
            }
        }

        snapshot.message?.takeIf { it.isNotBlank() && snapshot.status != DownloadStatus.DONE }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (snapshot.status == DownloadStatus.ERROR) Coral else TextFaint)
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                snapshot.status.active || snapshot.status == DownloadStatus.QUEUED ->
                    SmallAction("Cancel", onCancel, Coral)

                snapshot.status == DownloadStatus.ERROR || snapshot.status == DownloadStatus.CANCELLED -> {
                    SmallAction("Retry", onRetry, Amber)
                    SmallAction("Remove", onRemove, TextMuted)
                }

                else -> {
                    SmallAction("Open", { openSaved(context, entry) }, Mint)
                    SmallAction("Again", onAgain, TextMuted)
                    SmallAction("Remove", onRemove, TextMuted)
                }
            }
            Spacer(Modifier.weight(1f))
            SmallAction("Copy link", { copyLink(context, entry) }, TextMuted)
            SmallAction("Details", onOpen, TextMuted)
        }
    }
}

@Composable
private fun SplitBarColors(snapshot: DownloadSnapshot, links: List<Link>) {
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
            val link = links.firstOrNull { it.id == progress.id }
            Box(
                Modifier
                    .weight(progress.bytes.toFloat())
                    .height(6.dp)
                    .background(Color(link?.state?.value?.color ?: 0xFFFFFFFF)),
            )
        }
    }
}

@Composable
private fun DownloadDetailSheet(
    entry: DownloadEntry,
    links: List<Link>,
    speedUnit: SpeedUnit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onAgain: () -> Unit,
    onRemove: () -> Unit,
) {
    val snapshot = entry.snapshot
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        contentColor = TextPrimary,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(snapshot.name.ifBlank { "Download" }, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Text(statusLine(entry), style = MaterialTheme.typography.bodySmall, color = statusColor(snapshot.status))
                }
                if (snapshot.status.active && snapshot.totalBytes > 0) {
                    Text("${(snapshot.fraction * 100).toInt()}%", style = MaterialTheme.typography.displaySmall, color = Amber)
                }
            }

            PanelCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column {
                        SectionLabel("Speed")
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = speedNumber(snapshot.speedBps, speedUnit),
                                style = MaterialTheme.typography.displaySmall,
                                color = if (snapshot.speedBps > 0) Amber else TextMuted,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = speedSuffix(snapshot.speedBps, speedUnit),
                                style = MonoSmall,
                                color = TextFaint,
                                modifier = Modifier.padding(bottom = 5.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        SectionLabel("Done")
                        Text(formatBytes(snapshot.bytesDone), style = MonoMedium, color = TextPrimary)
                        if (snapshot.totalBytes > 0) {
                            Text("of ${formatBytes(snapshot.totalBytes)}", style = MonoSmall, color = TextFaint)
                        }
                    }
                }
                if (snapshot.totalBytes > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { snapshot.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = statusColor(snapshot.status),
                        trackColor = Track,
                    )
                }
                snapshot.etaSeconds?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "ETA ${formatEta(it)} · ${snapshot.links.size} links · ${snapshot.links.sumOf { p -> p.streams }} streams",
                        style = MonoSmall,
                        color = TextMuted,
                    )
                }
            }

            PanelCard {
                SectionLabel("Throughput", trailing = "60 SECONDS")
                Spacer(Modifier.height(8.dp))
                ThroughputChart(history = entry.history, height = 90)
            }

            if (snapshot.links.isNotEmpty()) {
                PanelCard {
                    SectionLabel("Per link")
                    Spacer(Modifier.height(8.dp))
                    snapshot.links.forEach { progress ->
                        val link = links.firstOrNull { it.id == progress.id }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LinkDot(link?.state?.value?.color ?: 0xFFFFFFFF, progress.bytes > 0, 8)
                            Spacer(Modifier.width(9.dp))
                            Text(
                                link?.state?.value?.displayName ?: link?.title ?: progress.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${formatBytes(progress.bytes)} · ${formatRate(progress.speedBps, speedUnit)}",
                                style = MonoSmall,
                                color = TextMuted,
                            )
                        }
                    }
                }
            }

            PanelCard {
                SectionLabel("Source")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = snapshot.url,
                    style = MonoSmall,
                    color = TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                snapshot.savedTo?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Mint)
                }
                snapshot.message?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (snapshot.status == DownloadStatus.ERROR) Coral else TextMuted)
                    if (snapshot.status == DownloadStatus.ERROR) {
                        Spacer(Modifier.height(4.dp))
                        SmallAction("Copy error", { copyError(context, entry) }, TextMuted)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    snapshot.status.active || snapshot.status == DownloadStatus.QUEUED ->
                        SmallAction("Cancel download", onCancel, Coral)

                    snapshot.status == DownloadStatus.DONE ->
                        SmallAction("Open file", { openSaved(context, entry) }, Mint)

                    snapshot.status == DownloadStatus.ERROR || snapshot.status == DownloadStatus.CANCELLED ->
                        SmallAction("Retry", onRetry, Amber)

                    else -> Unit
                }
                if (snapshot.status == DownloadStatus.DONE) {
                    SmallAction("Again", onAgain, TextMuted)
                }
                if (!snapshot.status.active) {
                    SmallAction("Remove from list", onRemove, TextMuted)
                }
                Spacer(Modifier.weight(1f))
                SmallAction("Copy link", { copyLink(context, entry) }, TextMuted)
            }
        }
    }
}

@Composable
private fun NewDownloadSheet(
    initialUrl: String?,
    states: List<Pair<Link, LinkState>>,
    speedUnit: SpeedUnit,
    scheduleOpen: Boolean,
    onDismiss: () -> Unit,
    onStart: (String, String?, Set<String>?, Int?) -> String?,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    var name by rememberSaveable { mutableStateOf("") }
    var streams by rememberSaveable { mutableStateOf(0) }
    var chosen by remember {
        mutableStateOf(states.filter { it.second.enabled }.map { it.first.id }.toSet())
    }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        contentColor = TextPrimary,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New download", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Text(
                "Plexo splits the file into byte ranges and fetches them from every selected link at once.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )

            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                label = { Text("Link") },
                placeholder = { Text("https://example.com/file.iso") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                colors = plexoFieldColors(),
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                label = { Text("File name (optional)") },
                placeholder = { Text("Taken from the server") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                colors = plexoFieldColors(),
            )

            SectionLabel("Networks for this download")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                states.forEach { (link, state) ->
                    val selected = link.id in chosen
                    val capped = state.enabled && state.connected
                    ChoiceChip(
                        label = buildString {
                            append(state.displayName.ifBlank { link.title })
                            if (!state.connected) append(" · off")
                        },
                        selected = selected,
                        enabled = true,
                        onClick = {
                            chosen = if (selected) chosen - link.id else chosen + link.id
                        },
                    )
                }
            }
            Text(
                text = if (chosen.isEmpty()) {
                    "Pick at least one network. Plexo cannot start without a route."
                } else {
                    "Only the selected radios carry this file. ${states.count { it.first.id in chosen && it.second.connected }} connected now."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (chosen.isEmpty()) Coral else TextFaint,
            )

            SectionLabel("Streams per network")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                listOf(0, 4, 8, 16, 32).forEach { option ->
                    ChoiceChip(
                        label = if (option == 0) "Auto" else option.toString(),
                        selected = streams == option,
                        onClick = { streams = option },
                    )
                }
            }
            Text(
                text = "Auto uses each network's own setting. More streams help high-latency links; 4–8 is usually best on mobile.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
            )

            if (!scheduleOpen) {
                Text(
                    text = "The download schedule is closed. Plexo will queue this file and start it when the window opens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
            }

            Text(
                text = "Saved to Downloads/Plexo · speed and data caps live in Settings",
                style = MonoSmall,
                color = TextFaint,
            )

            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Coral) }

            Button(
                onClick = {
                    val result = onStart(
                        url,
                        name.takeIf { it.isNotBlank() },
                        chosen.takeIf { it.isNotEmpty() },
                        streams.takeIf { it > 0 },
                    )
                    error = result
                },
                enabled = url.isNotBlank() && chosen.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = OnAmber),
            ) {
                Text("Start download", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
fun plexoFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Amber,
    focusedBorderColor = Amber,
    unfocusedBorderColor = StrokeStrong,
    focusedContainerColor = PanelRaised,
    unfocusedContainerColor = PanelRaised,
    focusedLabelColor = Amber,
    unfocusedLabelColor = TextFaint,
    focusedPlaceholderColor = TextFaint,
    unfocusedPlaceholderColor = TextFaint,
)

@Composable
private fun statusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.RUNNING, DownloadStatus.PREPARING, DownloadStatus.SAVING -> Amber
    DownloadStatus.QUEUED -> TextMuted
    DownloadStatus.DONE -> Mint
    DownloadStatus.ERROR -> Coral
    DownloadStatus.CANCELLED -> TextFaint
}

@Composable
private fun statusLine(entry: DownloadEntry): String {
    val s = entry.snapshot
    return when (s.status) {
        DownloadStatus.QUEUED -> s.message?.takeIf { it != "Queued" } ?: "Queued"
        DownloadStatus.PREPARING -> "Preparing · measuring the file and links"
        DownloadStatus.RUNNING -> {
            val streams = s.links.sumOf { it.streams }
            if (s.parallel) {
                "${s.links.size} links · $streams streams · ${formatEta(s.etaSeconds ?: 0)} left"
            } else {
                "Single connection · ranges are unavailable or throttled"
            }
        }

        DownloadStatus.SAVING -> "Saving to Downloads/Plexo"
        DownloadStatus.DONE -> "Complete"
        DownloadStatus.ERROR -> s.message ?: "Download failed"
        DownloadStatus.CANCELLED -> s.message ?: "Cancelled"
    }
}

private fun copyLink(context: Context, entry: DownloadEntry) {
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager.setPrimaryClip(ClipData.newPlainText("Plexo link", entry.snapshot.url))
}

private fun copyError(context: Context, entry: DownloadEntry) {
    val manager = context.getSystemService(ClipboardManager::class.java)
    val text = buildString {
        append(entry.snapshot.message ?: "Download failed")
        append('\n')
        append(entry.snapshot.url)
    }
    manager.setPrimaryClip(ClipData.newPlainText("Plexo error", text))
}

private fun openSaved(context: Context, entry: DownloadEntry) {
    val raw = entry.snapshot.savedUri ?: return
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
}
