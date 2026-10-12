package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ChatScrollSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/** Duration of the shortest jump-button scroll; longer ones scale up to [EASE_MAX_MS]. */
internal const val EASE_MIN_MS = 650

/** Duration of a jump that travels the full [EASE_MAX_VIEWPORTS]. */
private const val EASE_MAX_MS = 1000

/**
 * Farthest a jump eases, in viewports. A farther target is first placed this far away in one
 * frame, so the ease never has to compose the whole transcript.
 */
private const val EASE_MAX_VIEWPORTS = 2.5f

/** Duration of a short settle onto the end, for a jump chasing a stream. */
private const val SETTLE_MS = 220

/** Most chase eases a jump to the bottom runs after its main ease; following takes over after. */
private const val CHASE_PASSES = 3

/** Coming to rest this close to the end hands the transcript back to following. */
private val RELATCH_DISTANCE = 48.dp

/** Quiet frames the follow keeps pinning after the last growth, for animations still settling. */
private const val IDLE_FRAMES = 30

/** Share of the remaining distance a glide covers per 60 fps frame. */
private const val GLIDE_PER_FRAME = 0.15f

private const val PIN_EPSILON_PX = 0.5f
private const val NANOS_PER_SECOND = 1e9f
private const val MAX_FRAME_SECONDS = 1f / 30f
private const val FRAMES_PER_SECOND = 60f

/**
 * The transcript's scroll model: following the end, or holding wherever the reader left it.
 *
 * A top-anchored list holds still when content grows, which is already right for a reader
 * scrolled up. While following, one frame loop pins the list onto its end every frame something
 * can grow; the pin is a position request, so it lands in the same measure as the growth. A user
 * scroll stops following; coming to rest near the end resumes it.
 *
 * The runway: a sent prompt glides to the top of the viewport, and the rest of the viewport below
 * it is reserved, so the reply streams into still space instead of moving the view on every line.
 * The reserve is a minimum height on the reply's row (on the bottom spacer until the reply has a
 * row), so it is resolved in the same measure as the reply's growth; the end pin then holds the
 * prompt at the top until the reply outgrows the reserve. The space stays as scroll room until the
 * next send.
 */
internal class ChatScrollHandle(
    val listState: LazyListState,
) {
    var following by mutableStateOf(true)
        private set

    /** The prompt holding the runway, by message id. */
    var runwayMessageId by mutableStateOf<String?>(null)
        private set

    /** Easing onto the end instead of pinning: after a send, or a resume a little short of it. */
    private var gliding by mutableStateOf(false)

    /** A jump button's ease owns the list; nothing else scrolls meanwhile. */
    private var easing by mutableStateOf(false)
    internal var restorePending by mutableStateOf(false)

    /** A reply streaming, or text still revealing: the end can grow on any frame. */
    internal var live by mutableStateOf(false)
    internal var relatchPx by mutableStateOf(0f)

    /** At or past the last index; the measure clamps it onto the bottom spacer. */
    private var endIndexHint = 0
    private var lastUserId: String? = null
    private var userIdAtSend: String? = null
    private var awaitingTurn = false

    /** Following, and nothing else owns the scroll. */
    private val inCharge: Boolean get() = following && !easing && !restorePending

    val userScrollDetector: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Programmatic scrolls never come through here, so any user input is the reader's.
                if (source == NestedScrollSource.UserInput && abs(available.y) > PIN_EPSILON_PX) release()
                return Offset.Zero
            }
        }

    val showJumpToBottom: State<Boolean> = derivedStateOf { !following && distanceToEnd() > relatchPx }

    /** Called from composition, ahead of the measure that lays [messages] out. */
    fun onMessages(messages: List<ChatMessage>) {
        val grew = messages.size + 2 > endIndexHint
        // Rows above the transcript and the bottom spacer add at most three past the messages.
        endIndexHint = messages.size + 2
        lastUserId = messages.lastOrNull { it.role == ChatMessage.Role.USER }?.id
        if (awaitingTurn && lastUserId != userIdAtSend) {
            awaitingTurn = false
            runwayMessageId = lastUserId
        }
        // The first messages, or a reply arriving: pinned in the same frame they are measured, or the
        // list shows its top (or the new row's) for a frame before the loop catches up.
        if (grew && inCharge && !gliding) pin()
    }

    fun onSendMessage() {
        userIdAtSend = lastUserId
        awaitingTurn = true
        following = true
        gliding = true
    }

    suspend fun jumpToTop() {
        release()
        ease { listState.easeScrollToTop() }
    }

    suspend fun jumpToBottom() {
        ease { listState.easeScrollToBottom() }
        following = true
    }

    suspend fun jumpToItem(index: Int) {
        release()
        ease { listState.animateScrollToItem(index, 0) }
    }

    /**
     * Height [rowKey] needs, with [reservedPx] (and the item spacing, if a row follows) below it, to reach from the runway prompt at the top
     * to the viewport's end; 0 when either is not laid out. Only what sits above the row is read
     * from the last layout, and that holds still while the row grows. A row not laid out yet is
     * about to take the bottom spacer's place, so it is measured from there.
     */
    fun runwayPx(
        promptKey: Any?,
        rowKey: Any?,
        reservedPx: Int,
    ): Int {
        val info = listState.layoutInfo
        val items = info.visibleItemsInfo
        val prompt = promptKey?.let { key -> items.firstOrNull { it.key == key } } ?: return 0
        val row =
            items.firstOrNull { it.key == rowKey }
                ?: items.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }
                ?: return 0
        val followed = row.key != rowKey || row.index < info.totalItemsCount - 1
        val below = reservedPx + if (followed) info.mainAxisItemSpacing else 0
        return runwayPx(prompt.offset, row.offset, info.viewportEndOffset, below)
    }

    /** The frame loop: pins (or glides) onto the end while following and anything can grow. */
    suspend fun follow() {
        while (true) {
            snapshotFlow {
                inCharge && (gliding || live || distanceToEnd() > PIN_EPSILON_PX)
            }.first { it }
            chase()
        }
    }

    /** Hands the transcript back to following when the reader comes to rest near the end. */
    suspend fun relatch() {
        snapshotFlow {
            !following &&
                !easing &&
                !restorePending &&
                !listState.isScrollInProgress &&
                distanceToEnd() <= relatchPx
        }.collect { atRestNearEnd ->
            if (atRestNearEnd) {
                following = true
                // Ease the last few lines in; a pin would close them in one frame.
                gliding = true
            }
        }
    }

    private suspend fun chase() {
        var idle = 0
        var jumped = false
        var last = withFrameNanos { it }
        while (inCharge && idle < IDLE_FRAMES) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
            last = now
            val distance = distanceToEnd()
            if (gliding) {
                jumped = glide(distance, dt, mayJump = !jumped) || jumped
                if (!awaitingTurn && distance <= PIN_EPSILON_PX) gliding = false
            } else {
                pin()
            }
            idle = if (gliding || live || distance > PIN_EPSILON_PX) 0 else idle + 1
        }
    }

    /**
     * A position request, not a scroll delta: it is applied in this frame's measure, after the
     * content grew, so the end never trails a frame behind.
     */
    private fun pin() = listState.requestScrollToItem(endIndexHint)

    /** One glide step; true when it jumped instead of easing. */
    private suspend fun glide(
        distance: Float,
        dt: Float,
        mayJump: Boolean,
    ): Boolean {
        if (distance <= PIN_EPSILON_PX) return false
        try {
            // A far end would compose everything in between: land a few viewports short of it, once.
            // Its distance stays an estimate after landing, so jumping again would never arrive.
            if (mayJump && distance > listState.maxEasePx()) {
                listState.scrollToItem(endIndexHint)
                listState.scrollBy(-listState.maxEasePx())
                return true
            }
            // Frame-rate independent ease-out toward a target that may still be moving.
            listState.scrollBy(distance * (1f - (1f - GLIDE_PER_FRAME).pow(dt * FRAMES_PER_SECOND)))
        } catch (e: CancellationException) {
            // A drag took the scroll; following has already stopped. Only our own cancellation ends us.
            if (!currentCoroutineContext().isActive) throw e
        }
        return false
    }

    private suspend fun ease(animation: suspend () -> Unit) {
        easing = true
        try {
            animation()
        } finally {
            easing = false
        }
    }

    private fun release() {
        following = false
        gliding = false
    }

    private fun distanceToEnd(): Float = listState.distanceToBottom()

    internal fun markRestored(isFollowing: Boolean) {
        following = isFollowing
        restorePending = false
    }
}

/**
 * Height for a row at [rowOffset] that, with [reservedPx] below it, ends the list exactly at
 * [viewportEndOffset] while the prompt at [promptOffset] sits at the viewport's top.
 */
internal fun runwayPx(
    promptOffset: Int,
    rowOffset: Int,
    viewportEndOffset: Int,
    reservedPx: Int,
): Int = maxOf(0, viewportEndOffset - (rowOffset - promptOffset) - reservedPx)

/** [ChatScrollHandle.runwayPx], re-read only when the layout above the row moves. */
@Composable
internal fun ChatScrollHandle.rememberRunwayPx(
    promptKey: Any?,
    rowKey: Any?,
    reservedPx: Int,
): Int {
    val px by remember(promptKey, rowKey, reservedPx) { derivedStateOf { runwayPx(promptKey, rowKey, reservedPx) } }
    return px
}

/**
 * Creates the transcript's [ChatScrollHandle] and runs its follow loop, restore and persistence.
 *
 * @param restoredScrollSnapshot snapshot to restore from (null = fresh session)
 * @param restoreReady true when messages are loaded and ready for scroll restoration
 */
@Composable
internal fun rememberChatScrollState(
    sessionId: String,
    renderedMessages: List<ChatMessage>,
    activelyStreaming: Boolean,
    restoredScrollSnapshot: ChatScrollSnapshot?,
    restoreReady: Boolean,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
): ChatScrollHandle {
    val handle = remember(sessionId) { ChatScrollHandle(LazyListState()) }
    val listState = handle.listState
    val density = LocalDensity.current
    val restoreKey = restoredScrollSnapshot?.savedAt
    // Applied before the SideEffect below, so the first onMessages already sees a pending restore.
    DisposableEffect(sessionId, restoreKey) {
        handle.restorePending = restoredScrollSnapshot != null
        onDispose {}
    }
    SideEffect {
        handle.onMessages(renderedMessages)
        handle.live = activelyStreaming
        handle.relatchPx = with(density) { RELATCH_DISTANCE.toPx() }
    }
    LaunchedEffect(handle) { handle.follow() }
    LaunchedEffect(handle) { handle.relatch() }
    // The viewport is measured in pixels; snapshots record it in dp so the value stays
    // comparable across a fold or rotate (density can change with the display).
    val viewportWidthDp = {
        with(density) {
            listState.layoutInfo.viewportSize.width
                .toDp()
                .value
                .toInt()
        }
    }
    LaunchedEffect(handle, renderedMessages) {
        observePersistence(handle, renderedMessages, viewportWidthDp, onScrollSnapshotChanged)
    }
    LaunchedEffect(handle, restoreReady, restoreKey) {
        if (!restoreReady || !handle.restorePending || renderedMessages.isEmpty()) return@LaunchedEffect
        val snapshot = restoredScrollSnapshot ?: return@LaunchedEffect
        val restoredIndex =
            snapshot.anchorMessageId
                ?.let { anchorId -> renderedMessages.indexOfFirst { it.id == anchorId } }
                ?.takeIf { it >= 0 }
                ?: snapshot.firstVisibleItemIndex.coerceIn(0, renderedMessages.lastIndex)
        // Two-pass: the first scrollToItem positions the index, but layout may not have settled
        // yet. The offset is re-anchored to the message top when the viewport width changed (fold,
        // rotate, multi-window), because item heights re-flowed.
        repeat(2) {
            withFrameNanos { }
            listState.scrollToItem(restoredIndex, snapshot.offsetForRestore(viewportWidthDp()).coerceAtLeast(0))
        }
        handle.markRestored(snapshot.isFollowing)
    }
    return handle
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
private suspend fun observePersistence(
    handle: ChatScrollHandle,
    renderedMessages: List<ChatMessage>,
    viewportWidthDp: () -> Int,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
) {
    val listState = handle.listState
    snapshotFlow {
        if (renderedMessages.isEmpty() || handle.restorePending) return@snapshotFlow null
        val idx = listState.firstVisibleItemIndex
        ChatScrollSnapshot(
            anchorMessageId = renderedMessages.getOrNull(idx)?.id,
            firstVisibleItemIndex = idx,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            isFollowing = handle.following,
            savedAt = 0L,
            containerWidthDp = viewportWidthDp(),
        )
    }.distinctUntilChanged()
        .debounce(SCROLL_DEBOUNCE_MS)
        .collect { observation ->
            observation ?: return@collect
            onScrollSnapshotChanged(observation.copy(savedAt = System.currentTimeMillis()))
        }
}

private const val SCROLL_DEBOUNCE_MS = 1000L

/**
 * Eases to the top of item 0 along an exact, measured distance. See [glideOnto].
 */
internal suspend fun LazyListState.easeScrollToTop() {
    val anchor = layoutInfo.visibleItemsInfo.firstOrNull() ?: return
    val estimate = -distanceToTop()
    scrollToItem(0)
    // Where the item that was at the top now sits gives the exact distance travelled; only when
    // it has scrolled out of view is the distance an estimate, and then the start jumps anyway.
    val landed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor.index }
    val distance = landed?.let { (it.offset - anchor.offset).toFloat() } ?: min(estimate, maxEasePx())
    glideOnto(-distance, exact = landed != null)
}

/**
 * Eases to the end of the content along an exact, measured distance. See [glideOnto].
 */
internal suspend fun LazyListState.easeScrollToBottom() {
    val anchor = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val estimate = distanceToBottom()
    scrollToItem(layoutInfo.totalItemsCount - 1)
    val landed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor.index }
    val distance =
        landed?.let { (anchor.offset + anchor.size - (it.offset + it.size)).toFloat() } ?: min(estimate, maxEasePx())
    glideOnto(distance, exact = landed != null)
    // A reply still streaming grows during the ease, past the distance it set out to cover. Chase
    // the new end in short eases; snapping onto it hopped by every line that streamed meanwhile.
    repeat(CHASE_PASSES) {
        val left = distanceToBottom()
        if (left < 1f) return
        animateScrollBy(left, tween(SETTLE_MS, easing = EaseOutCubic))
    }
}

/**
 * Called with the list already resting on its target: steps back [travel] pixels and eases
 * forward onto the target again.
 *
 * The step back happens in the same frame as the snap to the target, so when the distance was
 * measured [exact]ly the list visibly never moves before the ease starts — it simply glides from
 * where it was. Easing a known distance is what makes the curve a real curve: an ease that
 * re-estimates an off-screen target every frame keeps stretching as taller items come into view
 * and reads as a linear scroll with a hard stop.
 *
 * A far target cannot be measured without composing everything in between, so the start jumps to
 * [EASE_MAX_VIEWPORTS] away and eases out only — the jump reads as the fast start of the motion.
 */
private suspend fun LazyListState.glideOnto(
    travel: Float,
    exact: Boolean,
) {
    if (travel == 0f) return
    val stepped = -scrollBy(-travel)
    val fraction = (abs(stepped) / maxEasePx()).coerceAtMost(1f)
    animateScrollBy(
        value = stepped,
        animationSpec =
            tween(
                durationMillis = EASE_MIN_MS + ((EASE_MAX_MS - EASE_MIN_MS) * fraction).toInt(),
                easing = if (exact) EaseInOutCubic else EaseOutCubic,
            ),
    )
}

private fun LazyListState.maxEasePx(): Float =
    layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset } * EASE_MAX_VIEWPORTS

/** Signed pixels from the current position to the top of item 0; negative scrolls up. */
private fun LazyListState.distanceToTop(): Float {
    val info = layoutInfo
    info.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { return it.offset.toFloat() }
    return -(firstVisibleItemIndex * info.averageItemSpan() + firstVisibleItemScrollOffset)
}

/** Signed pixels from the current position to the end of the content; positive scrolls down. */
private fun LazyListState.distanceToBottom(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    val itemsBelow = info.totalItemsCount - 1 - last.index
    return itemsBelow * info.averageItemSpan() + (last.offset + last.size - info.viewportEndOffset)
}

private fun LazyListLayoutInfo.averageItemSpan(): Float {
    val items = visibleItemsInfo
    if (items.isEmpty()) return 0f
    return items.sumOf { it.size }.toFloat() / items.size + mainAxisItemSpacing
}
