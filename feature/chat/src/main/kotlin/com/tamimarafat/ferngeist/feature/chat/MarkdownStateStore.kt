package com.tamimarafat.ferngeist.feature.chat

import com.mikepenz.markdown.model.parseMarkdownFlow
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mikepenz.markdown.model.State as MarkdownRenderState

/**
 * Maintains a cached set of parsed markdown states for assistant messages.
 *
 * Parsing is batched and throttled to avoid UI jank during streaming updates.
 */
internal class MarkdownStateStore(
    private val scope: CoroutineScope,
    private val currentMessages: () -> List<ChatMessage>,
    private val onMarkdownStatesChanged: (Map<String, MarkdownRenderState>) -> Unit,
    private val trace: (String) -> Unit,
) {
    companion object {
        private const val MARKDOWN_FLUSH_INTERVAL_MS = 28L
        private const val MARKDOWN_BATCH_SIZE = 24
        private const val MARKDOWN_STATE_EMIT_INTERVAL_MS = 120L
    }

    private var initialHydrated: Boolean = false
    private val markdownStateCache = linkedMapOf<String, MarkdownEntry>()
    private val pendingMarkdownQueue = linkedMapOf<String, String>()
    private val markdownParsingKeys = mutableSetOf<String>()
    private var markdownParserJob: Job? = null

    /**
     * Updates markdown cache for a new snapshot and returns the projection to render.
     *
     * During initial hydration, missing entries are pre-parsed synchronously so the
     * first render can show rich markdown without waiting for the scheduler.
     */
    suspend fun onSnapshot(
        messages: List<ChatMessage>,
        loadState: ChatLoadState,
    ): MarkdownStateProjection {
        val requiredEntries = collectRequiredEntries(messages)
        if (!initialHydrated && loadState != ChatLoadState.FAILED) {
            preparseMissingEntries(requiredEntries)
            if (loadState == ChatLoadState.READY) {
                initialHydrated = true
            }
        }

        val markdownStates = buildMarkdownEntries(messages)
        val pendingInitialHydration =
            loadState != ChatLoadState.FAILED &&
                !initialHydrated &&
                messages.isNotEmpty()
        return MarkdownStateProjection(
            markdownStates = markdownStates,
            pendingInitialHydration = pendingInitialHydration,
        )
    }

    /** Clears cached states and cancels any in-flight parsing work. */
    fun reset() {
        initialHydrated = false
        markdownParserJob?.cancel()
        markdownParserJob = null
        markdownStateCache.clear()
        pendingMarkdownQueue.clear()
        markdownParsingKeys.clear()
    }

    /**
     * Extracts assistant message content that requires markdown parsing.
     */
    private fun collectRequiredEntries(messages: List<ChatMessage>): LinkedHashMap<String, String> {
        val requiredEntries = linkedMapOf<String, String>()
        messages.forEach { message ->
            if (message.role != ChatMessage.Role.ASSISTANT) return@forEach
            if (message.segments.isNotEmpty()) {
                message.segments.forEach { segment ->
                    if (segment.kind == AssistantSegment.Kind.MESSAGE && segment.text.isNotBlank()) {
                        requiredEntries[segment.id] = segment.text
                    }
                }
            } else if (message.content.isNotBlank()) {
                requiredEntries[message.id] = message.content
            }
        }
        return requiredEntries
    }

    /**
     * Pre-parses any missing entries to minimize visible markdown "pop-in" on first load.
     */
    private suspend fun preparseMissingEntries(requiredEntries: Map<String, String>) {
        requiredEntries.forEach { (key, text) ->
            val cached = markdownStateCache[key]
            if (cached?.text == text) return@forEach
            try {
                val parsedState = parse(text)
                markdownStateCache[key] = MarkdownEntry(text = text, state = parsedState)
                pendingMarkdownQueue.remove(key)
                markdownParsingKeys.remove(key)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                trace("markdownPreparse:error key=$key message=${error.message}")
            }
        }
    }

    /**
     * Synchronizes the cache with current messages and schedules parsing for deltas.
     */
    private fun buildMarkdownEntries(messages: List<ChatMessage>): Map<String, MarkdownRenderState> {
        val requiredEntries = collectRequiredEntries(messages)
        val requiredKeys = requiredEntries.keys

        markdownStateCache.keys
            .toList()
            .filterNot(requiredKeys::contains)
            .forEach { markdownStateCache.remove(it) }
        pendingMarkdownQueue.keys
            .toList()
            .filterNot(requiredKeys::contains)
            .forEach { pendingMarkdownQueue.remove(it) }
        markdownParsingKeys.retainAll(requiredKeys)

        requiredEntries.forEach { (key, text) ->
            val cached = markdownStateCache[key]
            if (cached?.text == text) return@forEach
            pendingMarkdownQueue[key] = text
        }

        if (pendingMarkdownQueue.isNotEmpty()) {
            scheduleMarkdownParsing()
        }

        return requiredKeys
            .mapNotNull { key ->
                markdownStateCache[key]?.state?.let { key to it }
            }.toMap()
    }

    /**
     * Launches a background parser that drains the pending queue in batches.
     */
    private fun scheduleMarkdownParsing() {
        if (markdownParserJob?.isActive == true) return

        markdownParserJob =
            scope.launch {
                var lastEmitAtMs = 0L
                try {
                    while (pendingMarkdownQueue.isNotEmpty()) {
                        val batch = mutableListOf<Pair<String, String>>()
                        val iterator = pendingMarkdownQueue.entries.iterator()
                        while (iterator.hasNext() && batch.size < MARKDOWN_BATCH_SIZE) {
                            val entry = iterator.next()
                            iterator.remove()
                            batch += entry.key to entry.value
                            markdownParsingKeys += entry.key
                        }

                        val parsedBatch = mutableListOf<Pair<String, MarkdownEntry>>()
                        batch.forEach { (key, text) ->
                            try {
                                val parsedState = parse(text)
                                parsedBatch += key to MarkdownEntry(text = text, state = parsedState)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                trace("markdownParse:error key=$key message=${error.message}")
                            } finally {
                                markdownParsingKeys.remove(key)
                            }
                        }

                        parsedBatch.forEach { (key, entry) ->
                            val queuedOverride = pendingMarkdownQueue[key]
                            if (queuedOverride == null || queuedOverride == entry.text) {
                                markdownStateCache[key] = entry
                            }
                        }

                        val now = System.currentTimeMillis()
                        val shouldEmitState =
                            pendingMarkdownQueue.isEmpty() ||
                                (now - lastEmitAtMs) >= MARKDOWN_STATE_EMIT_INTERVAL_MS
                        if (shouldEmitState) {
                            lastEmitAtMs = now
                            publishMarkdownStatesForCurrentMessages()
                        }

                        if (pendingMarkdownQueue.isNotEmpty()) {
                            delay(MARKDOWN_FLUSH_INTERVAL_MS)
                        }
                    }
                } finally {
                    markdownParserJob = null
                }
            }
    }

    /**
     * Parses markdown text and returns the first non-loading state.
     */
    private suspend fun parse(text: String): MarkdownRenderState =
        withContext(Dispatchers.Default) {
            parseMarkdownFlow(text)
                .first { it !is MarkdownRenderState.Loading }
        }

    /** Emits markdown states for the currently rendered messages. */
    private fun publishMarkdownStatesForCurrentMessages() {
        onMarkdownStatesChanged(buildMarkdownEntries(currentMessages()))
    }

    private data class MarkdownEntry(
        val text: String,
        val state: MarkdownRenderState,
    )
}

/** Snapshot of markdown state used to render the current message list. */
internal data class MarkdownStateProjection(
    val markdownStates: Map<String, MarkdownRenderState>,
    val pendingInitialHydration: Boolean,
)
