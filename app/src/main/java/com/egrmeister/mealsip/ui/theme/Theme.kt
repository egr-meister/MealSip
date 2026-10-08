package com.egrmeister.mealsip.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.egrmeister.mealsip.domain.model.PeriodIcons

/** Calm daily-planner palette. Text is dark slate on warm cream (contrast > 10:1). */
object PlannerColors {
    val Cream = Color(0xFFFBF6EC)
    val Paper = Color(0xFFFFFCF6)
    val Rule = Color(0xFFE5DCCB)
    val Slate = Color(0xFF2E3A46)
    val SlateMuted = Color(0xFF55616D)
    val Ink = Color(0xFF34556E)

    val Peach = Color(0xFFF6D3BD)
    val PeachDeep = Color(0xFF9A5536)
    val Sage = Color(0xFFD4E1CC)
    val SageDeep = Color(0xFF4D6B45)
    val PaleBlue = Color(0xFFD3E2F0)
    val BlueDeep = Color(0xFF3D6487)
    val Lavender = Color(0xFFE2D9F0)
    val LavenderDeep = Color(0xFF654F8A)
    val Sand = Color(0xFFEDE1C7)
    val SandDeep = Color(0xFF7A6232)
    val Rose = Color(0xFFF1D6DA)
    val RoseDeep = Color(0xFF8E4552)
    val Mint = Color(0xFFD2ECE5)
    val MintDeep = Color(0xFF2F6B5E)
    val Stone = Color(0xFFE2E2DE)
    val StoneDeep = Color(0xFF55595E)
}

/** Soft fill + deep accent for a period. Labels and icons carry meaning; colour only supports them. */
data class PeriodAccent(val soft: Color, val deep: Color)

fun accentFor(iconKey: String): PeriodAccent = when (iconKey) {
    PeriodIcons.SUNRISE -> PeriodAccent(PlannerColors.Peach, PlannerColors.PeachDeep)
    PeriodIcons.SUN -> PeriodAccent(PlannerColors.Sage, PlannerColors.SageDeep)
    PeriodIcons.SUNSET -> PeriodAccent(PlannerColors.PaleBlue, PlannerColors.BlueDeep)
    PeriodIcons.MOON -> PeriodAccent(PlannerColors.Lavender, PlannerColors.LavenderDeep)
    PeriodIcons.CUP -> PeriodAccent(PlannerColors.Sand, PlannerColors.SandDeep)
    PeriodIcons.LEAF -> PeriodAccent(PlannerColors.Mint, PlannerColors.MintDeep)
    PeriodIcons.STAR -> PeriodAccent(PlannerColors.Rose, PlannerColors.RoseDeep)
    else -> PeriodAccent(PlannerColors.Stone, PlannerColors.StoneDeep)
}

private val PlannerScheme = lightColorScheme(
    primary = PlannerColors.Ink,
    onPrimary = Color.White,
    primaryContainer = PlannerColors.PaleBlue,
    onPrimaryContainer = PlannerColors.Slate,
    secondary = PlannerColors.SageDeep,
    onSecondary = Color.White,
    secondaryContainer = PlannerColors.Sage,
    onSecondaryContainer = PlannerColors.Slate,
    tertiary = PlannerColors.LavenderDeep,
    onTertiary = Color.White,
    background = PlannerColors.Cream,
    onBackground = PlannerColors.Slate,
    surface = PlannerColors.Cream,
    onSurface = PlannerColors.Slate,
    surfaceVariant = PlannerColors.Paper,
    onSurfaceVariant = PlannerColors.SlateMuted,
    surfaceContainer = PlannerColors.Paper,
    surfaceContainerLow = PlannerColors.Paper,
    surfaceContainerHigh = PlannerColors.Paper,
    surfaceContainerHighest = PlannerColors.Sand,
    outline = PlannerColors.SlateMuted,
    outlineVariant = PlannerColors.Rule,
    error = Color(0xFFA23B32),
    onError = Color.White,
)

private val PlannerShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun MealSipTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PlannerScheme, shapes = PlannerShapes, content = content)
}
