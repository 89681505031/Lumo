package app.lumo

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

/**
 * Device-scoped UI preferences, not server privacy controls or encryption.
 * The per-account key prevents one signed-in user's preference from changing
 * another signed-in account's settings on the same phone.
 */
class LumoPrivacy(private val prefs:android.content.SharedPreferences) {
    var hideChatPreviews by mutableStateOf(prefs.getBoolean("hide_chat_previews",false))
        private set
    var protectScreenshots by mutableStateOf(prefs.getBoolean("protect_screenshots",false))
        private set

    fun changeChatPreviews(hidden:Boolean) {
        hideChatPreviews=hidden
        prefs.edit().putBoolean("hide_chat_previews",hidden).apply()
    }

    fun changeScreenshotProtection(enabled:Boolean) {
        protectScreenshots=enabled
        prefs.edit().putBoolean("protect_screenshots",enabled).apply()
    }
}

@Composable
fun rememberLumoPrivacy(userId:String):LumoPrivacy {
    val context=LocalContext.current.applicationContext
    return remember(context,userId) {
        // UUIDs are server-assigned; hashing still keeps filesystem/pref keys bounded.
        val key=MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        LumoPrivacy(context.getSharedPreferences("lumo_privacy_$key",Context.MODE_PRIVATE))
    }
}

/** Android best-effort FLAG_SECURE. Applies to every window from this activity. */
@Composable
fun LumoScreenshotProtection(enabled:Boolean) {
    val activity=LocalContext.current as? Activity
    DisposableEffect(activity,enabled) {
        if(enabled) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }else{
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            // If the session changes, the next effect applies the new user's preference.
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

@Composable
fun LumoPrivacyControls(privacy:LumoPrivacy) {
    Column(Modifier.fillMaxWidth().lumoGlass(26).padding(17.dp)) {
        Text("Конфиденциальность",
            style=MaterialTheme.typography.titleLarge,
            fontWeight=FontWeight.Bold,color=Color.White)
        Spacer(Modifier.height(7.dp))
        Text("Эти настройки действуют только на этом устройстве.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(13.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Скрыть текст в списке чатов",color=Color.White)
                Text("Последнее сообщение останется скрытым на главном экране",
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked=privacy.hideChatPreviews,
                onCheckedChange=privacy::changeChatPreviews
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Защита от снимков экрана",color=Color.White)
                Text("Android ограничит скриншоты и миниатюру в недавних приложениях",
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked=privacy.protectScreenshots,
                onCheckedChange=privacy::changeScreenshotProtection
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            "Не заменяет шифрование и не блокирует съёмку другим устройством.",
            style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
