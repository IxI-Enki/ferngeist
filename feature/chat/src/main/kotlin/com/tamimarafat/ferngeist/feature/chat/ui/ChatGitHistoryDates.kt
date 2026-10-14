package com.tamimarafat.ferngeist.feature.chat.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.feature.chat.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** "Today" and "Yesterday" in the device's words, otherwise the weekday and date. */
@Composable
internal fun dayLabel(
    dayMillis: Long,
    nowMillis: Long,
): String {
    val context = LocalContext.current
    val daysAgo = localDay(nowMillis).toEpochDay() - localDay(dayMillis).toEpochDay()
    return if (daysAgo in 0..1) {
        DateUtils.getRelativeTimeSpanString(dayMillis, nowMillis, DateUtils.DAY_IN_MILLIS).toString()
    } else {
        // Without a year flag DateUtils adds the year only when it is not the current one.
        DateUtils.formatDateTime(
            context,
            dayMillis,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
        )
    }
}

internal fun localDay(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/** How a commit's age is shown in the narrow date column. */
internal sealed interface CommitAge {
    data object Now : CommitAge

    data class Minutes(
        val count: Int,
    ) : CommitAge

    data class Hours(
        val count: Int,
    ) : CommitAge

    /** Within the last week: show the weekday. */
    data object ThisWeek : CommitAge

    data object Older : CommitAge
}

internal fun commitAge(
    nowMillis: Long,
    thenMillis: Long,
): CommitAge {
    // Future timestamps (clock skew between gateway and phone) read as "now".
    val elapsed = (nowMillis - thenMillis).coerceAtLeast(0)
    return when {
        elapsed < DateUtils.MINUTE_IN_MILLIS -> CommitAge.Now
        elapsed < DateUtils.HOUR_IN_MILLIS -> CommitAge.Minutes((elapsed / DateUtils.MINUTE_IN_MILLIS).toInt())
        elapsed < DateUtils.DAY_IN_MILLIS -> CommitAge.Hours((elapsed / DateUtils.HOUR_IN_MILLIS).toInt())
        elapsed < DateUtils.WEEK_IN_MILLIS -> CommitAge.ThisWeek
        else -> CommitAge.Older
    }
}

@Composable
internal fun compactCommitDate(
    nowMillis: Long,
    thenMillis: Long,
): String {
    val context = LocalContext.current
    return when (val age = commitAge(nowMillis, thenMillis)) {
        CommitAge.Now -> stringResource(R.string.chat_git_date_now)
        is CommitAge.Minutes -> stringResource(R.string.chat_git_date_minutes, age.count)
        is CommitAge.Hours -> stringResource(R.string.chat_git_date_hours, age.count)
        CommitAge.ThisWeek ->
            DateUtils.formatDateTime(
                context,
                thenMillis,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
            )
        // Without a year flag DateUtils adds the year only when it is not the current one.
        CommitAge.Older -> DateUtils.formatDateTime(context, thenMillis, DateUtils.FORMAT_ABBREV_MONTH)
    }
}
