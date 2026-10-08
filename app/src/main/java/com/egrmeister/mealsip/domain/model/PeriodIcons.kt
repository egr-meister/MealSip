package com.egrmeister.mealsip.domain.model

/** Bundled icon keys. Each maps to a small vector drawable and a planner accent. */
object PeriodIcons {
    const val SUNRISE = "sunrise"
    const val SUN = "sun"
    const val SUNSET = "sunset"
    const val MOON = "moon"
    const val CUP = "cup"
    const val LEAF = "leaf"
    const val STAR = "star"
    const val CLOCK = "clock"

    val ALL: List<String> = listOf(SUNRISE, SUN, SUNSET, MOON, CUP, LEAF, STAR, CLOCK)

    fun label(key: String): String = when (key) {
        SUNRISE -> "Sunrise"
        SUN -> "Sun"
        SUNSET -> "Sunset"
        MOON -> "Moon"
        CUP -> "Cup"
        LEAF -> "Leaf"
        STAR -> "Star"
        CLOCK -> "Clock"
        else -> "Period"
    }

    fun normalize(key: String): String = if (key in ALL) key else CLOCK
}

/** Starter periods with example reminder times. All reminders start disabled. */
object StarterPeriods {
    data class Starter(val name: String, val iconKey: String, val reminderMinute: Int)

    val ALL: List<Starter> = listOf(
        Starter("Breakfast", PeriodIcons.SUNRISE, 8 * 60),
        Starter("Lunch", PeriodIcons.SUN, 13 * 60),
        Starter("Dinner", PeriodIcons.SUNSET, 19 * 60),
        Starter("Evening", PeriodIcons.MOON, 21 * 60),
    )
}
