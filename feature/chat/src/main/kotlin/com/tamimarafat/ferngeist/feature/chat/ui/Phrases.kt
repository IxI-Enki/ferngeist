package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints

/**
 * Joins phrases so a line only breaks after a separator: a phrase that doesn't fit moves down
 * whole rather than splitting "3 files" across lines.
 */
internal fun joinPhrases(
    phrases: List<String>,
    separator: String,
): String = phrases.joinToString(phraseSeparator(separator)) { it.keptTogether() }

/** The separator as [joinPhrases] writes it: only its trailing space may break. */
private fun phraseSeparator(separator: String): String {
    val kept = separator.trimEnd()
    return kept.keptTogether() + separator.drop(kept.length)
}

private fun String.keptTogether() = replace(' ', '\u00A0')

/** Swaps the separator ending each of [lineEnds] for a line break, so no line ends on a bare "·". */
internal fun breakAtSeparators(
    text: String,
    separator: String,
    lineEnds: List<Int>,
): String {
    val joined = phraseSeparator(separator)
    val out = StringBuilder(text)
    lineEnds.sortedDescending().forEach { end ->
        if (end >= joined.length && text.startsWith(joined, end - joined.length)) {
            out.replace(end - joined.length, end, "\n")
        }
    }
    return out.toString()
}

/** Text of [joinPhrases] phrases that, where it wraps, drops the [separator] at the wrap. */
@Composable
internal fun PhrasesText(
    text: String,
    separator: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val shown =
            remember(text, separator, style, constraints.maxWidth) {
                val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = constraints.maxWidth))
                breakAtSeparators(text, separator, (0 until layout.lineCount - 1).map { layout.getLineEnd(it) })
            }
        Text(text = shown, style = style)
    }
}
