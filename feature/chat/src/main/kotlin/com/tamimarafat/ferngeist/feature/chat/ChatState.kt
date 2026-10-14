package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.UsageState

/** UI state for the chat screen. */
data class ChatState(
    val serverId: String = "",
    val title: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pendingMessages: List<ChatMessage> = emptyList(),
    /**
     * Transcript list key per message id, where it differs from the id: the reducer's echo of a
     * sent prompt has a new id, and keeps the pending bubble's key so the list moves the same
     * item instead of swapping one for another.
     */
    val listKeys: Map<String, String> = emptyMap(),
    val markdownDocuments: Map<String, MarkdownRenderedDocument> = emptyMap(),
    val restoredScrollSnapshot: ChatScrollSnapshot? = null,
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val isSessionReady: Boolean = false,
    val canCancelStreaming: Boolean = true,
    val connectionState: ChatConnectionState = ChatConnectionState.Disconnected,
    val connectionDiagnostics: ChatConnectionDiagnostics = ChatConnectionDiagnostics(),
    val configOptions: List<ChatConfigOption> = emptyList(),
    val usage: UsageState? = null,
    val availableCommands: List<ChatCommand> = emptyList(),
    val commandsAdvertised: Boolean = false,
    val pendingElicitations: List<ChatElicitationRequest> = emptyList(),
    val canSendImages: Boolean = false,
    val supportsEmbeddedContext: Boolean = false,
    val supportsSteering: Boolean = false,
    /**
     * True when this screen attached to an existing session whose agent cannot
     * replay history, so earlier turns exist only on the agent's side and the
     * transcript starts empty. Drives the top-of-transcript resumed-session notice.
     */
    val resumedSession: Boolean = false,
    val gatewayWorkspaceConnection: GatewayWorkspaceConnection? = null,
    val gitStatus: ChatGitStatus? = null,
    val gitDiffStats: GitDiffStats? = null,
    val gitFileDiff: List<ToolCallContent.Diff>? = null,
    val gitFileDiffPath: String? = null,
    val isGitFileDiffLoading: Boolean = false,
    val gitFileDiffError: String? = null,
    /** The commit [gitFileDiff] was read from; null for the working tree. */
    val gitFileDiffCommit: String? = null,
    val gitHistory: GitHistoryState = GitHistoryState(),
    val error: String? = null,
    /**
     * Registry logo URL for this chat's agent, for identity marks like the
     * empty-session hero. Null for manual agents or when unresolved.
     */
    val iconUrl: String? = null,
)

/** The git sheet's History tab and the commit it has open. */
data class GitHistoryState(
    /** The sha page 1 was read from, pinned for later pages; null before then or for an empty repository. */
    val head: String? = null,
    val commits: List<ChatGitCommit> = emptyList(),
    val hasMore: Boolean = false,
    val remoteUrl: String? = null,
    /** Page 1 has answered at least once, so an empty [commits] means an empty repository. */
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Page 1 failed. */
    val error: String? = null,
    /** A later page failed; the list stays and offers a retry at its end. */
    val moreError: String? = null,
    /** The gateway predates history, so the sheet hides the tab. */
    val unsupported: Boolean = false,
    val commitSha: String? = null,
    val commitFiles: List<ChatChangedFile>? = null,
    val isCommitLoading: Boolean = false,
    val commitError: String? = null,
) {
    /** A next page exists from a pinned head and no page is in flight. */
    val canLoadMore: Boolean get() = hasMore && head != null && !isLoading && !isLoadingMore
}
