@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.LocalGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.dismissSheet
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.onEscapeKey
import com.tamimarafat.ferngeist.core.common.ui.rememberEscapeFocusAnchor
import com.tamimarafat.ferngeist.feature.chat.FileView
import com.tamimarafat.ferngeist.feature.chat.R

/**
 * A whole file a transcript chip opened, as it is in the working tree. Lines git counts as added
 * or changed since `HEAD` carry a green gutter bar; a red tick sits where lines were deleted.
 */
@Composable
internal fun FileSheet(
    file: FileView,
    onDismiss: () -> Unit,
) {
    // Expanded only: the file lands after the sheet opens, as the tool call sheet's diff does.
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey { scope.dismissSheet(sheetState, onDismiss) },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FileTitle(file.path, origin = fileOriginLabel(file.commit, file.compare))
            FileSheetBody(file)
        }
    }
}

/** The revision the sheet is showing, so its content is never read as the wrong one. */
@Composable
private fun fileOriginLabel(
    commit: String?,
    compare: String?,
): String =
    when {
        commit != null -> stringResource(R.string.chat_file_origin_commit, commit.take(SHORT_SHA))
        compare != null -> stringResource(R.string.chat_file_origin_compare, compare)
        else -> stringResource(R.string.chat_file_origin_working_tree)
    }

/** The files a name several share could mean; tapping one opens it. */
@Composable
internal fun FilePickerSheet(
    candidates: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey { scope.dismissSheet(sheetState, onDismiss) },
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(candidates) { path ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .handCursor()
                            .clickable { onPick(path) }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    FileTitle(path)
                }
            }
        }
    }
}

/** A file's chip over its directory, badged with the revision it was read at. */
@Composable
private fun FileTitle(
    path: String,
    origin: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FileChip(path, textStyle = MaterialTheme.typography.bodyMedium)
            origin?.let { FileOriginBadge(it) }
        }
        Text(
            text = path.replace('\\', '/').substringBeforeLast('/', ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
        )
    }
}

@Composable
private fun FileOriginBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun FileSheetBody(file: FileView) {
    val notice =
        when {
            file.error != null -> file.error
            file.deleted -> stringResource(R.string.chat_file_deleted)
            file.binary -> stringResource(R.string.chat_file_binary)
            file.text?.isEmpty() == true -> stringResource(R.string.chat_file_empty)
            else -> null
        }
    when {
        file.isLoading ->
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator(modifier = Modifier.size(48.dp))
            }
        notice != null ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (file.error !=
                        null
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        else -> FileLines(file)
    }
}

@Composable
private fun FileLines(file: FileView) {
    val lines = remember(file.text) { file.text.orEmpty().diffLines() }
    val colors = LocalGitSemanticColors.current
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val numberColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    // Measured, not guessed: the widest number at this style and font scale, so none wraps.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberWidth =
        remember(lines.size, style, density) {
            val widest = "9".repeat(lines.size.toString().length)
            with(density) {
                measurer
                    .measure(widest, style, maxLines = 1)
                    .size.width
                    .toDp()
            }
        }
    SelectionContainer {
        LazyColumn(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            itemsIndexed(lines) { index, line ->
                // Intrinsic height, so the gutter bar spans the line it marks.
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Gutter(
                        changed = index in file.changedLines,
                        deletedAbove = index in file.deletedAbove,
                        // Lines deleted at the end sit below the last line.
                        deletedBelow = index == lines.lastIndex && lines.size in file.deletedAbove,
                        added = colors.added,
                        deleted = colors.deleted,
                    )
                    Text(
                        text = "${index + 1}",
                        style = style,
                        color = numberColor,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(end = 8.dp).width(numberWidth),
                    )
                    Text(text = line, style = style, softWrap = false)
                }
            }
            if (file.truncated) {
                item {
                    Text(
                        text = stringResource(R.string.chat_file_truncated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Gutter(
    changed: Boolean,
    deletedAbove: Boolean,
    deletedBelow: Boolean,
    added: Color,
    deleted: Color,
) {
    Box(
        modifier =
            Modifier
                .width(GUTTER_WIDTH)
                .fillMaxHeight()
                .padding(end = 4.dp)
                .background(if (changed) added else Color.Transparent)
                .drawBehind {
                    val tick = 2.dp.toPx()
                    // Longer than the bar is wide, so a deletion reads apart from a change.
                    val length = DELETION_TICK_LENGTH.toPx()
                    if (deletedAbove) drawLine(deleted, Offset(0f, 0f), Offset(length, 0f), tick)
                    if (deletedBelow) drawLine(deleted, Offset(0f, size.height), Offset(length, size.height), tick)
                },
    )
}

private val GUTTER_WIDTH = 7.dp
private val DELETION_TICK_LENGTH = 9.dp
private const val SHORT_SHA = 7
