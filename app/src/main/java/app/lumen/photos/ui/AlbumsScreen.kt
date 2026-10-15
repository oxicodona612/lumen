package app.lumen.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumen.photos.data.Album
import app.lumen.photos.data.MediaItem
import app.lumen.photos.data.Source
import app.lumen.photos.ui.theme.Lumen
import app.lumen.photos.ui.theme.LumenType
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private data class TypeRow(val source: Source, val icon: ImageVector, val count: Int?, val locked: Boolean = false)

@Composable
fun AlbumsScreen(
    albums: List<Album>,
    library: List<MediaItem>,
    hiddenCount: Int,
    vaultUnlocked: Boolean,
    onOpen: (Source) -> Unit,
    bottomBarSpace: Dp,
) {
    val haze = rememberHazeState()
    val statusPad = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val navPad = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    val topPad = statusPad + TopBarHeight

    val mediaTypes = listOf(
        TypeRow(Source.Videos, LumenIcons.Video, library.count { it.isVideo }),
        TypeRow(Source.Screenshots, LumenIcons.Phone, library.count { it.isScreenshot }),
        TypeRow(Source.Favorites, LumenIcons.HeartOutline, library.count { it.isFavorite }),
    )
    val utilities = listOf(
        TypeRow(Source.Hidden, LumenIcons.EyeOff, if (vaultUnlocked) hiddenCount else null, locked = !vaultUnlocked),
        TypeRow(Source.Trash, LumenIcons.Trash, null),
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPad + 30.dp, bottom = navPad + bottomBarSpace + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().hazeSource(haze),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("My Albums", if (albums.isEmpty()) null else albums.size.toString())
            }
            items(albums, key = { it.bucketId }) { album ->
                AlbumCard(album) { onOpen(Source.Bucket(album.bucketId, album.name)) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    SectionHeader("Media Types", null)
                    Spacer(Modifier.height(10.dp))
                    GroupedList(mediaTypes, onOpen)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    SectionHeader("Utilities", null)
                    Spacer(Modifier.height(10.dp))
                    GroupedList(utilities, onOpen)
                }
            }
        }

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
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .height(TopBarHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Albums", style = LumenType.Title.copy(fontSize = LumenType.Title.fontSize * 1.08f))
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navPad + 110.dp)
                .hazeBlur(input = HazeInput.Sources(haze), style = BottomEdgeStyle),
        )
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, style = LumenType.Title, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = LumenType.Callout.copy(color = Lumen.TextDim))
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = rememberThumbRequest(album.cover),
            contentDescription = album.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Lumen.Raised),
        )
        Spacer(Modifier.height(8.dp))
        Text(album.name, style = LumenType.Callout, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("%,d".format(album.count), style = LumenType.Footnote)
    }
}

@Composable
private fun GroupedList(rows: List<TypeRow>, onOpen: (Source) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Lumen.Raised),
    ) {
        rows.forEachIndexed { i, row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(row.source) }
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(row.icon, null, tint = Lumen.Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(sourceLabel(row.source), style = LumenType.Body, modifier = Modifier.weight(1f))
                when {
                    row.locked -> Icon(LumenIcons.Lock, "Locked", tint = Lumen.TextDim, modifier = Modifier.size(16.dp))
                    row.count != null -> Text("%,d".format(row.count), style = LumenType.Callout.copy(color = Lumen.TextDim))
                }
                Spacer(Modifier.width(6.dp))
                Icon(LumenIcons.Chevron, null, tint = Lumen.TextFaint, modifier = Modifier.size(20.dp))
            }
            if (i < rows.lastIndex) {
                HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 0.5.dp, color = Lumen.Hairline)
            }
        }
    }
}

private fun sourceLabel(source: Source) = when (source) {
    Source.Videos -> "Videos"
    Source.Screenshots -> "Screenshots"
    Source.Favorites -> "Favorites"
    Source.Hidden -> "Hidden"
    Source.Trash -> "Recently Deleted"
    else -> ""
}
