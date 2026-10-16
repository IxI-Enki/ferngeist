package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A file's type icon beside its name: the one look for a file wherever a tool call or a turn's diff names one. */
@Composable
internal fun FileChip(
    path: String,
    modifier: Modifier = Modifier,
    // Off where the chip already sits on a card: a pill inside a card reads as clutter.
    filled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .then(if (filled) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)
                .padding(horizontal = if (filled) 6.dp else 0.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(path, modifier = Modifier.size(14.dp))
        Text(
            text = path.fileName(),
            style = textStyle,
            maxLines = 1,
            // The extension is the part worth keeping.
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
internal fun FileTypeIcon(
    path: String,
    modifier: Modifier = Modifier,
) = Icon(
    painter = painterResource(fileIconFor(path)),
    contentDescription = null,
    tint = Color.Unspecified,
    modifier = modifier,
)

internal fun String.fileName() = replace('\\', '/').trimEnd('/').substringAfterLast('/')
