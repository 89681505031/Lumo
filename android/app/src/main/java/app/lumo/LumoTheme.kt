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
val LumoCyan = Color(0xFF4C8DFF)
val LumoPink = Color(0xFF7C5CFF)
val LumoViolet = Color(0xFF6B65F6)
val LumoMuted = Color(0xFF9298A8)

val LumoGradient = Brush.verticalGradient(
    listOf(Color(0xFF0B0C10), Color(0xFF0B0C10))
)

val LumoAvatarGradient = Brush.linearGradient(
    listOf(Color(0xFF7E8DAA), Color(0xFF58667F))
)

private val lumoPalette = darkColorScheme(
    primary = Color(0xFF4C8DFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF20345D),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF8EA6D8),
    onSecondary = Color(0xFF111318),
    background = Color(0xFF0B0C10),
    onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFF4F5F7),
    surfaceVariant = Color(0xFF1C1F26),
    onSurfaceVariant = Color(0xFF9298A8),
    outline = Color(0xFF30333B),
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
        .background(Color(0xFF15171C), shape)
        .border(1.dp, Color(0xFF24272E), shape)
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
        modifier = modifier.background(Color(0xFF0B0C10)),
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
    val fill = if (outgoing) Color(0xFF315FAF) else Color(0xFF1C1F26)
    val edge = if (outgoing) Color(0xFF3F73CC) else Color(0xFF252932)
    return this
        .background(fill, shape)
        .border(1.dp, edge, shape)
}
