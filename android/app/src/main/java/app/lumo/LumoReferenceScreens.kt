package app.lumo

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ReferenceBg = Color(0xFF0B141A)
private val ReferencePanel = Color(0xFF111B21)
private val ReferencePanel2 = Color(0xFF202C33)
private val ReferencePurple = Color(0xFF25D366)
private val ReferenceBlue = Color(0xFF25D366)
private val ReferenceGreen = Color(0xFF25D366)
private val ReferenceMuted = Color(0xFF8696A0)
private val ReferenceLine = Color(0xFF202C33)

@Composable
fun LumoReferenceHome(
    token:String,
    me:User,
    openChat:(User)->Unit,
    openGroups:()->Unit,
    openCalls:()->Unit,
    openAi:()->Unit,
    profileChanged:(User)->Unit,
    privacy:LumoPrivacy,
    logout:()->Unit
){
    var tab by remember { mutableIntStateOf(0) }
    var peoplePicker by remember { mutableStateOf(false) }
    var advancedProfile by remember { mutableStateOf(false) }

    LumoBackdrop(Modifier.fillMaxSize()){
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if(!peoplePicker && !advancedProfile){
                    LumoReferenceBottomBar(
                        selected = tab,
                        onSelect = { tab=it }
                    )
                }
            }
        ){ padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ){
                when{
                    peoplePicker -> LumoReferencePeoplePicker(
                        token=token,
                        onBack={peoplePicker=false},
                        open={user->peoplePicker=false;openChat(user)}
                    )
                    advancedProfile -> Column(Modifier.fillMaxSize()){
                        LumoReferenceHeader(
                            title="Настройки Lumo",
                            onBack={advancedProfile=false}
                        )
                        Box(Modifier.weight(1f)){
                            Profile(
                                token=token,
                                me=me,
                                profileChanged=profileChanged,
                                privacy=privacy,
                                openCalls=openCalls,
                                openAi=openAi,
                                logout=logout
                            )
                        }
                    }
                    else -> when(tab){
                        0 -> LumoReferenceChats(
                            token=token,
                            me=me,
                            privacy=privacy,
                            openChat=openChat,
                            newChat={peoplePicker=true},
                            openGroups=openGroups,
                            openCalls={tab=1},
                            openSettings={tab=4}
                        )
                        1 -> LumoReferenceCalls(
                            token=token,
                            openCalls=openCalls
                        )
                        2 -> LumoReferenceStatus(token=token,me=me)
                        3 -> LumoReferenceCommunities(openGroups=openGroups)
                        else -> LumoReferenceSettings(
                            token=token,
                            me=me,
                            privacy=privacy,
                            profileChanged=profileChanged,
                            logout=logout,
                            openAdvanced={advancedProfile=true}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LumoReferenceBottomBar(
    selected:Int,
    onSelect:(Int)->Unit
){
    val tabs=listOf(
        Triple(Icons.Rounded.Chat,"Чаты",0),
        Triple(Icons.Rounded.Call,"Звонки",1),
        Triple(Icons.Rounded.RadioButtonChecked,"Статус",2),
        Triple(Icons.Rounded.Group,"Сообщества",3),
        Triple(Icons.Rounded.MoreVert,"Ещё",4)
    )
    Surface(
        color=Color(0xFF111B21),
        tonalElevation=0.dp,
        shadowElevation=0.dp,
        modifier=Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ){
        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal=6.dp,vertical=5.dp),
            horizontalArrangement=Arrangement.SpaceAround,
            verticalAlignment=Alignment.CenterVertically
        ){
            tabs.forEach{(icon,label,index)->
                val active=index==selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable{onSelect(index)}
                        .padding(vertical=4.dp),
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
                        Icon(
                            imageVector=icon,
                            contentDescription=label,
                            tint=if(active)Color.White else Color(0xFFB9C5DA),
                            modifier=Modifier.size(23.dp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        color=if(active)Color.White else Color(0xFFB9C5DA),
                        style=MaterialTheme.typography.labelSmall,
                        fontWeight=if(active)FontWeight.Bold else FontWeight.Medium,
                        maxLines=1
                    )
                }
            }
        }
    }
}

@Composable
private fun LumoReferenceChats(
    token:String,
    me:User,
    privacy:LumoPrivacy,
    openChat:(User)->Unit,
    newChat:()->Unit,
    openGroups:()->Unit,
    openCalls:()->Unit,
    openSettings:()->Unit
){
    val context=LocalContext.current
    var chats by remember(me.id){ mutableStateOf<List<Conversation>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf("Все") }

    LaunchedEffect(token,me.id){
        val cached=runCatching{
            withContext(Dispatchers.IO){
                LumoOfflineStore.loadConversations(context,me.id)
            }
        }.getOrDefault(emptyList())
        if(cached.isNotEmpty()){
            chats=cached
            loading=false
            offline=true
        }
        while(true){
            runCatching{
                withContext(Dispatchers.IO){Api.conversations(token)}
            }.onSuccess{
                chats=it
                offline=false
                loading=false
                runCatching{
                    withContext(Dispatchers.IO){
                        LumoOfflineStore.saveConversations(context,me.id,it)
                    }
                }
            }.onFailure{
                offline=true
                loading=false
            }
            delay(12_000)
        }
    }

    Box(Modifier.fillMaxSize()){
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ){
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal=18.dp,vertical=11.dp),
                verticalAlignment=Alignment.CenterVertically
            ){
                Text(
                    "Lumo",
                    color=Color.White,
                    style=MaterialTheme.typography.headlineLarge,
                    fontWeight=FontWeight.ExtraBold
                )
                Spacer(Modifier.weight(1f))
                ReferenceIconButton("⌕"){searchVisible=!searchVisible}
                Spacer(Modifier.width(6.dp))
                ReferenceIconButton("✎",newChat)
                Spacer(Modifier.width(2.dp))
                Box{
                    ReferenceIconButton("⋮"){menu=true}
                    DropdownMenu(
                        expanded=menu,
                        onDismissRequest={menu=false},
                        containerColor=Color(0xFF111820)
                    ){
                        DropdownMenuItem(
                            text={Text("Новая группа")},
                            onClick={menu=false;openGroups()}
                        )
                        DropdownMenuItem(
                            text={Text("Связанные устройства")},
                            onClick={menu=false;openSettings()}
                        )
                        DropdownMenuItem(
                            text={Text("Избранные")},
                            onClick={menu=false;activeFilter="Избранное"}
                        )
                        DropdownMenuItem(
                            text={Text("Прочитать все")},
                            onClick={menu=false}
                        )
                        DropdownMenuItem(
                            text={Text("Настройки")},
                            onClick={menu=false;openSettings()}
                        )
                    }
                }
            }

            LazyRowFilters(
                selected=activeFilter,
                select={filter->
                    activeFilter=filter
                    if(filter=="Группы")openGroups()
                }
            )

            if(searchVisible){
                LumoSearchField(
                    value=query,
                    onValueChange={query=it},
                    placeholder="Поиск чатов, людей и сообщений…",
                    modifier=Modifier
                        .fillMaxWidth()
                        .padding(horizontal=16.dp,vertical=10.dp)
                )
            }

            if(offline){
                Text(
                    "Офлайн-копия",
                    color=ReferenceMuted,
                    style=MaterialTheme.typography.labelSmall,
                    modifier=Modifier.padding(horizontal=20.dp,vertical=3.dp)
                )
            }

            if(loading){
                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                    CircularProgressIndicator(color=ReferenceBlue)
                }
            }else{
                val visible=chats.filter{
                    query.isBlank() ||
                    it.peer.displayName.contains(query,true) ||
                    it.peer.username.contains(query,true) ||
                    it.lastMessage.contains(query,true)
                }
                if(visible.isEmpty()){
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment=Alignment.Center
                    ){
                        Column(horizontalAlignment=Alignment.CenterHorizontally){
                            Text(
                                "Чатов пока нет",
                                color=Color.White,
                                style=MaterialTheme.typography.titleLarge,
                                fontWeight=FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Нажми +, чтобы найти сохранённый контакт Lumo.",
                                color=ReferenceMuted
                            )
                        }
                    }
                }else{
                    LazyColumn(
                        modifier=Modifier.fillMaxSize(),
                        contentPadding=PaddingValues(
                            start=14.dp,end=14.dp,top=6.dp,bottom=92.dp
                        )
                    ){
                        items(visible,key={it.peer.id}){chat->
                            ReferenceChatRow(
                                token=token,
                                chat=chat,
                                hidePreview=privacy.hideChatPreviews,
                                onClick={openChat(chat.peer)}
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick=newChat,
            containerColor=ReferenceBlue,
            contentColor=Color(0xFF04101A),
            shape=RoundedCornerShape(22.dp),
            modifier=Modifier
                .align(Alignment.BottomEnd)
                .padding(end=18.dp,bottom=20.dp)
                .size(64.dp)
        ){
            Text("+",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Light)
        }
    }
}

@Composable
private fun LazyRowFilters(
    selected:String,
    select:(String)->Unit
){
    val values=listOf("Все","Непрочитанные","Группы","Избранное")
    androidx.compose.foundation.lazy.LazyRow(
        modifier=Modifier.fillMaxWidth(),
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(8.dp)
    ){
        items(values){value->
            val active=selected==value
            Surface(
                color=if(active)ReferencePurple.copy(alpha=.38f) else ReferencePanel,
                shape=RoundedCornerShape(18.dp),
                border=BorderStroke(
                    1.dp,
                    if(active)ReferencePurple else ReferenceLine
                ),
                modifier=Modifier.clickable{select(value)}
            ){
                Text(
                    value,
                    color=if(active)Color.White else Color(0xFFC4CDDB),
                    modifier=Modifier.padding(horizontal=14.dp,vertical=8.dp),
                    style=MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ReferenceChatRow(
    token:String,
    chat:Conversation,
    hidePreview:Boolean,
    onClick:()->Unit
){
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick=onClick)
            .padding(vertical=9.dp,horizontal=4.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        LumoUserAvatar(token,chat.peer,size=56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Text(
                    chat.peer.displayName,
                    color=Color.White,
                    fontWeight=FontWeight.Bold,
                    style=MaterialTheme.typography.titleMedium,
                    maxLines=1,
                    modifier=Modifier.weight(1f)
                )
                if(chat.lastAt.isNotBlank()){
                    Text(
                        formatMessageTime(chat.lastAt),
                        color=ReferenceMuted,
                        style=MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if(hidePreview)"Содержимое скрыто" else chat.lastMessage,
                color=ReferenceMuted,
                maxLines=1,
                style=MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun LumoReferencePeoplePicker(
    token:String,
    onBack:()->Unit,
    open:(User)->Unit
){
    Column(Modifier.fillMaxSize()){
        LumoReferenceHeader("Новый чат",onBack)
        Box(Modifier.weight(1f)){
            People(token,open)
        }
    }
}

@Composable
private fun LumoReferenceCalls(
    token:String,
    openCalls:()->Unit
){
    var peers by remember { mutableStateOf<List<User>>(emptyList()) }
    LaunchedEffect(token){
        runCatching{
            withContext(Dispatchers.IO){Api.conversations(token)}
        }.onSuccess{list->peers=list.map{it.peer}.take(12)}
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal=16.dp)
    ){
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top=12.dp,bottom=10.dp),
            verticalAlignment=Alignment.CenterVertically
        ){
            Text(
                "Lumo",
                color=Color.White,
                style=MaterialTheme.typography.headlineLarge,
                fontWeight=FontWeight.ExtraBold
            )
            Spacer(Modifier.weight(1f))
            ReferenceIconButton("⌕"){}
            ReferenceIconButton("⋮"){}
        }
        Text(
            "Звонки",
            color=Color.White,
            style=MaterialTheme.typography.headlineSmall,
            fontWeight=FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement=Arrangement.spacedBy(10.dp)
        ){
            ReferenceActionCard(
                icon="☎",
                title="Позвонить",
                subtitle="Аудиозвонок",
                modifier=Modifier.weight(1f),
                onClick=openCalls
            )
            ReferenceActionCard(
                icon="▣",
                title="Видеозвонок",
                subtitle="Начать звонок",
                modifier=Modifier.weight(1f),
                onClick=openCalls
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement=Arrangement.spacedBy(10.dp)
        ){
            ReferenceMiniAction("▦","Запланировать",Modifier.weight(1f),openCalls)
            ReferenceMiniAction("↗","Ссылка на звонок",Modifier.weight(1f),openCalls)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text(
                "Недавние",
                color=Color.White,
                style=MaterialTheme.typography.titleLarge,
                fontWeight=FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text("Все",color=ReferenceBlue)
        }
        Spacer(Modifier.height(5.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding=PaddingValues(bottom=20.dp)
        ){
            items(peers,key={it.id}){peer->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick=openCalls)
                        .padding(vertical=9.dp),
                    verticalAlignment=Alignment.CenterVertically
                ){
                    LumoUserAvatar(token,peer,size=48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)){
                        Text(peer.displayName,color=Color.White,fontWeight=FontWeight.SemiBold)
                        Text(
                            "Последний контакт",
                            color=ReferenceMuted,
                            style=MaterialTheme.typography.bodySmall
                        )
                    }
                    Text("☎",color=Color.White,style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(16.dp))
                    Text("▣",color=ReferenceBlue,style=MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun LumoReferenceStatus(
    token:String,
    me:User
){
    var peers by remember { mutableStateOf<List<User>>(emptyList()) }
    LaunchedEffect(token){
        runCatching{
            withContext(Dispatchers.IO){Api.conversations(token)}
        }.onSuccess{peers=it.map{c->c.peer}.take(8)}
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal=16.dp)
    ){
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top=12.dp,bottom=6.dp),
            verticalAlignment=Alignment.CenterVertically
        ){
            Text(
                "Lumo",
                color=Color.White,
                style=MaterialTheme.typography.headlineLarge,
                fontWeight=FontWeight.ExtraBold
            )
            Spacer(Modifier.weight(1f))
            ReferenceIconButton("⌕"){}
            ReferenceIconButton("⋮"){}
        }
        Text(
            "Статус",
            color=Color.White,
            style=MaterialTheme.typography.headlineSmall,
            fontWeight=FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .lumoGlass(24)
                .padding(12.dp),
            verticalAlignment=Alignment.CenterVertically
        ){
            LumoUserAvatar(token,me,size=58.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)){
                Text("Мой статус",color=Color.White,fontWeight=FontWeight.Bold)
                Text("Поделиться моментом…",color=ReferenceMuted)
            }
            Text("+",color=ReferenceBlue,style=MaterialTheme.typography.headlineMedium)
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text("Недавние",color=Color.White,fontWeight=FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("Все ›",color=Color(0xFFC5B8FF))
        }
        Spacer(Modifier.height(8.dp))

        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement=Arrangement.spacedBy(12.dp)
        ){
            item{
                ReferenceStatusAvatar(token,me,"Мой статус")
            }
            items(peers,key={it.id}){peer->
                ReferenceStatusAvatar(token,peer,peer.displayName)
            }
        }

        Spacer(Modifier.height(18.dp))
        peers.forEach{peer->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical=8.dp),
                verticalAlignment=Alignment.CenterVertically
            ){
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                listOf(
                                    ReferenceGreen,
                                    ReferenceBlue,
                                    ReferencePurple,
                                    ReferenceGreen
                                )
                            )
                        )
                        .padding(2.dp)
                ){
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(ReferenceBg)
                            .padding(2.dp)
                    ){
                        LumoUserAvatar(token,peer,size=48.dp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)){
                    Text(peer.displayName,color=Color.White,fontWeight=FontWeight.SemiBold)
                    Text("Недавно",color=ReferenceMuted,style=MaterialTheme.typography.bodySmall)
                }
                Text("⋮",color=Color.White,style=MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun ReferenceStatusAvatar(
    token:String,
    user:User,
    label:String
){
    Column(
        horizontalAlignment=Alignment.CenterHorizontally,
        modifier=Modifier.width(72.dp)
    ){
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            ReferenceGreen,
                            ReferenceBlue,
                            ReferencePurple,
                            ReferenceGreen
                        )
                    )
                )
                .padding(2.dp)
        ){
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(ReferenceBg)
                    .padding(2.dp)
            ){
                LumoUserAvatar(token,user,size=54.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color=Color.White,
            style=MaterialTheme.typography.labelSmall,
            maxLines=1
        )
    }
}

@Composable
private fun LumoReferenceCommunities(
    openGroups:()->Unit
){
    val categories=listOf(
        Triple("Lumo Новости","Новости и важные обновления","✦"),
        Triple("Техника и гаджеты","Обсуждение устройств","▣"),
        Triple("Игры и технологии","Игры, приложения и сервисы","◈"),
        Triple("Авто","Обсуждение автомобилей","◆"),
        Triple("Кино и сериалы","Фильмы и премьеры","▶"),
        Triple("Работа и услуги","Проекты и предложения","▰")
    )
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal=16.dp)
    ){
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top=12.dp,bottom=4.dp),
            verticalAlignment=Alignment.CenterVertically
        ){
            Text(
                "Lumo",
                color=Color.White,
                style=MaterialTheme.typography.headlineLarge,
                fontWeight=FontWeight.ExtraBold
            )
            Spacer(Modifier.weight(1f))
            ReferenceIconButton("⌕"){}
            ReferenceIconButton("⋮"){}
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment=Alignment.CenterVertically
        ){
            Text(
                "Сообщества",
                color=Color.White,
                style=MaterialTheme.typography.headlineSmall,
                fontWeight=FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            ReferenceIconButton("+",openGroups)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement=Arrangement.spacedBy(8.dp)
        ){
            Surface(
                color=ReferencePurple.copy(alpha=.42f),
                border=BorderStroke(1.dp,ReferencePurple),
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.weight(1f).clickable(onClick=openGroups)
            ){
                Text(
                    "Мои сообщества",
                    color=Color.White,
                    modifier=Modifier.padding(vertical=10.dp),
                    textAlign=androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Surface(
                color=ReferencePanel,
                border=BorderStroke(1.dp,ReferenceLine),
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.weight(1f)
            ){
                Text(
                    "Интересное",
                    color=ReferenceMuted,
                    modifier=Modifier.padding(vertical=10.dp),
                    textAlign=androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            contentPadding=PaddingValues(bottom=20.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)
        ){
            items(categories){item->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .lumoGlass(22)
                        .clickable(onClick=openGroups)
                        .padding(11.dp),
                    verticalAlignment=Alignment.CenterVertically
                ){
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(ReferencePanel2),
                        contentAlignment=Alignment.Center
                    ){
                        Text(item.third,style=MaterialTheme.typography.headlineMedium,color=Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)){
                        Text(item.first,color=Color.White,fontWeight=FontWeight.Bold)
                        Text(item.second,color=ReferenceMuted,style=MaterialTheme.typography.bodySmall)
                    }
                    Text("›",color=Color.White,style=MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun LumoReferenceSettings(
    token:String,
    me:User,
    privacy:LumoPrivacy,
    profileChanged:(User)->Unit,
    logout:()->Unit,
    openAdvanced:()->Unit
){
    var screen by remember { mutableStateOf("root") }
    when(screen){
        "profile" -> LumoReferenceProfileScreen(
            token=token,
            me=me,
            profileChanged=profileChanged,
            onBack={screen="root"}
        )
        "account" -> LumoReferenceAccountScreen(
            onBack={screen="root"},
            openAdvanced=openAdvanced,
            logout=logout
        )
        "notifications" -> LumoReferenceNotifications(onBack={screen="root"})
        "storage" -> LumoReferenceStorage(onBack={screen="root"})
        "accessibility" -> LumoReferenceAccessibility(onBack={screen="root"})
        "privacy" -> Column(Modifier.fillMaxSize()){
            LumoReferenceHeader("Конфиденциальность"){screen="root"}
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ){
                LumoPrivacyControls(privacy)
            }
        }
        "generic" -> LumoReferenceGenericScreen(
            title="Настройки Lumo",
            onBack={screen="root"},
            openAdvanced=openAdvanced
        )
        else -> LumoReferenceSettingsRoot(
            token=token,
            me=me,
            go={screen=it},
            openAdvanced=openAdvanced
        )
    }
}

@Composable
private fun LumoReferenceSettingsRoot(
    token:String,
    me:User,
    go:(String)->Unit,
    openAdvanced:()->Unit
){
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ){
        Box(
            Modifier
                .fillMaxWidth()
                .background(ReferenceBg)
                .padding(horizontal=18.dp,vertical=10.dp)
        ){
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment=Alignment.CenterVertically
            ){
                Text(
                    "Lumo",
                    color=Color.White,
                    style=MaterialTheme.typography.headlineLarge,
                    fontWeight=FontWeight.ExtraBold
                )
                Spacer(Modifier.weight(1f))
                ReferenceIconButton("⌕"){}
                ReferenceIconButton("▦"){}
                ReferenceIconButton("✎"){go("profile")}
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(ReferenceBg)
                .padding(horizontal=18.dp,vertical=16.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ){
            Box{
                LumoUserAvatar(token,me,size=118.dp)
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(ReferenceGreen)
                        .align(Alignment.BottomEnd)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                me.displayName,
                color=Color.White,
                style=MaterialTheme.typography.headlineMedium
            )
            Text("@"+me.username,color=ReferenceMuted)
        }

        SettingsSection{
            ReferenceSettingsRow("▣","Связанные устройства","Используйте Lumo на других устройствах"){
                openAdvanced()
            }
            ReferenceSettingsRow("⚿","Аккаунт","Уведомления безопасности, изменение номера"){
                go("account")
            }
            ReferenceSettingsRow("▢","Конфиденциальность","Заблокированные аккаунты, приватность"){
                go("privacy")
            }
            ReferenceSettingsRow("▤","Списки","Управление контактами и группами"){
                go("generic")
            }
            ReferenceSettingsRow("▰","Чаты","Тема, обои, история чатов"){
                openAdvanced()
            }
            ReferenceSettingsRow("♧","Уведомления","Звуки сообщений, групп и звонков"){
                go("notifications")
            }
            ReferenceSettingsRow("◔","Данные и хранилище","Использование сети, автозагрузка"){
                go("storage")
            }
            ReferenceSettingsRow("♢","Родительский контроль","Настройки для семьи"){
                go("generic")
            }
            ReferenceSettingsRow("⚝","Специальные возможности","Контрастность, анимация"){
                go("accessibility")
            }
            ReferenceSettingsRow("⊕","Язык приложения","Русский (язык устройства)"){
                go("generic")
            }
            ReferenceSettingsRow("?","Помощь и отзывы","Справочный центр, связь с нами"){
                go("generic")
            }
            ReferenceSettingsRow("♙","Пригласить друга",""){
                go("generic")
            }
        }

        Spacer(Modifier.height(8.dp))
        SettingsSection{
            ReferenceSettingsRow("∞","Центр аккаунтов","Управление аккаунтом Lumo"){
                openAdvanced()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LumoReferenceProfileScreen(
    token:String,
    me:User,
    profileChanged:(User)->Unit,
    onBack:()->Unit
){
    val scope=rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var name by remember(me.displayName){ mutableStateOf(me.displayName) }
    var bio by remember(me.bio){ mutableStateOf(me.bio) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()){
        LumoReferenceHeader("Профиль",onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal=22.dp,vertical=18.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ){
            LumoEditableAvatar(token,me,profileChanged,size=150.dp)
            Spacer(Modifier.height(28.dp))

            if(editing){
                OutlinedTextField(
                    value=name,
                    onValueChange={name=it.take(50)},
                    label={Text("Имя")},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value=bio,
                    onValueChange={bio=it.take(160)},
                    label={Text("Информация")},
                    maxLines=3,
                    modifier=Modifier.fillMaxWidth()
                )
                if(message.isNotBlank()){
                    Text(
                        message,
                        color=if(message.startsWith("Не"))MaterialTheme.colorScheme.error else ReferenceGreen,
                        modifier=Modifier.padding(top=8.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick={
                        busy=true
                        scope.launch{
                            runCatching{
                                withContext(Dispatchers.IO){Api.updateMe(token,name,bio)}
                            }.onSuccess{result->
                                profileChanged(
                                    result.first.copy(
                                        bio=if(result.second)result.first.bio else bio.trim()
                                    )
                                )
                                message="Сохранено"
                                editing=false
                            }.onFailure{
                                message="Не удалось сохранить профиль"
                            }
                            busy=false
                        }
                    },
                    enabled=!busy&&name.isNotBlank(),
                    modifier=Modifier.fillMaxWidth()
                ){
                    Text(if(busy)"Сохраняем…" else "Сохранить")
                }
            }else{
                ReferenceProfileInfoRow("♙","Имя",me.displayName)
                ReferenceProfileInfoRow(
                    "ⓘ",
                    "Информация",
                    me.bio.ifBlank{"Добавить информацию"}
                )
                ReferenceProfileInfoRow(
                    "@",
                    "Имя пользователя",
                    "@"+me.username
                )
                ReferenceProfileInfoRow(
                    "☎",
                    "Телефон",
                    "Номер подтверждается через Lumo"
                )
                ReferenceProfileInfoRow(
                    "↗",
                    "Ссылки",
                    "Добавить ссылки"
                )
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick={editing=true},
                    modifier=Modifier.fillMaxWidth()
                ){
                    Text("Редактировать профиль")
                }
            }
        }
    }
}

@Composable
private fun ReferenceProfileInfoRow(
    icon:String,
    title:String,
    value:String
){
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical=14.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Text(
            icon,
            color=ReferenceMuted,
            style=MaterialTheme.typography.headlineSmall,
            modifier=Modifier.width(48.dp)
        )
        Column{
            Text(title,color=Color.White,style=MaterialTheme.typography.titleMedium)
            Text(
                value,
                color=if(value.startsWith("Добавить"))ReferenceGreen else ReferenceMuted,
                style=MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun LumoReferenceAccountScreen(
    onBack:()->Unit,
    openAdvanced:()->Unit,
    logout:()->Unit
){
    Column(Modifier.fillMaxSize()){
        LumoReferenceHeader("Аккаунт",onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ){
            SettingsSection{
                ReferenceSettingsRow("♙+","Добавить аккаунт",""){
                    openAdvanced()
                }
            }
            ReferenceSectionTitle("Вход и безопасность")
            SettingsSection{
                ReferenceSettingsRow("♙⚿","Ключи доступа",""){
                    openAdvanced()
                }
                ReferenceSettingsRow("***","Пароль",""){
                    openAdvanced()
                }
                ReferenceSettingsRow("✉","Электронный адрес",""){
                    openAdvanced()
                }
                ReferenceSettingsRow("▣","Двухшаговая проверка",""){
                    openAdvanced()
                }
                ReferenceSettingsRow("◇","Уведомления безопасности",""){
                    openAdvanced()
                }
            }
            ReferenceSectionTitle("Ваш аккаунт")
            SettingsSection{
                ReferenceSettingsRow("@","Имя пользователя",""){
                    openAdvanced()
                }
                ReferenceSettingsRow("☎","Изменить номер телефона","Подтверждение через SMS"){
                    openAdvanced()
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsSection{
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick=logout)
                        .padding(horizontal=18.dp,vertical=17.dp),
                    verticalAlignment=Alignment.CenterVertically
                ){
                    Text("⇥",color=Color(0xFFFF5B74),style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(18.dp))
                    Text(
                        "Выйти",
                        color=Color(0xFFFF5B74),
                        style=MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun LumoReferenceNotifications(
    onBack:()->Unit
){
    val context=LocalContext.current
    val prefs=remember{
        context.getSharedPreferences("lumo_reference_notifications",Context.MODE_PRIVATE)
    }
    var chatSounds by remember{
        mutableStateOf(prefs.getBoolean("chat_sounds",true))
    }
    var reminders by remember{
        mutableStateOf(prefs.getBoolean("reminders",true))
    }
    var priority by remember{
        mutableStateOf(prefs.getBoolean("priority",true))
    }
    var reactions by remember{
        mutableStateOf(prefs.getBoolean("reactions",true))
    }

    Column(Modifier.fillMaxSize()){
        LumoReferenceHeader("Уведомления",onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ){
            SettingsSection{
                ReferenceToggleRow(
                    "Звуки в чате",
                    "Воспроизводить звуки для входящих и исходящих сообщений",
                    chatSounds
                ){
                    chatSounds=it
                    prefs.edit().putBoolean("chat_sounds",it).apply()
                }
                ReferenceToggleRow(
                    "Напоминания",
                    "Получайте периодические напоминания о сообщениях и звонках",
                    reminders
                ){
                    reminders=it
                    prefs.edit().putBoolean("reminders",it).apply()
                }
            }

            ReferenceSectionTitle("Сообщения")
            SettingsSection{
                ReferenceSettingsRow("♪","Звук уведомления","По умолчанию"){
                }
                ReferenceSettingsRow("▥","Вибрация","По умолчанию"){
                }
                ReferenceSettingsRow("☀","Свет","Белый"){
                }
                ReferenceToggleRow(
                    "Приоритетные уведомления",
                    "Показывать всплывающие уведомления в верхней части экрана",
                    priority
                ){
                    priority=it
                    prefs.edit().putBoolean("priority",it).apply()
                }
                ReferenceToggleRow(
                    "Уведомления о реакциях",
                    "Показывать уведомления о реакциях на ваши сообщения",
                    reactions
                ){
                    reactions=it
                    prefs.edit().putBoolean("reactions",it).apply()
                }
            }

            ReferenceSectionTitle("Группы")
            SettingsSection{
                ReferenceSettingsRow("♪","Звук уведомления","Без звука"){}
                ReferenceSettingsRow("▥","Вибрация","Выкл."){}
                ReferenceSettingsRow("☀","Свет","Нет"){}
                ReferenceToggleRow(
                    "Приоритетные уведомления",
                    "Показывать всплывающие уведомления",
                    priority
                ){priority=it}
                ReferenceToggleRow(
                    "Уведомления о реакциях",
                    "Показывать уведомления о реакциях",
                    reactions
                ){reactions=it}
            }

            ReferenceSectionTitle("Звонки")
            SettingsSection{
                ReferenceSettingsRow("♪","Рингтон","По умолчанию"){}
                ReferenceSettingsRow("▥","Вибрация","Короткий"){}
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LumoReferenceStorage(
    onBack:()->Unit
){
    val context=LocalContext.current
    val prefs=remember{
        context.getSharedPreferences("lumo_reference_storage",Context.MODE_PRIVATE)
    }
    var saver by remember{
        mutableStateOf(prefs.getBoolean("data_saver",false))
    }
    val cacheSize=remember{
        runCatching{
            fun size(file:java.io.File):Long =
                if(file.isFile)file.length()
                else file.listFiles()?.sumOf{size(it)}?:0L
            size(context.cacheDir)
        }.getOrDefault(0L)
    }
    val cacheLabel=when{
        cacheSize>1024L*1024L*1024L ->
            String.format(java.util.Locale.US,"%.1f ГБ",cacheSize/(1024.0*1024.0*1024.0))
        cacheSize>1024L*1024L ->
            String.format(java.util.Locale.US,"%.1f МБ",cacheSize/(1024.0*1024.0))
        else -> String.format(java.util.Locale.US,"%.0f КБ",cacheSize/1024.0)
    }

    Column(Modifier.fillMaxSize()){
        LumoReferenceHeader("Данные и хранилище",onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ){
            SettingsSection{
                ReferenceSettingsRow("▭","Управление хранилищем",cacheLabel){}
            }
            SettingsSection{
                ReferenceSettingsRow("◔","Статистика","Локальные данные Lumo"){}
                ReferenceToggleRow(
                    "Экономия данных",
                    "Снижать расход мобильного трафика",
                    saver
                ){
                    saver=it
                    prefs.edit().putBoolean("data_saver",it).apply()
                }
                ReferenceSettingsRow("","Прокси-сервер","Выкл."){}
            }
            SettingsSection{
                ReferenceSettingsRow("HD","Качество медиа","Стандартное качество"){}
                ReferenceSettingsRow("","Качество автозагрузки","Авто"){}
            }
            ReferenceSectionTitle("Автоскачивание медиа")
            Text(
                "Голосовые сообщения всегда скачиваются автоматически.",
                color=ReferenceMuted,
                style=MaterialTheme.typography.bodyMedium,
                modifier=Modifier.padding(horizontal=22.dp).padding(bottom=6.dp)
            )
            SettingsSection{
                ReferenceSettingsRow("▯","Мобильный трафик","Фото"){}
                ReferenceSettingsRow("⌁","Wi-Fi","Все медиафайлы"){}
                ReferenceSettingsRow("✈","В роуминге","Нет"){}
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LumoReferenceAccessibility(
    onBack:()->Unit
){
    val context=LocalContext.current
    val prefs=remember{
        context.getSharedPreferences("lumo_reference_accessibility",Context.MODE_PRIVATE)
    }
    var contrast by remember{
        mutableStateOf(prefs.getBoolean("contrast",false))
    }
    var animation by remember{
        mutableStateOf(prefs.getBoolean("animation",true))
    }
    Column(Modifier.fillMaxSize()){
        LumoReferenceHeader("Специальные возможности",onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal=18.dp,vertical=12.dp)
        ){
            ReferenceToggleRow(
                "Повысить контрастность",
                "Затемнить основные цвета, чтобы улучшить видимость в дневном режиме.",
                contrast
            ){
                contrast=it
                prefs.edit().putBoolean("contrast",it).apply()
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "Анимация",
                color=Color.White,
                style=MaterialTheme.typography.titleLarge
            )
            Text(
                "Выберите, будут ли элементы чата анимироваться автоматически.",
                color=ReferenceMuted,
                style=MaterialTheme.typography.bodyLarge,
                modifier=Modifier.padding(top=6.dp,bottom=10.dp)
            )
            Switch(
                checked=animation,
                onCheckedChange={
                    animation=it
                    prefs.edit().putBoolean("animation",it).apply()
                }
            )
        }
    }
}

@Composable
private fun LumoReferenceGenericScreen(
    title:String,
    onBack:()->Unit,
    openAdvanced:()->Unit
){
    Column(Modifier.fillMaxSize()){
        LumoReferenceHeader(title,onBack)
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center
        ){
            Text(
                "Раздел подключён к новой навигации Lumo.",
                color=Color.White,
                style=MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Расширенные рабочие параметры сохранены и доступны ниже.",
                color=ReferenceMuted
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick=openAdvanced){
                Text("Открыть расширенные настройки")
            }
        }
    }
}

@Composable
fun LumoReferenceHeader(
    title:String,
    onBack:()->Unit
){
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(Color(0xFF07101A))
            .padding(horizontal=10.dp,vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        TextButton(
            onClick=onBack,
            contentPadding=PaddingValues(6.dp)
        ){
            Text(
                "‹",
                color=Color.White,
                style=MaterialTheme.typography.headlineLarge
            )
        }
        Text(
            title,
            color=Color.White,
            style=MaterialTheme.typography.headlineSmall,
            fontWeight=FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        ReferenceIconButton("⋮"){}
    }
    HorizontalDivider(color=ReferenceLine)
}

@Composable
private fun SettingsSection(
    content:@Composable ColumnScope.()->Unit
){
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal=10.dp,vertical=4.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xB50A111C))
            .padding(vertical=2.dp),
        content=content
    )
}

@Composable
private fun ReferenceSettingsRow(
    icon:String,
    title:String,
    subtitle:String,
    onClick:()->Unit
){
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick=onClick)
            .padding(horizontal=16.dp,vertical=14.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Box(
            Modifier.width(48.dp),
            contentAlignment=Alignment.CenterStart
        ){
            Icon(
                imageVector=referenceIcon(icon),
                contentDescription=null,
                tint=ReferenceMuted,
                modifier=Modifier.size(24.dp)
            )
        }
        Column(Modifier.weight(1f)){
            Text(
                title,
                color=Color.White,
                style=MaterialTheme.typography.titleMedium
            )
            if(subtitle.isNotBlank()){
                Text(
                    subtitle,
                    color=ReferenceMuted,
                    style=MaterialTheme.typography.bodyMedium
                )
            }
        }
        Text("›",color=ReferenceMuted,style=MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ReferenceToggleRow(
    title:String,
    subtitle:String,
    checked:Boolean,
    onChecked:(Boolean)->Unit
){
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal=16.dp,vertical=14.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Column(Modifier.weight(1f)){
            Text(
                title,
                color=Color.White,
                style=MaterialTheme.typography.titleMedium
            )
            if(subtitle.isNotBlank()){
                Text(
                    subtitle,
                    color=ReferenceMuted,
                    style=MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked=checked,
            onCheckedChange=onChecked,
            colors=SwitchDefaults.colors(
                checkedThumbColor=Color(0xFF06251A),
                checkedTrackColor=ReferenceGreen
            )
        )
    }
}

@Composable
private fun ReferenceSectionTitle(
    title:String
){
    Text(
        title,
        color=ReferenceMuted,
        fontWeight=FontWeight.Bold,
        style=MaterialTheme.typography.titleMedium,
        modifier=Modifier.padding(start=22.dp,end=22.dp,top=20.dp,bottom=8.dp)
    )
}

private fun referenceIcon(token:String):ImageVector = when(token){
    "⌕" -> Icons.Rounded.Search
    "✎" -> Icons.Rounded.Edit
    "⋮" -> Icons.Rounded.MoreVert
    "+" -> Icons.Rounded.Add
    "☎" -> Icons.Rounded.Call
    "▣" -> Icons.Rounded.Videocam
    "▦" -> Icons.Rounded.Event
    "↗" -> Icons.Rounded.Link
    "♙" -> Icons.Rounded.Person
    "♙+" -> Icons.Rounded.PersonAdd
    "⚿" -> Icons.Rounded.Key
    "***" -> Icons.Rounded.Lock
    "✉" -> Icons.Rounded.Email
    "◇" -> Icons.Rounded.Security
    "@" -> Icons.Rounded.AlternateEmail
    "▭" -> Icons.Rounded.Storage
    "◔" -> Icons.Rounded.DataUsage
    "HD" -> Icons.Rounded.HighQuality
    "▯" -> Icons.Rounded.PhoneAndroid
    "⌁" -> Icons.Rounded.Wifi
    "✈" -> Icons.Rounded.Flight
    "▰" -> Icons.Rounded.Work
    "✦" -> Icons.Rounded.Public
    "◈" -> Icons.Rounded.SportsEsports
    "◆" -> Icons.Rounded.DirectionsCar
    "▶" -> Icons.Rounded.Movie
    "ⓘ" -> Icons.Rounded.Info
    "↗" -> Icons.Rounded.Link
    "∞" -> Icons.Rounded.AccountCircle
    "?" -> Icons.Rounded.HelpOutline
    "♧" -> Icons.Rounded.Notifications
    "⊕" -> Icons.Rounded.Language
    "⚝" -> Icons.Rounded.Accessibility
    "▤" -> Icons.Rounded.List
    "▢" -> Icons.Rounded.Lock
    else -> Icons.Rounded.Circle
}

@Composable
private fun ReferenceIconButton(
    text:String,
    onClick:()->Unit
){
    IconButton(
        onClick=onClick,
        modifier=Modifier.size(42.dp)
    ){
        Icon(
            imageVector=referenceIcon(text),
            contentDescription=null,
            tint=Color.White,
            modifier=Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ReferenceActionCard(
    icon:String,
    title:String,
    subtitle:String,
    modifier:Modifier=Modifier,
    onClick:()->Unit
){
    Column(
        modifier
            .height(120.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(ReferencePanel2)
            .clickable(onClick=onClick)
            .padding(16.dp),
        verticalArrangement=Arrangement.Center,
        horizontalAlignment=Alignment.CenterHorizontally
    ){
        Icon(referenceIcon(icon),contentDescription=null,tint=Color.White,modifier=Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(title,color=Color.White,fontWeight=FontWeight.Bold)
        Text(subtitle,color=Color(0xFFD6DDF0),style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReferenceMiniAction(
    icon:String,
    title:String,
    modifier:Modifier=Modifier,
    onClick:()->Unit
){
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ReferencePanel)
            .clickable(onClick=onClick)
            .padding(horizontal=13.dp,vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Icon(referenceIcon(icon),contentDescription=null,tint=Color.White,modifier=Modifier.size(20.dp))
        Spacer(Modifier.width(9.dp))
        Text(title,color=Color(0xFFD6DDF0),style=MaterialTheme.typography.bodyMedium)
    }
}
