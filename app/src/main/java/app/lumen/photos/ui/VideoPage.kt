package app.lumen.photos.ui

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.lumen.photos.data.MediaItem
import app.lumen.photos.ui.theme.Lumen
import app.lumen.photos.ui.theme.LumenType
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem as ExoItem

@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    item: MediaItem,
    active: Boolean,
    controlsVisible: Boolean,
    container: Size,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val player = remember(item.key) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    var playing by remember { mutableStateOf(false) }
    var firstFrame by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(item.durationMs.coerceAtLeast(1L)) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player.duration > 0) duration = player.duration
                if (state == Player.STATE_ENDED) {
                    player.pause()
                    player.seekTo(0)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // iOS plays a video the moment it's the one you're looking at.
    LaunchedEffect(active) {
        if (active) {
            player.play()
        } else {
            player.pause()
            player.seekTo(0)
            position = 0
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = player.currentPosition
            delay(80)
        }
        position = player.currentPosition
    }

    val fit = fitRect(container, item.aspect)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
    ) {
        Box(
            Modifier
                .offset { IntOffset(fit.left.roundToInt(), fit.top.roundToInt()) }
                .size(with(density) { fit.width.toDp() }, with(density) { fit.height.toDp() }),
        ) {
            AsyncImage(
                model = rememberThumbRequest(item),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (firstFrame) 1f else 0f },
            )
        }

        AnimatedVisibility(
            visible = !playing && controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            GlassCircleButton(LumenIcons.Play, null, size = 76.dp, contentDescription = "Play") { player.play() }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Scrubber(
                position = position,
                duration = duration,
                playing = playing,
                onToggle = { if (playing) player.pause() else player.play() },
                onSeek = {
                    position = it
                    player.seekTo(it)
                },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(start = 20.dp, end = 20.dp, bottom = 84.dp),
            )
        }
    }
}

@Composable
private fun Scrubber(
    position: Long,
    duration: Long,
    playing: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = (position.toFloat() / duration.coerceAtLeast(1L)).coerceIn(0f, 1f)
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(Color(0xCC1C1C1E))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (playing) LumenIcons.Pause else LumenIcons.Play,
            if (playing) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier
                .size(22.dp)
                .clickable(onClick = onToggle),
        )
        Spacer(Modifier.width(12.dp))
        Text(formatDuration(position), style = LumenType.Footnote.copy(color = Color.White), modifier = Modifier.width(44.dp))
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(duration) {
                    detectTapGestures { onSeek((it.x / size.width * duration).toLong().coerceIn(0, duration)) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures { change, _ ->
                        onSeek((change.position.x / size.width * duration).toLong().coerceIn(0, duration))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(Lumen.SunsetH))
            Box(
                Modifier
                    .offset(x = maxWidth * fraction - 7.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "-" + formatDuration((duration - position).coerceAtLeast(0)),
            style = LumenType.Footnote.copy(color = Color.White),
        )
    }
}
