package com.egrmeister.mealsip.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.egrmeister.mealsip.R
import com.egrmeister.mealsip.domain.model.PeriodIcons
import com.egrmeister.mealsip.ui.theme.accentFor
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@DrawableRes
fun periodIconRes(iconKey: String): Int = when (iconKey) {
    PeriodIcons.SUNRISE -> R.drawable.ic_period_sunrise
    PeriodIcons.SUN -> R.drawable.ic_period_sun
    PeriodIcons.SUNSET -> R.drawable.ic_period_sunset
    PeriodIcons.MOON -> R.drawable.ic_period_moon
    PeriodIcons.CUP -> R.drawable.ic_period_cup
    PeriodIcons.LEAF -> R.drawable.ic_period_leaf
    PeriodIcons.STAR -> R.drawable.ic_period_star
    else -> R.drawable.ic_period_clock
}

/** Decorative period icon in a soft accent circle (the period name is always shown next to it). */
@Composable
fun PeriodBadge(iconKey: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val accent = accentFor(iconKey)
    Box(
        modifier = modifier.size(size).background(accent.soft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(periodIconRes(iconKey)),
            contentDescription = null,
            tint = accent.deep,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

object Fmt {
    private val number: NumberFormat get() = NumberFormat.getIntegerInstance(Locale.US)
    private val longDate = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US)
    private val shortDate = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.US)
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.US)

    fun ml(value: Long): String = "${number.format(value)} mL"
    fun ml(value: Int): String = ml(value.toLong())
    fun count(n: Int): String = if (n == 1) "1 entry" else "$n entries"
    fun longDate(d: LocalDate): String = longDate.format(d)
    fun shortDate(d: LocalDate): String = shortDate.format(d)
    fun time(t: LocalTime): String = time.format(t)
    fun clock(t: LocalTime): String = DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(t)
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
