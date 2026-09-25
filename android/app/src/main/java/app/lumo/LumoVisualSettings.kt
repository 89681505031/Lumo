package app.lumo

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Kept for compatibility with preferences written by older Lumo builds.
 * The reference redesign intentionally uses one coherent visual language instead
 * of separate cosmic/aurora/violet themes.
 */
enum class LumoVisualTheme(val title:String) {
    COSMOS("Основная"),
    AURORA("Основная"),
    VIOLET("Основная"),
    MINIMAL("Основная")
}

class LumoAppearance(private val prefs:SharedPreferences) {
    var theme by mutableStateOf(LumoVisualTheme.MINIMAL)
        private set
    var animated by mutableStateOf(false)
        private set
    var lowPower by mutableStateOf(true)
        private set

    fun chooseTheme(value:LumoVisualTheme){
        theme=LumoVisualTheme.MINIMAL
        prefs.edit().putString("theme",LumoVisualTheme.MINIMAL.name).apply()
    }

    fun updateAnimationEnabled(value:Boolean){
        animated=false
        prefs.edit().putBoolean("animated",false).apply()
    }

    fun updateLowPowerMode(value:Boolean){
        lowPower=true
        prefs.edit().putBoolean("low_power",true).apply()
    }
}

val LocalLumoAppearance=staticCompositionLocalOf<LumoAppearance>{
    error("LumoAppearance must be provided by LumoTheme")
}

@Composable
fun rememberLumoAppearance():LumoAppearance {
    val ctx=LocalContext.current.applicationContext
    return remember(ctx){
        LumoAppearance(
            ctx.getSharedPreferences("lumo_visual",Context.MODE_PRIVATE)
        )
    }
}

fun lumoSpaceColors(theme:LumoVisualTheme):List<Color> =
    listOf(Color(0xFF0B0C10),Color(0xFF0B0C10))

@Composable
fun LumoAppearanceControls(){
    Column(
        Modifier
            .fillMaxWidth()
            .lumoGlass(22)
            .padding(horizontal=17.dp,vertical=16.dp)
    ){
        Text(
            "Оформление",
            fontWeight=FontWeight.Bold,
            style=MaterialTheme.typography.titleMedium,
            color=Color.White
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Единый тёмный интерфейс Lumo без стеклянных панелей, планет, мерцания и неонового свечения.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
