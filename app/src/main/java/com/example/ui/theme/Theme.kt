package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.AgingTier

@Immutable
data class VoiceLedgerSemanticColors(
    val freshBg: Color,
    val freshFg: Color,
    val warmBg: Color,
    val warmFg: Color,
    val agingBg: Color,
    val agingFg: Color,
    val criticalBg: Color,
    val criticalFg: Color,
    val closedBg: Color,
    val closedFg: Color
) {
    fun colorsForTier(tier: AgingTier): Pair<Color, Color> = when (tier) {
        AgingTier.FRESH -> freshBg to freshFg
        AgingTier.WARM -> warmBg to warmFg
        AgingTier.AGING -> agingBg to agingFg
        AgingTier.CRITICAL -> criticalBg to criticalFg
        AgingTier.CLOSED -> closedBg to closedFg
    }
}

val LocalVoiceLedgerColors = staticCompositionLocalOf {
    VoiceLedgerSemanticColors(
        freshBg = AgingFreshBgLight,
        freshFg = AgingFreshFgLight,
        warmBg = AgingWarmBgLight,
        warmFg = AgingWarmFgLight,
        agingBg = AgingOldBgLight,
        agingFg = AgingOldFgLight,
        criticalBg = AgingCriticalBgLight,
        criticalFg = AgingCriticalFgLight,
        closedBg = AgingClosedBgLight,
        closedFg = AgingClosedFgLight
    )
}

private val LightColorScheme = lightColorScheme(
    primary = TerracottaPrimary,
    onPrimary = TerracottaOnPrimary,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = TerracottaOnContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealContainer,
    onSecondaryContainer = TealOnContainer,
    tertiary = BrassTertiary,
    tertiaryContainer = BrassContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline.copy(alpha = 0.55f)
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkTerracottaPrimary,
    onPrimary = DarkTerracottaOnPrimary,
    primaryContainer = DarkTerracottaContainer,
    onPrimaryContainer = DarkTerracottaOnContainer,
    secondary = DarkTealSecondary,
    onSecondary = DarkTealOnSecondary,
    secondaryContainer = DarkTealContainer,
    onSecondaryContainer = DarkTealOnContainer,
    tertiary = BrassTertiary,
    tertiaryContainer = DarkSurfaceVariant,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutline.copy(alpha = 0.6f)
)

private val VoiceLedgerShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun VoiceLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) {
        VoiceLedgerSemanticColors(
            freshBg = AgingFreshBgDark,
            freshFg = AgingFreshFgDark,
            warmBg = AgingWarmBgDark,
            warmFg = AgingWarmFgDark,
            agingBg = AgingOldBgDark,
            agingFg = AgingOldFgDark,
            criticalBg = AgingCriticalBgDark,
            criticalFg = AgingCriticalFgDark,
            closedBg = AgingClosedBgDark,
            closedFg = AgingClosedFgDark
        )
    } else {
        VoiceLedgerSemanticColors(
            freshBg = AgingFreshBgLight,
            freshFg = AgingFreshFgLight,
            warmBg = AgingWarmBgLight,
            warmFg = AgingWarmFgLight,
            agingBg = AgingOldBgLight,
            agingFg = AgingOldFgLight,
            criticalBg = AgingCriticalBgLight,
            criticalFg = AgingCriticalFgLight,
            closedBg = AgingClosedBgLight,
            closedFg = AgingClosedFgLight
        )
    }

    CompositionLocalProvider(LocalVoiceLedgerColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VoiceLedgerTypography,
            shapes = VoiceLedgerShapes,
            content = content
        )
    }
}
