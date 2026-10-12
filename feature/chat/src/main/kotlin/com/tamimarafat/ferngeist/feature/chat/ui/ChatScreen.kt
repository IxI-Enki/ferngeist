package com.tamimarafat.ferngeist.feature.chat.ui

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.OnSwitchSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// region: ChatScreen

/**
 * Full-screen chat session view.
 *
 * Layout hierarchy:
 * - [sharedTransitionScope] Box (shared element bounds for session list transition)
 *   - [Scaffold] with [TwoRowsTopAppBar] via [ChatTopBar]
 *     - [ChatScreenBody] (message list + loading/error states)
 *     - [ChatComposerBar] (floating bottom composer)
 *     - [SnackbarHost]
 *     - Dialogs: thought, tool call, config picker, connection status, commands
 *
 * Scrolling (following the end, the runway under a sent prompt, jumps, restore) is owned by
 * [ChatScrollHandle].
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun ChatScreen(
    sessionId: String,
    fallbackTitle: String,
    onNavigateBack: () -> Unit,
    onSwitchSession: OnSwitchSession,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    sharedBoundsEnabled: Boolean = true,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val screenState = rememberChatScreenState(viewModel, sessionId)

    // --- ViewModel effects ---
    CollectChatEffects(viewModel, screenState.snackbarHostState, onNavigateBack)

    // Auto-focus the text field when the composer expands
    LaunchedEffect(screenState.composerExpanded.value, screenState.imageFocusTrigger.value) {
        if (screenState.composerExpanded.value) {
            screenState.focusRequester.requestFocus()
        }
    }

    // --- Event haptics ---
    // A permission request blocks the agent, so it earns an unmistakable tick; a finished reply
    // gets one so the moment can be noticed without watching. Both key off state that only moves
    // one way per event, so neither can fire twice for the same occurrence.
    val haptics = LocalHapticFeedback.current
    val awaitingPermission =
        remember(screenState.state.messages) {
            screenState.state.messages.sumOf { message ->
                message.segments.count { !it.toolCall?.permissionOptions.isNullOrEmpty() }
            }
        }
    LaunchedEffect(awaitingPermission) {
        if (awaitingPermission > 0) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    var wasStreaming by remember { mutableStateOf(screenState.state.isStreaming) }
    LaunchedEffect(screenState.state.isStreaming) {
        if (wasStreaming && !screenState.state.isStreaming) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            // The turn's files without reported diffs take git's counts, which must be current.
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
        }
        wasStreaming = screenState.state.isStreaming
    }

    ChatScreenScaffold(
        screenState = screenState,
        sessionId = sessionId,
        fallbackTitle = fallbackTitle,
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onSwitchSession = onSwitchSession,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        modifier = modifier,
        showBackButton = showBackButton,
        sharedBoundsEnabled = sharedBoundsEnabled,
    )
}

private data class ChatScreenMutations(
    val selectedConfigPickerOptionId: MutableState<String?>,
    val selectedThoughtSegmentId: MutableState<String?>,
    val selectedToolCallSegmentId: MutableState<String?>,
    val showCommandsDialog: MutableState<Boolean>,
    val showConnectionStatusDialog: MutableState<Boolean>,
    val showGitStatusSheet: MutableState<Boolean>,
    val showSwitcherSheet: MutableState<Boolean>,
    val composerContentHeightPx: MutableState<Int>,
    val messageText: TextFieldState,
    val selectedImages: MutableState<List<ChatImageData>>,
    val selectedFiles: MutableState<List<ChatFileData>>,
    val composerExpanded: MutableState<Boolean>,
    val focusRequester: FocusRequester,
    val imageFocusTrigger: MutableState<Int>,
)

@Composable
private fun rememberChatScreenMutations(): ChatScreenMutations =
    ChatScreenMutations(
        selectedConfigPickerOptionId = remember { mutableStateOf<String?>(null) },
        selectedThoughtSegmentId = remember { mutableStateOf<String?>(null) },
        selectedToolCallSegmentId = remember { mutableStateOf<String?>(null) },
        showCommandsDialog = remember { mutableStateOf(false) },
        showConnectionStatusDialog = remember { mutableStateOf(false) },
        showGitStatusSheet = remember { mutableStateOf(false) },
        showSwitcherSheet = remember { mutableStateOf(false) },
        composerContentHeightPx = remember { mutableIntStateOf(0) },
        messageText = rememberTextFieldState(),
        selectedImages = remember { mutableStateOf<List<ChatImageData>>(emptyList()) },
        selectedFiles = remember { mutableStateOf<List<ChatFileData>>(emptyList()) },
        composerExpanded = remember { mutableStateOf(false) },
        focusRequester = remember { FocusRequester() },
        imageFocusTrigger = remember { mutableIntStateOf(0) },
    )

internal class ChatScreenState(
    val state: ChatState,
    val snackbarHostState: SnackbarHostState,
    val selectedConfigPickerOptionId: MutableState<String?>,
    val selectedThoughtSegmentId: MutableState<String?>,
    val selectedToolCallSegmentId: MutableState<String?>,
    val showCommandsDialog: MutableState<Boolean>,
    val showConnectionStatusDialog: MutableState<Boolean>,
    val showGitStatusSheet: MutableState<Boolean>,
    val showSwitcherSheet: MutableState<Boolean>,
    val composerContentHeightPx: MutableState<Int>,
    val messageText: TextFieldState,
    val selectedImages: MutableState<List<ChatImageData>>,
    val selectedFiles: MutableState<List<ChatFileData>>,
    val composerExpanded: MutableState<Boolean>,
    val focusRequester: FocusRequester,
    val imageFocusTrigger: MutableState<Int>,
    val attachmentPickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    val attachUris: (List<Uri>) -> Unit,
    val scrollHandle: ChatScrollHandle,
    val showJumpToBottom: State<Boolean>,
    val activeModel: String?,
    val modeOption: ChatConfigOption.Select?,
    val toolbarConfigOptions: List<ChatConfigOption>,
    val selectedConfigPickerOption: ChatConfigOption.Select?,
    val canCancelStreaming: Boolean,
    val hasStreamingBubble: Boolean,
    val activelyStreaming: Boolean,
    val showStopAction: Boolean,
    val showModeButton: Boolean,
    val currentModeLabel: String,
    val gitAdditions: Int,
    val gitDeletions: Int,
    val renderedMessages: List<ChatMessage>,
    val selectedThought: String?,
    val selectedToolCall: ToolCallDisplay?,
    val activePermissionRequest: PendingPermissionRequest?,
    val renderedLastMessageId: String?,
    val listBottomPadding: Dp,
    val bottomFadeBandHeight: Dp,
    val snackbarBottomPadding: Dp,
    val showComposerToolbar: Boolean,
    val buttonsAlpha: State<Float>,
    val inputAlpha: State<Float>,
    val sendMessage: () -> Unit,
    val sendCommand: (String) -> Unit,
)

@Composable
private fun rememberChatScreenState(
    viewModel: ChatViewModel,
    sessionId: String,
): ChatScreenState {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val mutations = rememberChatScreenMutations()
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // -- Attachments: the document picker and the composer's content receiver (paste/drop) feed the
    // same ingest, so both apply identical capability checks, size limits and count caps. --
    val context = LocalContext.current
    val resources = LocalResources.current
    // The ingest reads the selected lists, suspends on IO and writes them back, so two arrivals
    // close together (a drop racing the picker, a second paste) would otherwise both start from the
    // same snapshot and the later write would drop the earlier one's attachments.
    val ingestLock = remember { Mutex() }
    val attachUris: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            // Content can arrive while the composer is collapsed (a drop on the chat pane), and
            // the attachment it just took is only visible in the expanded composer. Pasting
            // cannot reach here collapsed, and the picker opens from the expanded composer, so
            // this only ever acts on the drop.
            mutations.composerExpanded.value = true
            coroutineScope.launch {
                ingestLock.withLock {
                    val (images, files, feedback) =
                        processPickedUris(
                            uris = uris,
                            context = context,
                            canSendImages = state.canSendImages,
                            supportsEmbeddedContext = state.supportsEmbeddedContext,
                            existingImages = mutations.selectedImages.value,
                            existingFiles = mutations.selectedFiles.value,
                            resources = resources,
                        )
                    mutations.selectedImages.value = images
                    mutations.selectedFiles.value = files
                    mutations.imageFocusTrigger.value++
                    feedback?.let { snackbarHostState.showSnackbar(it) }
                }
            }
        }
    }
    val attachmentPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> -> attachUris(uris) }
    val composerInsets =
        rememberComposerInsets(
            state = state,
            composerContentHeightPx = mutations.composerContentHeightPx,
        )

    val derived =
        rememberChatDerived(
            state = state,
            sessionId = sessionId,
            selectedConfigPickerOptionId = mutations.selectedConfigPickerOptionId,
            selectedThoughtSegmentId = mutations.selectedThoughtSegmentId,
            selectedToolCallSegmentId = mutations.selectedToolCallSegmentId,
            composerExpanded = mutations.composerExpanded,
            onScrollSnapshotChanged = viewModel::persistScrollSnapshot,
        )
    val sendHandlers =
        rememberSendHandlers(
            viewModel = viewModel,
            state = state,
            messageText = mutations.messageText,
            selectedImages = mutations.selectedImages,
            selectedFiles = mutations.selectedFiles,
            composerExpanded = mutations.composerExpanded,
            focusManager = focusManager,
            scrollHandle = derived.scrollHandle,
        )
    return buildChatScreenState(
        state = state,
        snackbarHostState = snackbarHostState,
        mutations = mutations,
        attachmentPickerLauncher = attachmentPickerLauncher,
        attachUris = attachUris,
        derived = derived,
        insets = composerInsets,
        sendMessage = sendHandlers.first,
        sendCommand = sendHandlers.second,
    )
}

private fun buildChatScreenState(
    state: ChatState,
    snackbarHostState: SnackbarHostState,
    mutations: ChatScreenMutations,
    attachmentPickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    attachUris: (List<Uri>) -> Unit,
    derived: ChatDerivedValues,
    insets: ComposerInsets,
    sendMessage: () -> Unit,
    sendCommand: (String) -> Unit,
): ChatScreenState =
    ChatScreenState(
        state = state,
        snackbarHostState = snackbarHostState,
        selectedConfigPickerOptionId = mutations.selectedConfigPickerOptionId,
        selectedThoughtSegmentId = mutations.selectedThoughtSegmentId,
        selectedToolCallSegmentId = mutations.selectedToolCallSegmentId,
        showCommandsDialog = mutations.showCommandsDialog,
        showConnectionStatusDialog = mutations.showConnectionStatusDialog,
        showGitStatusSheet = mutations.showGitStatusSheet,
        showSwitcherSheet = mutations.showSwitcherSheet,
        composerContentHeightPx = mutations.composerContentHeightPx,
        messageText = mutations.messageText,
        selectedImages = mutations.selectedImages,
        selectedFiles = mutations.selectedFiles,
        composerExpanded = mutations.composerExpanded,
        focusRequester = mutations.focusRequester,
        imageFocusTrigger = mutations.imageFocusTrigger,
        attachmentPickerLauncher = attachmentPickerLauncher,
        attachUris = attachUris,
        scrollHandle = derived.scrollHandle,
        showJumpToBottom = derived.showJumpToBottom,
        activeModel = derived.activeModel,
        modeOption = derived.modeOption,
        toolbarConfigOptions = derived.toolbarConfigOptions,
        selectedConfigPickerOption = derived.selectedConfigPickerOption,
        canCancelStreaming = derived.canCancelStreaming,
        hasStreamingBubble = derived.hasStreamingBubble,
        activelyStreaming = derived.activelyStreaming,
        showStopAction = derived.showStopAction,
        showModeButton = derived.showModeButton,
        currentModeLabel = derived.currentModeLabel,
        gitAdditions = derived.gitAdditions,
        gitDeletions = derived.gitDeletions,
        renderedMessages = derived.renderedMessages,
        selectedThought = derived.selectedThought,
        selectedToolCall = derived.selectedToolCall,
        activePermissionRequest = derived.activePermissionRequest,
        renderedLastMessageId = derived.renderedLastMessageId,
        listBottomPadding = insets.listBottomPadding,
        bottomFadeBandHeight = insets.bottomFadeBandHeight,
        snackbarBottomPadding = insets.snackbarBottomPadding,
        showComposerToolbar = insets.showComposerToolbar,
        buttonsAlpha = derived.buttonsAlphaState,
        inputAlpha = derived.inputAlphaState,
        sendMessage = sendMessage,
        sendCommand = sendCommand,
    )

// endregion
