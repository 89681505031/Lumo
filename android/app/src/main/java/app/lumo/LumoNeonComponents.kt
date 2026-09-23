package app.lumo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val neonStroke = Brush.horizontalGradient(listOf(LumoCyan, Color(0xFF8BAAFF), LumoPink))
private val neonFill = Brush.horizontalGradient(
    listOf(Color(0xFF24C8F5), Color(0xFF4351EB), Color(0xFFE543D7))
)

@Composable
fun LumoNeonButton(
    text:String,
    onClick:()->Unit,
    modifier:Modifier=Modifier,
    enabled:Boolean=true
) {
    val shape=RoundedCornerShape(28.dp)
    Box(
        modifier
            .heightIn(min=52.dp)
            .shadow(if(enabled) 10.dp else 0.dp,shape,ambientColor=LumoCyan,spotColor=LumoPink)
            .clip(shape)
            .background(if(enabled) neonFill else Brush.horizontalGradient(listOf(Color(0xFF536398),Color(0xFF655A9E))))
            .border(1.6.dp,neonStroke,shape)
            .clickable(enabled=enabled,onClick=onClick)
            .padding(horizontal=18.dp,vertical=14.dp),
        contentAlignment=Alignment.Center
    ){
        Text(
            text,
            color=if(enabled) Color.White else Color.White.copy(alpha=.57f),
            style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.Bold,
            maxLines=1,
            overflow=TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LumoNeonAvatar(
    name:String,
    size:Dp=52.dp,
    modifier:Modifier=Modifier
) {
    Box(
        modifier.size(size)
            .shadow(7.dp,CircleShape,ambientColor=LumoCyan,spotColor=LumoPink)
            .background(LumoAvatarGradient,CircleShape)
            .border(1.6.dp,neonStroke,CircleShape),
        contentAlignment=Alignment.Center
    ) {
        Text(
            name.take(1).uppercase(),
            color=Color(0xFF101641),
            fontWeight=FontWeight.SemiBold,
            style=if(size>=90.dp) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineSmall
        )
    }
}

/** Distinct outline symbols for chats, people and profile: no placeholder Unicode glyphs. */
@Composable
fun LumoTabSymbol(index:Int,modifier:Modifier=Modifier){
    Canvas(modifier.size(27.dp,24.dp)) {
        val width=size.width
        val height=size.height
        val stroke=1.7.dp.toPx()
        when(index) {
            0->{
                drawRoundRect(
                    Color.White, topLeft=Offset(width*.11f,height*.10f),
                    size=androidx.compose.ui.geometry.Size(width*.77f,height*.64f),
                    cornerRadius=CornerRadius(width*.19f),
                    style=Stroke(stroke)
                )
                drawLine(Color.White,Offset(width*.26f,height*.73f),Offset(width*.18f,height*.94f),stroke)
                drawLine(Color.White,Offset(width*.18f,height*.94f),Offset(width*.45f,height*.73f),stroke)
            }
            1->{
                drawCircle(Color.White,width*.13f,Offset(width*.52f,height*.28f),style=Stroke(stroke))
                drawArc(Color.White,200f,140f,false,
                    topLeft=Offset(width*.18f,height*.34f),
                    size=androidx.compose.ui.geometry.Size(width*.68f,height*.61f),
                    style=Stroke(stroke)
                )
            }
            else->{
                drawCircle(Color.White,width*.27f,Offset(width*.5f,height*.48f),style=Stroke(stroke))
            }
        }
    }
}

@Composable
fun LumoBottomNavigation(selected:Int,onSelect:(Int)->Unit){
    val names=listOf("Чаты","Люди","Профиль")
    Row(
        Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=8.dp)
            .lumoGlass(34).padding(6.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        names.forEachIndexed { index,label ->
            val active=selected==index
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(27.dp))
                    .background(
                        if(active) Brush.horizontalGradient(
                            listOf(Color(0xB92B71E5),Color(0xD86C44CC),Color(0xA8D445C7))
                        ) else Brush.horizontalGradient(listOf(Color.Transparent,Color.Transparent))
                    )
                    .then(if(active) Modifier.border(1.dp,neonStroke,RoundedCornerShape(27.dp)) else Modifier)
                    .clickable { onSelect(index) }
                    .padding(vertical=8.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ){
                LumoTabSymbol(index)
                Spacer(Modifier.height(3.dp))
                Text(
                    label,color=Color.White,
                    fontWeight=if(active) FontWeight.Bold else FontWeight.Medium,
                    style=MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun LumoSearchField(
    value:String,
    onValueChange:(String)->Unit,
    placeholder:String,
    modifier:Modifier=Modifier
){
    OutlinedTextField(
        value=value,onValueChange=onValueChange,
        placeholder={Text(placeholder,color=Color(0xFFD3E2FF))},
        leadingIcon={Text("⌕",style=MaterialTheme.typography.headlineMedium,color=Color.White)},
        singleLine=true, shape=RoundedCornerShape(28.dp),
        colors=OutlinedTextFieldDefaults.colors(
            focusedTextColor=Color.White,
            unfocusedTextColor=Color.White,
            focusedBorderColor=LumoCyan,
            unfocusedBorderColor=Color(0xFF9DDCFF),
            cursorColor=LumoCyan,
            focusedContainerColor=Color(0x882342A6),
            unfocusedContainerColor=Color(0x772343A6)
        ),
        modifier=modifier
    )
}
