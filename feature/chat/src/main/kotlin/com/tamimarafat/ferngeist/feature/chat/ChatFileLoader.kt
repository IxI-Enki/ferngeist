package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.feature.chat.ui.diffLines
import com.tamimarafat.ferngeist.feature.chat.ui.entryFor
import io.github.diff.DeltaType
import io.github.diff.generatePatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The file sheet's file: [path] as the transcript named it. */
data class FileView(
    val path: String,
    val isLoading: Boolean = true,
    val text: String? = null,
    /** Lines (0-based) added or changed since `HEAD`. */
    val changedLines: Set<Int> = emptySet(),
    /** Lines that deleted lines sat just above; the line count for lines deleted at the end. */
    val deletedAbove: Set<Int> = emptySet(),
    /** Cut at the gateway's 1 MiB limit. */
    val truncated: Boolean = false,
    val binary: Boolean = false,
    val error: String? = null,
)

/**
 * Reads the file a transcript chip opens into [ChatState.openFile]: git's diff when the working
 * tree has the file changed, whose new side is the whole file and whose old side marks what
 * changed, else the plain file.
 */
internal class ChatFileLoader(
    private val scope: CoroutineScope,
    private val facade: ChatSessionFacade,
    private val update: (ChatState.() -> ChatState) -> Unit,
    /** The project directory the gateway reads paths relative to. */
    private val cwd: String,
    private val tree: () -> List<ChatChangedFile>,
) {
    /** The read in flight; a newer open or a close replaces it, and a replaced read never lands. */
    private var job: Job? = null

    fun open(path: String) {
        job?.cancel()
        val relative = path.relativeTo(cwd)
        if (relative == null) {
            job = null
            update { copy(openFile = FileView(path, isLoading = false, error = "$path is outside the project")) }
            return
        }
        update { copy(openFile = FileView(path)) }
        job =
            scope.launch {
                val result = runCatching { read(relative) }
                if (job !== coroutineContext[Job]) return@launch
                job = null
                val file =
                    result.fold(
                        onSuccess = {
                            it?.copy(
                                path = path,
                            ) ?: FileView(path, isLoading = false, error = NO_WORKSPACE)
                        },
                        onFailure = { error ->
                            if (error is CancellationException) return@launch
                            val message = error.message?.takeIf { it.isNotBlank() } ?: "Failed to load $path"
                            FileView(path, isLoading = false, error = message)
                        },
                    )
                update { copy(openFile = file) }
            }
    }

    fun close() {
        job?.cancel()
        job = null
        update { copy(openFile = null) }
    }

    private suspend fun read(relative: String): FileView? {
        val entry = tree().entryFor(relative)?.takeIf { it.status != "D" }
        if (entry != null) {
            val diff = facade.fetchGitDiff(entry.path, oldPath = entry.oldPath)?.firstOrNull() ?: return null
            return markedFile(diff.oldText, diff.newText)
        }
        val file = facade.fetchFile(relative) ?: return null
        return FileView("", isLoading = false, text = file.text, binary = file.text == null, truncated = file.truncated)
    }

    private companion object {
        const val NO_WORKSPACE = "No gateway workspace connection to read the file"
    }
}

/**
 * [this] relative to the project directory [cwd], as the gateway reads it; null for an absolute
 * path outside it, which the gateway would refuse.
 */
internal fun String.relativeTo(cwd: String): String? {
    val path = replace('\\', '/')
    val root = cwd.replace('\\', '/').trimEnd('/')
    return when {
        root.isNotEmpty() && path.startsWith("$root/") -> path.removePrefix("$root/")
        path.startsWith('/') || WINDOWS_ROOT.containsMatchIn(path) -> null
        else -> path.removePrefix("./")
    }
}

private val WINDOWS_ROOT = Regex("^[A-Za-z]:/")

/** [newText] as the whole file, its gutter marked with what changed since [oldText] (null: all new). */
internal fun markedFile(
    oldText: String?,
    newText: String,
): FileView {
    val newLines = newText.diffLines()
    val changed = HashSet<Int>()
    val deleted = HashSet<Int>()
    if (oldText == null) {
        changed += newLines.indices
    } else {
        val patch =
            generatePatch {
                original = oldText.diffLines()
                revised = newLines
            }
        patch.getDeltas().forEach { delta ->
            val target = delta.target
            when (delta.type) {
                DeltaType.DELETE -> deleted += target.position
                DeltaType.INSERT, DeltaType.CHANGE ->
                    changed +=
                        target.position until target.position + target.lines.size
                DeltaType.EQUAL -> Unit
            }
        }
    }
    return FileView("", isLoading = false, text = newText, changedLines = changed, deletedAbove = deleted)
}
