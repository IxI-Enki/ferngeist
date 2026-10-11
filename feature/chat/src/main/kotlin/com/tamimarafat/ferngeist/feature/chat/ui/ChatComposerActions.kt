package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.MutableState
import androidx.compose.ui.focus.FocusManager
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun rememberSendHandlers(
    viewModel: ChatViewModel,
    state: ChatState,
    messageText: TextFieldState,
    selectedImages: MutableState<List<ChatImageData>>,
    selectedFiles: MutableState<List<ChatFileData>>,
    composerExpanded: MutableState<Boolean>,
    focusManager: FocusManager,
    scrollHandle: ChatScrollHandle,
): Pair<() -> Unit, (String) -> Unit> {
    // --- Send message (composer) ---
    val sendMessage: () -> Unit = {
        val hasContent =
            messageText.text.isNotBlank() || selectedImages.value.isNotEmpty() || selectedFiles.value.isNotEmpty()
        if (hasContent) {
            viewModel.dispatch(
                ChatIntent.SendMessage(
                    text = messageText.text.toString(),
                    images = selectedImages.value,
                    files = selectedFiles.value,
                ),
            )
            scrollHandle.onSendMessage()
            messageText.clearText()
            selectedImages.value = emptyList()
            selectedFiles.value = emptyList()
            composerExpanded.value = false
            focusManager.clearFocus()
        }
    }

    // --- Slash-command send (commands sheet) ---
    val sendCommand: (String) -> Unit = { command ->
        if (state.isSessionReady) {
            val normalized = command.trim()
            val slashCommand = if (normalized.startsWith("/")) normalized else "/$normalized"
            viewModel.dispatch(ChatIntent.SendMessage(slashCommand))
            scrollHandle.onSendMessage()
        }
    }
    return sendMessage to sendCommand
}

internal data class ComposerCallbacks(
    val onCancelStreaming: () -> Unit,
    val onSetStringConfigOption: (String, String) -> Unit,
    val onSetBooleanConfigOption: (String, Boolean) -> Unit,
    val onShowCommands: () -> Unit,
    val onShowConfigOptionPicker: (String) -> Unit,
    val onJumpToBottom: () -> Unit,
)

internal fun rememberComposerCallbacks(
    screenState: ChatScreenState,
    coroutineScope: CoroutineScope,
    viewModel: ChatViewModel,
): ComposerCallbacks =
    ComposerCallbacks(
        onCancelStreaming = {
            viewModel.dispatch(ChatIntent.CancelStreaming)
        },
        onSetStringConfigOption = { optionId, value ->
            viewModel.dispatch(
                ChatIntent.SetConfigOption(
                    optionId = optionId,
                    value = ChatConfigValue.StringValue(value),
                ),
            )
        },
        onSetBooleanConfigOption = { optionId, value ->
            viewModel.dispatch(
                ChatIntent.SetConfigOption(
                    optionId = optionId,
                    value = ChatConfigValue.BoolValue(value),
                ),
            )
        },
        onShowCommands = { screenState.showCommandsDialog.value = true },
        onShowConfigOptionPicker = { optionId ->
            screenState.selectedConfigPickerOptionId.value = optionId
        },
        onJumpToBottom = {
            coroutineScope.launch { screenState.scrollHandle.jumpToBottom() }
        },
    )
