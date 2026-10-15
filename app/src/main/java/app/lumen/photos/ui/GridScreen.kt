package app.lumen.photos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumen.photos.data.ActionSet
import app.lumen.photos.data.MediaItem
import app.lumen.photos.data.Source
import app.lumen.photos.data.actionSet
import app.lumen.photos.data.title
import app.lumen.photos.ui.theme.Lumen
import app.lumen.photos.ui.theme.LumenType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MediaActions(
    val share: (List<MediaItem>) -> Unit,
    val setFavorite: (List<MediaItem>, Boolean) -> Unit,
    val hide: (List<MediaItem>) -> Unit,
    val trash: (List<MediaItem>) -> Unit,
    val unhide: (List<MediaItem>) -> Unit,
    val recover: (List<MediaItem>) -> Unit,
    val deleteForever: (List<MediaItem>) -> Unit,
)

val TopBarHeight = 58.dp
val TabBarSpace = 96.dp

private val dayFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
private val yearFmt = DateTimeFormatter.ofPattern("yyyy")

/** iOS titles the grid with the date of what's under the bar, coarser as you zoom out. */
fun gridTitle(millis: Long, columns: Int): String {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return when {
        columns <= 3 -> dayFmt.format(date)
        columns <= 5 -> monthFmt.format(date)
        else -> yearFmt.format(date)
    }
}

@Composable
fun GridScreen(
    source: Source,
    items: List<MediaItem>,
    state: LazyGridState,
    registry: ThumbRegistry,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    hiddenKey: String?,
    actions: MediaActions,
    onOpen: (MediaItem, ThumbAnchor) -> Unit,
    onBack: (() -> Unit)?,
    onSelectingChange: (Boolean) -> Unit,
    bottomBarSpace: Dp,
    pinned: MutableState<Boolean>,
    empty: Pair<String, String>,
    emptyIcon: ImageVector = LumenIcons.Library,
    menu: (@Composable (HazeState) -> Unit)? = null,
    cellBadge: (@Composable androidx.compose.foundation.layout.BoxScope.(MediaItem) -> Unit)? = null,
) {
    val haze = rememberHazeState()
    val density = LocalDensity.current
    val set = source.actionSet
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val statusPad = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val navPad = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    val topPad = statusPad + TopBarHeight
    val bottomPad = navPad + if (selecting) 92.dp else bottomBarSpace

    LaunchedEffect(selecting) { onSelectingChange(selecting) }
    LaunchedEffect(items) {
        if (selected.isNotEmpty()) {
            val alive = items.mapTo(HashSet()) { it.key }
            selected = selected.filterTo(HashSet()) { it in alive }
        }
    }
    // Open pinned to the newest row, like iOS. Only once per screen life.
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty() && !pinned.value) {
            state.scrollToItem(items.size)
            pinned.value = true
        }
    }
    BackHandler(enabled = selecting) {
        selecting = false
        selected = emptySet()
    }

    val latestItems by rememberUpdatedState(items)
    val latestColumns by rememberUpdatedState(columns)
    val anchor = remember(state, registry) {
        object : ThumbAnchor {
            override fun rectOf(key: String) = registry.rectOf(key)
            override suspend fun reveal(key: String) {
                val index = latestItems.indexOfFirst { it.key == key }
                if (index < 0) return
                val r = registry.rectOf(key)
                val safeTop = with(density) { topPad.toPx() }
                val safeBottom = state.layoutInfo.viewportEndOffset - with(density) { bottomPad.toPx() }
                val onScreen = r != null &&
                    state.layoutInfo.visibleItemsInfo.any { it.key == key } &&
                    r.top >= safeTop && r.bottom <= safeBottom
                if (!onScreen) {
                    state.scrollToItem((index - latestColumns * 2).coerceAtLeast(0))
                    withFrameNanos { }
                    withFrameNanos { }
                }
            }
        }
    }

    val title by remember(source, columns) {
        derivedStateOf {
            if (source != Source.Library) return@derivedStateOf source.title()
            val probe = (state.firstVisibleItemIndex + columns).coerceAtMost(latestItems.lastIndex)
            latestItems.getOrNull(probe)?.let { gridTitle(it.dateTaken, columns) } ?: "Library"
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isEmpty()) {
            EmptyState(emptyIcon, empty.first, empty.second, Modifier.align(Alignment.Center))
        }
        MediaGrid(
            items = items,
            state = state,
            registry = registry,
            columns = columns,
            onColumnsChange = onColumnsChange,
            selecting = selecting,
            selected = selected,
            onSelectedChange = { selected = it },
            onStartSelecting = { selecting = true },
            onOpen = { onOpen(it, anchor) },
            hiddenKey = hiddenKey,
            contentPadding = PaddingValues(top = topPad, bottom = 0.dp),
            cellBadge = cellBadge,
            footer = if (items.isNotEmpty()) ({ GridFooter(items, bottomPad + 12.dp) }) else null,
            modifier = Modifier.fillMaxSize().hazeSource(haze),
        )

        // --- top edge -------------------------------------------------------------------
        Box(
            Modifier
                .fillMaxWidth()
                .height(topPad + 28.dp)
                .hazeBlur(input = HazeInput.Sources(haze), style = TopEdgeStyle),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(topPad + 28.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .height(TopBarHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                GlassCircleButton(LumenIcons.Back, haze, contentDescription = "Back", onClick = onBack)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = if (selecting) selectionTitle(selected.size) else title,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "title",
                ) { text ->
                    Text(
                        text,
                        style = if (onBack == null) LumenType.Title.copy(fontSize = LumenType.Title.fontSize * 1.08f) else LumenType.Title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onBack != null && !selecting && items.isNotEmpty()) {
                    Text(countLabel(items), style = LumenType.Footnote, maxLines = 1)
                }
            }
            if (items.isNotEmpty()) {
                GlassPill(
                    text = if (selecting) "Cancel" else "Select",
                    haze = haze,
                    highlighted = selecting,
                ) {
                    selecting = !selecting
                    if (!selecting) selected = emptySet()
                }
            }
            if (menu != null && !selecting) {
                Spacer(Modifier.width(8.dp))
                menu(haze)
            }
        }

        // --- bottom edge ----------------------------------------------------------------
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navPad + 110.dp)
                .hazeBlur(input = HazeInput.Sources(haze), style = BottomEdgeStyle),
        )

        AnimatedVisibility(
            visible = selecting,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val picked = items.filter { it.key in selected }
            SelectionBar(
                set = set,
                count = picked.size,
                allFavorite = picked.isNotEmpty() && picked.all { it.isFavorite },
                haze = haze,
                onAction = { action ->
                    when (action) {
                        BarAction.Share -> actions.share(picked)
                        BarAction.Favorite -> actions.setFavorite(picked, !picked.all { it.isFavorite })
                        BarAction.Hide -> actions.hide(picked)
                        BarAction.Trash -> actions.trash(picked)
                        BarAction.Unhide -> actions.unhide(picked)
                        BarAction.Recover -> actions.recover(picked)
                        BarAction.DeleteForever -> actions.deleteForever(picked)
                    }
                    if (action != BarAction.Share && action != BarAction.Favorite) {
                        selecting = false
                        selected = emptySet()
                    }
                },
            )
        }
    }
}

enum class BarAction { Share, Favorite, Hide, Trash, Unhide, Recover, DeleteForever }

@Composable
private fun SelectionBar(
    set: ActionSet,
    count: Int,
    allFavorite: Boolean,
    haze: HazeState,
    onAction: (BarAction) -> Unit,
) {
    val enabled = count > 0
    Row(
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(60.dp)
            .glass(haze, CircleShape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        when (set) {
            ActionSet.Library, ActionSet.External -> {
                BarIcon(LumenIcons.Share, "Share", enabled) { onAction(BarAction.Share) }
                Text(selectionTitle(count), style = LumenType.Callout, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                BarIcon(if (allFavorite) LumenIcons.Heart else LumenIcons.HeartOutline, "Favorite", enabled, if (allFavorite) Lumen.Rose else Color.White) {
                    onAction(BarAction.Favorite)
                }
                BarIcon(LumenIcons.EyeOff, "Hide", enabled) { onAction(BarAction.Hide) }
                BarIcon(LumenIcons.Trash, "Delete", enabled) { onAction(BarAction.Trash) }
            }
            ActionSet.Hidden -> {
                BarText("Unhide", enabled) { onAction(BarAction.Unhide) }
                Text(selectionTitle(count), style = LumenType.Callout, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                BarText("Delete", enabled, Lumen.Danger) { onAction(BarAction.DeleteForever) }
            }
            ActionSet.Trash -> {
                BarText("Recover", enabled) { onAction(BarAction.Recover) }
                Text(selectionTitle(count), style = LumenType.Callout, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                BarText("Delete", enabled, Lumen.Danger) { onAction(BarAction.DeleteForever) }
            }
        }
    }
}

@Composable
fun BarIcon(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun BarText(text: String, enabled: Boolean, color: Color = Lumen.Accent, onClick: () -> Unit) {
    Box(
        Modifier
            .height(46.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LumenType.Headline.copy(color = if (enabled) color else color.copy(alpha = 0.35f)))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Lumen.TextFaint, modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = LumenType.Title)
        Spacer(Modifier.height(6.dp))
        Text(body, style = LumenType.Subhead, textAlign = TextAlign.Center)
    }
}

fun selectionTitle(n: Int) = when (n) {
    0 -> "Select Items"
    1 -> "1 Selected"
    else -> "%,d Selected".format(n)
}

fun countLabel(items: List<MediaItem>): String {
    val n = items.size
    return if (n == 1) "1 item" else "%,d items".format(n)
}
