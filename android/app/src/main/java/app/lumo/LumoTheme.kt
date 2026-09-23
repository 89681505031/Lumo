package app.lumo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF18233E)
private val Frost = Color(0xFFE8F2FF)
private val Lilac = Color(0xFFD9CFFF)
private val Glass = Color(0x66EAF1FF)
val LumoGradient = Brush.linearGradient(listOf(Color(0xFF172A55), Color(0xFF526CB5), Color(0xFF8A78BC), Color(0xFF24345D)))
val LumoAvatarGradient = Brush.linearGradient(listOf(Color(0xFF8BE4FF), Color(0xFF9DADFF), Color(0xFFFFAEE1)))

private val palette = darkColorScheme(
 primary=Frost,onPrimary=Ink,primaryContainer=Color(0xFFB5C4FF),
 onPrimaryContainer=Ink,secondary=Lilac,
 background=Color(0xFF24345D),onBackground=Color.White,
 surface=Color(0xFF4C619A),onSurface=Color.White,
 surfaceVariant=Color(0xFF5A6E9D),onSurfaceVariant=Color(0xFFE9EFFF),
 outline=Color(0xFFDAE8FF)
)
@Composable fun LumoTheme(content:@Composable ()->Unit){
 MaterialTheme(colorScheme=palette,content=content)
}
fun Modifier.lumoGlass(radius:Int=24):Modifier=this
 .background(Brush.linearGradient(listOf(Color(0x80F1F6FF),Glass,Color(0x4D7E98D7))),RoundedCornerShape(radius.dp))
 .border(1.dp,Brush.linearGradient(listOf(Color.White.copy(alpha=.85f),Color.White.copy(alpha=.14f),Color.White.copy(alpha=.45f))),RoundedCornerShape(radius.dp))

/** Layered radial light gives the glass panels depth without images or Android 12-only blur. */
@Composable fun LumoBackdrop(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit){
 Box(modifier.background(LumoGradient)){
  Canvas(Modifier.fillMaxSize()){
   fun glow(x:Float,y:Float,r:Float,c:Color){
    drawCircle(brush=Brush.radialGradient(listOf(c.copy(alpha=.62f),c.copy(alpha=.18f),Color.Transparent),center=Offset(size.width*x,size.height*y),radius=size.minDimension*r),radius=size.minDimension*r,center=Offset(size.width*x,size.height*y))
   }
   glow(.93f,.13f,.64f,Color(0xFF99DBFF))
   glow(.04f,.55f,.68f,Color(0xFF9F8CFF))
   glow(.88f,.92f,.60f,Color(0xFFFFACD6))
  }
  content()
 }
}
