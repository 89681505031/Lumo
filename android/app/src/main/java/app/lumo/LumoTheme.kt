package app.lumo

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Flat dark Lumo palette based on the supplied Android messenger references.
// No frosted glass, cosmic backgrounds, neon shadows or glowing borders.
val LumoCyan = Color(0xFF25D366)
val LumoPink = Color(0xFF8696A0)
val LumoViolet = Color(0xFF00A884)
val LumoMuted = Color(0xFF8696A0)
val LumoGradient = Brush.verticalGradient(
    listOf(Color(0xFF0B141A), Color(0xFF0B141A))
)
val LumoAvatarGradient = Brush.linearGradient(
    listOf(Color(0xFF202C33), Color(0xFF2A3942))
)

private val flatDarkPalette = darkColorScheme(
    primary=Color(0xFF25D366),
    onPrimary=Color(0xFF061A10),
    primaryContainer=Color(0xFF005C4B),
    onPrimaryContainer=Color.White,
    secondary=Color(0xFF00A884),
    onSecondary=Color.White,
    background=Color(0xFF0B141A),
    onBackground=Color(0xFFE9EDEF),
    surface=Color(0xFF111B21),
    onSurface=Color(0xFFE9EDEF),
    surfaceVariant=Color(0xFF202C33),
    onSurfaceVariant=Color(0xFF8696A0),
    outline=Color(0xFF2A3942),
    error=Color(0xFFFF5B74),
    onError=Color.White
)

@Composable
fun LumoTheme(content:@Composable () -> Unit) {
    val appearance=rememberLumoAppearance()
    CompositionLocalProvider(LocalLumoAppearance provides appearance) {
        MaterialTheme(colorScheme=flatDarkPalette, content=content)
    }
}

/**
 * Legacy helper name retained so older screens keep compiling.
 * It now renders a normal solid dark panel instead of glass.
 */
fun Modifier.lumoGlass(radius:Int=24):Modifier {
    val shape=RoundedCornerShape(radius.dp)
    return this.background(Color(0xFF111B21),shape)
}

/**
 * The reference design uses a plain dark app background.
 * The old animated starfield/planet renderer is intentionally removed.
 */
@Composable
fun LumoBackdrop(
    modifier:Modifier=Modifier,
    content:@Composable BoxScope.() -> Unit
) {
    Box(
        modifier.background(Color(0xFF0B141A)),
        content=content
    )
}

/**
 * Flat messenger bubbles: outgoing dark green, incoming dark gray.
 */
fun Modifier.lumoBubble(outgoing:Boolean):Modifier {
    val shape=RoundedCornerShape(14.dp)
    return this.background(
        if(outgoing) Color(0xFF005C4B) else Color(0xFF202C33),
        shape
    )
}
