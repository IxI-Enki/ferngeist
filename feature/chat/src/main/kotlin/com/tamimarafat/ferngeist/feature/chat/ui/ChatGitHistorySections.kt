@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.tamimarafat.ferngeist.core.common.ui.DarkGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.EdgeFade
import com.tamimarafat.ferngeist.core.common.ui.LocalGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.feature.chat.GraphEdge
import com.tamimarafat.ferngeist.feature.chat.GraphRow
import com.tamimarafat.ferngeist.feature.chat.GraphStroke
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.graphReach
import com.tamimarafat.ferngeist.feature.chat.layoutGraph
import com.tamimarafat.ferngeist.feature.chat.strokesFor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.security.MessageDigest

// Fixed so graph segments in neighbouring rows meet exactly at the row boundary.
private val CommitRowHeight = 48.dp
private val LaneWidth = 10.dp
private const val MAX_LANES = 6
private const val MAX_REF_CHARS = 12
private const val LOADING_MORE_ROWS = 3
private const val LOAD_MORE_AHEAD = 10

/** The git sheet's Changes | History switch. Siblings, so switching crossfades rather than slides. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GitSheetTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf(stringResource(R.string.chat_git_tab_changes), stringResource(R.string.chat_git_tab_history))
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        labels.forEachIndexed { index, label ->
            ToggleButton(
                checked = index == selected,
                onCheckedChange = { onSelect(index) },
                shapes =
                    if (index == 0) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                    },
                // Exactly one is on, so it reads as a choice rather than a pair of switches.
                modifier = Modifier.weight(1f).handCursor().semantics { role = Role.RadioButton },
            ) {
                Text(label)
            }
        }
    }
}

/**
 * Commit history with a graph column. Rows never animate in: the list is scrolled
 * constantly, and motion there would read as lag.
 */
@Composable
internal fun GitHistoryList(
    commits: List<ChatGitCommit>,
    nowMillis: Long,
    onCommitClick: (ChatGitCommit) -> Unit,
    modifier: Modifier = Modifier,
    isLoadingMore: Boolean = false,
    /** Asked for the next page once the end is within [LOAD_MORE_AHEAD] rows. */
    onLoadMore: () -> Unit = {},
    /** A later page failed; the end of the list offers [onLoadMore] as a retry. */
    moreError: String? = null,
) {
    val listState = rememberLazyListState()
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, moreError) {
        // A failed page waits for its retry button rather than re-asking on every scroll.
        if (moreError != null) return@LaunchedEffect
        // Keyed on the item count, so a page that lands still near the end asks for the next.
        snapshotFlow {
            val info = listState.layoutInfo
            val nearEnd = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - LOAD_MORE_AHEAD
            if (nearEnd) info.totalItemsCount else -1
        }.distinctUntilChanged()
            .filter { it >= 0 }
            .collect { currentOnLoadMore() }
    }
    val rows = remember(commits) { layoutGraph(commits) }
    val strokes =
        remember(rows) { rows.indices.map { strokesFor(rows[it], rows.getOrNull(it - 1), rows.getOrNull(it + 1)) } }
    val reach = remember(strokes) { graphReach(strokes) }
    // Width follows the widest loaded row, capped, so the text column holds still.
    val laneCount =
        remember(rows) {
            val widest = rows.maxOfOrNull { row -> maxOf(row.lane, row.up.maxLane(), row.down.maxLane()) } ?: 0
            (widest + 1).coerceAtMost(MAX_LANES)
        }
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(commits, key = { _, commit -> commit.sha }) { index, commit ->
                GitCommitRow(
                    commit = commit,
                    row = rows[index],
                    strokes = strokes[index],
                    reach = reach[index],
                    laneCount = laneCount,
                    nowMillis = nowMillis,
                    onClick = { onCommitClick(commit) },
                )
            }
            if (isLoadingMore) {
                item(key = "loadingMore") {
                    SkeletonPulse {
                        Column {
                            repeat(LOADING_MORE_ROWS) {
                                GitCommitRowSkeleton(it, laneCount, first = false, last = it == LOADING_MORE_ROWS - 1)
                            }
                        }
                    }
                }
            }
            if (moreError != null) {
                item(key = "moreError") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_git_history_more_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onLoadMore, modifier = Modifier.handCursor()) {
                            Text(stringResource(R.string.chat_retry))
                        }
                    }
                }
            }
        }
        EdgeFade(listState = listState, fadeColor = MaterialTheme.colorScheme.surfaceContainerLow)
    }
}

/** First page loading: rows the same height and layout as real ones, so nothing jumps when they land. */
@Composable
internal fun GitHistorySkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 8,
) {
    SkeletonPulse(modifier = modifier.fillMaxWidth()) {
        Column { repeat(rows) { GitCommitRowSkeleton(it, laneCount = 1, first = it == 0, last = it == rows - 1) } }
    }
}

/** A lane-0 line with a dot, a subject bar, date and avatar. The line fades out on the last row. */
@Composable
private fun GitCommitRowSkeleton(
    index: Int,
    laneCount: Int,
    first: Boolean,
    last: Boolean,
) {
    val bone = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = Modifier.fillMaxWidth().height(CommitRowHeight).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier =
                Modifier
                    .width(LaneWidth * laneCount + 6.dp)
                    .fillMaxHeight()
                    .drawBehind {
                        val x = (6.dp + LaneWidth / 2).toPx()
                        val mid = size.height / 2
                        val top = if (first) mid else 0f
                        val bottom = if (last) mid else size.height
                        drawLine(bone, Offset(x, top), Offset(x, bottom), 2.dp.toPx(), StrokeCap.Round)
                        drawCircle(bone, radius = 4.dp.toPx(), center = Offset(x, mid))
                    },
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            Bone(height = 12.dp, modifier = Modifier.fillMaxWidth(BoneFractions[index % BoneFractions.size]))
        }
        Spacer(Modifier.width(8.dp))
        Bone(height = 10.dp, width = 28.dp)
        Spacer(Modifier.width(8.dp))
        Bone(height = 24.dp, width = 24.dp, shape = CircleShape)
    }
}

private fun List<GraphEdge>.maxLane(): Int = maxOfOrNull { maxOf(it.from, it.to) } ?: 0

@Composable
private fun GitCommitRow(
    commit: ChatGitCommit,
    row: GraphRow,
    strokes: List<GraphStroke>,
    reach: Float,
    laneCount: Int,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    val description = commitDescription(commit, nowMillis)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(CommitRowHeight)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GitGraphCell(
            row = row,
            strokes = strokes,
            reach = reach,
            laneCount = laneCount,
            isHead = commit.refs.any { it.isHead || it.kind == ChatGitRef.Kind.HEAD },
        )
        Spacer(Modifier.width(8.dp))
        // The card sits beside the graph so the lane lines run unbroken between rows.
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 3.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .handCursor()
                        .clickable(role = Role.Button, onClick = onClick)
                        .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val refs = commit.refs.sortedBy { it.order() }
                    Text(
                        text = commit.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    refs.firstOrNull()?.let { RefBadge(it) }
                    if (refs.size > 1) MoreRefsBadge(refs.size - 1)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = compactCommitDate(nowMillis, commit.authorTimeMillis),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 40.dp).clearAndSetSemantics {},
                )
                Spacer(Modifier.width(8.dp))
                AuthorAvatar(name = commit.authorName, email = commit.authorEmail)
            }
        }
    }
}

/** Current branch first, then local branches, tags, detached HEAD, remotes. */
private val RefKindOrder =
    listOf(ChatGitRef.Kind.BRANCH, ChatGitRef.Kind.TAG, ChatGitRef.Kind.HEAD, ChatGitRef.Kind.REMOTE)

internal fun ChatGitRef.order(): Int = if (isHead) -1 else RefKindOrder.indexOf(kind)

@Composable
private fun graphPalette(): List<Color> {
    val colors = MaterialTheme.colorScheme
    return listOf(
        colors.primary,
        colors.tertiary,
        colors.secondary,
        LocalGitSemanticColors.current.added,
        colors.error,
        colors.outline,
    )
}

@Composable
private fun GitGraphCell(
    row: GraphRow,
    strokes: List<GraphStroke>,
    reach: Float,
    laneCount: Int,
    isHead: Boolean,
) {
    val palette = graphPalette()
    val nodeColor = palette[row.color % palette.size]
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    // Only as wide as this row's own lines, so its card sits right beside them.
    val lanes = reach.coerceAtMost(laneCount - 1f) + 1f
    Spacer(
        modifier =
            Modifier
                .width(LaneWidth * lanes + 6.dp)
                .fillMaxHeight()
                .clearAndSetSemantics {}
                .drawBehind {
                    // Lanes past the cap share the last column so they stay visible.
                    fun x(lane: Int) = (6.dp + LaneWidth * lane.coerceAtMost(laneCount - 1) + LaneWidth / 2).toPx()
                    val mid = size.height / 2
                    val stroke = 2.dp.toPx()
                    val edgeStroke = Stroke(width = stroke, cap = StrokeCap.Round)

                    // S-curve: both control points sit at the vertical midpoint, so the bend leaves and
                    // lands vertically and joins the straight runs without a kink. Bends span a full
                    // row and draw past this cell's bounds; nothing clips, so they land in the neighbour.
                    strokes.forEach { s ->
                        val top = s.startY * size.height
                        val bottom = s.endY * size.height
                        val bend = (top + bottom) / 2
                        val path =
                            Path().apply {
                                moveTo(x(s.from), top)
                                cubicTo(x(s.from), bend, x(s.to), bend, x(s.to), bottom)
                            }
                        drawPath(path, color = palette[s.color % palette.size], style = edgeStroke)
                    }
                    val center = Offset(x(row.lane), mid)
                    val radius = (if (row.isMerge) 5.dp else 4.dp).toPx()
                    if (isHead) {
                        // Hollow ring: punch out the lines behind it, then stroke the ring.
                        drawCircle(color = background, radius = radius + stroke / 2, center = center)
                        drawCircle(color = nodeColor, radius = radius, center = center, style = Stroke(stroke))
                    } else {
                        drawCircle(color = nodeColor, radius = radius, center = center)
                    }
                },
    )
}

@Composable
internal fun RefBadge(ref: ChatGitRef) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    val (container, content) =
        when {
            ref.isHead -> colors.primaryContainer to colors.onPrimaryContainer
            ref.kind == ChatGitRef.Kind.TAG -> colors.tertiaryContainer to colors.onTertiaryContainer
            ref.kind == ChatGitRef.Kind.REMOTE -> colors.surfaceVariant to colors.onSurfaceVariant
            ref.kind == ChatGitRef.Kind.HEAD -> colors.secondaryContainer to colors.onSecondaryContainer
            else -> Color.Transparent to colors.onSurface
        }
    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        modifier =
            if (ref.kind == ChatGitRef.Kind.BRANCH && !ref.isHead) {
                Modifier.border(1.dp, colors.outline, shape)
            } else {
                Modifier
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            if (ref.kind == ChatGitRef.Kind.TAG) {
                Icon(Icons.Outlined.Sell, contentDescription = null, modifier = Modifier.size(12.dp))
            }
            Text(
                text = ref.name.ellipsize(MAX_REF_CHARS),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

private fun String.ellipsize(max: Int): String = if (length <= max) this else take(max - 1) + "…"

@Composable
private fun MoreRefsBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = "+$count",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Initials on a color picked from the email, so one author keeps one color everywhere. The
 * author's picture covers them once it loads; a miss or no network leaves the initials.
 */
@Composable
internal fun AuthorAvatar(
    name: String,
    email: String,
    size: Dp = 24.dp,
) {
    val colors = MaterialTheme.colorScheme
    val swatches =
        listOf(
            colors.primaryContainer to colors.onPrimaryContainer,
            colors.secondaryContainer to colors.onSecondaryContainer,
            colors.tertiaryContainer to colors.onTertiaryContainer,
        )
    val (container, content) = swatches[Math.floorMod(email.lowercase().hashCode(), swatches.size)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clearAndSetSemantics {},
    ) {
        Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(size)) {}
        Text(
            text = initialsOf(name, email),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
        )
        val pixels = with(LocalDensity.current) { size.roundToPx() }
        avatarUrl(email, pixels)?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

private val GitHubNoReply = Regex("^(\\d+)\\+[^@]+@users\\.noreply\\.github\\.com$")

/**
 * GitHub's own avatar for its noreply addresses, Gravatar for the rest. Gravatar's `d=404` makes
 * an address without a picture fail, so the initials stay rather than a generated pattern.
 */
internal fun avatarUrl(
    email: String,
    pixels: Int,
): String? {
    val normalized = email.trim().lowercase()
    if (normalized.isEmpty()) return null
    val gitHubId = GitHubNoReply.find(normalized)?.groupValues?.get(1)
    if (gitHubId != null) return "https://avatars.githubusercontent.com/u/$gitHubId?s=$pixels"
    val hash =
        MessageDigest
            .getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
    return "https://gravatar.com/avatar/$hash?s=$pixels&d=404"
}

internal fun initialsOf(
    name: String,
    email: String,
): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val letters =
        when {
            words.size >= 2 -> "${words.first().first()}${words.last().first()}"
            words.size == 1 -> words.first().first().toString()
            else -> email.firstOrNull()?.toString() ?: "?"
        }
    return letters.uppercase()
}

/** How a commit's age is shown in the narrow date column. */
internal sealed interface CommitAge {
    data object Now : CommitAge

    data class Minutes(
        val count: Int,
    ) : CommitAge

    data class Hours(
        val count: Int,
    ) : CommitAge

    /** Within the last week: show the weekday. */
    data object ThisWeek : CommitAge

    data object Older : CommitAge
}

internal fun commitAge(
    nowMillis: Long,
    thenMillis: Long,
): CommitAge {
    // Future timestamps (clock skew between gateway and phone) read as "now".
    val elapsed = (nowMillis - thenMillis).coerceAtLeast(0)
    return when {
        elapsed < DateUtils.MINUTE_IN_MILLIS -> CommitAge.Now
        elapsed < DateUtils.HOUR_IN_MILLIS -> CommitAge.Minutes((elapsed / DateUtils.MINUTE_IN_MILLIS).toInt())
        elapsed < DateUtils.DAY_IN_MILLIS -> CommitAge.Hours((elapsed / DateUtils.HOUR_IN_MILLIS).toInt())
        elapsed < DateUtils.WEEK_IN_MILLIS -> CommitAge.ThisWeek
        else -> CommitAge.Older
    }
}

@Composable
private fun compactCommitDate(
    nowMillis: Long,
    thenMillis: Long,
): String {
    val context = LocalContext.current
    return when (val age = commitAge(nowMillis, thenMillis)) {
        CommitAge.Now -> stringResource(R.string.chat_git_date_now)
        is CommitAge.Minutes -> stringResource(R.string.chat_git_date_minutes, age.count)
        is CommitAge.Hours -> stringResource(R.string.chat_git_date_hours, age.count)
        CommitAge.ThisWeek ->
            DateUtils.formatDateTime(
                context,
                thenMillis,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
            )
        // Without a year flag DateUtils adds the year only when it is not the current one.
        CommitAge.Older -> DateUtils.formatDateTime(context, thenMillis, DateUtils.FORMAT_ABBREV_MONTH)
    }
}

@Composable
private fun commitDescription(
    commit: ChatGitCommit,
    nowMillis: Long,
): String {
    val refs =
        commit.refs.sortedBy { it.order() }.map { ref ->
            when (ref.kind) {
                ChatGitRef.Kind.BRANCH -> stringResource(R.string.chat_git_ref_branch, ref.name)
                ChatGitRef.Kind.REMOTE -> stringResource(R.string.chat_git_ref_remote, ref.name)
                ChatGitRef.Kind.TAG -> stringResource(R.string.chat_git_ref_tag, ref.name)
                ChatGitRef.Kind.HEAD -> stringResource(R.string.chat_git_ref_detached)
            }
        }
    val ago =
        DateUtils.getRelativeTimeSpanString(commit.authorTimeMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
    return (listOf(commit.subject) + refs + listOf(commit.authorName, ago)).joinToString(", ")
}

// region Previews

private const val PREVIEW_NOW = 1_792_000_000_000L

private fun previewCommit(
    sha: String,
    subject: String,
    hoursAgo: Double,
    author: String = "Tamim Arafat",
    email: String = "tamim@example.com",
    parents: List<String>,
    refs: List<ChatGitRef> = emptyList(),
) = ChatGitCommit(
    sha = sha,
    parents = parents,
    subject = subject,
    authorName = author,
    authorEmail = email,
    authorTimeMillis = PREVIEW_NOW - (hoursAgo * DateUtils.HOUR_IN_MILLIS).toLong(),
    refs = refs,
)

/** A feature branch merged back, a second open branch, and a tag — the shapes a real log has. */
private val previewBranchyHistory =
    listOf(
        previewCommit(
            "m2",
            "Merge branch 'feat/history' into master",
            0.01,
            parents = listOf("c5", "h3"),
            refs =
                listOf(
                    ChatGitRef("master", ChatGitRef.Kind.BRANCH, isHead = true),
                    ChatGitRef("origin/master", ChatGitRef.Kind.REMOTE),
                ),
        ),
        previewCommit(
            "h3",
            "feat(chat): draw the history graph",
            0.4,
            author = "Claude",
            email = "agent@ferngeist",
            parents = listOf("h2"),
        ),
        previewCommit(
            "w1",
            "wip: try a squarer avatar",
            1.5,
            author = "Ada Lovelace",
            email = "ada@example.com",
            parents = listOf("c4"),
            refs = listOf(ChatGitRef("try/avatar", ChatGitRef.Kind.BRANCH)),
        ),
        previewCommit("c5", "fix(app): resize for the keyboard instead of panning", 3.0, parents = listOf("c4")),
        previewCommit(
            "h2",
            "feat(chat): lay out graph lanes",
            5.0,
            author = "Claude",
            email = "agent@ferngeist",
            parents = listOf("h1"),
        ),
        previewCommit(
            "h1",
            "feat(gateway): add /git/log",
            26.0,
            author = "Claude",
            email = "agent@ferngeist",
            parents = listOf("c4"),
        ),
        previewCommit(
            "c4",
            "fix(chat): time a turn by the gateway's duration",
            70.0,
            parents = listOf("c3"),
            refs = listOf(ChatGitRef("v0.9.0", ChatGitRef.Kind.TAG)),
        ),
        previewCommit("c3", "fix(chat): leave a reply closed by a reattach untimed", 200.0, parents = listOf("c2")),
        previewCommit(
            "c2",
            "fix(chat): let go of the held reply once the reader scrolls",
            400.0,
            parents = listOf("c1"),
            refs =
                listOf(
                    ChatGitRef("v0.8.0", ChatGitRef.Kind.TAG),
                    ChatGitRef("v0.8.0-rc2", ChatGitRef.Kind.TAG),
                ),
        ),
        previewCommit("c1", "fix(chat): restore a chat's scroll once", 9000.0, parents = emptyList()),
    )

private val previewLinearHistory =
    listOf(
        previewCommit(
            "l4",
            "fix(app): resize for the keyboard instead of panning the window",
            0.2,
            parents = listOf("l3"),
            refs =
                listOf(
                    ChatGitRef("master", ChatGitRef.Kind.BRANCH, isHead = true),
                    ChatGitRef("origin/master", ChatGitRef.Kind.REMOTE),
                ),
        ),
        previewCommit("l3", "fix(chat): time a turn by the gateway's duration", 2.0, parents = listOf("l2")),
        previewCommit("l2", "fix(chat): leave a reply closed by a reattach untimed", 30.0, parents = listOf("l1")),
        previewCommit("l1", "fix(chat): restore a chat's scroll once", 900.0, parents = emptyList()),
    )

@Composable
private fun GitHistoryPreviewFrame(commits: List<ChatGitCommit>) {
    var tab by remember { mutableIntStateOf(1) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = stringResource(R.string.chat_git_status_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            GitSheetTabs(selected = tab, onSelect = { tab = it })
            Spacer(Modifier.height(8.dp))
            GitHistoryList(commits = commits, nowMillis = PREVIEW_NOW, onCommitClick = {})
        }
    }
}

@Preview(name = "History · branches", widthDp = 380, heightDp = 720)
@Composable
private fun GitHistoryBranchyPreview() {
    MaterialTheme { GitHistoryPreviewFrame(previewBranchyHistory) }
}

@Preview(name = "History · branches · dark", widthDp = 380, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GitHistoryBranchyDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(
            LocalGitSemanticColors provides DarkGitSemanticColors,
        ) {
            GitHistoryPreviewFrame(previewBranchyHistory)
        }
    }
}

@Preview(name = "History · loading", widthDp = 380, heightDp = 480)
@Composable
private fun GitHistoryLoadingPreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                GitSheetTabs(selected = 1, onSelect = {})
                Spacer(Modifier.height(8.dp))
                GitHistorySkeleton()
            }
        }
    }
}

@Preview(name = "History · loading more", widthDp = 380, heightDp = 360)
@Composable
private fun GitHistoryLoadingMorePreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            GitHistoryList(
                commits = previewLinearHistory,
                nowMillis = PREVIEW_NOW,
                onCommitClick = {},
                isLoadingMore = true,
            )
        }
    }
}

@Preview(name = "History · linear", widthDp = 380, heightDp = 360)
@Composable
private fun GitHistoryLinearPreview() {
    MaterialTheme { GitHistoryPreviewFrame(previewLinearHistory) }
}

// endregion
