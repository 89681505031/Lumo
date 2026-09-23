package app.lumo

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp

val LumoCyan = Color(0xFF53E6FF)
val LumoPink = Color(0xFFFF6FE3)
val LumoViolet = Color(0xFF7F73FF)
val LumoMuted = Color(0xFFDBE7FF)
val LumoGradient = Brush.verticalGradient(
    listOf(Color(0xFF030F40), Color(0xFF101D73), Color(0xFF110C46))
)
val LumoAvatarGradient = Brush.linearGradient(
    listOf(Color(0xFF61DFFF), Color(0xFF9C9BFF), Color(0xFFFF82DD))
)

private val cosmicPalette = darkColorScheme(
    primary=Color(0xFFF0F8FF), onPrimary=Color(0xFF132453),
    primaryContainer=Color(0xFF455CE5), onPrimaryContainer=Color.White,
    secondary=LumoCyan, onSecondary=Color(0xFF09164A),
    background=Color(0xFF030F40), onBackground=Color.White,
    surface=Color(0xFF1A3288), onSurface=Color.White,
    surfaceVariant=Color(0xFF26398B), onSurfaceVariant=LumoMuted,
    outline=Color(0xFFB6DFFF),
    error=Color(0xFFFFB6C6), onError=Color(0xFF530D30)
)

@Composable
fun LumoTheme(content:@Composable () -> Unit) {
    val appearance=rememberLumoAppearance()
    CompositionLocalProvider(LocalLumoAppearance provides appearance) {
        MaterialTheme(colorScheme=cosmicPalette, content=content)
    }
}

/** Frosted blue glass and contrasting cyan / pink edge from the approved mockup. */
fun Modifier.lumoGlass(radius:Int=24):Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this
        .shadow(7.dp, shape, ambientColor=LumoCyan, spotColor=LumoPink)
        .background(
            Brush.linearGradient(
                listOf(Color(0xCC2857BB), Color(0xAE243E9B), Color(0xAE71339F))
            ), shape
        )
        .border(
            1.35.dp,
            Brush.linearGradient(
                listOf(Color(0xFFB9F8FF), Color(0xFF6B9DFF), Color(0xFFFF9FEF))
            ), shape
        )
}

/**
 * Native Compose illustration: starfield and partially cropped illuminated planets.
 * No screenshots are used as backgrounds, so all controls stay interactive and scale
 * to different phone sizes. The optional twinkle can be turned off to save battery.
 */
@Composable
fun LumoBackdrop(modifier:Modifier=Modifier,content:@Composable BoxScope.() -> Unit) {
    val appearance=LocalLumoAppearance.current
    val selected=appearance.theme
    val lowPower=appearance.lowPower || selected==LumoVisualTheme.MINIMAL
    val twinkle=if(!lowPower && appearance.animated) {
        val infinite=rememberInfiniteTransition(label="Lumo star twinkle")
        val alpha by infinite.animateFloat(
            initialValue=.47f,targetValue=1f,
            animationSpec=infiniteRepeatable(tween(durationMillis=11000),RepeatMode.Reverse),
            label="star intensity"
        )
        alpha
    } else 1f
    val spaceColors=lumoSpaceColors(selected)
    val glows=when(selected){
        LumoVisualTheme.AURORA->listOf(Color(0xFF29EDDD),Color(0xFF3094FF),Color(0xFF4DBDCC))
        LumoVisualTheme.VIOLET->listOf(Color(0xFFAD70FF),Color(0xFF5B41F8),Color(0xFFFF42BD))
        else->listOf(Color(0xFF2677FF),Color(0xFF5B36FF),Color(0xFFFF39C7))
    }
    Box(modifier.background(Brush.verticalGradient(spaceColors))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val unit = minOf(w,h)
            if (w <= 0f || h <= 0f) return@Canvas

            fun halo(x:Float,y:Float,r:Float,color:Color) {
                val c=Offset(w*x,h*y)
                val rr=unit*r
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha=.49f),color.copy(alpha=.14f),Color.Transparent),
                        center=c,radius=rr
                    ),radius=rr,center=c
                )
            }

            if(!lowPower){
                halo(.95f,.18f,.95f,glows[0])
                halo(-.15f,.47f,.87f,glows[1])
                halo(.78f,.88f,.75f,glows[2])
            }

            fun planet(x:Float,y:Float,r:Float,lit:Color,dark:Color) {
                val c=Offset(w*x,h*y)
                val rr=unit*r
                halo(x,y,r*1.65f,lit)
                drawCircle(
                    Brush.radialGradient(
                        listOf(lit.copy(alpha=.94f),dark.copy(alpha=.94f),Color(0xFF060D50)),
                        center=Offset(c.x-rr*.44f,c.y-rr*.4f),radius=rr*1.9f
                    ),radius=rr,center=c
                )
                drawCircle(
                    color=lit.copy(alpha=.65f),radius=rr,
                    center=c,style=Stroke(width=1.4.dp.toPx())
                )
                // A subtle planetary terminator and orbit; never intersects the UI layout.
                rotate(-24f,c) {
                    drawOval(
                        brush=Brush.linearGradient(
                            listOf(LumoCyan.copy(alpha=.20f),lit.copy(alpha=.8f),LumoPink.copy(alpha=.45f))
                        ),
                        topLeft=Offset(c.x-rr*1.31f,c.y-rr*.27f),
                        size=Size(rr*2.62f,rr*.54f),
                        style=Stroke(width=1.5.dp.toPx())
                    )
                }
            }

            // Large planets are skipped in battery-saver and minimal mode.
            if(!lowPower){
                planet(1.27f,.22f,.59f,glows[0],Color(0xFF172984))
                planet(-.43f,.76f,.52f,glows[1],Color(0xFF172988))
                planet(.88f,1.13f,.45f,glows[2],Color(0xFF12246D))
            }

            // Reproducible star positions; low power draws only a small static set.
            repeat(if(lowPower && selected==LumoVisualTheme.MINIMAL) 0
                   else if(lowPower) 25 else 105) { i ->
                val x=((i*73+17)%109)/109f*w
                val y=((i*47+11)%113)/113f*h
                val light=if(i%4==0) LumoCyan else if(i%7==0) LumoPink else Color.White
                val rr=if(i%12==0) 1.45.dp.toPx() else .63.dp.toPx()
                drawCircle(light.copy(alpha=(if(i%3==0).83f else .48f)*twinkle),rr,Offset(x,y))
                if(!lowPower && i%16==0) {
                    drawLine(light.copy(alpha=.7f),Offset(x-rr*3,y),Offset(x+rr*3,y),.7.dp.toPx())
                    drawLine(light.copy(alpha=.7f),Offset(x,y-rr*3),Offset(x,y+rr*3),.7.dp.toPx())
                }
            }
        }
        content()
    }
}

/** Two related glass treatments distinguish incoming from outgoing messages. */
fun Modifier.lumoBubble(outgoing:Boolean):Modifier {
    val shape=RoundedCornerShape(21.dp)
    val fill=if(outgoing) Brush.linearGradient(
        listOf(Color(0xDC3156DE),Color(0xCB6647DB),Color(0xBDCA40B8))
    ) else Brush.linearGradient(
        listOf(Color(0xD02654AE),Color(0xCF1D3987),Color(0xB9394D9B))
    )
    return this.shadow(
        5.dp,shape,ambientColor=if(outgoing) LumoPink else LumoCyan,
        spotColor=if(outgoing) LumoPink else LumoCyan
    ).background(fill,shape)
        .border(
            1.1.dp,
            if(outgoing) Brush.linearGradient(listOf(LumoCyan,Color.White,LumoPink))
            else Brush.linearGradient(listOf(Color(0xFF8CE6FF),Color(0xFF8496FF))),
            shape
        )
}
