package app.lumen.photos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lumen.photos.data.MediaItem
import app.lumen.photos.ui.theme.LumenType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val TouchWidth = 44.dp
private val TouchHeight = 64.dp

/**
 * Right-edge scrubber: shows while the grid moves, fades when it settles. Grabbing the
 * thumb jumps straight through the library with a month bubble beside the finger.
 */
@Composable
fun FastScroller(
    state: LazyGridState,
    items: List<MediaItem>,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestItems by rememberUpdatedState(items)
    val latestColumns by rememberUpdatedState(columns)

    // 0..1 through the grid, or null when everything already fits on screen.
    val scrolled by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val range = info.totalItemsCount - visible.size
            if (visible.isEmpty() || range <= latestColumns) return@derivedStateOf null
            val cell = visible.first().size.height.coerceAtLeast(1)
            val exact = state.firstVisibleItemIndex +
                state.firstVisibleItemScrollOffset.toFloat() / cell * latestColumns
            (exact / range).coerceIn(0f, 1f)
        }
    }

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    var bubble by remember { mutableStateOf("") }

    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) {
            shown = true
        } else {
            delay(1300)
            shown = false
        }
    }
    val visible = shown && scrolled != null
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 120 else 450), label = "scroller")
    val thumbWidth by animateDpAsState(if (dragging) 8.dp else 6.dp, spring(stiffness = 900f), label = "thumbW")
    val thumbHeight by animateDpAsState(if (dragging) 60.dp else 46.dp, spring(stiffness = 900f), label = "thumbH")

    BoxWithConstraints(modifier.fillMaxHeight().width(220.dp)) {
        val trackPx = with(density) { (maxHeight - TouchHeight).toPx() }.coerceAtLeast(1f)
        val fraction = if (dragging) dragFraction else scrolled ?: 0f
        val thumbY = (fraction * trackPx).roundToInt()

        fun jumpTo(f: Float) {
            val info = state.layoutInfo
            val range = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
            val cols = latestColumns
            val index = ((f * range).roundToInt() / cols) * cols
            val label = latestItems.getOrNull((index + cols).coerceAtMost(latestItems.lastIndex))
                ?.let { gridTitle(it.dateTaken, 5) }
                .orEmpty()
            if (label != bubble) {
                if (bubble.isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                bubble = label
            }
            scope.launch { state.scrollToItem(index) }
        }

        AnimatedVisibility(
            visible = dragging && bubble.isNotEmpty(),
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(-with(density) { (TouchWidth + 6.dp).roundToPx() }, thumbY) }
                .padding(top = 12.dp),
        ) {
            Box(
                Modifier
                    .glass(null, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(bubble, style = LumenType.Callout)
            }
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY) }
                .size(TouchWidth, TouchHeight)
                .graphicsLayer { this.alpha = alpha }
                // Only grabbable while showing, so it never eats taps on the last column.
                .then(
                    if (!visible) Modifier else Modifier.pointerInput(trackPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            dragging = true
                            dragFraction = scrolled ?: 0f
                            bubble = ""
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            jumpTo(dragFraction)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                dragFraction = (dragFraction + change.positionChange().y / trackPx).coerceIn(0f, 1f)
                                jumpTo(dragFraction)
                                change.consume()
                            }
                            dragging = false
                        }
                    },
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .padding(end = 3.dp)
                    .size(thumbWidth, thumbHeight)
                    // Dark rim keeps it readable over white screenshots and skies.
                    .border(1.dp, Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(1.dp)
                    .background(Color.White.copy(alpha = if (dragging) 1f else 0.9f), CircleShape),
            )
        }
    }
}
