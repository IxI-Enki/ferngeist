package com.tamimarafat.ferngeist.feature.chat.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.ToolCallContent
import io.github.diff.DeltaType
import io.github.diff.generatePatch

@Composable
internal fun ContentBlockRenderer(block: ContentBlock) {
    when (block) {
        is ContentBlock.Text -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
            }
        }

        is ContentBlock.Image -> {
            val bitmap = remember(block.data) {
                runCatching {
                    val bytes = Base64.decode(block.data, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            if (bitmap != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Tool output image (${block.mimeType})",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .padding(8.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                Text(
                    text = "Failed to decode image (${block.mimeType})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is ContentBlock.Audio -> {
            Text(
                text = "Audio content (${block.mimeType})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        is ContentBlock.ResourceLink -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = block.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = block.uri,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                block.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }

        is ContentBlock.Resource -> {
            when (val resource = block.resource) {
                is EmbeddedResourceResource.TextResourceContents -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = resource.uri,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = resource.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }
                    }
                }

                is EmbeddedResourceResource.BlobResourceContents -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = resource.uri,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        resource.mimeType?.let { mimeType ->
                            Text(
                                text = "Binary resource ($mimeType)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiffRenderer(diff: ToolCallContent.Diff) {
    val rows = remember(diff.oldText, diff.newText) {
        val oldLines = diff.oldText?.lines() ?: emptyList()
        val newLines = diff.newText.lines()
        val result = mutableListOf<LineDiffRow>()

        if (oldLines.isEmpty()) {
            newLines.forEach { line -> result.add(LineDiffRow.Insert(line)) }
        } else {
            val patch = generatePatch {
                original = oldLines
                revised = newLines
            }

            var oldPos = 0
            var newPos = 0

            for (delta in patch.getDeltas()) {
                val sourceChunk = delta.source
                val targetChunk = delta.target
                val equalCount = sourceChunk.position - oldPos
                for (i in 0 until equalCount) {
                    result.add(LineDiffRow.Equal(oldLines[oldPos + i]))
                }
                oldPos = sourceChunk.position
                newPos += equalCount

                when (delta.type) {
                    DeltaType.DELETE -> {
                        for (line in sourceChunk.lines) {
                            result.add(LineDiffRow.Delete(line))
                        }
                        oldPos += sourceChunk.lines.size
                    }
                    DeltaType.INSERT -> {
                        for (line in targetChunk.lines) {
                            result.add(LineDiffRow.Insert(line))
                        }
                        newPos += targetChunk.lines.size
                    }
                    DeltaType.CHANGE -> {
                        for (line in sourceChunk.lines) {
                            result.add(LineDiffRow.Delete(line))
                        }
                        for (line in targetChunk.lines) {
                            result.add(LineDiffRow.Insert(line))
                        }
                        oldPos += sourceChunk.lines.size
                        newPos += targetChunk.lines.size
                    }
                    DeltaType.EQUAL -> {}
                }
            }

            for (i in oldPos until oldLines.size) {
                result.add(LineDiffRow.Equal(oldLines[i]))
            }
        }

        result
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = diff.path,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        rows.forEach { row ->
            val (prefix, bgColor, textColor) = when (row) {
                is LineDiffRow.Delete -> Triple(
                    "- ",
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    MaterialTheme.colorScheme.error,
                )
                is LineDiffRow.Insert -> Triple(
                    "+ ",
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    Color(0xFF43A047),
                )
                is LineDiffRow.Equal -> Triple(
                    "  ",
                    Color.Transparent,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "$prefix${row.text}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = textColor,
                )
            }
        }
    }
}

internal sealed class LineDiffRow(val text: String) {
    class Delete(text: String) : LineDiffRow(text)
    class Insert(text: String) : LineDiffRow(text)
    class Equal(text: String) : LineDiffRow(text)
}

@Composable
internal fun TerminalRenderer(terminal: ToolCallContent.Terminal) {
    Text(
        text = "Terminal output (${terminal.terminalId})",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
