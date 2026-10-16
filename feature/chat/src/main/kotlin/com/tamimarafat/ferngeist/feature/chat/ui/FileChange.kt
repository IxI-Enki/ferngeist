package com.tamimarafat.ferngeist.feature.chat.ui

import android.icu.text.MeasureFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.core.model.TurnTiming
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

private val PATH_KEYS = listOf("file_path", "filePath", "path", "filename")
private val CONTENT_KEYS = listOf("content", "contents", "text", "file_text")

// Search-and-replace pairs as agents name them in an edit's raw input.
private val REPLACEMENT_KEYS =
    listOf(
        "old_string" to "new_string",
        "oldString" to "newString",
        "old_str" to "new_str",
        "old_text" to "new_text",
        "oldText" to "newText",
    )

/**
 * The call's diffs. When an EDIT call reports none, they are rebuilt exactly from its raw input:
 * a write's contents count as a new file, every line added; a search-and-replace (or each entry of
 * an `edits` list) becomes a diff of the replaced text.
 */
internal fun ToolCallDisplay.diffs(): List<ToolCallContent.Diff> {
    val reported = content.orEmpty().filterIsInstance<ToolCallContent.Diff>()
    val path = editedPath()
    val input = rawInput as? JsonObject
    return if (reported.isEmpty() && path != null && input != null) input.inferredDiffs(path) else reported
}

// ponytail: a replace-all edit counts its replacement once; multiply by occurrences if that misleads.
private fun JsonObject.inferredDiffs(path: String): List<ToolCallContent.Diff> {
    val written = firstString(CONTENT_KEYS)
    val edits = (get("edits") as? JsonArray)?.filterIsInstance<JsonObject>() ?: listOf(this)
    return if (written != null) {
        listOf(ToolCallContent.Diff(path = path, oldText = null, newText = written))
    } else {
        edits.mapNotNull { it.replacement(path) }
    }
}

private fun JsonObject.replacement(path: String): ToolCallContent.Diff? =
    REPLACEMENT_KEYS.firstNotNullOfOrNull { (oldKey, newKey) ->
        val old = firstString(listOf(oldKey))
        val new = firstString(listOf(newKey))
        if (old != null && new != null) ToolCallContent.Diff(path = path, oldText = old, newText = new) else null
    }

/** The file an EDIT call names in its raw input, whether or not it reported what it changed. */
private fun ToolCallDisplay.editedPath(): String? =
    (rawInput as? JsonObject)?.firstString(PATH_KEYS)?.takeIf { kind == ToolKind.EDIT }

/**
 * Every file an EDIT call says it touched: its raw-input path, or else its ACP `locations`, which
 * name files even for agents that report nothing else about a write.
 */
private fun ToolCallDisplay.editedPaths(): List<String> =
    editedPath()?.let(::listOf) ?: locations.orEmpty().takeIf { kind == ToolKind.EDIT }.orEmpty()

private val FILE_KINDS = setOf(ToolKind.READ, ToolKind.EDIT, ToolKind.DELETE, ToolKind.MOVE)

/** The file a read, edit, delete or move works on, from its raw input or else its first location. */
internal fun ToolCallDisplay.filePath(): String? =
    ((rawInput as? JsonObject)?.firstString(PATH_KEYS) ?: locations?.firstOrNull())
        ?.takeIf { kind in FILE_KINDS && it.isNotBlank() }

/** The call's title, with its file's name added when the agent's title leaves it out ("Read" → "Read a.kt"). */
internal fun ToolCallDisplay.titleWithFile(): String {
    val name = filePath()?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/')
    return if (name.isNullOrEmpty() || title.contains(name)) title else "$title $name"
}

/** The title without its file ("Read src/A.kt" → "Read"), for a row that shows the file as a [FileChip]. */
internal fun ToolCallDisplay.titleWithoutFile(): String {
    val name = filePath()?.fileName()?.takeIf { it.isNotEmpty() } ?: return title
    return title.replaceFirst(Regex("""\S*${Regex.escape(name)}\S*"""), "").trim()
}

/**
 * Git's entry for [path], from the working tree as it is now. [path] is usually absolute and git's
 * relative to the repo root, so a suffix on a path boundary is a match; the longest such entry wins,
 * so `src/X.kt` is not mistaken for a root `X.kt`.
 */
internal fun List<ChatChangedFile>.entryFor(path: String): ChatChangedFile? = matching(path, dirs = false)

/** Git's counts for [path]; see [entryFor] for how it is matched. Unlike it, a directory entry counts. */
internal fun List<ChatChangedFile>.statsFor(path: String): DiffStats? =
    matching(path, dirs = true)?.let { DiffStats(additions = it.added, deletions = it.removed) }

private fun List<ChatChangedFile>.matching(
    path: String,
    dirs: Boolean,
): ChatChangedFile? {
    val normalized = path.replace('\\', '/')
    return filter {
        !it.binary && (dirs || !it.isDir) && (normalized == it.path || normalized.endsWith("/" + it.path))
    }.maxByOrNull { it.path.length }
}

/** Git's status and counts for an entry: a request for the same file at a different value refetches. */
private fun ChatChangedFile.revision() = "$status:$added:$removed"

/**
 * The working tree's changed files, provided to the newest turn only: git reports the tree as it is
 * now, so counts it gave an older turn would include everything since.
 */
internal val LocalWorkingTreeChanges = compositionLocalOf { emptyList<ChatChangedFile>() }

/**
 * Git's changed files that can stand for the newest turn's work: a file already changed when the
 * turn started would mix earlier work into its diff and counts, so it is left out.
 */
internal fun ChatState.turnWorkingTree(): List<ChatChangedFile> {
    val tree = gitStatus?.changed.orEmpty()
    val dirty = turnDirtyAtStart ?: return tree
    return tree.filterNot { it.path in dirty }
}

/**
 * Working-tree diffs backing the latest turn's files whose tool calls reported
 * none, with the request that loads one. Provided where the chat state is at
 * hand; empty (no-op request) anywhere else, e.g. previews.
 */
internal data class TurnGitFallback(
    val diffs: Map<String, List<ToolCallContent.Diff>> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val onRequest: (path: String, oldPath: String?, revision: String) -> Unit = { _, _, _ -> },
    /** Re-reads git's status, so a file the running turn just changed is listed. */
    val onRefresh: () -> Unit = {},
)

internal val LocalTurnGitFallback = compositionLocalOf { TurnGitFallback() }

private fun JsonObject.firstString(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { key -> (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content }

/** A failed call's change never happened, and one awaiting permission has not happened yet. */
internal fun ToolCallDisplay.landed(): Boolean = status != ToolCallStatus.FAILED && permissionOptions.isNullOrEmpty()

/** One file a turn changed: the diffs the turn reported for it (maybe none), and the last call that touched it. */
internal class FileChange(
    val path: String,
    val diffs: List<ToolCallContent.Diff>,
    val segmentId: String,
)

/**
 * Git's counts when the working tree has the file (newest turn only), else the reported diffs:
 * summing every edit's diff overstates a file edited more than once.
 */
internal fun FileChange.stats(tree: List<ChatChangedFile>): DiffStats? =
    tree.statsFor(path) ?: diffs.takeIf { it.isNotEmpty() }?.computeDiffStats()

/**
 * The files a turn changed, in the order each was first touched. A failed call's diff never
 * landed, so it is left out. ponytail: repeated edits to one file sum their counts rather than
 * netting them; diff first-old against last-new if that ever misleads.
 */
internal fun List<AssistantSegment>.fileChanges(): List<FileChange> {
    val byPath = LinkedHashMap<String, FileChange>()
    for (segment in this) {
        val call = segment.toolCall?.takeIf { it.landed() } ?: continue
        val diffs = call.diffs()
        // An edit with nothing to count still changed its file: listed, with no diff blocks.
        if (diffs.isEmpty()) {
            call.editedPaths().forEach { path ->
                byPath[path] = FileChange(path, byPath[path]?.diffs.orEmpty(), segment.id)
            }
        }
        diffs.forEach { diff ->
            byPath[diff.path] = FileChange(diff.path, byPath[diff.path]?.diffs.orEmpty() + diff, segment.id)
        }
    }
    return byPath.values.toList()
}

/**
 * The turn's close: how long it ran and when it finished, then its edits, "3 files changed" with the
 * total diff, folding open into one row per file. A file whose calls reported no diff takes git's
 * counts while it is the newest turn, and otherwise shows none. With no edits, only the time.
 */
@Composable
internal fun TurnChanges(
    segments: List<AssistantSegment>,
    turn: TurnTiming?,
    onToolCallClick: (String) -> Unit,
    foldsInFlight: MutableIntState,
    modifier: Modifier = Modifier,
) {
    val files = remember(segments) { segments.fileChanges() }
    val timing = turn?.let { turnSummary(it) }
    val separator = stringResource(R.string.chat_turn_separator)
    if (files.isEmpty()) {
        timing?.let {
            PhrasesText(
                text = it,
                separator = separator,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = modifier.padding(vertical = 4.dp),
            )
        }
        return
    }
    val tree = LocalWorkingTreeChanges.current
    val stats =
        remember(files, tree) {
            files.map { it.stats(tree) }
        }
    val counted = stats.filterNotNull()
    val total =
        counted.takeIf { it.isNotEmpty() }?.let {
            DiffStats(additions = it.sumOf { s -> s.additions }, deletions = it.sumOf { s -> s.deletions })
        }
    // One counted file's stats would repeat the header's total, so only the header shows them.
    val rowStats = counted.size > 1
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        ToolGroupHeader(
            // "3 files changed", not "Edited 3 files": an outcome, so it doesn't read as one more call.
            summary =
                joinPhrases(
                    listOfNotNull(
                        timing,
                        pluralStringResource(R.plurals.chat_git_files_changed, files.size, files.size),
                    ),
                    separator,
                ),
            separator = separator,
            stats = total,
            open = open,
            active = false,
            onClick = { open = !open },
            chevronAtEnd = true,
        )
        FoldBody(open = open, foldsInFlight = foldsInFlight) {
            SettledRail(rowCount = files.size) {
                files.forEachIndexed { index, file ->
                    // Shaped like a rail row: the branch leads straight into the path.
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(RAIL_ROW_HEIGHT)
                                .handCursor()
                                .clickable { onToolCallClick(file.segmentId) }
                                .padding(start = RAIL_TEXT_START),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileChip(file.path)
                        Text(
                            text = file.path.replace('\\', '/').substringBeforeLast('/', ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        stats[index]?.takeIf { rowStats }?.let {
                            DiffStatsRow(it, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the tool call sheet's diff slot shows for an EDIT call with no exact diff of its own: none
 * reported, and none rebuilt from its input but a whole-file write, which cannot tell an overwrite
 * from a new file. It is the working tree's diffs for the files the call touched, requested on first
 * display and again whenever git's entry for a file changes. Empty until git answers, for a file git
 * no longer lists (committed or reverted), and wherever no tree is provided (see [turnWorkingTree]).
 */
@Composable
internal fun ToolCallDisplay.gitFallbackDiffs(): List<ToolCallContent.Diff> {
    val tree = LocalWorkingTreeChanges.current
    val fallback = LocalTurnGitFallback.current
    val wanted =
        remember(this) {
            kind == ToolKind.EDIT &&
                content.orEmpty().none { it is ToolCallContent.Diff } &&
                diffs().all { it.oldText == null }
        }
    // The status may predate this call, so it is re-read once when the sheet opens.
    LaunchedEffect(wanted) { if (wanted) fallback.onRefresh() }
    val entries =
        remember(this, tree, wanted) {
            if (wanted) {
                editedPaths().mapNotNull { tree.entryFor(it) }.distinctBy { it.path }
            } else {
                emptyList()
            }
        }
    LaunchedEffect(entries) {
        entries.forEach { fallback.onRequest(it.path, it.oldPath, it.revision()) }
    }
    return entries.flatMap { fallback.diffs[it.path].orEmpty() }
}

private fun ToolCallContent.Diff.lineCount() = maxOf(newText.count { it == '\n' }, oldText?.count { it == '\n' } ?: 0)

/** "Brewed for 1m 20s · done 2:24 AM": the verb is seeded by the turn, so it stays put across reloads. */
@Composable
private fun turnSummary(turn: TurnTiming): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    val verb =
        remember(turn.startedAt, resources) {
            val verbs = resources.getStringArray(R.array.chat_turn_verbs)
            verbs[Random(turn.startedAt).nextInt(verbs.size)]
        }
    val elapsed = elapsedOf((turn.finishedAt - turn.startedAt).coerceAtLeast(MS_PER_SECOND)) ?: Elapsed(0, 0, 1)
    // Today reads as a time of day; anything older needs its date too.
    val flags =
        DateUtils.FORMAT_SHOW_TIME or
            if (DateUtils.isToday(turn.finishedAt)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    val done = remember(turn.finishedAt, flags) { DateUtils.formatDateTime(context, turn.finishedAt, flags) }
    return joinPhrases(
        listOf(
            stringResource(R.string.chat_turn_took, verb, formatElapsed(elapsed, MeasureFormat.FormatWidth.NARROW)),
            stringResource(R.string.chat_turn_done, done),
        ),
        stringResource(R.string.chat_turn_separator),
    )
}
