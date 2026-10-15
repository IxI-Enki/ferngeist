package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads the gateway-backed working tree's git status, the per-file diff behind the git sheet, and
 * the latest turn's inline fallback diffs into [ChatState], keeping the in-flight request
 * bookkeeping out of [ChatViewModel].
 */
internal class ChatGitDiffLoader(
    private val scope: CoroutineScope,
    private val facade: ChatSessionFacade,
    private val update: (ChatState.() -> ChatState) -> Unit,
    /** The ACP session this chat shows, scoping workspace calls to its cwd; null before it is minted. */
    private val acpSessionId: () -> String?,
) {
    /**
     * In-flight diff fetch. Cancelling it on a newer request (and checking it after the
     * suspend call) guarantees a stale response can never overwrite the state a newer
     * request set.
     */
    private var job: Job? = null

    /**
     * The latest turn's inline fallback fetches, one per git-relative path and independent of the
     * sheet's single-file [job]. A path's in-flight fetch is replaced only by a request for a newer
     * [revision]; [turnLoaded] remembers the revision each path's stored diff was read at. Touched
     * only on [scope]'s (main) thread and without suspending between check and write, so the
     * [mutex] is not needed here.
     */
    private class TurnFetch(
        val job: Job,
        val revision: String,
    )

    private val turnInFlight = mutableMapOf<String, TurnFetch>()

    /** Each loaded path's request (old path and revision), so a status refresh can re-read it. */
    private val turnLoaded = mutableMapOf<String, Pair<String?, String>>()

    /**
     * Serializes request setup (job cancellation, state initialization, job assignment)
     * and the post-fetch identity check, so rapid intents cannot race through those steps.
     * `withLock` keeps the holder cancellable, and the identity guard inside the lock
     * retains latest-wins.
     */
    private val mutex = Mutex()

    /**
     * Fetches the git status for a gateway-backed working tree and stores line-based
     * add/delete totals in [ChatState.gitDiffStats]. The gateway returns per-file
     * `added`/`removed` counts (from `git diff --numstat`, with untracked files counted
     * from disk), so the totals are git's own authoritative numbers.
     *
     * Errors keep the previous value so a transient poll failure does not clear the
     * indicator.
     */
    fun refresh() {
        // Nothing to scope to until create-on-arrival mints a session; sessionReady retries.
        if (acpSessionId() == null) return
        scope.launch {
            runCatching { facade.fetchGitStatus() }
                .onSuccess { status ->
                    if (status == null) return@onSuccess
                    val stats =
                        GitDiffStats(
                            additions = status.changed.sumOf { it.added },
                            deletions = status.changed.sumOf { it.removed },
                        )
                    update { copy(gitStatus = status, gitDiffStats = stats) }
                    // Git's counts can stay put while the text changes, so every refresh re-reads
                    // the turn's diffs already shown.
                    turnLoaded.toMap().forEach { (path, request) ->
                        turnLoaded.remove(path)
                        loadTurnFile(path, request.first, request.second)
                    }
                }
        }
    }

    /**
     * Fetches the diff for a single file in the gateway-backed working tree, or in
     * [commit] against its first parent, and stores it in [ChatState.gitFileDiff] (the UI renders the first
     * item).
     *
     * When the chat has no gateway workspace, or the fetch throws, the requested
     * path is retained with a nonblank error so the UI can retry the same file.
     */
    suspend fun load(
        path: String,
        commit: String? = null,
        oldPath: String? = null,
    ) {
        mutex.withLock {
            job?.cancel()
            update {
                copy(
                    gitFileDiffPath = path,
                    gitFileDiffCommit = commit,
                    isGitFileDiffLoading = true,
                    gitFileDiffError = null,
                    gitFileDiff = null,
                )
            }
            job =
                scope.launch {
                    val currentJob = coroutineContext[Job]
                    val result = runCatching { facade.fetchGitDiff(path, commit, oldPath) }
                    mutex.withLock {
                        if (currentJob != job) return@withLock
                        handleResult(result, path)
                    }
                }
        }
    }

    private fun handleResult(
        result: Result<List<ToolCallContent.Diff>?>,
        path: String,
    ) {
        result
            .onSuccess { diffs ->
                if (diffs == null) {
                    // No gateway workspace to read: keep the path so the UI can retry
                    // the same file once a runtime is up.
                    update {
                        copy(
                            gitFileDiff = null,
                            isGitFileDiffLoading = false,
                            gitFileDiffError = noWorkspaceMessage(path),
                        )
                    }
                    return@onSuccess
                }
                update {
                    copy(
                        gitFileDiff = diffs,
                        isGitFileDiffLoading = false,
                        gitFileDiffError = null,
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return
                val message = failureMessage(throwable, path)
                update {
                    copy(
                        gitFileDiff = null,
                        isGitFileDiffLoading = false,
                        gitFileDiffError = message,
                    )
                }
            }
    }

    /**
     * Fetches one latest-turn file's working-tree diff into [ChatState.turnGitDiffs], for inline
     * display when its tool calls reported none. [revision] identifies the git entry the request
     * was made for (e.g. its status and counts): the same revision is never fetched twice, whether
     * answered or in flight, and a newer one replaces the stored diff, so the diff cannot go stale
     * behind counts that moved on. A failed path retries on the next call.
     */
    fun loadTurnFile(
        path: String,
        oldPath: String? = null,
        revision: String = "",
    ) {
        if (path.isBlank() || turnLoaded[path]?.second == revision) return
        val inFlight = turnInFlight[path]
        if (inFlight?.revision == revision) return
        inFlight?.job?.cancel()
        update {
            copy(
                turnGitDiffsLoading = turnGitDiffsLoading + path,
                turnGitDiffsErrors = turnGitDiffsErrors - path,
            )
        }
        // Lazy so the entry is registered before the body can run: on an immediate dispatcher the
        // body would otherwise check the map before this call had stored its job.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val result = runCatching { facade.fetchGitDiff(path, null, oldPath) }
                // Replaced or cleared while fetching: a newer request owns the path's state.
                if (turnInFlight[path]?.job !== coroutineContext[Job]) return@launch
                turnInFlight.remove(path)
                handleTurnResult(result, path, oldPath, revision)
            }
        turnInFlight[path] = TurnFetch(job, revision)
        job.start()
    }

    private fun handleTurnResult(
        result: Result<List<ToolCallContent.Diff>?>,
        path: String,
        oldPath: String?,
        revision: String,
    ) {
        result
            .onSuccess { diffs ->
                if (diffs == null) {
                    update {
                        copy(
                            turnGitDiffsLoading = turnGitDiffsLoading - path,
                            turnGitDiffsErrors = turnGitDiffsErrors + (path to noWorkspaceMessage(path)),
                        )
                    }
                    return@onSuccess
                }
                turnLoaded[path] = oldPath to revision
                update {
                    copy(
                        turnGitDiffs = turnGitDiffs + (path to diffs),
                        turnGitDiffsLoading = turnGitDiffsLoading - path,
                    )
                }
            }.onFailure { throwable ->
                // Only the whole scope dying cancels a fetch that still owns its path.
                if (throwable is CancellationException) return@onFailure
                update {
                    copy(
                        turnGitDiffsLoading = turnGitDiffsLoading - path,
                        turnGitDiffsErrors = turnGitDiffsErrors + (path to failureMessage(throwable, path)),
                    )
                }
            }
    }

    /**
     * Drops the inline fallback diffs when a new turn starts: they describe the
     * tree as it is now, so the next turn must not borrow them. Also notes which files
     * were already changed, whose git diff would mix earlier work into this turn's.
     */
    fun clearTurnDiffs() {
        turnInFlight.values.forEach { it.job.cancel() }
        turnInFlight.clear()
        turnLoaded.clear()
        update {
            copy(
                turnGitDiffs = emptyMap(),
                turnGitDiffsLoading = emptySet(),
                turnGitDiffsErrors = emptyMap(),
                turnDirtyAtStart = gitStatus?.changed?.mapTo(HashSet()) { it.path },
            )
        }
    }

    private fun noWorkspaceMessage(path: String) = "No gateway workspace connection to load git diff for $path"

    private fun failureMessage(
        throwable: Throwable,
        path: String,
    ) = throwable.message?.takeIf { it.isNotBlank() } ?: "Failed to load git diff for $path"
}
