package app.focal.ui.library

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The device's own date and time patterns (from `DateFormat.getBestDateTimePattern` in the device locale and its
 * 12/24 h setting), injected so the library's date rules run on the JVM in tests.
 */
@Immutable
data class DatePatterns(
    /** "16:05" or "4:05 PM". */
    val time: String,
    /** "Mon". */
    val weekdayShort: String,
    /** "Monday". */
    val weekdayLong: String,
    /** "28 Oct". */
    val dayMonthShort: String,
    /** "28 Oct 2025". */
    val dayMonthShortYear: String,
    /** "3 November". */
    val dayMonthLong: String,
    /** "3 November 2025". */
    val dayMonthLongYear: String,
)

/** The words for the two relative days (spec 7.1: `day_today`, `day_yesterday`). */
@Immutable
data class DayWords(val today: String, val yesterday: String)

/**
 * The library's dates (spec 4.12.1, 4.22.4, 5.2, 7.1), in the device locale and time zone. [today] is passed in, so
 * a library left open across midnight relabels on the next state change rather than on a clock.
 */
class LibraryDates(locale: Locale, private val zone: ZoneId, patterns: DatePatterns, private val words: DayWords) {
    private val time = DateTimeFormatter.ofPattern(patterns.time, locale)
    private val weekdayShort = DateTimeFormatter.ofPattern(patterns.weekdayShort, locale)
    private val weekdayLong = DateTimeFormatter.ofPattern(patterns.weekdayLong, locale)
    private val dayMonthShort = DateTimeFormatter.ofPattern(patterns.dayMonthShort, locale)
    private val dayMonthShortYear = DateTimeFormatter.ofPattern(patterns.dayMonthShortYear, locale)
    private val dayMonthLong = DateTimeFormatter.ofPattern(patterns.dayMonthLong, locale)
    private val dayMonthLongYear = DateTimeFormatter.ofPattern(patterns.dayMonthLongYear, locale)

    fun date(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** The time of day: "16:05" (the device's 12/24 h setting). */
    fun time(epochMs: Long): String = time.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** A list day label: "Today", "Yesterday", "Monday" for the past week, "3 November", "3 November 2025". */
    fun dayLabel(epochDay: Long, today: LocalDate): String {
        val day = LocalDate.ofEpochDay(epochDay)
        return relative(day, today) ?: when {
            withinWeek(day, today) -> weekdayLong.format(day)
            day.year == today.year -> dayMonthLong.format(day)
            else -> dayMonthLongYear.format(day)
        }
    }

    /** The stage's time (spec 4.22.4): a bare time means today; "Yesterday"; "Mon"; "28 Oct"; "28 Oct 2025". */
    fun stageWhen(epochMs: Long, today: LocalDate): String {
        val day = date(epochMs)
        return when {
            day == today -> time(epochMs)
            day == today.minusDays(1) -> words.yesterday
            withinWeek(day, today) -> weekdayShort.format(day)
            day.year == today.year -> dayMonthShort.format(day)
            else -> dayMonthShortYear.format(day)
        }
    }

    /** A search result's run-in (spec 5.2): "Today", "Yesterday", "Mon", "3 Nov", "3 Nov 2025". */
    fun searchDay(epochMs: Long, today: LocalDate): String {
        val day = date(epochMs)
        return relative(day, today) ?: when {
            withinWeek(day, today) -> weekdayShort.format(day)
            day.year == today.year -> dayMonthShort.format(day)
            else -> dayMonthShortYear.format(day)
        }
    }

    /**
     * The full form TalkBack reads (spec 4.22.4, 7.1 `dateline*`): "Today, 16:05", "Yesterday, 21:47",
     * "Monday, 16:40", "28 October, 09:12", "28 October 2025, 09:12". [dated] joins a date and a time.
     */
    fun spoken(epochMs: Long, today: LocalDate, dateline: SpokenDateline): String {
        val day = date(epochMs)
        val clock = time(epochMs)
        return when {
            day == today -> dateline.today(clock)
            day == today.minusDays(1) -> dateline.yesterday(clock)
            withinWeek(day, today) -> dateline.dated(weekdayLong.format(day), clock)
            day.year == today.year -> dateline.dated(dayMonthLong.format(day), clock)
            else -> dateline.dated(dayMonthLongYear.format(day), clock)
        }
    }

    /**
     * When a note was made, as its page's meta line opens (spec 5.5): "Today, 14:32", "Yesterday, 21:47",
     * "Mon, 16:40" for the past week, "28 Oct, 09:12", "28 Oct 2025, 09:12".
     */
    fun noteWhen(epochMs: Long, today: LocalDate, dateline: SpokenDateline): String {
        val day = date(epochMs)
        val clock = time(epochMs)
        return when {
            day == today -> dateline.today(clock)
            day == today.minusDays(1) -> dateline.yesterday(clock)
            withinWeek(day, today) -> dateline.dated(weekdayShort.format(day), clock)
            day.year == today.year -> dateline.dated(dayMonthShort.format(day), clock)
            else -> dateline.dated(dayMonthShortYear.format(day), clock)
        }
    }

    private fun relative(day: LocalDate, today: LocalDate): String? = when (day) {
        today -> words.today
        today.minusDays(1) -> words.yesterday
        else -> null
    }

    /** "The past seven days": two to six days ago (today and yesterday have their own words). */
    private fun withinWeek(day: LocalDate, today: LocalDate): Boolean =
        ChronoUnit.DAYS.between(day, today) in WEEKDAY_MIN_DAYS..WEEKDAY_MAX_DAYS

    private companion object {
        const val WEEKDAY_MIN_DAYS = 2L
        const val WEEKDAY_MAX_DAYS = 6L
    }
}

/** The three `dateline*` templates (spec 7.1), resolved from resources by the caller. */
class SpokenDateline(val today: (String) -> String, val yesterday: (String) -> String, val dated: (String, String) -> String)

/** A unit of a note's length. */
enum class LengthUnit { Seconds, Minutes, Hours }

/** One numeral and its unit: the stage draws "12" large and "min" small; rows join them ("12 min"). */
@Immutable
data class LengthPart(val value: Long, val unit: LengthUnit)

/** Lengths and timers (spec 4.12.2, 4.22.4, 4.21.3): never m:ss for a note's length, only while live. */
object Lengths {
    private const val SECOND_MS = 1000.0
    private const val MINUTE_S = 60L
    private const val HOUR_MIN = 60L

    /**
     * "42 s" under a minute; "24 min" (to the nearest minute) under an hour; "1 h 12 min" or "2 h" from an hour.
     */
    fun parts(durationMs: Long): List<LengthPart> {
        val seconds = (durationMs.coerceAtLeast(0) / SECOND_MS).roundToLong()
        if (seconds < MINUTE_S) return listOf(LengthPart(seconds, LengthUnit.Seconds))
        val minutes = (seconds.toDouble() / MINUTE_S).roundToLong()
        if (minutes < HOUR_MIN) return listOf(LengthPart(minutes, LengthUnit.Minutes))
        val hours = minutes / HOUR_MIN
        val rest = minutes % HOUR_MIN
        return if (rest == 0L) listOf(LengthPart(hours, LengthUnit.Hours))
        else listOf(LengthPart(hours, LengthUnit.Hours), LengthPart(rest, LengthUnit.Minutes))
    }

    /** The live timer: "0:00", "12:48", "1:02:03". */
    fun timer(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val hours = s / (MINUTE_S * MINUTE_S)
        val minutes = s / MINUTE_S % MINUTE_S
        val rest = s % MINUTE_S
        return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, rest) else "%d:%02d".format(Locale.ROOT, minutes, rest)
    }

    /** The timer's reserved template (spec 4.21.3, R5): the widest string of its format, so ticking never shifts. */
    fun timerTemplate(seconds: Long): String = when {
        seconds >= MINUTE_S * MINUTE_S -> "0:00:00"
        seconds >= MINUTE_S * TEN -> "00:00"
        else -> "0:00"
    }

    /** Hours, minutes and seconds for the spoken duration (each part omitted when 0). */
    fun spokenParts(seconds: Long): Triple<Long, Long, Long> {
        val s = seconds.coerceAtLeast(0)
        return Triple(s / (MINUTE_S * MINUTE_S), s / MINUTE_S % MINUTE_S, s % MINUTE_S)
    }

    private const val TEN = 10L
}

/** Search snippets and previews (spec 4.12.2, G4). */
object WordCut {
    /** Characters a cut preview drops before its ellipsis, so it never reads "at all.…". */
    private const val TRAILING = ",.;:–—"
    const val ELLIPSIS = "…"

    /**
     * The longest prefix of [text] that fits, cut back to the last whole word at or before [visibleEnd] (the end of
     * the last line that fits), with trailing punctuation and spaces dropped. Null when not even one word fits.
     */
    fun cut(text: CharSequence, visibleEnd: Int): Int? {
        var end = visibleEnd.coerceIn(0, text.length)
        // Inside a word: back to its start.
        if (end < text.length && !text[end].isWhitespace() && end > 0 && !text[end - 1].isWhitespace()) {
            while (end > 0 && !text[end - 1].isWhitespace()) end--
        }
        while (end > 0 && (text[end - 1].isWhitespace() || text[end - 1] in TRAILING)) end--
        return end.takeIf { it > 0 }
    }

    /** One step shorter: the end of the word before the one ending at [end]. */
    fun previousWord(text: CharSequence, end: Int): Int? {
        var at = end
        while (at > 0 && !text[at - 1].isWhitespace()) at--
        return cut(text, at)
    }
}
