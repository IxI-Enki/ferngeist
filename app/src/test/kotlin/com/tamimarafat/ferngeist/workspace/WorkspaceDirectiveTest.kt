package com.tamimarafat.ferngeist.workspace

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.window.core.layout.WindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Partition count per width class. `minWidthDp` is the class lower bound, and Expanded (840dp)
 * still yields 2 — the third pane needs Large (1200dp).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class WorkspaceDirectiveTest {
    @Test
    fun partitions_followWidthBreakpoints() {
        // Class lower bound to expected partitions.
        mapOf(0 to 1, 600 to 2, 840 to 2, 1200 to 3, 1600 to 3).forEach { (width, partitions) ->
            val info = WindowAdaptiveInfo(WindowSizeClass(width, 800), Posture())
            assertEquals(
                partitions,
                calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(info).maxHorizontalPartitions,
            )
        }
    }
}
