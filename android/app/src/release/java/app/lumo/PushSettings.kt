package app.lumo

import android.content.Context
import androidx.compose.runtime.Composable

// Production variant does not link Firebase, expose an opt-in UI,
// or start a Firebase token on the user's behalf.
@Composable
fun PushSettings(session: String, me: User) = Unit

internal object PushLifecycle {
    fun forgetOnLogout(context: Context) {
        PushOptState.clear(context)
    }
}
