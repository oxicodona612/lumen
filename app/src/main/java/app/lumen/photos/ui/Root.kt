package app.lumen.photos.ui

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.GalleryViewModel
import app.lumen.photos.Launch
import app.lumen.photos.data.MediaItem
import app.lumen.photos.data.Source
import app.lumen.photos.ui.theme.Lumen
import app.lumen.photos.ui.theme.LumenType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MediaAccess { None, Partial, Full }

fun Context.mediaAccess(): MediaAccess {
    fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    return when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.Full
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial
        else -> MediaAccess.None
    }
}

private val MediaPermissions = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
)

@Composable
fun LumenRoot(vm: GalleryViewModel, launch: Launch, onLaunchHandled: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var access by remember { mutableStateOf(context.mediaAccess()) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = context.mediaAccess()
        if (access != MediaAccess.None) vm.start()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    access = context.mediaAccess()
                    if (access != MediaAccess.None) vm.start()
                }
                // Leaving the app re-locks Hidden. Coming back means proving it's you again.
                Lifecycle.Event.ON_STOP -> vm.lockVault()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // MediaStore consent sheets, one at a time.
    var pending by remember { mutableStateOf<app.lumen.photos.ConsentBroker.Request?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pending?.result?.complete(result.resultCode == Activity.RESULT_OK)
        pending = null
    }
    LaunchedEffect(Unit) {
        vm.consent.requests.collect { request ->
            pending = request
            consentLauncher.launch(IntentSenderRequest.Builder(request.sender).build())
            request.result.await()
        }
    }

    var toast by remember { mutableStateOf<String?>(null) }
    var lastToast by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        vm.messages.collectLatest {
            lastToast = it
            toast = it
            delay(2200)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            launch is Launch.Standalone -> StandaloneViewer(vm, launch) { activity?.finish() }
            access == MediaAccess.None -> Onboarding { permissionLauncher.launch(MediaPermissions) }
            else -> MainScreens(
                vm = vm,
                access = access,
                review = launch as? Launch.Review,
                onLaunchHandled = onLaunchHandled,
                onManageAccess = { permissionLauncher.launch(MediaPermissions) },
            )
        }

        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn() + slideInVertically { -it } + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(top = TopBarHeight + 10.dp),
        ) {
            Box(
                Modifier
                    .glass(null, CircleShape)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            ) {
                Text(lastToast, style = LumenType.Callout)
            }
        }
    }
}

@Composable
private fun MainScreens(
    vm: GalleryViewModel,
    access: MediaAccess,
    review: Launch.Review?,
    onLaunchHandled: () -> Unit,
    onManageAccess: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()

    val libraryOrNull by vm.library.collectAsStateWithLifecycle()
    val library = libraryOrNull.orEmpty()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val unlocked by vm.vaultUnlocked.collectAsStateWithLifecycle()

    val stack = remember { mutableStateListOf<Source>() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val libraryState = rememberLazyGridState()
    val libraryRegistry = remember { ThumbRegistry() }
    var libraryColumns by rememberSaveable { mutableIntStateOf(3) }
    val libraryPinned = remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    var viewerKey by remember { mutableStateOf<String?>(null) }
    var librarySelecting by remember { mutableStateOf(false) }
    val rootHaze = rememberHazeState()

    val actions = remember(vm) {
        MediaActions(
            share = { shareItems(context, it) },
            setFavorite = { items, fav -> vm.setFavorite(items, fav) },
            hide = { vm.hide(it) },
            trash = { vm.moveToTrash(it) },
            unhide = { vm.unhide(it) },
            recover = { vm.recover(it) },
            deleteForever = { vm.deleteForever(it) },
        )
    }

    fun itemsOf(source: Source): List<MediaItem> = when (source) {
        Source.Library -> library
        is Source.Bucket -> library.filter { it.bucketId == source.id }
        Source.Videos -> library.filter { it.isVideo }
        Source.Screenshots -> library.filter { it.isScreenshot }
        Source.Favorites -> library.filter { it.isFavorite }
        Source.Hidden -> if (unlocked) hidden else emptyList()
        Source.Trash -> trash
        is Source.External -> listOf(source.item)
    }

    // Camera "review": land on the library with that shot already open.
    LaunchedEffect(review, libraryOrNull) {
        val r = review ?: return@LaunchedEffect
        val lib = libraryOrNull ?: return@LaunchedEffect
        val id = runCatching { ContentUris.parseId(r.uri) }.getOrNull()
        val item = lib.firstOrNull { it.id == id && r.uri.authority == MediaStore.AUTHORITY }
        if (item != null) {
            stack.clear()
            tab = 0
            viewer = ViewerRequest(Source.Library, item.key, null)
        }
        onLaunchHandled()
    }

    val secure = (stack.lastOrNull() == Source.Hidden && unlocked) || viewer?.source == Source.Hidden
    DisposableEffect(secure) {
        val window = activity?.window
        if (secure) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
    LaunchedEffect(unlocked) {
        if (!unlocked && viewer?.source == Source.Hidden) {
            viewer = null
            viewerKey = null
        }
    }

    BackHandler(enabled = viewer == null && stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = viewer == null && stack.isEmpty() && tab == 1) { tab = 0 }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = stack.toList(),
            transitionSpec = {
                val push = targetState.size > initialState.size
                val spec = tween<androidx.compose.ui.unit.IntOffset>(340, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                if (push) {
                    (slideInHorizontally(spec) { it } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(340)))
                } else {
                    (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(340))) togetherWith
                        (slideOutHorizontally(spec) { it } + fadeOut(tween(200)))
                }
            },
            modifier = Modifier.fillMaxSize().hazeSource(rootHaze),
            label = "nav",
        ) { routes ->
            when (val route = routes.lastOrNull()) {
                null -> Crossfade(tab, animationSpec = tween(180), label = "tabs") { t ->
                    if (t == 0) {
                        GridScreen(
                            source = Source.Library,
                            items = library,
                            state = libraryState,
                            registry = libraryRegistry,
                            columns = libraryColumns,
                            onColumnsChange = { libraryColumns = it },
                            hiddenKey = viewerKey,
                            actions = actions,
                            onOpen = { item, anchor -> viewer = ViewerRequest(Source.Library, item.key, anchor) },
                            onBack = null,
                            onSelectingChange = { librarySelecting = it },
                            bottomBarSpace = TabBarSpace,
                            pinned = libraryPinned,
                            empty = if (libraryOrNull == null) "" to "" else "No Photos" to "Photos and videos you take will appear here.",
                            menu = { haze -> LibraryMenu(haze, access, library, onManageAccess) },
                        )
                    } else {
                        AlbumsScreen(
                            albums = albums,
                            library = library,
                            hiddenCount = hidden.size,
                            vaultUnlocked = unlocked,
                            onOpen = { stack.add(it) },
                            bottomBarSpace = TabBarSpace,
                        )
                    }
                }
                else -> CollectionRoute(
                    source = route,
                    items = itemsOf(route),
                    vm = vm,
                    unlocked = unlocked,
                    hiddenKey = viewerKey,
                    actions = actions,
                    onOpen = { item, anchor -> viewer = ViewerRequest(route, item.key, anchor) },
                    onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
                    onUnlock = { activity?.unlockVault { vm.unlockVault() } },
                )
            }
        }

        AnimatedVisibility(
            visible = stack.isEmpty() && !librarySelecting && viewer == null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TabBar(
                selected = tab,
                haze = rootHaze,
                onSelect = { t ->
                    if (t == tab && t == 0 && library.isNotEmpty()) {
                        scope.launch { libraryState.animateScrollToItem(library.size) }
                    }
                    tab = t
                },
            )
        }

        viewer?.let { request ->
            ViewerOverlay(
                items = itemsOf(request.source),
                request = request,
                actions = actions,
                onCurrentKey = { viewerKey = it },
                onClosed = {
                    viewer = null
                    viewerKey = null
                },
            )
        }
    }
}

@Composable
private fun CollectionRoute(
    source: Source,
    items: List<MediaItem>,
    vm: GalleryViewModel,
    unlocked: Boolean,
    hiddenKey: String?,
    actions: MediaActions,
    onOpen: (MediaItem, ThumbAnchor) -> Unit,
    onBack: () -> Unit,
    onUnlock: () -> Unit,
) {
    val context = LocalContext.current
    if (source == Source.Trash) LaunchedEffect(Unit) { vm.wantTrash() }
    if (source == Source.Hidden && !unlocked) {
        HiddenLocked(hasLock = context.hasScreenLock(), onUnlock = onUnlock, onBack = onBack)
        return
    }
    val state = rememberLazyGridState()
    val registry = remember { ThumbRegistry() }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    val pinned = remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    GridScreen(
        source = source,
        items = items,
        state = state,
        registry = registry,
        columns = columns,
        onColumnsChange = { columns = it },
        hiddenKey = hiddenKey,
        actions = actions,
        onOpen = onOpen,
        onBack = onBack,
        onSelectingChange = { },
        bottomBarSpace = 24.dp,
        pinned = pinned,
        empty = when (source) {
            Source.Hidden -> "No Hidden Photos" to "Select photos in your library and tap the eye to hide them here."
            Source.Trash -> "No Recently Deleted Items" to "Deleted photos and videos stay here for 30 days."
            Source.Favorites -> "No Favorites" to "Tap the heart on any photo to add it here."
            else -> "No Items" to ""
        },
        emptyIcon = when (source) {
            Source.Hidden -> LumenIcons.EyeOff
            Source.Trash -> LumenIcons.Trash
            Source.Favorites -> LumenIcons.HeartOutline
            Source.Videos -> LumenIcons.Video
            else -> LumenIcons.Library
        },
        cellBadge = if (source == Source.Trash) {
            { item ->
                val days = ((item.expiresAt - now) / 86_400_000L).coerceAtLeast(0)
                Text(
                    if (days == 1L) "1 day" else "$days days",
                    style = LumenType.Caption,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        } else null,
    )
}

@Composable
private fun TabBar(selected: Int, haze: HazeState, onSelect: (Int) -> Unit) {
    val itemWidth = 104.dp
    val indicator by animateDpAsState(itemWidth * selected, spring(dampingRatio = 0.72f, stiffness = 520f), label = "tab")
    Box(
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .padding(bottom = 14.dp)
            .height(64.dp)
            .glass(haze, CircleShape)
            .padding(5.dp),
    ) {
        Box(
            Modifier
                .offset(x = indicator)
                .width(itemWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.13f)),
        )
        Row {
            TabItem("Library", LumenIcons.Library, selected == 0, itemWidth) { onSelect(0) }
            TabItem("Albums", LumenIcons.Albums, selected == 1, itemWidth) { onSelect(1) }
        }
    }
}

@Composable
private fun TabItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val tint = if (active) Lumen.Accent else Color.White.copy(alpha = 0.88f)
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clip(CircleShape)
            .pressable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = LumenType.Caption.copy(color = tint))
    }
}

@Composable
private fun LibraryMenu(haze: HazeState, access: MediaAccess, library: List<MediaItem>, onManageAccess: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        GlassCircleButton(LumenIcons.More, haze, size = 36.dp, contentDescription = "More") { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = Lumen.Raised2,
        ) {
            DropdownMenuItem(
                text = { Text("Make Lumen the default viewer", style = LumenType.Callout) },
                onClick = {
                    open = false
                    // Opening a photo with no explicit target makes Android show its
                    // "Open with" sheet; picking Lumen + Always sets the default.
                    val sample = library.lastOrNull { !it.isVideo } ?: library.lastOrNull()
                    if (sample != null) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(sample.uri, sample.mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    }
                },
            )
            if (!MediaStore.canManageMedia(context)) {
                DropdownMenuItem(
                    text = { Text("Skip delete & hide confirmations", style = LumenType.Callout) },
                    onClick = {
                        open = false
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    },
                )
            }
            if (access == MediaAccess.Partial) {
                DropdownMenuItem(
                    text = { Text("Manage photo access", style = LumenType.Callout) },
                    onClick = {
                        open = false
                        onManageAccess()
                    },
                )
            }
        }
    }
}

@Composable
private fun StandaloneViewer(vm: GalleryViewModel, launch: Launch.Standalone, onDone: () -> Unit) {
    val context = LocalContext.current
    var item by remember(launch) { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(launch) {
        item = withContext(Dispatchers.IO) { vm.describe(launch.uri, launch.mime) }
    }
    val current = item ?: return
    val actions = remember {
        MediaActions(
            share = { shareItems(context, it) },
            setFavorite = { _, _ -> },
            hide = { },
            trash = { },
            unhide = { },
            recover = { },
            deleteForever = { },
        )
    }
    ViewerOverlay(
        items = listOf(current),
        request = ViewerRequest(Source.External(current), current.key, null),
        actions = actions,
        onCurrentKey = { },
        onClosed = onDone,
    )
}

fun shareItems(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { it.uri })
    val mime = items.map { it.mime }.distinct().singleOrNull() ?: when {
        items.none { it.isVideo } -> "image/*"
        items.all { it.isVideo } -> "video/*"
        else -> "*/*"
    }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    send.type = mime
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}
