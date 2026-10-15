package app.lumen.photos.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.lumen.photos.ui.theme.Lumen
import app.lumen.photos.ui.theme.LumenType

private const val VaultAuthenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

fun Context.hasScreenLock(): Boolean =
    BiometricManager.from(this).canAuthenticate(VaultAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Fingerprint / face, falling back to the device PIN. With no screen lock configured
 * there is nothing to verify against, so the album opens and the lock screen says so.
 */
fun FragmentActivity.unlockVault(onSuccess: () -> Unit) {
    if (!hasScreenLock()) {
        onSuccess()
        return
    }
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Hidden")
            .setSubtitle("Lumen")
            .setAllowedAuthenticators(VaultAuthenticators)
            .build(),
    )
}

@Composable
fun HiddenLocked(hasLock: Boolean, onUnlock: () -> Unit, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .background(
                Brush.radialGradient(
                    listOf(Lumen.Ember.copy(alpha = 0.16f), Color.Transparent),
                    radius = 900f,
                ),
            ),
    ) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .height(TopBarHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircleButton(LumenIcons.Back, null, contentDescription = "Back", onClick = onBack)
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .background(Lumen.Raised, CircleShape)
                    .border(0.7.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LumenIcons.Lock, null, tint = Lumen.Accent, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Hidden Album is Locked", style = LumenType.Title)
            Spacer(Modifier.height(10.dp))
            Text(
                "Hidden photos are stored privately inside Lumen. They don't appear in your library, in other apps, or in file managers.",
                style = LumenType.Subhead,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            SunsetButton("View Album", Modifier.fillMaxWidth(), onClick = onUnlock)
            if (!hasLock) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Set a screen lock in Settings to protect this album with your fingerprint or PIN.",
                    style = LumenType.Footnote.copy(color = Lumen.TextFaint),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
