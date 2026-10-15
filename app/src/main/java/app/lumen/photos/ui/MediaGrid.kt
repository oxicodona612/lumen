package app.lumen.photos.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.lumen.photos.data.MediaItem
import app.lumen.photos.ui.theme.LumenType
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val ColumnSteps = listOf(3, 5, 8)
const val ThumbPx = 384

/** Where each visible cell sits on screen, so the viewer can zoom out of / back into it. */
@Stable
class ThumbRegistry {
    internal val rects = HashMap<String, Rect>()
    fun rectOf(key: String): Rect? = rects[key]
}

@Composable
fun rememberThumbRequest(item: MediaItem): ImageRequest {
    val context = LocalPlatformContext.current
    return remember(item.key) {
        ImageRequest.Builder(context)
            .data(item.thumbData())
            .size(ThumbPx)
            .memoryCacheKey(MemoryCache.Key(item.thumbCacheKey))
            .build()
    }
}

@Composable
fun MediaGrid(
    items: List<MediaItem>,
    state: LazyGridState,
    registry: ThumbRegistry,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    selecting: Boolean,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    onStartSelecting: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    hiddenKey: String?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    cellBadge: (@Composable BoxScope.(MediaItem) -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val spacing = 1.5.dp
    val spacingPx = with(density) { spacing.toPx() }
    val topPadPx = with(density) { contentPadding.calculateTopPadding().toPx() }
    val bottomPadPx = with(density) { contentPadding.calculateBottomPadding().toPx() }

    val latestItems by rememberUpdatedState(items)
    val latestColumns by rememberUpdatedState(columns)
    val latestSelecting by rememberUpdatedState(selecting)
    val latestSelected by rememberUpdatedState(selected)
    val latestOnOpen by rememberUpdatedState(onOpen)
    val latestOnSelectedChange by rememberUpdatedState(onSelectedChange)
    val latestOnStartSelecting by rememberUpdatedState(onStartSelecting)
    val latestOnColumnsChange by rememberUpdatedState(onColumnsChange)

    val origin = remember { arrayOf(Offset.Zero) }
    var gridSize by remember { mutableStateOf(IntSize.Zero) }

    fun indexAt(local: Offset): Int? {
        val root = local + origin[0]
        for (info in state.layoutInfo.visibleItemsInfo) {
            val key = info.key as? String ?: continue
            val rect = registry.rects[key] ?: continue
            if (rect.contains(root) && info.index < latestItems.size) return info.index
        }
        return null
    }

    // Drag-select bookkeeping. Plain fields: gestures write them, nothing recomposes on them.
    val drag = remember { DragSelect() }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }

    fun applyRange(current: Int) {
        val list = latestItems
        if (drag.anchor !in list.indices || current !in list.indices) return
        val range = (min(drag.anchor, current)..max(drag.anchor, current)).map { list[it].key }
        latestOnSelectedChange(if (drag.adding) drag.base + range else drag.base - range.toSet())
    }

    fun zoomTo(next: Int, centroid: Offset) {
        val anchorIndex = indexAt(centroid) ?: state.firstVisibleItemIndex
        latestOnColumnsChange(next)
        scope.launch {
            // Keep the photo under the fingers under the fingers.
            val width = gridSize.width.toFloat()
            val cell = (width - spacingPx * (next - 1)) / next
            val pitch = cell + spacingPx
            val row = anchorIndex / next
            val desiredTop = centroid.y - cell / 2f
            val rowsAbove = ceil((desiredTop - topPadPx) / pitch).toInt().coerceIn(0, row)
            val offset = (topPadPx + rowsAbove * pitch - desiredTop).roundToInt().coerceAtLeast(0)
            state.scrollToItem((row - rowsAbove) * next, offset)
        }
    }

    // Edge auto-scroll while drag-selecting, iOS-style.
    LaunchedEffect(dragPointer != null) {
        if (dragPointer == null) return@LaunchedEffect
        val edge = with(density) { 90.dp.toPx() }
        while (isActive) {
            val p = dragPointer ?: break
            val top = topPadPx + edge
            val bottom = gridSize.height - bottomPadPx - edge
            val speed = when {
                p.y < top -> -((top - p.y) / edge).coerceAtMost(1.5f) * 26f
                p.y > bottom -> ((p.y - bottom) / edge).coerceAtMost(1.5f) * 26f
                else -> 0f
            }
            if (speed != 0f) {
                state.scrollBy(speed)
                indexAt(p)?.let(::applyRange)
            }
            withFrameNanos { }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
        modifier = modifier
            .onGloballyPositioned {
                origin[0] = it.positionInRoot()
                gridSize = it.size
            }
            // Pinch: watched on the Initial pass so the list never starts scrolling under two fingers.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoom = 1f
                    var fired = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            zoom *= event.calculateZoom()
                            if (!fired) {
                                val cols = latestColumns
                                val next = when {
                                    zoom > 1.22f -> ColumnSteps.lastOrNull { it < cols }
                                    zoom < 0.82f -> ColumnSteps.firstOrNull { it > cols }
                                    else -> null
                                }
                                if (next != null) {
                                    fired = true
                                    zoomTo(next, event.calculateCentroid(useCurrent = true))
                                }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Tap opens / toggles. Long-press starts selecting and the same finger paints a range.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull Gesture.Cancel
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull Gesture.Cancel
                            if (!change.pressed) {
                                return@withTimeoutOrNull if (change.isConsumed) Gesture.Cancel else Gesture.Tap
                            }
                            if (change.isConsumed || (change.position - down.position).getDistance() > slop) {
                                return@withTimeoutOrNull Gesture.Cancel
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        Gesture.Cancel
                    }
                    when (outcome) {
                        Gesture.Tap -> {
                            val index = indexAt(down.position) ?: return@awaitEachGesture
                            val item = latestItems[index]
                            if (latestSelecting) {
                                val sel = latestSelected
                                latestOnSelectedChange(if (item.key in sel) sel - item.key else sel + item.key)
                            } else {
                                latestOnOpen(item)
                            }
                        }
                        null -> {
                            val index = indexAt(down.position) ?: return@awaitEachGesture
                            if (!latestSelecting) latestOnStartSelecting()
                            drag.anchor = index
                            drag.base = if (latestSelecting) latestSelected else emptySet()
                            drag.adding = latestItems[index].key !in drag.base
                            applyRange(index)
                            dragPointer = down.position
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                dragPointer = change.position
                                indexAt(change.position)?.let(::applyRange)
                                change.consume()
                            }
                            dragPointer = null
                        }
                        else -> Unit
                    }
                }
            },
    ) {
        items(items, key = { it.key }, contentType = { 0 }) { item ->
            MediaCell(
                item = item,
                registry = registry,
                selecting = selecting,
                selected = item.key in selected,
                hidden = item.key == hiddenKey,
                badge = cellBadge,
                modifier = Modifier.animateItem(fadeInSpec = null),
            )
        }
        if (footer != null) {
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = 1) { footer() }
        }
    }
}

private enum class Gesture { Tap, Cancel }

private class DragSelect {
    var anchor = -1
    var base: Set<String> = emptySet()
    var adding = true
}

private val labelShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 4f)

@Composable
private fun MediaCell(
    item: MediaItem,
    registry: ThumbRegistry,
    selecting: Boolean,
    selected: Boolean,
    hidden: Boolean,
    badge: (@Composable BoxScope.(MediaItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(item.key) {
        onDispose { registry.rects.remove(item.key) }
    }
    val selScale by animateFloatAsState(if (selected) 1f else 0.6f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "check")
    Box(
        modifier
            .aspectRatio(1f)
            .onGloballyPositioned { registry.rects[item.key] = Rect(it.positionInRoot(), it.size.toSize()) }
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .background(Color(0xFF141417)),
    ) {
        AsyncImage(
            model = rememberThumbRequest(item),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo && !(selecting && selected)) {
            Text(
                formatDuration(item.durationMs),
                style = LumenType.Caption.copy(shadow = labelShadow),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 3.dp),
            )
        }
        if (item.isFavorite && !selecting) {
            Icon(
                LumenIcons.Heart,
                null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(13.dp),
            )
        }
        badge?.invoke(this, item)
        if (selecting && selected) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.18f)))
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .graphicsLayer { scaleX = selScale; scaleY = selScale }
                    .background(app.lumen.photos.ui.theme.Lumen.Sunset, CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LumenIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun GridFooter(items: List<MediaItem>, bottomSpace: androidx.compose.ui.unit.Dp) {
    val photos = items.count { !it.isVideo }
    val videos = items.size - photos
    val text = buildString {
        if (photos > 0) append("%,d %s".format(photos, if (photos == 1) "Photo" else "Photos"))
        if (photos > 0 && videos > 0) append(", ")
        if (videos > 0) append("%,d %s".format(videos, if (videos == 1) "Video" else "Videos"))
    }
    Box(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = bottomSpace), contentAlignment = Alignment.TopCenter) {
        Text(text, style = LumenType.Footnote.copy(color = Color.White.copy(alpha = 0.85f)))
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
