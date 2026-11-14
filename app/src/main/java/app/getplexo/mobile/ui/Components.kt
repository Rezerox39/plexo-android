@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.getplexo.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.getplexo.mobile.core.DownloadSnapshot
import app.getplexo.mobile.core.Link
import kotlin.math.max

/** The desktop app's mark: three network lines merging into one node and arrow. */
@Composable
fun PlexoMark(modifier: Modifier = Modifier) {
    val mint = Mint
    val amber = Amber
    val steel = Steel
    val text = TextPrimary
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

        merge(mint, 0.27f)
        drawLine(
            color = amber,
            start = Offset(w * 0.05f, cy),
            end = Offset(cx, cy),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        merge(steel, 0.73f)
        drawCircle(
            color = text,
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
        drawPath(arrow, text)
    }
}

@Composable
fun PanelCard(
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

@Composable
fun SectionLabel(text: String, trailing: String? = null, trailingColor: Color = TextFaint) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint,
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = trailingColor)
        }
    }
}

@Composable
fun Bullet(text: String, color: Color = TextMuted) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(3.dp)
                .clip(CircleShape)
                .background(TextFaint),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
fun PlexoSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = OnAmber,
            checkedTrackColor = Amber,
            uncheckedThumbColor = TextFaint,
            uncheckedTrackColor = PanelRaised,
            uncheckedBorderColor = StrokeStrong,
        ),
    )
}

@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val border = if (selected) Amber else StrokeStrong
    val bg = if (selected) Amber.copy(alpha = 0.14f) else Color.Transparent
    val fg = when {
        !enabled -> TextFaint.copy(alpha = 0.55f)
        selected -> Amber
        else -> TextMuted
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SmallAction(
    label: String,
    onClick: () -> Unit,
    color: Color = TextMuted,
    enabled: Boolean = true,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) color else TextFaint.copy(alpha = 0.5f),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    )
}

/** A 60-second strip chart: the same idea as desktop's throughput graph. */
@Composable
fun ThroughputChart(
    history: List<Long>,
    modifier: Modifier = Modifier,
    height: Int = 70,
) {
    val amber = Amber
    val stroke = StrokeSubtle
    val track = Track.copy(alpha = 0.55f)
    val samples = rememberFilled(history)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(track),
    ) {
        val w = size.width
        val h = size.height
        val maxValue = max(1L, samples.maxOrNull() ?: 1L).toFloat()
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(stroke, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (samples.size < 2) return@Canvas
        val step = w / (samples.size - 1).coerceAtLeast(1)
        val line = Path()
        val area = Path()
        samples.forEachIndexed { index, value ->
            val x = index * step
            val y = h - (value / maxValue) * (h - 4f) - 2f
            if (index == 0) {
                line.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(w, h)
        area.close()
        drawPath(area, amber.copy(alpha = 0.13f))
        drawPath(line, amber, style = StrokeStyle(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
        val lastY = h - (samples.last() / maxValue) * (h - 4f) - 2f
        drawCircle(amber, radius = 2.6.dp.toPx(), center = Offset(w, lastY))
    }
}

@Composable
private fun rememberFilled(history: List<Long>): List<Long> =
    androidx.compose.runtime.remember(history) {
        if (history.size >= 60) history.takeLast(60) else List(60 - history.size) { 0L } + history
    }

@Composable
fun SplitBar(snapshot: DownloadSnapshot, links: List<Link>) {
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
            val state = links.firstOrNull { it.id == progress.id }
            val color = when {
                state == null -> TextFaint
                else -> Color(state.state.value.color)
            }
            Box(
                Modifier
                    .weight(progress.bytes.toFloat())
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

@Composable
fun LinkDot(color: Long, on: Boolean = true, size: Int = 8) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(color).copy(alpha = if (on) 1f else 0.3f)),
    )
}
