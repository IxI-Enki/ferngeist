package com.tamimarafat.ferngeist.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatFileLoaderTest {
    @Test
    fun aPathInsideTheProjectIsReadRelativeToIt() {
        assertEquals("src/A.kt", "/repo/src/A.kt".relativeTo("/repo/"))
        assertEquals("src/A.kt", "./src/A.kt".relativeTo("/repo"))
        assertEquals("src/A.kt", "C:\\repo\\src\\A.kt".relativeTo("C:\\repo"))
    }

    @Test
    fun anAbsolutePathOutsideTheProjectIsRefused() {
        assertNull("/elsewhere/A.kt".relativeTo("/repo"))
        assertNull("/repository/A.kt".relativeTo("/repo"))
        assertNull("D:/x/A.kt".relativeTo("C:/repo"))
    }

    @Test
    fun changedAndInsertedLinesAreMarkedAndADeletionMarksTheLineBelowIt() {
        val file = markedFile(oldText = "a\nb\nc\nd\n", newText = "a\nB\nc\nnew\n")

        // b -> B changed (1); d -> new changed (3).
        assertEquals(setOf(1, 3), file.changedLines)
        assertEquals(emptySet<Int>(), file.deletedAbove)

        val deleted = markedFile(oldText = "a\nb\nc\n", newText = "a\nc\n")
        assertEquals(emptySet<Int>(), deleted.changedLines)
        assertEquals(setOf(1), deleted.deletedAbove)
    }

    @Test
    fun linesDeletedAtTheEndMarkPastTheLastLine() {
        assertEquals(setOf(1), markedFile(oldText = "a\nb\n", newText = "a\n").deletedAbove)
    }

    @Test
    fun anUntrackedFileIsAllNew() {
        assertEquals(setOf(0, 1), markedFile(oldText = null, newText = "a\nb\n").changedLines)
    }
}
