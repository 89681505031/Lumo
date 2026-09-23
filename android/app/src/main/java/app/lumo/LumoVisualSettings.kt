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
    Column(Modifier.fillMaxWidth().lumoGlass(26).padding(16.dp)){
        Text("Оформление",fontWeight=FontWeight.Bold,
            style=MaterialTheme.typography.titleMedium,color=Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Выбери фон Lumo. Настройки сохраняются на устройстве.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(13.dp))
        LumoVisualTheme.values().toList().chunked(2).forEach{pair->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)){
                pair.forEach{item->
                    val selected=appearance.theme==item
                    val shape=RoundedCornerShape(17.dp)
                    Column(
                        Modifier.weight(1f).border(
                            if(selected)2.dp else 1.dp,
                            if(selected)LumoCyan else Color(0xFF7896D8),shape
                        ).background(Brush.linearGradient(lumoSpaceColors(item)),shape)
                            .clickable{appearance.chooseTheme(item)}
                            .padding(horizontal=11.dp,vertical=12.dp),
                        horizontalAlignment=Alignment.CenterHorizontally
                    ){
                        Box(
                            Modifier.size(35.dp).background(
                                if(item==LumoVisualTheme.MINIMAL)
                                    Brush.linearGradient(listOf(Color(0xFF263456),Color(0xFF162341)))
                                else LumoAvatarGradient,
                                RoundedCornerShape(11.dp)
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(item.title,color=Color.White,
                            style=MaterialTheme.typography.labelMedium)
                        if(selected)Text("✓ Выбрано",color=LumoCyan,
                            style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text("Мерцание звёзд",color=Color.White)
                Text("Медленная анимация фона",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked=appearance.animated,
                onCheckedChange=appearance::updateAnimationEnabled,
                enabled=!appearance.lowPower && appearance.theme!=LumoVisualTheme.MINIMAL
            )
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text("Экономия батареи",color=Color.White)
                Text("Без анимации и крупных планет",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=appearance.lowPower,onCheckedChange=appearance::updateLowPowerMode)
        }
    }
}
