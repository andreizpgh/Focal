package app.focal.ui.library

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.focal.R
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The device's patterns for the library's dates, in its locale and 12/24 h setting (spec 7). */
fun devicePatterns(locale: Locale, is24Hour: Boolean): DatePatterns {
    fun best(skeleton: String) = DateFormat.getBestDateTimePattern(locale, skeleton)
    return DatePatterns(
        time = best(if (is24Hour) "Hm" else "hm"),
        weekdayShort = best("EEE"),
        weekdayLong = best("EEEE"),
        dayMonthShort = best("dMMM"),
        dayMonthShortYear = best("dMMMy"),
        dayMonthLong = best("dMMMM"),
        dayMonthLongYear = best("dMMMMy"),
    )
}

/** The library's date formatter for the current configuration (locale, 12/24 h, time zone). */
@Composable
fun rememberLibraryDates(): LibraryDates {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // The configuration's first locale: observable, so a language change re-formats the library.
    val locale: Locale = configuration.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    val today = stringResource(R.string.day_today)
    val yesterday = stringResource(R.string.day_yesterday)
    return remember(locale, is24Hour, today, yesterday) {
        LibraryDates(locale, ZoneId.systemDefault(), devicePatterns(locale, is24Hour), DayWords(today, yesterday))
    }
}

/** Today's date, re-read when the app resumes and at each midnight while it is visible. */
@Composable
fun rememberToday(): LocalDate {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val today by produceState(LocalDate.now()) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = LocalDate.now()
                val now = ZonedDateTime.now()
                val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                delay(Duration.between(now, midnight).toMillis() + MIDNIGHT_SLACK_MS)
            }
        }
    }
    return today
}

/** Wakes just after midnight, not on it. */
private const val MIDNIGHT_SLACK_MS = 1_000L
