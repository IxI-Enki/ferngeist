package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Thin adapter that wraps [ChatSessionFacade] and exposes a callback-based
 * API for [ChatViewModel].
 *
 * All ACP-specific logic lives in the facade implementation
 * ([com.tamimarafat.ferngeist.acp.bridge.facade.AcpChatSessionFacade]).
 * This coordinator has zero ACP imports.
 */
internal class ChatSessionCoordinator(
    scope: CoroutineScope,
    private val facade: ChatSessionFacade,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** Emitted before any snapshot is delivered. */
        suspend fun onLoadStarted()
        /** Called for every session snapshot update. */
        suspend fun onSnapshot(snapshot: ChatSessionSnapshot)
        /** Emitted when the session has finished loading and is ready for use. */
        suspend fun onSessionReady()
        /** Persists newly created session metadata. */
        suspend fun onSessionStored(sessionId: String, cwd: String, updatedAt: Long)
        /** Surfaces a fatal load error to the UI. */
        suspend fun onLoadFailed(message: String)
        /** Surfaces a non-fatal operational error to the UI. */
        suspend fun onOperationError(message: String, stopStreaming: Boolean)
        /** Emitted when a streaming cancel completes. */
        suspend fun onStreamingCancelled()
        /** Emitted when the server does not support cancellation. */
        suspend fun onCancelUnsupported()
        /** Emitted when a model change is confirmed by the server. */
        suspend fun onModelUpdated()
        /** Updates UI capability flags (images, embedded context, etc.). */
        suspend fun onCapabilitiesChanged(capabilities: ChatAgentCapabilities)
    }

    init {
        scope.launch {
            facade.connectionState.collect { state ->
                // Let the facade translate connection state into recovery decisions.
                facade.onConnectionStateChanged(state)
            }
        }
        scope.launch {
            facade.sessionSnapshot.collect { snapshot ->
                if (snapshot != null) callbacks.onSnapshot(snapshot)
            }
        }
        scope.launch {
            facade.agentCapabilities.collect { caps ->
                callbacks.onCapabilitiesChanged(caps)
            }
        }
        scope.launch {
            facade.sessionReady.collect {
                callbacks.onSessionReady()
            }
        }
        scope.launch {
            facade.loadFailed.collect { message ->
                callbacks.onLoadFailed(message)
            }
        }
        scope.launch {
            facade.operationError.collect { error ->
                callbacks.onOperationError(error.message, error.stopStreaming)
            }
        }
        scope.launch {
            facade.streamingCancelled.collect {
                callbacks.onStreamingCancelled()
            }
        }
        scope.launch {
            facade.cancelUnsupported.collect {
                callbacks.onCancelUnsupported()
            }
        }
        scope.launch {
            facade.modelUpdated.collect {
                callbacks.onModelUpdated()
            }
        }
    }

    /** Starts session load, then delegates to the facade. */
    suspend fun loadSession() {
        callbacks.onLoadStarted()
        facade.loadSession()
    }

    /** Sends a chat message via the facade. */
    suspend fun sendMessage(text: String, images: List<ChatImageData>) {
        facade.sendMessage(text, images)
    }

    /** Requests a streaming cancellation. */
    suspend fun cancelStreaming() {
        facade.cancelStreaming()
    }

    /** Updates a session configuration option. */
    suspend fun setConfigOption(optionId: String, value: ChatConfigValue) {
        facade.setConfigOption(optionId, value)
    }

    /** Grants a permission prompt for a tool call. */
    suspend fun grantPermission(toolCallId: String, optionId: String) {
        facade.grantPermission(toolCallId, optionId)
    }

    /** Denies a permission prompt for a tool call. */
    suspend fun denyPermission(toolCallId: String) {
        facade.denyPermission(toolCallId)
    }

    /** Clears any facade resources when the view model is torn down. */
    fun clear() {
        facade.clear()
    }
}
