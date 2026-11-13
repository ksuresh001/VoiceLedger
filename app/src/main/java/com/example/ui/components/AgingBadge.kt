package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.AgingTier
import com.example.data.model.NoteAging
import com.example.data.model.NotePriority
import com.example.ui.theme.LocalVoiceLedgerColors
import kotlin.math.sin

@Composable
fun AgingBadge(
    aging: NoteAging,
    modifier: Modifier = Modifier
) {
    val semantic = LocalVoiceLedgerColors.current
    val (bg, fg) = semantic.colorsForTier(aging.tier)
    val icon = when {
        aging.tier == AgingTier.CLOSED -> Icons.Filled.CheckCircle
        aging.isOverdue || aging.tier == AgingTier.CRITICAL -> Icons.Filled.Warning
        else -> Icons.Filled.Schedule
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Age ${aging.label}",
                tint = fg,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = if (aging.isOverdue) "${aging.label} • Overdue" else aging.label,
                style = MaterialTheme.typography.labelSmall,
                color = fg
            )
        }
    }
}

@Composable
fun CategoryBadge(
    category: String,
    modifier: Modifier = Modifier
) {
    val isOfficial = category.equals("Official", ignoreCase = true) ||
        category.equals("Client", ignoreCase = true)
    val bg = if (isOfficial) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (isOfficial) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Text(
            text = category,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun PriorityPill(
    priority: NotePriority,
    modifier: Modifier = Modifier
) {
    val semantic = LocalVoiceLedgerColors.current
    val (bg, fg) = when (priority) {
        NotePriority.HIGH -> semantic.criticalBg to semantic.criticalFg
        NotePriority.MEDIUM -> semantic.warmBg to semantic.warmFg
        NotePriority.LOW -> semantic.freshBg to semantic.freshFg
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Text(
            text = priority.label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun LiveWaveformCanvas(
    amplitude: Float,
    isActive: Boolean,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(64.dp)
) {
    val smoothedAmp by animateFloatAsState(
        targetValue = if (isActive) amplitude.coerceIn(0.08f, 1f) else 0.08f,
        animationSpec = spring(stiffness = 380f),
        label = "waveform_amp"
    )

    Canvas(modifier = modifier) {
        val barCount = 32
        val totalWidth = size.width
        val height = size.height
        val slotWidth = totalWidth / barCount
        val barWidth = (slotWidth * 0.56f).coerceAtLeast(3.dp.toPx())
        val centerY = height / 2f

        for (i in 0 until barCount) {
            val waveEnvelope = 0.45f + 0.55f * kotlin.math.abs(sin((i + 1) * 0.45f + smoothedAmp * 6f))
            val barH = (height * 0.88f * smoothedAmp * waveEnvelope).coerceAtLeast(8.dp.toPx())
            val x = i * slotWidth + (slotWidth - barWidth) / 2f
            drawRoundRect(
                color = if (isActive) barColor.copy(alpha = 0.55f + 0.45f * waveEnvelope) else trackColor,
                topLeft = Offset(x, centerY - barH / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
