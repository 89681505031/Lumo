package app.lumo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Lumo reference UI foundation.
 *
 * The previous implementation used a cosmic backdrop, planets, animated stars and
 * bright glass/neon cards. Those effects made every screen drift away from the
 * supplied messenger references. Keep the existing public names so the rest of
 * the app does not need a risky logic rewrite, but render them as a restrained,
 * flat messenger design instead.
 */
val LumoCyan = Color(0xFF00A884)
val LumoPink = Color(0xFF25D366)
val LumoViolet = Color(0xFF00A884)
val LumoMuted = Color(0xFF8696A0)

val LumoGradient = Brush.verticalGradient(
    listOf(Color(0xFF0B141A), Color(0xFF0B141A))
)

val LumoAvatarGradient = Brush.linearGradient(
    listOf(Color(0xFF00A884), Color(0xFF008069))
)

private val lumoPalette = darkColorScheme(
    primary = Color(0xFF00A884),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF005C4B),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF25D366),
    onSecondary = Color(0xFF111318),
    background = Color(0xFF0B141A),
    onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF111B21),
    onSurface = Color(0xFFF4F5F7),
    surfaceVariant = Color(0xFF202C33),
    onSurfaceVariant = Color(0xFF8696A0),
    outline = Color(0xFF374248),
    error = Color(0xFFFF6B78),
    onError = Color.White
)

@Composable
fun LumoTheme(content: @Composable () -> Unit) {
    val appearance = rememberLumoAppearance()
    CompositionLocalProvider(LocalLumoAppearance provides appearance) {
        MaterialTheme(colorScheme = lumoPalette, content = content)
    }
}

/**
 * Compatibility name used throughout the app.
 * It is intentionally no longer "glass": reference screens use calm solid cards,
 * a thin separator and almost no glow.
 */
fun Modifier.lumoGlass(radius: Int = 24): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this
        .shadow(
            elevation = 1.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = .22f),
            spotColor = Color.Black.copy(alpha = .22f)
        )
        .background(Color(0xFF111B21), shape)
        .border(1.dp, Color(0xFF202C33), shape)
}

/**
 * Global screen background. No planets, stars, glows or animated decoration.
 * This makes every destination visually consistent with a modern messenger.
 */
@Composable
fun LumoBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.background(Color(0xFF0B141A)),
        content = content
    )
}

/**
 * Message bubbles: outgoing messages get the single Lumo accent; incoming
 * messages stay neutral so text remains the focus.
 */
fun Modifier.lumoBubble(outgoing: Boolean): Modifier {
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (outgoing) 20.dp else 6.dp,
        bottomEnd = if (outgoing) 6.dp else 20.dp
    )
    val fill = if (outgoing) Color(0xFF005C4B) else Color(0xFF202C33)
    val edge = if (outgoing) Color(0xFF005C4B) else Color(0xFF202C33)
    return this
        .background(fill, shape)
        .border(1.dp, edge, shape)
}
