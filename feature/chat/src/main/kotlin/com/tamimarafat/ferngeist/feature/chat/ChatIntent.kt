package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatElicitationValue
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData

/** User intents emitted from the chat UI. */
sealed interface ChatIntent {
    data class SendMessage(
        val text: String,
        val images: List<ChatImageData> = emptyList(),
        val files: List<ChatFileData> = emptyList(),
    ) : ChatIntent

    data object CancelStreaming : ChatIntent

    data class SetConfigOption(
        val optionId: String,
        val value: ChatConfigValue,
    ) : ChatIntent

    data class GrantPermission(
        val toolCallId: String,
        val optionId: String,
    ) : ChatIntent

    data class DenyPermission(
        val toolCallId: String,
    ) : ChatIntent

    data class SubmitElicitation(
        val key: String,
        val values: Map<String, ChatElicitationValue>,
    ) : ChatIntent

    data class DeclineElicitation(
        val key: String,
    ) : ChatIntent

    data class CancelElicitation(
        val key: String,
    ) : ChatIntent

    data object RetryLoad : ChatIntent

    /** Retry sending a previously failed (or queued) message identified by its [clientId]. */
    data class RetryMessage(
        val clientId: String,
    ) : ChatIntent

    /** Stop the turn and send this QUEUED prompt, with those queued ahead of it, right away. */
    data class ForceSendMessage(
        val clientId: String,
    ) : ChatIntent

    /** Drop a QUEUED or FAILED prompt without sending it. */
    data class RemoveQueuedMessage(
        val clientId: String,
    ) : ChatIntent

    /** Re-fetch the git status, and history once it is loaded, for the gateway-backed working tree (e.g. after a turn). */
    data object RefreshGitStatus : ChatIntent

    /**
     * Load the diff for a single changed file from the gateway-backed working tree, or from
     * [commit] against its first parent. [oldPath] names a renamed file's old side.
     */
    data class LoadGitDiff(
        val path: String,
        val commit: String? = null,
        val oldPath: String? = null,
    ) : ChatIntent

    /** Reload history from `HEAD`, keeping the current list until it lands, or append the next page ([more]). */
    data class LoadGitLog(
        val more: Boolean,
    ) : ChatIntent

    /** Load the files a commit changed. */
    data class LoadGitCommit(
        val sha: String,
    ) : ChatIntent

    /** Consumes the switcher bubble's first-run swipe hint so it never shows again. */
    data object MarkSwitcherHintSeen : ChatIntent

    /**
     * Releases another live session the switcher listed, the way the session
     * list's Disconnect does. Carries the row's own ids: the sheet lists every
     * warm server, so the target is not always this chat's server.
     */
    data class CloseSession(
        val serverId: String,
        val sessionId: String,
    ) : ChatIntent
}
