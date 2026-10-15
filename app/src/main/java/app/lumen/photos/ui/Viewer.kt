package app.lumen.photos.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.lumen.photos.data.ActionSet
import app.lumen.photos.data.MediaItem
import app.lumen.photos.data.Source
import app.lumen.photos.data.actionSet
import app.lumen.photos.ui.theme.Lumen
import app.lumen.photos.ui.theme.LumenType
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Lets the viewer fly out of, and back into, the exact grid cell it came from. */
interface ThumbAnchor {
    fun rectOf(key: String): Rect?
    suspend fun reveal(key: String)
}

@Immutable
data class ViewerRequest(val source: Source, val startKey: String, val anchor: ThumbAnchor?)

private enum class Phase { Entering, Shown, Exiting }

fun fitRect(container: Size, aspect: Float): Rect {
    val a = if (aspect > 0f) aspect else 1f
    val w: Float
    val h: Float
    if (container.width / container.height > a) {
        h = container.height
        w = h * a
    } else {
        w = container.width
        h = w / a
    }
    return Rect(Offset((container.width - w) / 2f, (container.height - h) / 2f), Size(w, h))
}

private fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(
    androidx.compose.ui.util.lerp(a.left, b.left, t),
    androidx.compose.ui.util.lerp(a.top, b.top, t),
    androidx.compose.ui.util.lerp(a.right, b.right, t),
    androidx.compose.ui.util.lerp(a.bottom, b.bottom, t),
)

/** Photo shrinks a little as it's pulled down, never below 62%. */
private fun dragScale(dy: Float, height: Float): Float =
    (1f - (max(dy, 0f) / height) * 0.55f).coerceIn(0.62f, 1f)

@Composable
fun ViewerOverlay(
    items: List<MediaItem>,
    request: ViewerRequest,
    actions: MediaActions,
    onCurrentKey: (String?) -> Unit,
    onClosed: () -> Unit,
) {
    val set = request.source.actionSet
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haze = rememberHazeState()
    val latestItems by rememberUpdatedState(items)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val container = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val latestContainer by rememberUpdatedState(container)
        val startIndex = remember { items.indexOfFirst { it.key == request.startKey }.coerceAtLeast(0) }
        val pager = rememberPagerState(initialPage = startIndex) { latestItems.size }

        var phase by remember { mutableStateOf(Phase.Entering) }
        val progress = remember { Animatable(0f) }
        var transitionItem by remember { mutableStateOf(items.getOrNull(startIndex)) }
        var thumbRect by remember { mutableStateOf<Rect?>(null) }
        var fullRect by remember { mutableStateOf(Rect.Zero) }
        var drag by remember { mutableStateOf(Offset.Zero) }
        var chrome by remember { mutableStateOf(true) }
        var info by remember { mutableStateOf(false) }
        var zoomed by remember { mutableStateOf(false) }
        val dragging by remember { derivedStateOf { drag != Offset.Zero } }

        val current = items.getOrNull(pager.currentPage.coerceAtMost(items.lastIndex))
        LaunchedEffect(current?.key) { onCurrentKey(current?.key) }

        // Live blur can't sample TextureView video frames — on device it renders a torn
        // noise strip through the bar. Bars go solid whenever a video is on or next to screen.
        val videoNear = (pager.currentPage - 1..pager.currentPage + 1).any { items.getOrNull(it)?.isVideo == true }
        val barHaze = if (videoNear) null else haze
        LaunchedEffect(items.isEmpty()) {
            if (items.isEmpty() && phase == Phase.Shown) {
                onCurrentKey(null)
                onClosed()
            }
        }

        LaunchedEffect(Unit) {
            val item = transitionItem ?: run { onClosed(); return@LaunchedEffect }
            thumbRect = request.anchor?.rectOf(item.key)
            fullRect = fitRect(container, item.aspect)
            progress.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 360f))
            phase = Phase.Shown
        }

        val close: () -> Unit = remember {
            {
                if (phase == Phase.Shown) {
                    scope.launch {
                        val list = latestItems
                        val item = list.getOrNull(pager.currentPage)
                        if (item == null) {
                            onCurrentKey(null)
                            onClosed()
                            return@launch
                        }
                        info = false
                        val fit = fitRect(latestContainer, item.aspect)
                        val s = dragScale(drag.y, latestContainer.height)
                        val c = fit.center + drag
                        fullRect = Rect(c.x - fit.width * s / 2f, c.y - fit.height * s / 2f, c.x + fit.width * s / 2f, c.y + fit.height * s / 2f)
                        transitionItem = item
                        thumbRect = request.anchor?.rectOf(item.key)
                        progress.snapTo(1f)
                        phase = Phase.Exiting
                        drag = Offset.Zero
                        request.anchor?.reveal(item.key)
                        thumbRect = request.anchor?.rectOf(item.key)
                        progress.animateTo(0f, spring(dampingRatio = 0.92f, stiffness = 420f))
                        onCurrentKey(null)
                        onClosed()
                    }
                }
            }
        }

        BackHandler {
            if (info) info = false else close()
        }

        // System bars follow the chrome so a bare photo really is bare.
        val activity = LocalActivity.current
        val view = LocalView.current
        val showChrome = chrome && phase == Phase.Shown && !dragging
        LaunchedEffect(showChrome, phase) {
            val window = activity?.window ?: return@LaunchedEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showChrome || phase == Phase.Exiting) controller.show(WindowInsetsCompat.Type.systemBars())
            else if (phase == Phase.Shown) controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        DisposableEffect(Unit) {
            onDispose {
                activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
            }
        }

        val dismissDistance = with(density) { 110.dp.toPx() }
        val upDistance = with(density) { 70.dp.toPx() }

        // Backdrop: fades with the transition, thins out while you pull the photo down.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val alpha = when (phase) {
                        Phase.Shown -> 1f - (max(drag.y, 0f) / (size.height * 0.42f)).coerceIn(0f, 0.92f)
                        else -> progress.value
                    }
                    drawRect(Color.Black.copy(alpha = alpha.coerceIn(0f, 1f)))
                },
        )

        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            pageSpacing = 18.dp,
            key = { latestItems.getOrNull(it)?.key ?: it },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (phase == Phase.Shown) 1f else 0f }
                .hazeSource(haze)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (phase != Phase.Shown || zoomed) return@awaitEachGesture
                        var over = 0f
                        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            over = overSlop
                            change.consume()
                        } ?: return@awaitEachGesture
                        if (zoomed) return@awaitEachGesture
                        tracker.resetTracking()
                        tracker.addPosition(start.uptimeMillis, start.position)
                        val downward = over > 0f
                        var lifted = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } > 1) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val delta = change.positionChange()
                            if (downward) drag += delta else lifted += delta.y
                            change.consume()
                        }
                        val velocity = tracker.calculateVelocity()
                        if (downward) {
                            if (drag.y > dismissDistance || velocity.y > 1100f) {
                                close()
                            } else {
                                val from = drag
                                scope.launch {
                                    animate(0f, 1f, animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f)) { v, _ ->
                                        drag = from * (1f - v)
                                    }
                                }
                            }
                        } else if (lifted < -upDistance || velocity.y < -900f) {
                            info = true
                            chrome = true
                        }
                    }
                },
        ) { page ->
            val item = latestItems.getOrNull(page) ?: return@HorizontalPager
            val isCurrent = page == pager.currentPage
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (isCurrent) {
                            val s = dragScale(drag.y, size.height)
                            translationX = drag.x
                            translationY = drag.y
                            scaleX = s
                            scaleY = s
                        }
                    },
            ) {
                val onTap = {
                    if (info) info = false else chrome = !chrome
                }
                if (item.isVideo) {
                    VideoPage(
                        item = item,
                        active = isCurrent && phase == Phase.Shown,
                        controlsVisible = showChrome && !info,
                        container = container,
                        onTap = onTap,
                    )
                } else {
                    PhotoPage(
                        item = item,
                        settled = pager.settledPage == page,
                        onTap = onTap,
                        onZoomed = { if (page == pager.currentPage) zoomed = it },
                    )
                }
            }
        }

        if (phase != Phase.Shown) {
            transitionItem?.let { item ->
                TransitionImage(item, thumbRect, fullRect) { progress.value }
            }
        }

        // ---- chrome ----------------------------------------------------------------------
        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCircleButton(LumenIcons.Back, barHaze, contentDescription = "Close", onClick = close)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (current != null) {
                        if (set == ActionSet.External) {
                            Text(current.name, style = LumenType.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Text(dayLabel(current.dateTaken), style = LumenType.Headline, maxLines = 1)
                            Text(timeLabel(current.dateTaken), style = LumenType.Footnote.copy(color = Color.White.copy(alpha = 0.7f)))
                        }
                    }
                }
                Spacer(Modifier.size(44.dp))
            }
        }

        AnimatedVisibility(
            visible = showChrome && info && current != null,
            enter = fadeIn(tween(180)) + slideInVertically { it / 3 },
            exit = fadeOut(tween(140)) + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            current?.let { InfoCard(it, set, barHaze) }
        }

        AnimatedVisibility(
            visible = showChrome && current != null,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val item = current ?: return@AnimatedVisibility
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .height(58.dp)
                    .glass(barHaze, CircleShape)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                when (set) {
                    ActionSet.Library -> {
                        BarIcon(LumenIcons.Share, "Share") { actions.share(listOf(item)) }
                        BarIcon(
                            if (item.isFavorite) LumenIcons.Heart else LumenIcons.HeartOutline,
                            "Favorite",
                            tint = if (item.isFavorite) Lumen.Rose else Color.White,
                        ) { actions.setFavorite(listOf(item), !item.isFavorite) }
                        BarIcon(LumenIcons.Info, "Info", tint = if (info) Lumen.Accent else Color.White) { info = !info }
                        BarIcon(LumenIcons.EyeOff, "Hide") { actions.hide(listOf(item)) }
                        BarIcon(LumenIcons.Trash, "Delete") { actions.trash(listOf(item)) }
                    }
                    ActionSet.Hidden -> {
                        BarIcon(LumenIcons.Info, "Info", tint = if (info) Lumen.Accent else Color.White) { info = !info }
                        BarIcon(LumenIcons.Eye, "Unhide") { actions.unhide(listOf(item)) }
                        BarIcon(LumenIcons.Trash, "Delete", tint = Lumen.Danger) { actions.deleteForever(listOf(item)) }
                    }
                    ActionSet.Trash -> {
                        BarIcon(LumenIcons.Restore, "Recover") { actions.recover(listOf(item)) }
                        BarIcon(LumenIcons.Info, "Info", tint = if (info) Lumen.Accent else Color.White) { info = !info }
                        BarIcon(LumenIcons.Trash, "Delete", tint = Lumen.Danger) { actions.deleteForever(listOf(item)) }
                    }
                    ActionSet.External -> {
                        BarIcon(LumenIcons.Share, "Share") { actions.share(listOf(item)) }
                        BarIcon(LumenIcons.Info, "Info", tint = if (info) Lumen.Accent else Color.White) { info = !info }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoPage(
    item: MediaItem,
    settled: Boolean,
    onTap: () -> Unit,
    onZoomed: (Boolean) -> Unit,
) {
    val context = LocalPlatformContext.current
    val zoomState = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f)))
    val request = remember(item.key) {
        ImageRequest.Builder(context)
            .data(item.fullData())
            .placeholderMemoryCacheKey(MemoryCache.Key(item.thumbCacheKey))
            .build()
    }
    ZoomableAsyncImage(
        model = request,
        contentDescription = item.name,
        state = zoomState,
        modifier = Modifier.fillMaxSize(),
        onClick = { onTap() },
    )
    val zoomed = (zoomState.zoomableState.zoomFraction ?: 0f) > 0.01f
    LaunchedEffect(zoomed) { onZoomed(zoomed) }
    if (!settled) {
        LaunchedEffect(Unit) { zoomState.zoomableState.resetZoom(animationSpec = SnapSpec()) }
    }
}

/** A single thumbnail morphing between its grid cell and its fitted full-screen frame. */
@Composable
private fun TransitionImage(item: MediaItem, thumb: Rect?, full: Rect, progress: () -> Float) {
    val from = thumb ?: run {
        val inset = min(full.width, full.height) * 0.06f
        Rect(full.left + inset, full.top + inset, full.right - inset, full.bottom - inset)
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val r = lerpRect(from, full, progress())
                    val placeable = measurable.measure(
                        Constraints.fixed(max(1, r.width.roundToInt()), max(1, r.height.roundToInt())),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(r.left.roundToInt(), r.top.roundToInt())
                    }
                }
                .graphicsLayer {
                    clip = true
                    alpha = if (thumb == null) progress() else 1f
                },
        ) {
            AsyncImage(
                model = rememberThumbRequest(item),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun InfoCard(item: MediaItem, set: ActionSet, haze: dev.chrisbanes.haze.HazeState?) {
    val context = LocalContext.current
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .padding(start = 20.dp, end = 20.dp, bottom = 84.dp)
            .fillMaxWidth()
            .glass(haze, RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        val zone = Instant.ofEpochMilli(item.dateTaken).atZone(ZoneId.systemDefault())
        if (set != ActionSet.External) {
            Text(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(zone) + " · " + timeLabel(item.dateTaken),
                style = LumenType.Footnote,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(item.name, style = LumenType.Headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        val parts = buildList {
            if (!item.isVideo && item.width > 0) add("%.1f MP".format(item.width * item.height / 1_000_000f))
            if (item.width > 0) add("${item.width} × ${item.height}")
            if (item.isVideo && item.durationMs > 0) add(formatDuration(item.durationMs))
            if (item.sizeBytes > 0) add(Formatter.formatShortFileSize(context, item.sizeBytes))
        }
        Text(parts.joinToString("  ·  "), style = LumenType.Callout.copy(color = Color.White.copy(alpha = 0.9f)))
        val where = when (set) {
            ActionSet.Hidden -> "Hidden · from ${item.relativePath.ifBlank { "Library" }}"
            else -> item.relativePath.trimEnd('/')
        }
        if (where.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(where, style = LumenType.Footnote)
        }
    }
}

private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val weekdayFmt = DateTimeFormatter.ofPattern("EEEE")
private val monthDayFmt = DateTimeFormatter.ofPattern("MMMM d")
private val fullDayFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy")

fun dayLabel(millis: Long): String {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.isAfter(today.minusDays(7)) -> weekdayFmt.format(date)
        date.year == today.year -> monthDayFmt.format(date)
        else -> fullDayFmt.format(date)
    }
}

fun timeLabel(millis: Long): String =
    timeFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
