package app.lumen.photos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import app.lumen.photos.ui.LumenRoot
import app.lumen.photos.ui.theme.LumenTheme

/** How we were opened: from the launcher, or by another app handing us one file. */
sealed interface Launch {
    data object Library : Launch

    /** Camera thumbnail taps: open the library with this item already full-screen. */
    data class Review(val uri: Uri, val mime: String?) : Launch

    /** Some other app said "view this": standalone viewer, back returns to them. */
    data class Standalone(val uri: Uri, val mime: String?) : Launch
}

class MainActivity : FragmentActivity() {

    private val vm: GalleryViewModel by viewModels()
    private val launch = mutableStateOf<Launch>(Launch.Library)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        launch.value = parse(intent)
        setContent {
            LumenTheme {
                LumenRoot(
                    vm = vm,
                    launch = launch.value,
                    onLaunchHandled = { launch.value = Launch.Library },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch.value = parse(intent)
    }

    private fun parse(intent: Intent?): Launch {
        val data = intent?.data ?: return Launch.Library
        return when (intent.action) {
            MediaStore.ACTION_REVIEW, "com.android.camera.action.REVIEW" -> Launch.Review(data, intent.type)
            Intent.ACTION_VIEW -> Launch.Standalone(data, intent.type)
            else -> Launch.Library
        }
    }
}
