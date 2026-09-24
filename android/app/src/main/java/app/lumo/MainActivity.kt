package app.lumo

import android.os.Bundle
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.pm.PackageInstaller
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch

data class User(val id:String,val username:String,val displayName:String,val bio:String="",val bioSupported:Boolean=false,val hasAvatar:Boolean=false,val avatarVersion:String="")
data class Msg(val id:String,val from:String,val to:String,val text:String,val createdAt:String="",val deliveredAt:String="",val readAt:String="",val clientMessageId:String="",val attachmentId:String="",val replyToMessageId:String="",val replyPreviewText:String="",val replyPreviewFrom:String="",val editedAt:String="",val deletedAt:String="")
data class Conversation(val peer:User,val lastMessage:String,val lastAt:String="")
data class UpdateInfo(val versionCode:Int,val downloadUrl:String)
data class Receipt(val messageId:String,val deliveredAt:String,val readAt:String)
data class PendingMessage(val clientMessageId:String,val text:String,val replyToMessageId:String="",val replyPreviewText:String="",val replyPreviewFrom:String="")
private fun nullableJsonText(o:JSONObject,key:String):String=if(o.isNull(key))"" else o.optString(key)
class SessionExpiredException:Exception("Сессия недействительна")

class MainActivity:ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);PushLifecycle.onAppStart(this);setContent{LumoTheme{App()}}}
 override fun onResume(){super.onResume();PushLifecycle.onAppResume(this)}
}

@Composable fun App(){
 val context=LocalContext.current
 val prefs=remember{context.getSharedPreferences("lumo_session",Context.MODE_PRIVATE)}
 var token by remember{mutableStateOf(prefs.getString("token",null))}
 var me by remember{mutableStateOf<User?>(null)}
 var peer by remember{mutableStateOf<User?>(null)}
 var activeGroup by remember{mutableStateOf<LumoGroup?>(null)}
 var viewingGroups by remember{mutableStateOf(false)}
 var viewingCalls by remember{mutableStateOf(false)}
 var callQuery by remember{mutableStateOf("")}
 var callAutoKind by remember{mutableStateOf<String?>(null)}
 var viewingAi by remember{mutableStateOf(false)}
 var aiDraft by remember{mutableStateOf("")}
 var logoutNonce by remember{mutableIntStateOf(0)}
 var restoring by remember{mutableStateOf(token!=null)}
 var restoreError by remember{mutableStateOf(false)}
 var restoreRetry by remember{mutableIntStateOf(0)}
 val privacy=rememberLumoPrivacy(me?.id.orEmpty())
 LumoScreenshotProtection(me!=null&&privacy.protectScreenshots)
 LaunchedEffect(token,restoreRetry){
  val t=token
  if(t==null){restoring=false;me=null;restoreError=false}
  else if(me==null){
   restoring=true;restoreError=false
   runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.me(t)}}
    .onSuccess{me=it}
    .onFailure{error->if(error is SessionExpiredException){PushLifecycle.forgetOnLogout(context);prefs.edit().remove("token").apply();token=null}else restoreError=true}
   restoring=false
  }
 }
 when {
  restoring -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
  restoreError -> Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
   Text("Не удалось подключиться к серверу. Аккаунт сохранён.")
   Spacer(Modifier.height(16.dp));Button({restoreRetry++}){Text("Повторить")}
  }
  token==null || me==null -> Register{t,u->prefs.edit().putString("token",t).apply();token=t;me=u}
  peer==null&&activeGroup==null&&!viewingGroups&&!viewingCalls&&!viewingAi -> Home(
   token!!,me!!,
   {peer=it},
   {viewingGroups=true},
   {callQuery="";callAutoKind=null;viewingCalls=true},
   {aiDraft="";viewingAi=true},
   {me=it},privacy
  ){
   PushLifecycle.forgetOnLogout(context);prefs.edit().clear().apply()
   token=null;me=null;peer=null;activeGroup=null
   viewingGroups=false;viewingCalls=false;callQuery="";callAutoKind=null;viewingAi=false;logoutNonce++
  }
  activeGroup!=null -> GroupRoom(token!!,me!!,activeGroup!!){activeGroup=null}
  viewingGroups -> LumoBackdrop(Modifier.fillMaxSize()){
   Column(Modifier.fillMaxSize().statusBarsPadding()){
    TextButton(onClick={viewingGroups=false}){Text("‹ К чатам",color=Color.White)}
    GroupsScreen(token!!,me!!){activeGroup=it}
   }
  }
  viewingCalls -> LumoCallsLab(
   token!!,me!!,initialQuery=callQuery,initialAutoKind=callAutoKind
  ){
   viewingCalls=false
   callQuery=""
   callAutoKind=null
  }
  viewingAi -> LumoAiScreen(token!!,initialDraft=aiDraft){aiDraft="";viewingAi=false}
  else -> Chat(
   token!!,me!!,peer!!,
   openAudioCall={callQuery=peer!!.username;callAutoKind="audio";viewingCalls=true},
   openVideoCall={callQuery=peer!!.username;callAutoKind="video";viewingCalls=true},
   askAi={text->aiDraft=("Помоги понять это сообщение:\n“"+text.take(1200)+"”");viewingAi=true},
   back={peer=null}
  )
 }
}

@Composable fun Register(done:(String,User)->Unit){
 val scope=rememberCoroutineScope()
 var loginMode by remember{mutableStateOf(false)}
 var name by remember{mutableStateOf("")}
 var login by remember{mutableStateOf("")}
 var password by remember{mutableStateOf("")}
 var passwordVisible by remember{mutableStateOf(false)}
 var err by remember{mutableStateOf("")}
 var busy by remember{mutableStateOf(false)}
 val fieldColors=OutlinedTextFieldDefaults.colors(
  focusedTextColor=Color.White,unfocusedTextColor=Color.White,
  focusedBorderColor=LumoCyan,unfocusedBorderColor=Color(0xFFACCFFF),
  focusedLabelColor=Color.White,unfocusedLabelColor=Color(0xFFE0EAFF),
  focusedContainerColor=Color(0x862C4AAB),
  unfocusedContainerColor=Color(0x70233D91),
  cursorColor=LumoCyan
 )
 LumoBackdrop(Modifier.fillMaxSize()){
  Column(
   Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    .padding(horizontal=24.dp,vertical=30.dp),
   verticalArrangement=Arrangement.Center,
   horizontalAlignment=Alignment.CenterHorizontally
  ){
   LumoPlanetIcon(140.dp)
   Spacer(Modifier.height(15.dp))
   Text("Lumo",style=MaterialTheme.typography.displayLarge,
    fontWeight=FontWeight.ExtraBold,color=Color.White)
   Text(if(loginMode)"С возвращением" else "Ближе к важным людям",
    style=MaterialTheme.typography.titleMedium,color=Color.White)
   Spacer(Modifier.height(24.dp))
   Column(Modifier.fillMaxWidth().lumoGlass(28).padding(18.dp)){
    if(!loginMode){
     OutlinedTextField(
      name,{name=it},label={Text("Имя")},singleLine=true,
      shape=RoundedCornerShape(20.dp),colors=fieldColors,
      modifier=Modifier.fillMaxWidth()
     )
     Spacer(Modifier.height(10.dp))
    }
    OutlinedTextField(
     login,{login=it},label={Text("Логин")},singleLine=true,
     shape=RoundedCornerShape(20.dp),colors=fieldColors,
     modifier=Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
     password,{password=it},label={Text("Пароль")},singleLine=true,
     visualTransformation=if(passwordVisible)
      androidx.compose.ui.text.input.VisualTransformation.None
      else PasswordVisualTransformation(),
     trailingIcon={
      TextButton({passwordVisible=!passwordVisible}){
       Text(if(passwordVisible)"Скрыть" else "◉",color=Color.White)
      }
     },
     shape=RoundedCornerShape(20.dp),colors=fieldColors,
     modifier=Modifier.fillMaxWidth()
    )
    if(!loginMode)Text("Пароль: минимум 10 символов",
     style=MaterialTheme.typography.bodySmall,
     color=MaterialTheme.colorScheme.onSurfaceVariant,
     modifier=Modifier.padding(top=6.dp))
    if(err.isNotEmpty())Text(err,color=MaterialTheme.colorScheme.error,
     modifier=Modifier.padding(top=8.dp))
    Spacer(Modifier.height(16.dp))
    LumoNeonButton(
     text=if(busy)"Подключаем…" else if(loginMode)"Войти" else "Создать аккаунт",
     enabled=!busy&&login.isNotBlank()&&password.isNotBlank()
      &&(loginMode||(name.isNotBlank()&&password.length>=10)),
     modifier=Modifier.fillMaxWidth(),
     onClick={
      busy=true;err=""
      scope.launch{
       runCatching{
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
         if(loginMode)Api.login(login,password) else Api.register(login,name,password)
        }
       }.onSuccess{password="";done(it.first,it.second)}
        .onFailure{err=it.message?:"Ошибка подключения"}
       busy=false
      }
     }
    )
    Spacer(Modifier.height(12.dp))
    Text(
     if(loginMode)"Ещё нет аккаунта?" else "Уже есть аккаунт?",
     color=MaterialTheme.colorScheme.onSurfaceVariant,
     style=MaterialTheme.typography.bodySmall,
     modifier=Modifier.align(Alignment.CenterHorizontally)
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
     onClick={loginMode=!loginMode;password="";err="";passwordVisible=false},
     enabled=!busy,
     modifier=Modifier.fillMaxWidth().height(48.dp),
     shape=RoundedCornerShape(24.dp),
     border=androidx.compose.foundation.BorderStroke(1.4.dp,LumoPink),
     colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)
    ){
     Text(if(loginMode)"Создать аккаунт" else "Войти",
      style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    }
   }
  }
 }
}

@Composable fun Home(token:String,me:User,open:(User)->Unit,openGroups:()->Unit,openCalls:()->Unit,openAi:()->Unit,profileChanged:(User)->Unit,privacy:LumoPrivacy,logout:()->Unit){
 var tab by remember{mutableIntStateOf(0)}
 LumoBackdrop(Modifier.fillMaxSize()){
  Scaffold(
   containerColor=Color.Transparent,
   topBar={
    Row(
     Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=18.dp,vertical=12.dp),
     verticalAlignment=Alignment.CenterVertically
    ){
     Text("Lumo",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.ExtraBold,color=Color.White)
     Spacer(Modifier.weight(1f))
     Box(Modifier.lumoGlass(22).clickable{tab=2}.padding(horizontal=15.dp,vertical=8.dp)){
      Text(me.displayName,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,color=Color.White,maxLines=1)
     }
    }
   },
   bottomBar={LumoBottomNavigation(selected=tab,onSelect={tab=it})}
  ){pad->
   Box(Modifier.fillMaxSize().padding(pad)){
    when(tab){
     0->Chats(token,me,{tab=1},open,openGroups,openCalls,privacy)
     1->People(token,open)
     else->Profile(token,me,profileChanged,privacy,openCalls,openAi,logout)
    }
   }
  }
 }
}

@Composable fun Chats(
 token:String,me:User,find:()->Unit,open:(User)->Unit,
 openGroups:()->Unit,openCalls:()->Unit,privacy:LumoPrivacy
){
 val context=LocalContext.current
 var chats by remember{mutableStateOf<List<Conversation>>(emptyList())}
 var chatQuery by remember{mutableStateOf("")}
 var loading by remember{mutableStateOf(true)}
 var loadError by remember{mutableStateOf(false)}
 var loadErrorDetail by remember{mutableStateOf("")}
 var refreshError by remember{mutableStateOf(false)}
 var showingCached by remember(me.id){mutableStateOf(false)}
 var retry by remember{mutableIntStateOf(0)}
 LaunchedEffect(token,me.id,retry){
  loading=true;loadError=false;refreshError=false
  if(chats.isEmpty()){
   val cached=runCatching{
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
     LumoOfflineStore.loadConversations(context,me.id)
    }
   }.getOrDefault(emptyList())
   if(cached.isNotEmpty()){
    chats=cached
    showingCached=true
    loading=false
   }
  }
  while(true){
   val result=runCatching{
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
     Api.conversations(token)
    }
   }
   result.onSuccess{fresh->
    chats=fresh
    loadError=false
    refreshError=false
    showingCached=false
    runCatching{
     kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
      LumoOfflineStore.saveConversations(context,me.id,fresh)
     }
    }
   }.onFailure{
    loadErrorDetail=it.message?:"Ошибка соединения"
    if(chats.isEmpty())loadError=true
    else {
     refreshError=true
     showingCached=true
    }
   }
   loading=false
   kotlinx.coroutines.delay(12_000)
  }
 }
 if(loading){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(color=LumoCyan)};return}
 Column(Modifier.fillMaxSize()){
  if(loadError&&chats.isEmpty()){
   Column(
    Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)
     .lumoGlass(22).padding(horizontal=14.dp,vertical=12.dp)
   ){
    Text("Не удалось обновить список чатов",color=Color.White,fontWeight=FontWeight.SemiBold)
    Text(loadErrorDetail,color=MaterialTheme.colorScheme.onSurfaceVariant,
     style=MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick={retry++}){Text("Повторить")}
   }
  }
  Row(
   Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)
    .lumoGlass(22).clickable{openGroups()}.padding(13.dp),
   verticalAlignment=Alignment.CenterVertically
  ){
   LumoNeonAvatar("Группы",size=44.dp)
   Spacer(Modifier.width(12.dp))
   Column(Modifier.weight(1f)){
    Text("Группы",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=Color.White)
    Text("Создать группу или открыть групповой чат",color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Text("›",color=Color.White)
  }
  Row(
   Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)
    .lumoGlass(22).clickable{openCalls()}.padding(13.dp),
   verticalAlignment=Alignment.CenterVertically
  ){
   LumoNeonAvatar("Звонки",size=44.dp)
   Spacer(Modifier.width(12.dp))
   Column(Modifier.weight(1f)){
    Text("Звонки",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=Color.White)
    Text("Аудио и видео",color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Text("›",color=Color.White)
  }
  LumoSearchField(
   chatQuery,{chatQuery=it},"Поиск по чатам",
   modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp)
  )
  if(refreshError||showingCached)Text(
   "Офлайн: показываем зашифрованную копию последних чатов с этого телефона.",
   color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=18.dp,vertical=6.dp)
  )
  if(chats.isEmpty()){
   Box(Modifier.fillMaxSize().padding(20.dp),contentAlignment=Alignment.Center){
    Column(Modifier.fillMaxWidth().lumoGlass(28).padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally){
     Text(if(loadError)"Чаты временно не загружены" else "Сообщений пока нет",
      style=MaterialTheme.typography.titleLarge,color=Color.White,fontWeight=FontWeight.Bold)
     Spacer(Modifier.height(8.dp))
     Text(
      if(loadError)"Можно найти человека и открыть диалог — список чатов обновится, когда сервер ответит."
      else "Найди человека и начни первый диалог",
      color=MaterialTheme.colorScheme.onSurfaceVariant
     )
     Spacer(Modifier.height(16.dp))
     LumoNeonButton("Найти людей",find,Modifier.fillMaxWidth())
    }
   }
  }else{
   val filtered=chats.filter{
    it.peer.displayName.contains(chatQuery,true)||it.peer.username.contains(chatQuery,true)
   }
   if(filtered.isEmpty()) Text(
    "Совпадений не найдено",color=Color.White,modifier=Modifier.padding(22.dp)
   )
   LazyColumn(
    modifier=Modifier.fillMaxSize(),
    contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
    verticalArrangement=Arrangement.spacedBy(10.dp)
   ){
    items(filtered,key={it.peer.id}){chat->
     Row(
      Modifier.fillMaxWidth().lumoGlass(22).clickable{open(chat.peer)}.padding(12.dp),
      verticalAlignment=Alignment.CenterVertically
     ){
      LumoUserAvatar(token,chat.peer,size=54.dp)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)){
       Text(chat.peer.displayName,style=MaterialTheme.typography.titleMedium,
        fontWeight=FontWeight.Bold,color=Color.White,maxLines=1)
       Spacer(Modifier.height(3.dp))
       Text(if(privacy.hideChatPreviews)"Содержимое скрыто" else chat.lastMessage,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if(chat.lastAt.isNotBlank()){
       Spacer(Modifier.width(8.dp))
       Text(formatMessageTime(chat.lastAt),style=MaterialTheme.typography.labelSmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
      }
     }
    }
   }
  }
 }
}

@Composable fun People(token:String,open:(User)->Unit){
 var users by remember{mutableStateOf<List<User>>(emptyList())}
 var q by remember{mutableStateOf("")}
 var loading by remember{mutableStateOf(false)}
 var loadError by remember{mutableStateOf(false)}
 var retry by remember{mutableIntStateOf(0)}
 LaunchedEffect(token,q,retry){
  loading=true;loadError=false;users=emptyList()
  kotlinx.coroutines.delay(300)
  runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.users(token,q)}}
   .onSuccess{users=it}.onFailure{loadError=true}
  loading=false
 }
 Column(Modifier.fillMaxSize()){
  LumoSearchField(
   value=q,onValueChange={q=it},placeholder="Поиск по имени или логину",
   modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp)
  )
  if(loading)LinearProgressIndicator(Modifier.fillMaxWidth(),color=LumoCyan)
  if(loadError){
   Column(
    Modifier.fillMaxWidth().padding(16.dp).lumoGlass(24).padding(20.dp),
    horizontalAlignment=Alignment.CenterHorizontally
   ){
    Text("Не удалось загрузить пользователей",color=Color.White)
    Spacer(Modifier.height(10.dp))
    LumoNeonButton("Повторить",onClick={retry++},modifier=Modifier.fillMaxWidth())
   }
  }
  if(!loading&&!loadError&&users.isEmpty()){
   Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){
    Text(if(q.isBlank())"Пользователей пока нет" else "Ничего не найдено",
     color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  LazyColumn(
   Modifier.fillMaxSize(),
   contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
   verticalArrangement=Arrangement.spacedBy(9.dp)
  ){
   items(users,key={it.id}){u->
    Row(
     Modifier.fillMaxWidth().lumoGlass(22).clickable{open(u)}.padding(12.dp),
     verticalAlignment=Alignment.CenterVertically
    ){
     LumoUserAvatar(token,u,size=52.dp)
     Spacer(Modifier.width(14.dp))
     Column(Modifier.weight(1f)){
      Text(u.displayName,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium,
       color=Color.White,maxLines=1)
      Spacer(Modifier.height(3.dp))
      Text("@"+u.username,color=MaterialTheme.colorScheme.onSurfaceVariant)
      if(u.bio.isNotBlank())Text(
       u.bio,maxLines=1,
       style=MaterialTheme.typography.bodySmall,
       color=MaterialTheme.colorScheme.onSurfaceVariant
      )
     }
     Text("›",style=MaterialTheme.typography.headlineSmall,color=Color.White,
      modifier=Modifier.padding(end=4.dp))
    }
   }
  }
 }
}

@Composable fun Profile(token:String,me:User,profileChanged:(User)->Unit,privacy:LumoPrivacy,openCalls:()->Unit,openAi:()->Unit,logout:()->Unit){
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 val profilePrefs=remember{context.getSharedPreferences("lumo_local_profile",Context.MODE_PRIVATE)}
 var editing by remember{mutableStateOf(false)}
 var name by remember(me.displayName){mutableStateOf(me.displayName)}
 var bio by remember(me.id,me.bio,me.bioSupported){mutableStateOf(if(me.bioSupported)me.bio else me.bio.ifBlank{profilePrefs.getString("bio_"+me.id,"")?:""})}
 var bioNotice by remember(me.id){mutableStateOf("")}
 var saving by remember{mutableStateOf(false)}
 var profileError by remember{mutableStateOf("")}
 var loggingOut by remember{mutableStateOf(false)}
 var logoutError by remember{mutableStateOf("")}
 var update by remember{mutableStateOf<UpdateInfo?>(null)}
 var checking by remember{mutableStateOf(true)}
 var updateText by remember{mutableStateOf("Проверяем обновления…")}
 var progress by remember{mutableIntStateOf(-1)}
 LaunchedEffect(Unit){
  runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.latestRelease()}}
   .onSuccess{info->
    update=info.takeIf{it.versionCode>BuildConfig.VERSION_CODE}
    updateText=if(update!=null)"Доступна новая версия Lumo" else "Установлена последняя версия"
   }.onFailure{updateText="Не удалось проверить обновления"}
  checking=false
 }
 Column(
  Modifier.fillMaxSize().verticalScroll(rememberScrollState())
   .padding(horizontal=18.dp,vertical=18.dp),
  horizontalAlignment=Alignment.CenterHorizontally
 ){
  // Keeps the first profile content clear of the fixed header on compact phones.
  Spacer(Modifier.height(14.dp))
  LumoEditableAvatar(token,me,profileChanged,size=122.dp)
  Spacer(Modifier.height(14.dp))
  Text(me.displayName,style=MaterialTheme.typography.headlineMedium,
   fontWeight=FontWeight.Bold,color=Color.White)
  Text("@"+me.username,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text("Публичное фото синхронизируется только после твоего отдельного подтверждения.",
   style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(24.dp))

  Column(Modifier.fillMaxWidth().lumoGlass(26).padding(17.dp)){
   Text(
    if(editing)"Редактирование профиля" else "Профиль",
    style=MaterialTheme.typography.titleLarge,
    fontWeight=FontWeight.Bold,color=Color.White
   )
   Spacer(Modifier.height(13.dp))
   if(editing){
    OutlinedTextField(
     name,{name=it;profileError=""},label={Text("Имя")},
     shape=RoundedCornerShape(20.dp),singleLine=true,modifier=Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
     "@"+me.username,{},label={Text("Логин")},readOnly=true,
     shape=RoundedCornerShape(20.dp),singleLine=true,modifier=Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
     bio,{bio=it.take(160)},label={Text("О себе")},
     shape=RoundedCornerShape(20.dp),maxLines=3,modifier=Modifier.fillMaxWidth()
    )
    Text("Описание синхронизируется между устройствами, когда сервер поддерживает новую версию профиля.",
     color=MaterialTheme.colorScheme.onSurfaceVariant,
     style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=6.dp))
    if(profileError.isNotBlank())Text(profileError,color=MaterialTheme.colorScheme.error,
     modifier=Modifier.padding(top=6.dp))
    Spacer(Modifier.height(16.dp))
    LumoNeonButton(
     text=if(saving)"Сохраняем…" else "Сохранить",
     enabled=!saving&&name.isNotBlank(),modifier=Modifier.fillMaxWidth(),
     onClick={
      saving=true
      scope.launch{
       runCatching{
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.updateMe(token,name,bio)}
       }.onSuccess{result->
        profilePrefs.edit().putString("bio_"+me.id,bio).apply()
        val serverUser=result.first
        val synced=result.second && serverUser.bio==bio.trim()
        bioNotice=if(synced)"Описание синхронизировано с аккаунтом." else "Сервер пока не поддерживает описание — сохранено только на этом телефоне."
        profileChanged(serverUser.copy(bio=if(synced)serverUser.bio else bio.trim()));editing=false;profileError=""
       }.onFailure{profileError="Не удалось сохранить имя"}
       saving=false
      }
     }
    )
    TextButton(
     onClick={
      name=me.displayName
      bio=if(me.bioSupported)me.bio else me.bio.ifBlank{profilePrefs.getString("bio_"+me.id,"")?:""}
      editing=false;profileError=""
     },modifier=Modifier.align(Alignment.CenterHorizontally)
    ){Text("Отмена",color=Color.White)}
   }else{
    Text(
     if(bio.isBlank())"Управление данными" else bio,
     style=MaterialTheme.typography.bodyMedium,
     color=MaterialTheme.colorScheme.onSurfaceVariant
    )
    if(bioNotice.isNotBlank())Text(
     bioNotice,
     style=MaterialTheme.typography.labelSmall,
     color=LumoCyan,
     modifier=Modifier.padding(top=6.dp)
    )
    Spacer(Modifier.height(14.dp))
    LumoNeonButton(
     "✎   Редактировать профиль",onClick={editing=true},
     modifier=Modifier.fillMaxWidth()
    )
   }
  }
  Spacer(Modifier.height(16.dp))
  LumoAppearanceControls()
  Spacer(Modifier.height(16.dp))
  LumoPrivacyControls(privacy)
  Spacer(Modifier.height(16.dp))
  LumoOfflineCacheControls(me.id)
  Spacer(Modifier.height(16.dp))
  LumoServerStatusCard()
  Spacer(Modifier.height(16.dp))
  PushSettings(token,me)
  Spacer(Modifier.height(16.dp))
  Column(Modifier.fillMaxWidth().lumoGlass(25).padding(17.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){
    LumoPlanetIcon(48.dp)
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)){
     Text("Lumo AI",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=Color.White)
     Text("Отдельный ИИ-диалог без автоматического доступа к твоим чатам",
      style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
   }
   Spacer(Modifier.height(12.dp))
   LumoNeonButton("Открыть Lumo AI",openAi,Modifier.fillMaxWidth())
  }
  Spacer(Modifier.height(16.dp))
  Column(Modifier.fillMaxWidth().lumoGlass(25).padding(17.dp)){
   Text("Звонки",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=Color.White)
   Spacer(Modifier.height(6.dp))
   Text("Звонки Lumo. Микрофон и камера включаются только после вашего явного действия.",
    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Spacer(Modifier.height(12.dp))
   LumoNeonButton("Открыть звонки",openCalls,Modifier.fillMaxWidth())
  }
  Spacer(Modifier.height(16.dp))
  Column(Modifier.fillMaxWidth().lumoGlass(25).padding(18.dp)){
   Text("⚙   Обновление",style=MaterialTheme.typography.titleMedium,
    fontWeight=FontWeight.Bold,color=Color.White)
   Spacer(Modifier.height(8.dp))
   Text(updateText,color=Color.White)
   Spacer(Modifier.height(3.dp))
   Text("Версия "+BuildConfig.VERSION_NAME,style=MaterialTheme.typography.bodySmall,
    color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(checking)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp),color=LumoCyan)
   if(progress>=0){
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth(),color=LumoCyan)
    Text("Загрузка: $progress%",modifier=Modifier.padding(top=6.dp),color=Color.White)
   }
   update?.let{u->
    if(progress<0){
     Spacer(Modifier.height(14.dp))
     LumoNeonButton(
      "Обновить Lumo",
      onClick={
       startUpdate(context,u.downloadUrl){p->
        scope.launch{
         progress=p
         updateText=when{
          p<0->"Не удалось загрузить обновление"
          p<100->"Загружаем обновление…"
          else->"Устанавливаем обновление…"
         }
        }
       }
      },modifier=Modifier.fillMaxWidth()
     )
    }
   }
  }
  Spacer(Modifier.height(18.dp))
  OutlinedButton(
   onClick={
    loggingOut=true;logoutError=""
    scope.launch{
     runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.logout(token)}}
      .onSuccess{logout()}
      .onFailure{error->
       if(error is SessionExpiredException)logout()
       else logoutError="Не удалось завершить сессию на сервере. Попробуйте ещё раз."
      }
     loggingOut=false
    }
   },
   enabled=!loggingOut,
   modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),
   shape=RoundedCornerShape(26.dp),
   border=androidx.compose.foundation.BorderStroke(1.5.dp,LumoPink),
   colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)
  ){
   Text(if(loggingOut)"Завершаем сессию…" else "Выйти из аккаунта")
  }
  if(logoutError.isNotEmpty()){
   Text(logoutError,color=MaterialTheme.colorScheme.error)
   TextButton(onClick=logout){Text("Выйти только с устройства",color=Color.White)}
   Text("При локальном выходе серверная сессия остаётся активной до истечения срока.",
    color=MaterialTheme.colorScheme.onSurfaceVariant,
    style=MaterialTheme.typography.bodySmall)
  }
  Spacer(Modifier.height(18.dp))
 }
}

fun startUpdate(context:Context,url:String,onProgress:(Int)->Unit){
 if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()){
  context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return
 }
 kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch{
  runCatching{
   val request=Request.Builder().url(url).build()
   Api.httpClient.newCall(request).execute().use{response->
    if(!response.isSuccessful)error("HTTP "+response.code)
    val body=response.body?:error("Пустой APK");val total=body.contentLength();val file=File(context.cacheDir,"Lumo-update.apk")
    body.byteStream().use{input->file.outputStream().use{out->val buf=ByteArray(64*1024);var read:Int;var done=0L;var last=-1;while(input.read(buf).also{read=it}>0){out.write(buf,0,read);done+=read;if(total>0){val p=((done*100)/total).toInt().coerceIn(0,100);if(p!=last){last=p;onProgress(p)}}}}}
    onProgress(100);installUpdate(context,file)
   }
  }.onFailure{onProgress(-1)}
 }
}

fun installUpdate(context:Context,apk:File){
 val installer=context.packageManager.packageInstaller
 val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
 val sessionId=installer.createSession(params);val session=installer.openSession(sessionId)
 apk.inputStream().use{input->session.openWrite("Lumo.apk",0,apk.length()).use{out->input.copyTo(out);session.fsync(out)}}
 val intent=Intent(context,LumoInstallReceiver::class.java).setAction("app.lumo.INSTALL_STATUS")
 val flags=if(Build.VERSION.SDK_INT>=31) android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE else android.app.PendingIntent.FLAG_UPDATE_CURRENT
 val pi=android.app.PendingIntent.getBroadcast(context,sessionId,intent,flags)
 session.commit(pi.intentSender);session.close()
}

class LumoInstallReceiver:BroadcastReceiver(){
 override fun onReceive(context:Context,intent:Intent){
  when(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)){
   PackageInstaller.STATUS_PENDING_USER_ACTION->{val confirm=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT);confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(confirm!=null)context.startActivity(confirm)}
   PackageInstaller.STATUS_SUCCESS->{val launch=context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP);if(launch!=null)context.startActivity(launch)}
  }
 }
}

fun mergeChatMessages(current:List<Msg>,incoming:List<Msg>):List<Msg>{
 val merged=LinkedHashMap<String,Msg>()
 for(m in current+incoming){
  val old=merged[m.id]
  if(old==null){merged[m.id]=m;continue}
  // A delayed socket/history response must never resurrect text that was
  // already edited or deleted on this device.
  val oldWins=when{
   old.deletedAt.isNotBlank() && m.deletedAt.isBlank()->true
   old.deletedAt.isNotBlank() && m.deletedAt.isNotBlank()->old.deletedAt>m.deletedAt
   old.editedAt.isNotBlank() && m.deletedAt.isBlank() &&
    (m.editedAt.isBlank() || old.editedAt>m.editedAt)->true
   else->false
  }
  val preferred=if(oldWins)old else m
  val other=if(oldWins)m else old
  merged[m.id]=preferred.copy(
   deliveredAt=preferred.deliveredAt.ifBlank{other.deliveredAt},
   readAt=preferred.readAt.ifBlank{other.readAt},
   clientMessageId=preferred.clientMessageId.ifBlank{other.clientMessageId},
   attachmentId=preferred.attachmentId.ifBlank{other.attachmentId},
   replyToMessageId=preferred.replyToMessageId.ifBlank{other.replyToMessageId},
   replyPreviewText=preferred.replyPreviewText.ifBlank{other.replyPreviewText},
   replyPreviewFrom=preferred.replyPreviewFrom.ifBlank{other.replyPreviewFrom}
  )
 }
 return merged.values.sortedBy{it.createdAt}
}

@Composable fun Chat(
 token:String,me:User,peer:User,
 openAudioCall:()->Unit,
 openVideoCall:()->Unit,
 askAi:(String)->Unit,
 back:()->Unit
){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val queuePrefs=remember{context.getSharedPreferences("lumo_pending",Context.MODE_PRIVATE)};val queueKey="pending_"+me.id+"_"+peer.id
 var activeMessage by remember(peer.id){mutableStateOf<Msg?>(null)}
 var forwardingMessage by remember(peer.id){mutableStateOf<Msg?>(null)}
 var replyTarget by remember(peer.id){mutableStateOf<Msg?>(null)}
 var reactionsEnabled by remember(token,peer.id){mutableStateOf(false)}
 var aiEnabled by remember(token,peer.id){mutableStateOf(false)}
 var linkedRepliesEnabled by remember(token,peer.id){mutableStateOf(false)}
 var messageEditEnabled by remember(token,peer.id){mutableStateOf(false)}
 var messageDeleteEnabled by remember(token,peer.id){mutableStateOf(false)}
 var messageSearchEnabled by remember(token,peer.id){mutableStateOf(false)}
 var editTarget by remember(peer.id){mutableStateOf<Msg?>(null)}
 var editDraft by remember(peer.id){mutableStateOf("")}
 var deleteTarget by remember(peer.id){mutableStateOf<Msg?>(null)}
 var mutationBusy by remember{mutableStateOf(false)}
 var showSearch by remember(peer.id){mutableStateOf(false)}
 var searchText by remember(peer.id){mutableStateOf("")}
 var searchResults by remember(peer.id){mutableStateOf<List<Msg>>(emptyList())}
 var searchBusy by remember{mutableStateOf(false)}
 var searchPerformed by remember{mutableStateOf(false)}
 var searchError by remember{mutableStateOf("")}
 var reactions by remember(peer.id){mutableStateOf<List<LumoReaction>>(emptyList())}
 var reactionRefresh by remember{mutableIntStateOf(0)}
 var reactionBusy by remember{mutableStateOf(false)}
 var actionError by remember{mutableStateOf("")}
 var showingCachedHistory by remember(me.id,peer.id){mutableStateOf(false)}
 var networkHistoryLoaded by remember(me.id,peer.id){mutableStateOf(false)}
 LaunchedEffect(token,peer.id) {
  reactionsEnabled=runCatching {
   kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){LumoReactionApi.enabled(token)}
  }.getOrDefault(false)
  aiEnabled=runCatching {
   kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){LumoAiApi.enabled(token)}
  }.getOrDefault(false)
  linkedRepliesEnabled=runCatching {
   kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.linkedRepliesSupported(token)}
  }.getOrDefault(false)
  val mutationCaps=runCatching {
   kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
    Api.messageMutationCapabilities(token)
   }
  }.getOrDefault(Triple(false,false,false))
  messageEditEnabled=mutationCaps.first
  messageDeleteEnabled=mutationCaps.second
  messageSearchEnabled=mutationCaps.third
 }
 LaunchedEffect(token,peer.id,reactionsEnabled,reactionRefresh) {
  if(reactionsEnabled) while(true) {
   runCatching {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
     LumoReactionApi.list(token,peer.id)
    }
   }.onSuccess{reactions=it}
   kotlinx.coroutines.delay(12_000)
  }
 }
 val msgs=remember{mutableStateListOf<Msg>()};val listState=rememberLazyListState();var input by remember{mutableStateOf("")};var ws by remember{mutableStateOf<WebSocket?>(null)};var socketGeneration by remember{mutableIntStateOf(0)};var connected by remember{mutableStateOf(false)};var socketError by remember{mutableStateOf("")};var historyError by remember{mutableStateOf(false)};val pending=remember{mutableStateListOf<PendingMessage>().apply{val a=runCatching{JSONArray(queuePrefs.getString(queueKey,"[]"))}.getOrNull();if(a!=null)for(i in 0 until a.length()){val o=a.optJSONObject(i);if(o!=null){val id=o.optString("clientMessageId");val text=o.optString("text");if(id.isNotBlank()&&text.isNotBlank())add(PendingMessage(id,text,o.optString("replyToMessageId"),o.optString("replyPreviewText"),o.optString("replyPreviewFrom")))}else{val text=a.optString(i);if(text.isNotBlank())add(PendingMessage(java.util.UUID.randomUUID().toString(),text))}}}}
 fun savePending(){
  val snapshot=pending.toList()
  val encrypted=runCatching{
   LumoOfflineStore.savePending(context,me.id,peer.id,snapshot)
  }.isSuccess
  if(encrypted){
   queuePrefs.edit().remove(queueKey).apply()
  }else{
   // Reliability fallback for a rare Keystore/storage failure. Existing app
   // versions already understand this local legacy queue and can retry it.
   val a=JSONArray()
   snapshot.forEach{
    a.put(JSONObject()
     .put("clientMessageId",it.clientMessageId)
     .put("text",it.text)
     .put("replyToMessageId",it.replyToMessageId)
     .put("replyPreviewText",it.replyPreviewText)
     .put("replyPreviewFrom",it.replyPreviewFrom))
   }
   queuePrefs.edit().putString(queueKey,a.toString()).apply()
  }
 }
 LaunchedEffect(me.id,peer.id){
  val encrypted=runCatching{
   kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
    LumoOfflineStore.loadPending(context,me.id,peer.id)
   }
  }.getOrDefault(emptyList())
  if(encrypted.isNotEmpty()){
   pending.clear();pending.addAll(encrypted)
   queuePrefs.edit().remove(queueKey).apply()
  }else if(pending.isNotEmpty()){
   val legacySnapshot=pending.toList()
   val migrated=runCatching{
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
     LumoOfflineStore.savePending(context,me.id,peer.id,legacySnapshot)
    }
   }.isSuccess
   if(migrated)queuePrefs.edit().remove(queueKey).apply()
  }
 }
 fun persistHistory(){
  val snapshot=msgs.toList()
  scope.launch{
   runCatching{
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
     LumoOfflineStore.saveHistory(context,me.id,peer.id,snapshot)
    }
   }
  }
 }
 LaunchedEffect(me.id,peer.id){
  val cached=runCatching{
   kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
    LumoOfflineStore.loadHistory(context,me.id,peer.id)
   }
  }.getOrDefault(emptyList())
  if(!networkHistoryLoaded&&cached.isNotEmpty()){
   val merged=mergeChatMessages(msgs,cached)
   msgs.clear();msgs.addAll(merged)
   showingCachedHistory=true
  }
 }
 DisposableEffect(peer.id){
  scope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.history(token,peer.id)}}.onSuccess{fresh->historyError=false;networkHistoryLoaded=true;showingCachedHistory=false;val merged=mergeChatMessages(msgs,fresh);msgs.clear();msgs.addAll(merged);persistHistory();val unread=fresh.filter{m->m.from==peer.id&&m.readAt.isBlank()}.map{m->m.id};if(unread.isNotEmpty())ws?.send(JSONObject().put("type","read").put("ids",JSONArray(unread)).toString())}.onFailure{historyError=true;if(msgs.isNotEmpty())showingCachedHistory=true}}
  fun syncHistory(){scope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.history(token,peer.id)}}.onSuccess{fresh->historyError=false;networkHistoryLoaded=true;showingCachedHistory=false;val byId=mergeChatMessages(msgs,fresh);msgs.clear();msgs.addAll(byId);persistHistory();val unread=fresh.filter{m->m.from==peer.id&&m.readAt.isBlank()}.map{m->m.id};if(unread.isNotEmpty())ws?.send(JSONObject().put("type","read").put("ids",JSONArray(unread)).toString())}.onFailure{historyError=true;if(msgs.isNotEmpty())showingCachedHistory=true}}};fun connect(){val generation=++socketGeneration;ws=Api.socket(token,{m->scope.launch{if(m.from==peer.id||m.to==peer.id){if(m.from==me.id&&m.clientMessageId.isNotBlank()){pending.removeAll{it.clientMessageId==m.clientMessageId};savePending()};val existing=msgs.indexOfFirst{it.id==m.id};if(existing>=0){msgs[existing]=mergeChatMessages(listOf(msgs[existing]),listOf(m)).first()}else msgs.add(m);persistHistory();if(m.from==peer.id)ws?.send(JSONObject().put("type","read").put("ids",JSONArray().put(m.id)).toString())}}},{r->scope.launch{val i=msgs.indexOfFirst{it.id==r.messageId};if(i>=0){val old=msgs[i];msgs[i]=old.copy(deliveredAt=old.deliveredAt.ifBlank{r.deliveredAt},readAt=old.readAt.ifBlank{r.readAt});persistHistory()}}},{e->scope.launch{socketError=when(e){"service_unavailable"->"Сервер временно недоступен. Переподключаемся…";"recipient_not_found"->"Получатель больше не найден.";"message_too_long"->"Сообщение слишком длинное.";"empty_message"->"Пустое сообщение не отправлено.";"client_message_id_conflict"->"Конфликт повторной отправки. Сообщение сохранено.";"invalid_client_message_id"->"Ошибка идентификатора сообщения.";"invalid_recipient_id"->"Некорректный получатель.";"invalid_reply_message_id"->"Некорректный ответ на сообщение.";"reply_message_not_found"->"Исходное сообщение для ответа больше недоступно.";"invalid_server_message"->"Получен некорректный ответ сервера.";"cannot_message_self"->"Нельзя отправить сообщение самому себе." ;else->"Не удалось отправить сообщение"};if(e=="service_unavailable"){connected=false;ws?.close(1012,"retry")}}},{scope.launch{connected=true;socketError="";for(p in pending.toList()){val payload=JSONObject().put("type","message").put("to",peer.id).put("text",p.text).put("clientMessageId",p.clientMessageId);if(p.replyToMessageId.isNotBlank())payload.put("replyToMessageId",p.replyToMessageId);val sent=ws?.send(payload.toString())==true;if(!sent){connected=false;ws?.close(1012,"retry");break}};syncHistory()}},{scope.launch{if(generation==socketGeneration){connected=false;kotlinx.coroutines.delay(2000);if(generation==socketGeneration)connect()}}})};connect()
  onDispose{socketGeneration++;ws?.close(1000,"bye")}
 }
 // A message can be replied to or explicitly copied to another contact on
 // current text-only production. Reactions require the separate gated backend.
 activeMessage?.let { selected ->
  LumoMessageOptions(
   message=selected,
   reactionsEnabled=reactionsEnabled && !reactionBusy,
   aiEnabled=aiEnabled,
   canEdit=messageEditEnabled && selected.from==me.id && selected.deletedAt.isBlank(),
   canDelete=messageDeleteEnabled && selected.from==me.id && selected.deletedAt.isBlank(),
   onDismiss={activeMessage=null},
   onReply={replyTarget=selected;activeMessage=null},
   onForward={forwardingMessage=selected;activeMessage=null},
   onEdit={editDraft=selected.text;editTarget=selected;activeMessage=null;actionError=""},
   onDelete={deleteTarget=selected;activeMessage=null;actionError=""},
   onAskAi={askAi(selected.text);activeMessage=null},
   onReact={emoji->
    if(!reactionBusy) {
     val add=reactions.none {
      it.messageId==selected.id && it.userId==me.id && it.emoji==emoji
     }
     activeMessage=null
     reactionBusy=true
     scope.launch {
      runCatching {
       kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
        LumoReactionApi.set(token,selected.id,emoji,add)
       }
      }.onSuccess{reactionRefresh++}
       .onFailure{actionError="Не удалось изменить реакцию. Проверь соединение."}
      reactionBusy=false
     }
    }
   }
  )
 }
 editTarget?.let { target ->
  AlertDialog(
   onDismissRequest={if(!mutationBusy)editTarget=null},
   title={Text("Изменить сообщение")},
   text={
    OutlinedTextField(
     value=editDraft,
     onValueChange={editDraft=it.take(4000)},
     label={Text(if(target.attachmentId.isBlank())"Текст" else "Подпись")},
     modifier=Modifier.fillMaxWidth(),
     maxLines=6
    )
   },
   confirmButton={
    TextButton(
     enabled=!mutationBusy&&editDraft.trim().isNotEmpty()&&editDraft.trim().length<=4000,
     onClick={
      mutationBusy=true;actionError=""
      scope.launch{
       runCatching{
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
         Api.editMessage(token,target.id,editDraft)
        }
       }.onSuccess{changed->
        val updated=msgs.map{m->
         when{
          m.id==changed.id->changed
          m.replyToMessageId==changed.id->m.copy(replyPreviewText=changed.text.take(240))
          else->m
         }
        }
        msgs.clear();msgs.addAll(updated)
        searchResults=searchResults.map{m->
         when{
          m.id==changed.id->changed
          m.replyToMessageId==changed.id->m.copy(replyPreviewText=changed.text.take(240))
          else->m
         }
        }
        persistHistory()
        editTarget=null
       }.onFailure{actionError="Не удалось изменить сообщение. Проверь соединение."}
       mutationBusy=false
      }
     }
    ){Text(if(mutationBusy)"Сохраняем…" else "Сохранить")}
   },
   dismissButton={TextButton(onClick={editTarget=null},enabled=!mutationBusy){Text("Отмена")}}
  )
 }
 deleteTarget?.let { target ->
  AlertDialog(
   onDismissRequest={if(!mutationBusy)deleteTarget=null},
   title={Text("Удалить сообщение?")},
   text={Text(
    if(target.attachmentId.isBlank())
     "Текст будет заменён пометкой «Сообщение удалено»."
    else "Вложение перестанет открываться у получателя, а сообщение станет пометкой об удалении."
   )},
   confirmButton={
    TextButton(
     enabled=!mutationBusy,
     onClick={
      mutationBusy=true;actionError=""
      scope.launch{
       runCatching{
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
         Api.deleteMessage(token,target.id)
        }
       }.onSuccess{changed->
        val updated=msgs.map{m->
         when{
          m.id==changed.id->changed
          m.replyToMessageId==changed.id->m.copy(replyPreviewText="Сообщение удалено")
          else->m
         }
        }
        msgs.clear();msgs.addAll(updated)
        searchResults=searchResults.filterNot{it.id==changed.id}.map{m->
         if(m.replyToMessageId==changed.id)m.copy(replyPreviewText="Сообщение удалено") else m
        }
        reactions=reactions.filterNot{it.messageId==changed.id}
        if(replyTarget?.id==changed.id)replyTarget=null
        persistHistory()
        deleteTarget=null
       }.onFailure{actionError="Не удалось удалить сообщение. Проверь соединение."}
       mutationBusy=false
      }
     }
    ){Text(if(mutationBusy)"Удаляем…" else "Удалить")}
   },
   dismissButton={TextButton(onClick={deleteTarget=null},enabled=!mutationBusy){Text("Отмена")}}
  )
 }
 forwardingMessage?.let { chosen ->
  LumoForwardDialog(
   token=token,me=me,source=chosen,
   onDismiss={forwardingMessage=null}
  )
 }
 // Reconcile through PostgreSQL-backed HTTP because Vercel peers may use different function instances.
 LaunchedEffect(token,peer.id){
  while(true){
   kotlinx.coroutines.delay(5000)
   // Retry unacknowledged messages even if the socket looks connected:
   // HTTP and WebSocket share the same PostgreSQL idempotency key.
   for(p in pending.toList()){
    if(pending.none{it.clientMessageId==p.clientMessageId})continue
    val saved=runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.sendMessage(token,peer.id,p)}}
    if(saved.isFailure)break
    val m=saved.getOrThrow()
    pending.removeAll{it.clientMessageId==p.clientMessageId};savePending()
    val merged=mergeChatMessages(msgs,listOf(m));msgs.clear();msgs.addAll(merged);persistHistory()
   }
   val refreshed=runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
    val fresh=Api.history(token,peer.id)
    val unread=fresh.filter{it.from==peer.id&&it.readAt.isBlank()}.map{it.id}
    if(unread.isNotEmpty())runCatching{Api.readMessages(token,unread)}
    fresh
   }}
   refreshed.onSuccess{fresh->
    historyError=false
    networkHistoryLoaded=true
    showingCachedHistory=false
    val acknowledged=fresh.filter{it.from==me.id&&it.clientMessageId.isNotBlank()}.map{it.clientMessageId}.toSet()
    if(pending.removeAll{it.clientMessageId in acknowledged})savePending()
    val merged=mergeChatMessages(msgs,fresh);msgs.clear();msgs.addAll(merged);persistHistory()
   }.onFailure{historyError=true;if(msgs.isNotEmpty())showingCachedHistory=true}
  }
 }
 LumoBackdrop(Modifier.fillMaxSize()){
  Scaffold(
   containerColor=Color.Transparent,
   topBar={
    Row(
     Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=9.dp,vertical=8.dp)
      .lumoGlass(22).padding(horizontal=5.dp,vertical=4.dp),
     verticalAlignment=Alignment.CenterVertically
    ){
     TextButton(back){Text("‹",style=MaterialTheme.typography.headlineMedium,color=Color.White)}
     LumoUserAvatar(token,peer,size=43.dp)
     Spacer(Modifier.width(10.dp))
     Column(Modifier.weight(1f)){
      Text(peer.displayName,fontWeight=FontWeight.Bold,color=Color.White,
       style=MaterialTheme.typography.titleMedium,maxLines=1)
      Text("@"+peer.username,style=MaterialTheme.typography.labelMedium,
       color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
     }
     TextButton(
      onClick=openAudioCall,
      contentPadding=PaddingValues(horizontal=7.dp)
     ){
      Text("📞",color=LumoCyan,style=MaterialTheme.typography.titleLarge)
     }
     TextButton(
      onClick=openVideoCall,
      contentPadding=PaddingValues(horizontal=7.dp)
     ){
      Text("🎥",color=LumoPink,style=MaterialTheme.typography.titleLarge)
     }
     if(messageSearchEnabled){
      TextButton(onClick={
       showSearch=!showSearch
       searchResults=emptyList()
       searchPerformed=false
       searchError=""
       if(!showSearch)searchText=""
      }){
       Text(if(showSearch)"×" else "⌕",color=LumoCyan,
        style=MaterialTheme.typography.titleLarge)
      }
     }
    }
   }
  ){pad->
   Column(Modifier.fillMaxSize().padding(pad)){
    if(socketError.isNotEmpty()){
     Box(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).lumoGlass(16).padding(10.dp)){
      Text(socketError,color=Color(0xFFFFD5E4),style=MaterialTheme.typography.bodySmall)
     }
    }
    if(!connected){
     Box(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).lumoGlass(16).padding(10.dp)){
      Text("Нет прямого соединения. Сообщения синхронизируются через сервер…",
       color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
     }
    }
    if(historyError||showingCachedHistory){
     Box(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).lumoGlass(16).padding(10.dp)){
      Text(if(showingCachedHistory)"Офлайн: показываем зашифрованную копию последних сообщений с этого телефона." else "История пока недоступна. Повторим загрузку после подключения.",
       color=Color(0xFFFFD5E4),style=MaterialTheme.typography.bodySmall)
     }
    }
    if(actionError.isNotBlank()){
     Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).lumoGlass(16).padding(9.dp)){
      Text(actionError,color=Color(0xFFFFDBE8),
       style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
      TextButton(onClick={actionError=""}){Text("×",color=Color.White)}
     }
    }
    if(showSearch){
     Column(
      Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)
       .lumoGlass(20).padding(10.dp)
     ){
      Row(verticalAlignment=Alignment.CenterVertically){
       LumoSearchField(
        value=searchText,
        onValueChange={
         searchText=it.take(100)
         searchPerformed=false
         searchResults=emptyList()
         searchError=""
        },
        placeholder="Найти в переписке",
        modifier=Modifier.weight(1f)
       )
       Spacer(Modifier.width(7.dp))
       TextButton(
        enabled=!searchBusy&&searchText.trim().length in 2..100,
        onClick={
         searchBusy=true;searchError=""
         scope.launch{
          runCatching{
           kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
            Api.searchMessages(token,peer.id,searchText)
           }
          }.onSuccess{
           searchResults=it
           searchPerformed=true
          }.onFailure{searchError="Поиск временно недоступен"}
          searchBusy=false
         }
        }
       ){Text("Найти",color=LumoCyan)}
      }
      if(searchBusy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=LumoCyan)
      if(searchError.isNotBlank())Text(
       searchError,color=MaterialTheme.colorScheme.error,
       style=MaterialTheme.typography.bodySmall
      )
      if(searchPerformed)Text(
       if(searchResults.isEmpty())"Совпадений не найдено"
       else "Найдено: "+searchResults.size,
       color=MaterialTheme.colorScheme.onSurfaceVariant,
       style=MaterialTheme.typography.labelMedium
      )
     }
    }
    val visibleMessages=if(showSearch&&searchPerformed)searchResults else msgs.toList()
    LazyColumn(
     state=listState,
     modifier=Modifier.weight(1f).fillMaxWidth(),
     contentPadding=PaddingValues(horizontal=13.dp,vertical=14.dp),
     verticalArrangement=Arrangement.spacedBy(11.dp)
    ){
     items(visibleMessages,key={it.id}){m->
      val own=m.from==me.id
      Row(
       Modifier.fillMaxWidth(),
       horizontalArrangement=if(own)Arrangement.End else Arrangement.Start,
       verticalAlignment=Alignment.Bottom
      ){
       if(!own){
        LumoUserAvatar(token,peer,size=30.dp)
        Spacer(Modifier.width(7.dp))
       }
       Column(horizontalAlignment=if(own)Alignment.End else Alignment.Start){
        Box(Modifier.widthIn(max=290.dp).lumoBubble(own).padding(horizontal=14.dp,vertical=10.dp)){
         Column {
          if(m.replyToMessageId.isNotBlank()){
          Box(
           Modifier.fillMaxWidth().lumoGlass(14)
            .clickable{
             val index=visibleMessages.indexOfFirst{it.id==m.replyToMessageId}
             if(index>=0)scope.launch{listState.animateScrollToItem(index)}
            }
            .padding(horizontal=9.dp,vertical=7.dp)
          ){
           Column{
            Text(
             if(m.replyPreviewFrom==me.id)"Вы" else peer.displayName,
             color=LumoCyan,style=MaterialTheme.typography.labelMedium,
             fontWeight=FontWeight.SemiBold
            )
            Text(
             m.replyPreviewText.ifBlank{"Сообщение недоступно"},
             color=Color.White.copy(alpha=.82f),
             style=MaterialTheme.typography.bodySmall,
             maxLines=2
            )
           }
          }
          Spacer(Modifier.height(7.dp))
         }
         if(m.text.isNotBlank())Text(
          m.text,
          color=if(m.deletedAt.isNotBlank())
           MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
          fontStyle=if(m.deletedAt.isNotBlank())
           androidx.compose.ui.text.font.FontStyle.Italic
           else androidx.compose.ui.text.font.FontStyle.Normal
         )
         if(m.attachmentId.isNotBlank() && m.deletedAt.isBlank())
          MediaAttachmentButton(token,m.attachmentId)
         Spacer(Modifier.height(5.dp))
         Row(
          Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically
         ){
          if(m.editedAt.isNotBlank() && m.deletedAt.isBlank()){
           Text(
            "изменено",
            color=Color.White.copy(alpha=.62f),
            style=MaterialTheme.typography.labelSmall
           )
           Spacer(Modifier.width(5.dp))
          }
          if(m.createdAt.isNotBlank())Text(
           formatMessageTime(m.createdAt),
           color=Color.White.copy(alpha=.73f),
           style=MaterialTheme.typography.labelSmall
          )
          if(own){
           Spacer(Modifier.width(5.dp))
           Text(
            if(m.readAt.isNotBlank())"✓✓" else if(m.deliveredAt.isNotBlank())"✓✓" else "✓",
            color=if(m.readAt.isNotBlank())LumoCyan else Color.White.copy(alpha=.75f),
            style=MaterialTheme.typography.labelSmall
           )
          }
          if(m.deletedAt.isBlank()){
           Spacer(Modifier.width(5.dp))
           TextButton(onClick={activeMessage=m},contentPadding=PaddingValues(horizontal=7.dp)){
            Text("⋯",color=LumoCyan,style=MaterialTheme.typography.titleMedium)
           }
          }
         }
        }
       }
       if(reactionsEnabled && m.deletedAt.isBlank()){
        LumoReactionBadges(
         entries=reactions.filter{it.messageId==m.id},
         meId=me.id,
         onTap={emoji,add->
          if(!reactionBusy){
           reactionBusy=true
           scope.launch{
            runCatching{
             kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
              LumoReactionApi.set(token,m.id,emoji,add)
             }
            }.onSuccess{reactionRefresh++}
             .onFailure{actionError="Не удалось изменить реакцию. Проверь соединение."}
            reactionBusy=false
           }
          }
         }
        )
       }
       }
      }
     }
     items(
      if(showSearch)emptyList() else pending.filter{p->
       msgs.none{it.from==me.id&&it.clientMessageId==p.clientMessageId}
      },
      key={"pending-"+it.clientMessageId}
     ){p->
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
       Box(Modifier.widthIn(max=290.dp).lumoBubble(true).padding(horizontal=14.dp,vertical=10.dp)){
        Column{
         if(p.replyToMessageId.isNotBlank()){
          Box(
           Modifier.fillMaxWidth().lumoGlass(14)
            .clickable{
             val index=msgs.indexOfFirst{it.id==p.replyToMessageId}
             if(index>=0)scope.launch{listState.animateScrollToItem(index)}
            }
            .padding(8.dp)
          ){
           Text(
            p.replyPreviewText.ifBlank{"Ответ на сообщение"},
            color=Color.White.copy(alpha=.82f),
            style=MaterialTheme.typography.bodySmall,
            maxLines=2
           )
          }
          Spacer(Modifier.height(6.dp))
         }
         Text(p.text,color=Color.White)
         Text("Отправляется…",color=Color.White.copy(alpha=.7f),
          style=MaterialTheme.typography.labelSmall)
        }
       }
      }
     }
    }
    MediaComposer(token,me,peer,allowSend=true){attached->
     val merged=mergeChatMessages(msgs,listOf(attached))
     msgs.clear();msgs.addAll(merged);persistHistory()
    }
    replyTarget?.let { original ->
     Row(
      Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=5.dp)
       .lumoGlass(18).padding(horizontal=12.dp,vertical=6.dp),
      verticalAlignment=Alignment.CenterVertically
     ){
      Column(Modifier.weight(1f)){
       Text("Ответ на сообщение",color=LumoCyan,style=MaterialTheme.typography.labelLarge)
       Text(
        (if(original.text.isBlank())"Вложение" else original.text)
         .replace("\n"," ").take(115),
        color=Color.White.copy(alpha=.9f),
        style=MaterialTheme.typography.bodySmall,maxLines=1
       )
      }
      TextButton(onClick={replyTarget=null}){Text("×",color=Color.White)}
     }
    }
    Row(
     Modifier.fillMaxWidth().imePadding().padding(horizontal=11.dp,vertical=8.dp)
      .lumoGlass(30).padding(7.dp),
     verticalAlignment=Alignment.Bottom
    ){
     OutlinedTextField(
      value=input,onValueChange={input=it},placeholder={Text("Сообщение")},
      modifier=Modifier.weight(1f),maxLines=4,
      shape=RoundedCornerShape(22.dp)
     )
     Spacer(Modifier.width(7.dp))
     LumoNeonButton(
      text="➤",
      enabled=input.isNotBlank() && input.trim().length<=4000,
      modifier=Modifier.width(56.dp),
      onClick={
       val original=replyTarget
       val useLinked=linkedRepliesEnabled && original!=null
       val quote=if(!useLinked)original?.let{
        "↪ "+(if(it.text.isBlank())"Вложение" else it.text)
         .replace("\n"," ").take(120)+"\n"
       }.orEmpty() else ""
       val text=quote+input.trim()
       if(text.isNotBlank()&&text.length<=4000){
        val p=PendingMessage(
         clientMessageId=java.util.UUID.randomUUID().toString(),
         text=text,
         replyToMessageId=if(useLinked)original?.id.orEmpty() else "",
         replyPreviewText=if(useLinked)(original?.text?.ifBlank{"Вложение"}?:"").take(240) else "",
         replyPreviewFrom=if(useLinked)original?.from.orEmpty() else ""
        )
        pending.add(p);savePending()
        if(connected){
         val payload=JSONObject().put("type","message").put("to",peer.id)
          .put("text",p.text).put("clientMessageId",p.clientMessageId)
         if(p.replyToMessageId.isNotBlank())payload.put("replyToMessageId",p.replyToMessageId)
         val sent=ws?.send(payload.toString())==true
         if(!sent){connected=false;ws?.close(1012,"retry")}
        }
        input=""
        replyTarget=null
       }
      }
     )
    }
   }
  }
 }
}


fun formatMessageTime(iso:String):String=runCatching{java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(iso))}.getOrDefault("")

object Api{
 internal val HTTP=BuildConfig.LUMO_HTTP_BASE;internal val WS=BuildConfig.LUMO_WS_BASE;val httpClient=OkHttpClient.Builder().connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS).readTimeout(30,java.util.concurrent.TimeUnit.SECONDS).writeTimeout(30,java.util.concurrent.TimeUnit.SECONDS).pingInterval(25,java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(true).build();private val c=httpClient
 @Volatile private var conversationsFallbackUntilMs=0L
 private const val CONVERSATIONS_FALLBACK_MS=5*60*1000L
 fun register(login:String,name:String,password:String):Pair<String,User>{val j=JSONObject().put("username",login).put("displayName",name).put("password",password);val r=Request.Builder().url(HTTP+"/api/register").post(j.toString().toRequestBody("application/json".toMediaType())).build();c.newCall(r).execute().use{x->val body=x.body?.string().orEmpty();if(!x.isSuccessful){val code=runCatching{JSONObject(body).optString("error")}.getOrDefault("");error(when(code){"database_unavailable"->"Сервис временно недоступен: база данных не подключена";"username_taken"->"Этот логин уже занят";"invalid_profile"->"Проверь имя и логин";"invalid_password"->"Пароль должен содержать от 10 до 128 символов";else->"Ошибка регистрации ("+x.code+")"})};val o=JSONObject(body);return o.getString("token") to user(o.getJSONObject("user"))}}
 fun login(login:String,password:String):Pair<String,User>{
  val body=JSONObject().put("username",login).put("password",password)
  val request=Request.Builder().url(HTTP+"/api/login").post(body.toString().toRequestBody("application/json".toMediaType())).build()
  c.newCall(request).execute().use{response->
   val data=response.body?.string().orEmpty()
   if(!response.isSuccessful){
    val code=runCatching{JSONObject(data).optString("error")}.getOrDefault("")
    error(when(code){"invalid_credentials"->"Неверный логин или пароль";"database_unavailable"->"База данных временно недоступна";else->"Ошибка входа ("+response.code+")"})
   }
   val obj=JSONObject(data)
   return obj.getString("token") to user(obj.getJSONObject("user"))
  }
 }
 fun logout(t:String){
  val request=Request.Builder().url(HTTP+"/api/logout")
   .header("Authorization","Bearer "+t).post("".toRequestBody(null)).build()
  c.newCall(request).execute().use{response->
   if(response.code==401)throw SessionExpiredException()
   if(!response.isSuccessful)error("Выход: "+response.code)
  }
 }
 fun updateMe(t:String,name:String,bio:String):Pair<User,Boolean>{val j=JSONObject().put("displayName",name).put("bio",bio);val r=Request.Builder().url(HTTP+"/api/me").header("Authorization","Bearer "+t).patch(j.toString().toRequestBody("application/json".toMediaType())).build();c.newCall(r).execute().use{x->if(x.code==401)throw SessionExpiredException();if(!x.isSuccessful)error("Профиль: "+x.code);val o=JSONObject(x.body!!.string());return user(o) to o.has("bio")}}
 fun me(t:String):User{val r=Request.Builder().url(HTTP+"/api/me").header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(x.code==401)throw SessionExpiredException();if(!x.isSuccessful)error("Сессия: "+x.code);return user(JSONObject(x.body!!.string()))}}
 fun users(t:String,q:String):List<User>{val url=(HTTP+"/api/users").toHttpUrl().newBuilder().addQueryParameter("q",q).build();val r=Request.Builder().url(url).header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Поиск: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{user(a.getJSONObject(it))}}}
 fun conversations(t:String):List<Conversation>{
  val now=android.os.SystemClock.elapsedRealtime()
  if(now<conversationsFallbackUntilMs)return conversationsCompatibilityFallback(t)
  val request=Request.Builder().url(HTTP+"/api/conversations")
   .header("Authorization","Bearer "+t).build()
  c.newCall(request).execute().use{response->
   if(response.code==401)throw SessionExpiredException()
   if(response.isSuccessful){
    conversationsFallbackUntilMs=0L
    val a=JSONArray(response.body?.string().orEmpty())
    return (0 until a.length()).map{val o=a.getJSONObject(it)
     Conversation(user(o.getJSONObject("peer")),o.getString("lastMessage"),o.optString("lastAt"))
    }
   }
   if(response.code==503){
    conversationsFallbackUntilMs=android.os.SystemClock.elapsedRealtime()+CONVERSATIONS_FALLBACK_MS
    return conversationsCompatibilityFallback(t)
   }
   val reason=when(response.code){
    429->"Слишком много запросов. Подожди немного."
    else->"Ошибка сервера HTTP "+response.code
   }
   error(reason)
  }
 }
 private fun conversationsCompatibilityFallback(t:String):List<Conversation>{
  val peers=users(t,"").take(50)
  val restored=ArrayList<Conversation>()
  for(peer in peers){
   val last=runCatching{history(t,peer.id).maxByOrNull{it.createdAt}}.getOrNull()?:continue
   val preview=if(last.deletedAt.isNotBlank())"Сообщение удалено" else last.text
   restored.add(Conversation(peer,preview,last.createdAt))
  }
  return restored.sortedByDescending{it.lastAt}
 }
 fun history(t:String,p:String):List<Msg>{val r=Request.Builder().url(HTTP+"/api/messages/"+p).header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("История: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{msg(a.getJSONObject(it))}}}
 fun linkedRepliesSupported(t:String):Boolean{
  val request=Request.Builder().url(HTTP+"/api/messages/capabilities")
   .header("Authorization","Bearer "+t).get().build()
  c.newCall(request).execute().use{response->
   if(response.code==401)throw SessionExpiredException()
   if(!response.isSuccessful)return false
   return runCatching{
    JSONObject(response.body?.string().orEmpty()).optBoolean("linkedReplies",false)
   }.getOrDefault(false)
  }
 }
 fun messageMutationCapabilities(t:String):Triple<Boolean,Boolean,Boolean>{
  val request=Request.Builder().url(HTTP+"/api/messages/capabilities")
   .header("Authorization","Bearer "+t).get().build()
  c.newCall(request).execute().use{response->
   if(response.code==401)throw SessionExpiredException()
   if(!response.isSuccessful)return Triple(false,false,false)
   return runCatching{
    val o=JSONObject(response.body?.string().orEmpty())
    Triple(
     o.optBoolean("messageEdit",false),
     o.optBoolean("messageDelete",false),
     o.optBoolean("messageSearch",false)
    )
   }.getOrDefault(Triple(false,false,false))
  }
 }
 fun searchMessages(t:String,peerId:String,q:String):List<Msg>{
  val url=(HTTP+"/api/messages/search/"+peerId).toHttpUrl().newBuilder()
   .addQueryParameter("q",q.trim()).build()
  val request=Request.Builder().url(url).header("Authorization","Bearer "+t).get().build()
  c.newCall(request).execute().use{x->
   if(x.code==401)throw SessionExpiredException()
   if(!x.isSuccessful)error("Поиск: "+x.code)
   val a=JSONArray(x.body?.string().orEmpty())
   return (0 until a.length()).map{msg(a.getJSONObject(it))}
  }
 }
 fun editMessage(t:String,messageId:String,text:String):Msg{
  val body=JSONObject().put("text",text.trim())
  val request=Request.Builder().url(HTTP+"/api/messages/"+messageId)
   .header("Authorization","Bearer "+t)
   .patch(body.toString().toRequestBody("application/json".toMediaType())).build()
  c.newCall(request).execute().use{x->
   if(x.code==401)throw SessionExpiredException()
   if(!x.isSuccessful)error("Изменение: "+x.code)
   return msg(JSONObject(x.body?.string().orEmpty()))
  }
 }
 fun deleteMessage(t:String,messageId:String):Msg{
  val request=Request.Builder().url(HTTP+"/api/messages/"+messageId)
   .header("Authorization","Bearer "+t).delete().build()
  c.newCall(request).execute().use{x->
   if(x.code==401)throw SessionExpiredException()
   if(!x.isSuccessful)error("Удаление: "+x.code)
   return msg(JSONObject(x.body?.string().orEmpty()))
  }
 }
 fun sendMessage(t:String,to:String,p:PendingMessage):Msg{
  fun perform(body:JSONObject):Pair<Int,String>{
   val request=Request.Builder().url(HTTP+"/api/messages")
    .header("Authorization","Bearer "+t)
    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
   c.newCall(request).execute().use{response->
    if(response.code==401)throw SessionExpiredException()
    return response.code to response.body?.string().orEmpty()
   }
  }
  val body=JSONObject().put("to",to).put("text",p.text)
   .put("clientMessageId",p.clientMessageId)
  if(p.replyToMessageId.isNotBlank())body.put("replyToMessageId",p.replyToMessageId)
  var result=perform(body)
  if(result.first==404 && p.replyToMessageId.isNotBlank()){
   val code=runCatching{JSONObject(result.second).optString("error")}.getOrDefault("")
   if(code=="reply_message_not_found"){
    val preview=p.replyPreviewText.ifBlank{"Сообщение"}
     .replace("\n"," ").take(120)
    val prefix="↪ "+preview+"\n"
    val fallback=prefix+p.text.take((4000-prefix.length).coerceAtLeast(0))
    result=perform(
     JSONObject().put("to",to).put("text",fallback)
      .put("clientMessageId",p.clientMessageId)
    )
   }
  }
  if(result.first !in 200..299)error("Отправка: "+result.first)
  return msg(JSONObject(result.second))
 }
 fun readMessages(t:String,ids:List<String>){
  if(ids.isEmpty())return
  val body=JSONObject().put("ids",JSONArray(ids.take(200)))
  val request=Request.Builder().url(HTTP+"/api/messages/read").header("Authorization","Bearer "+t).post(body.toString().toRequestBody("application/json".toMediaType())).build()
  c.newCall(request).execute().use{response->if(!response.isSuccessful)error("Прочтение: "+response.code)}
 }
 fun latestRelease():UpdateInfo{
  val r=Request.Builder()
   .url("https://api.github.com/repos/89681505031/Lumo/releases/tags/lumo-latest")
   .header("Accept","application/vnd.github+json").build()
  c.newCall(r).execute().use{x->
   if(!x.isSuccessful)error("Обновление: "+x.code)
   val o=JSONObject(x.body!!.string())
   val code=Regex("versionCode=(\\d+)").find(o.optString("body"))
    ?.groupValues?.get(1)?.toIntOrNull()?:0
   val a=o.getJSONArray("assets")
   val assets=(0 until a.length()).map{a.getJSONObject(it)}
   val asset=
    assets.firstOrNull{it.optString("name")=="app-release.apk"} ?:
    assets.firstOrNull{it.optString("label")=="Lumo.apk" && it.optString("name")!="app-debug.apk"} ?:
    assets.firstOrNull{it.optString("name")=="app-debug.apk"}
   if(asset!=null)return UpdateInfo(code,asset.getString("browser_download_url"))
   error("APK не найден")
  }
 }
 fun socket(t:String,onMessage:(Msg)->Unit,onReceipt:(Receipt)->Unit,onError:(String)->Unit,onReady:()->Unit,onDisconnected:()->Unit):WebSocket{return c.newWebSocket(Request.Builder().url(WS).header("Authorization","Bearer "+t).build(),object:WebSocketListener(){override fun onOpen(w:WebSocket,response:Response){};override fun onMessage(w:WebSocket,s:String){runCatching{val o=JSONObject(s);when(o.optString("type")){"ready"->onReady();"message"->onMessage(msg(o.getJSONObject("message")));"receipt"->onReceipt(Receipt(o.getString("messageId"),nullableJsonText(o,"deliveredAt"),nullableJsonText(o,"readAt")));"error"->onError(o.optString("error"));else->Unit}}.onFailure{onError("invalid_server_message")}};override fun onClosed(w:WebSocket,code:Int,reason:String)=onDisconnected();override fun onFailure(w:WebSocket,t:Throwable,response:Response?)=onDisconnected()})}
 private fun user(o:JSONObject)=User(o.getString("id"),o.getString("username"),o.getString("displayName"),nullableJsonText(o,"bio"),o.has("bio"),o.optBoolean("hasAvatar",false),nullableJsonText(o,"avatarVersion"))
 private fun msg(o:JSONObject)=Msg(o.getString("id"),o.getString("from"),o.getString("to"),o.getString("text"),nullableJsonText(o,"createdAt"),nullableJsonText(o,"deliveredAt"),nullableJsonText(o,"readAt"),nullableJsonText(o,"clientMessageId"),nullableJsonText(o,"attachmentId"),nullableJsonText(o,"replyToMessageId"),nullableJsonText(o,"replyPreviewText"),nullableJsonText(o,"replyPreviewFrom"),nullableJsonText(o,"editedAt"),nullableJsonText(o,"deletedAt"))
}
