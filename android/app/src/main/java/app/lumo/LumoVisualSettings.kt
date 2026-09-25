package app.lumo

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Local-only visual choices. They never leave the device or change the server/account. */
enum class LumoVisualTheme(val title:String) {
    COSMOS("Космос"),
    AURORA("Сияние"),
    VIOLET("Фиолетовая"),
    MINIMAL("Без эффектов")
}

class LumoAppearance(private val prefs:SharedPreferences) {
    var theme by mutableStateOf(
        runCatching{
            LumoVisualTheme.valueOf(prefs.getString("theme", LumoVisualTheme.COSMOS.name)!!)
        }.getOrDefault(LumoVisualTheme.COSMOS)
    )
        private set
    var animated by mutableStateOf(prefs.getBoolean("animated",false))
        private set
    var lowPower by mutableStateOf(prefs.getBoolean("low_power",false))
        private set

    fun chooseTheme(value:LumoVisualTheme){
        theme=value
        prefs.edit().putString("theme",value.name).apply()
    }
    fun updateAnimationEnabled(value:Boolean){
        animated=value
        prefs.edit().putBoolean("animated",value).apply()
    }
    fun updateLowPowerMode(value:Boolean){
        lowPower=value
        prefs.edit().putBoolean("low_power",value).apply()
    }
}

val LocalLumoAppearance=staticCompositionLocalOf<LumoAppearance>{
    error("LumoAppearance must be provided by LumoTheme")
}

@Composable
fun rememberLumoAppearance():LumoAppearance {
    val ctx=LocalContext.current.applicationContext
    return remember(ctx){LumoAppearance(ctx.getSharedPreferences("lumo_visual",Context.MODE_PRIVATE))}
}

fun lumoSpaceColors(theme:LumoVisualTheme):List<Color> = when(theme){
    LumoVisualTheme.COSMOS->listOf(Color(0xFF020B3A),Color(0xFF0C1D72),Color(0xFF180B50))
    LumoVisualTheme.AURORA->listOf(Color(0xFF001F35),Color(0xFF03556D),Color(0xFF174A73))
    LumoVisualTheme.VIOLET->listOf(Color(0xFF180738),Color(0xFF57238B),Color(0xFF340B57))
    LumoVisualTheme.MINIMAL->listOf(Color(0xFF090F22),Color(0xFF121D38),Color(0xFF11172E))
}

/** One stable 2x2 theme chooser plus independently controllable visual effects. */
@Composable
fun LumoAppearanceControls(){
    val appearance=LocalLumoAppearance.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B141A))
            .padding(horizontal=18.dp,vertical=14.dp)
    ){
        Text(
            "Оформление",
            fontWeight=FontWeight.Bold,
            style=MaterialTheme.typography.titleMedium,
            color=Color(0xFFE9EDEF)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Тёмная тема Lumo",
            color=Color(0xFFE9EDEF),
            style=MaterialTheme.typography.bodyLarge
        )
        Text(
            "Единый плоский интерфейс без стекла и неоновых эффектов.",
            style=MaterialTheme.typography.bodySmall,
            color=Color(0xFF8696A0)
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment=Alignment.CenterVertically
        ){
            Column(Modifier.weight(1f)){
                Text("Анимация",color=Color(0xFFE9EDEF))
                Text(
                    "Анимировать элементы интерфейса",
                    style=MaterialTheme.typography.bodySmall,
                    color=Color(0xFF8696A0)
                )
            }
            Switch(
                checked=appearance.animated,
                onCheckedChange=appearance::updateAnimationEnabled
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment=Alignment.CenterVertically
        ){
            Column(Modifier.weight(1f)){
                Text("Экономия батареи",color=Color(0xFFE9EDEF))
                Text(
                    "Уменьшить фоновые визуальные эффекты",
                    style=MaterialTheme.typography.bodySmall,
                    color=Color(0xFF8696A0)
                )
            }
            Switch(
                checked=appearance.lowPower,
                onCheckedChange=appearance::updateLowPowerMode
            )
        }
    }
}
