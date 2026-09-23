package app.lumo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Navy=Color(0xFF1D2D49)
private val Ice=Color(0xFFB6C9FF)
private val Lavender=Color(0xFFA7A2F8)
private val TextWhite=Color(0xFFF6F7FF)
private val Glass=Color(0x663F5277)
val LumoGradient=Brush.verticalGradient(listOf(Color(0xFF344868),Navy,Color(0xFF273955)))
val LumoAvatarGradient=Brush.linearGradient(listOf(Ice,Color(0xFF8E9DF2),Color(0xFFF3B8DF)))

private val palette=darkColorScheme(
 primary=Ice,onPrimary=Navy,primaryContainer=Color(0xFF5968B8),
 onPrimaryContainer=TextWhite,secondary=Lavender,
 background=Navy,onBackground=TextWhite,
 surface=Color(0xFF304360),onSurface=TextWhite,
 surfaceVariant=Color(0xFF415575),onSurfaceVariant=Color(0xFFD7DEF3),
 outline=Color(0xFF9EAFD0)
)
@Composable fun LumoTheme(content:@Composable ()->Unit){
 MaterialTheme(colorScheme=palette,content=content)
}
fun Modifier.lumoGlass(radius:Int=24):Modifier=this
 .background(Glass,RoundedCornerShape(radius.dp))
 .border(1.dp,Color.White.copy(alpha=0.17f),RoundedCornerShape(radius.dp))
