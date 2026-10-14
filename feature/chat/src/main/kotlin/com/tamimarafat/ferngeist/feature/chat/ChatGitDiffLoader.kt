package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads the gateway-backed working tree's git status and per-file diff into [ChatState],
 * keeping the in-flight request bookkeeping out of [ChatViewModel].
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
                            gitFileDiffError = "No gateway workspace connection to load git diff for $path",
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
                val message =
                    throwable.message?.takeIf { it.isNotBlank() }
                        ?: "Failed to load git diff for $path"
                update {
                    copy(
                        gitFileDiff = null,
                        isGitFileDiffLoading = false,
                        gitFileDiffError = message,
                    )
                }
            }
    }
}
