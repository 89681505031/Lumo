package app.lumo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Legacy names are kept so the rest of the app can migrate safely.
 * Their visuals are now the flat dark Lumo system: no neon, glow or glass.
 */
@Composable
fun LumoNeonButton(
    text:String,
    onClick:()->Unit,
    modifier:Modifier=Modifier,
    enabled:Boolean=true
) {
    val shape=RoundedCornerShape(24.dp)
    Box(
        modifier
            .heightIn(min=48.dp)
            .clip(shape)
            .background(
                if(enabled) Color(0xFF25D366) else Color(0xFF2A3942)
            )
            .clickable(enabled=enabled,onClick=onClick)
            .padding(horizontal=18.dp,vertical=12.dp),
        contentAlignment=Alignment.Center
    ){
        Text(
            text,
            color=if(enabled) Color(0xFF061A10) else Color(0xFF8696A0),
            style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.SemiBold,
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
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF202C33)),
        contentAlignment=Alignment.Center
    ) {
        Text(
            name.take(1).uppercase(),
            color=Color(0xFF8696A0),
            fontWeight=FontWeight.Medium,
            style=if(size>=90.dp) MaterialTheme.typography.displayMedium
            else MaterialTheme.typography.headlineSmall
        )
    }
}

@Composable
fun LumoTabSymbol(index:Int,modifier:Modifier=Modifier){
    val icon=when(index){
        0->Icons.Rounded.Chat
        1->Icons.Rounded.Group
        else->Icons.Rounded.Person
    }
    Icon(
        imageVector=icon,
        contentDescription=null,
        tint=Color.White,
        modifier=modifier.size(24.dp)
    )
}

@Composable
fun LumoBottomNavigation(selected:Int,onSelect:(Int)->Unit){
    val names=listOf("Чаты","Люди","Профиль")
    Surface(
        color=Color(0xFF111B21),
        tonalElevation=0.dp,
        shadowElevation=0.dp
    ){
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(68.dp)
                .padding(horizontal=8.dp,vertical=4.dp),
            verticalAlignment=Alignment.CenterVertically
        ){
            names.forEachIndexed { index,label ->
                val active=selected==index
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(index) },
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.Center
                ){
                    Box(
                        Modifier
                            .width(54.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if(active)Color(0xFF103B34) else Color.Transparent),
                        contentAlignment=Alignment.Center
                    ){
                        LumoTabSymbol(index)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        color=if(active)Color.White else Color(0xFFB7C2CF),
                        fontWeight=if(active) FontWeight.Bold else FontWeight.Medium,
                        style=MaterialTheme.typography.labelSmall
                    )
                }
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
        value=value,
        onValueChange=onValueChange,
        placeholder={Text(placeholder,color=Color(0xFF8696A0))},
        leadingIcon={
            Icon(
                Icons.Rounded.Search,
                contentDescription=null,
                tint=Color(0xFF8696A0),
                modifier=Modifier.size(22.dp)
            )
        },
        singleLine=true,
        shape=RoundedCornerShape(24.dp),
        colors=OutlinedTextFieldDefaults.colors(
            focusedTextColor=Color(0xFFE9EDEF),
            unfocusedTextColor=Color(0xFFE9EDEF),
            focusedBorderColor=Color.Transparent,
            unfocusedBorderColor=Color.Transparent,
            cursorColor=Color(0xFF25D366),
            focusedContainerColor=Color(0xFF202C33),
            unfocusedContainerColor=Color(0xFF202C33)
        ),
        modifier=modifier.heightIn(min=48.dp)
    )
}
