package com.tamimarafat.ferngeist.feature.chat.ui

import android.text.format.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHistoryFormatTest {
    private val now = 1_792_000_000_000L

    @Test
    fun commitAge_bucketsByElapsedTime() {
        assertEquals(CommitAge.Now, commitAge(now, now - 59_000))
        assertEquals(CommitAge.Minutes(12), commitAge(now, now - 12 * DateUtils.MINUTE_IN_MILLIS))
        assertEquals(CommitAge.Hours(3), commitAge(now, now - 3 * DateUtils.HOUR_IN_MILLIS - 1))
        assertEquals(CommitAge.ThisWeek, commitAge(now, now - 6 * DateUtils.DAY_IN_MILLIS))
        assertEquals(CommitAge.Older, commitAge(now, now - DateUtils.WEEK_IN_MILLIS))
    }

    @Test
    fun commitAge_futureTimestampReadsAsNow() {
        assertEquals(CommitAge.Now, commitAge(now, now + DateUtils.HOUR_IN_MILLIS))
    }

    @Test
    fun initialsOf_usesFirstAndLastWordThenFallsBack() {
        assertEquals("TA", initialsOf("Tamim  Arafat", "t@x"))
        assertEquals("AL", initialsOf("ada byron lovelace", "a@x"))
        assertEquals("C", initialsOf("Claude", "c@x"))
        assertEquals("X", initialsOf(" ", "x@y"))
        assertEquals("?", initialsOf("", ""))
    }
}
