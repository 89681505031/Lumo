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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

data class User(val id:String,val username:String,val displayName:String)
data class Msg(val id:String,val from:String,val to:String,val text:String,val createdAt:String="",val deliveredAt:String="",val readAt:String="",val clientMessageId:String="",val attachmentId:String="")
data class Conversation(val peer:User,val lastMessage:String,val lastAt:String="",val unreadCount:Int=0,val pinned:Boolean=false)
data class UpdateInfo(val versionCode:Int,val downloadUrl:String)
data class Receipt(val messageId:String,val deliveredAt:String,val readAt:String)
data class PendingMessage(val clientMessageId:String,val text:String)
private fun nullableJsonText(o:JSONObject,key:String):String=if(o.isNull(key))"" else o.optString(key)
class SessionExpiredException:Exception("Сессия недействительна")

class MainActivity:ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{App()}}
}

@Composable fun App(){
 val context=LocalContext.current
 val prefs=remember{context.getSharedPreferences("lumo_session",Context.MODE_PRIVATE)}
 val uiPrefs=remember{context.getSharedPreferences("lumo_ui",Context.MODE_PRIVATE)}
 var darkMode by remember{mutableStateOf(uiPrefs.getBoolean("dark_mode",false))}
 var token by remember{mutableStateOf(prefs.getString("token",null))}
 var me by remember{mutableStateOf<User?>(null)}
 var peer by remember{mutableStateOf<User?>(null)}
 var logoutNonce by remember{mutableIntStateOf(0)}
 var restoring by remember{mutableStateOf(token!=null)}
 var restoreError by remember{mutableStateOf(false)}
 var restoreRetry by remember{mutableIntStateOf(0)}
 LaunchedEffect(token,restoreRetry){
  val t=token
  if(t==null){restoring=false;me=null;restoreError=false}
  else if(me==null){
   restoring=true;restoreError=false
   runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.me(t)}}
    .onSuccess{me=it}
    .onFailure{error->if(error is SessionExpiredException){prefs.edit().remove("token").apply();token=null}else restoreError=true}
   restoring=false
  }
 }
 MaterialTheme(colorScheme=if(darkMode)darkColorScheme() else lightColorScheme()){
 when {
  restoring -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
  restoreError -> Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
   Text("Не удалось подключиться к серверу. Аккаунт сохранён.")
   Spacer(Modifier.height(16.dp));Button({restoreRetry++}){Text("Повторить")}
  }
  token==null || me==null -> Register{t,u->prefs.edit().putString("token",t).apply();token=t;me=u}
  peer==null -> Home(token!!,me!!,{peer=it},{me=it},darkMode,{value->darkMode=value;uiPrefs.edit().putBoolean("dark_mode",value).apply()}){prefs.edit().clear().apply();token=null;me=null;peer=null;logoutNonce++}
  else -> Chat(token!!,me!!,peer!!){peer=null}
 }
 }
}

@Composable fun Register(done:(String,User)->Unit){
 val scope=rememberCoroutineScope()
 var loginMode by remember{mutableStateOf(false)}
 var name by remember{mutableStateOf("")}
 var login by remember{mutableStateOf("")}
 var password by remember{mutableStateOf("")}
 var err by remember{mutableStateOf("")}
 var busy by remember{mutableStateOf(false)}
 Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center){
  Text("Lumo",style=MaterialTheme.typography.displayLarge,fontWeight=FontWeight.Bold)
  Spacer(Modifier.height(8.dp))
  Text(if(loginMode)"С возвращением" else "Общайся просто",style=MaterialTheme.typography.titleMedium)
  Spacer(Modifier.height(28.dp))
  if(!loginMode){
   OutlinedTextField(name,{name=it},label={Text("Имя")},singleLine=true,modifier=Modifier.fillMaxWidth())
   Spacer(Modifier.height(10.dp))
  }
  OutlinedTextField(login,{login=it},label={Text("Логин")},singleLine=true,modifier=Modifier.fillMaxWidth())
  Spacer(Modifier.height(10.dp))
  OutlinedTextField(password,{password=it},label={Text("Пароль")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
  if(!loginMode)Text("Пароль: минимум 10 символов",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=6.dp))
  if(err.isNotEmpty())Text(err,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp))
  Spacer(Modifier.height(16.dp))
  Button({
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
  },enabled=!busy&&login.isNotBlank()&&password.isNotBlank()&&(loginMode||(name.isNotBlank()&&password.length>=10)),modifier=Modifier.fillMaxWidth().height(52.dp)){
   Text(if(busy)"Подключаем..." else if(loginMode)"Войти" else "Создать аккаунт")
  }
  TextButton(onClick={loginMode=!loginMode;password="";err=""},enabled=!busy,modifier=Modifier.fillMaxWidth()){
   Text(if(loginMode)"Нет аккаунта? Зарегистрироваться" else "Уже есть аккаунт? Войти")
  }
 }
}

@Composable fun Home(token:String,me:User,open:(User)->Unit,profileChanged:(User)->Unit,darkMode:Boolean,onDarkModeChange:(Boolean)->Unit,logout:()->Unit){
 var tab by remember{mutableIntStateOf(0)}
 Scaffold(
  topBar={Surface(shadowElevation=2.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().padding(20.dp,14.dp),verticalAlignment=Alignment.CenterVertically){
   Text("Lumo",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));Text(me.displayName,style=MaterialTheme.typography.labelLarge)
  }}},
  bottomBar={NavigationBar{
   NavigationBarItem(selected=tab==0,onClick={tab=0},icon={Text("●")},label={Text("Чаты")})
   NavigationBarItem(selected=tab==1,onClick={tab=1},icon={Text("⌕")},label={Text("Люди")})
   NavigationBarItem(selected=tab==2,onClick={tab=2},icon={Text("☺")},label={Text("Профиль")})
  }}
 ){pad->
  Box(Modifier.padding(pad).fillMaxSize()){
   when(tab){
    0->Chats(token,{tab=1},open)
    1->People(token,open)
    else->Profile(token,me,profileChanged,darkMode,onDarkModeChange,logout)
   }
  }
 }
}

@Composable fun Chats(token:String,find:()->Unit,open:(User)->Unit){
 var chats by remember{mutableStateOf<List<Conversation>>(emptyList())}
 var loading by remember{mutableStateOf(true)}
 var loadError by remember{mutableStateOf(false)}
 var refreshError by remember{mutableStateOf(false)}
 var retry by remember{mutableIntStateOf(0)}
 var actionError by remember{mutableStateOf("")}
 val scope=rememberCoroutineScope()
 LaunchedEffect(token,retry){
  loading=true;loadError=false;refreshError=false;chats=emptyList()
  while(true){
   val result=runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.conversations(token)}}
   result.onSuccess{chats=it;loadError=false;refreshError=false}
    .onFailure{if(chats.isEmpty())loadError=true else refreshError=true}
   loading=false
   kotlinx.coroutines.delay(12_000)
  }
 }
 if(loadError){Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Не удалось загрузить чаты");Spacer(Modifier.height(12.dp));Button({retry++}){Text("Повторить")}};return}
 if(loading){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()};return}
 if(chats.isEmpty()){
  Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
   Text("Сообщений пока нет",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
   Spacer(Modifier.height(8.dp));Text("Найди человека и начни первый диалог",style=MaterialTheme.typography.bodyLarge)
   Spacer(Modifier.height(20.dp));Button(find){Text("Найти людей")}
  }
 } else {
  Column(Modifier.fillMaxSize()){
    if(actionError.isNotEmpty())Text(actionError,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(8.dp))
   if(refreshError){
    Text("Нет связи. Показываем последнюю загруженную историю чатов.",
     color=MaterialTheme.colorScheme.error,modifier=Modifier.fillMaxWidth().padding(12.dp))
   }
   LazyColumn(Modifier.fillMaxSize()){
   items(chats,key={it.peer.id}){chat->
    Row(Modifier.fillMaxWidth().clickable{open(chat.peer)}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
     Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Text(chat.peer.displayName.take(1).uppercase(),style=MaterialTheme.typography.titleLarge)}
     Spacer(Modifier.width(12.dp))
     Column(Modifier.weight(1f)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Text(chat.peer.displayName,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
       if(chat.unreadCount>0)Badge{Text(chat.unreadCount.coerceAtMost(99).toString())}
      }
      Row(verticalAlignment=Alignment.CenterVertically){
       Text(chat.lastMessage,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
       if(chat.lastAt.isNotBlank())Text(formatMessageTime(chat.lastAt),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
      }
     }
     TextButton(onClick={
      actionError=""
      scope.launch{
       runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.pin(token,chat.peer.id,!chat.pinned)}}
        .onSuccess{newValue->chats=chats.map{if(it.peer.id==chat.peer.id)it.copy(pinned=newValue)else it}
         .sortedWith(compareByDescending<Conversation>{it.pinned}.thenByDescending{it.lastAt})}
        .onFailure{actionError="Не удалось изменить закрепление"}
      }
     }){Text(if(chat.pinned)"📌" else "☆")}
    };HorizontalDivider()
   }
   }
  }
 }
}

@Composable fun People(token:String,open:(User)->Unit){
 var users by remember{mutableStateOf<List<User>>(emptyList())};var q by remember{mutableStateOf("")};var loading by remember{mutableStateOf(false)};var loadError by remember{mutableStateOf(false)};var retry by remember{mutableIntStateOf(0)}
 LaunchedEffect(token,q,retry){loading=true;loadError=false;users=emptyList();kotlinx.coroutines.delay(300);runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.users(token,q)}}.onSuccess{users=it}.onFailure{loadError=true};loading=false}
 Column(Modifier.fillMaxSize()){
  OutlinedTextField(q,{q=it},label={Text("Поиск по имени или логину")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(16.dp))
  if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
  if(loadError){Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("Не удалось загрузить пользователей");Spacer(Modifier.height(8.dp));Button({retry++}){Text("Повторить")}}}
  if(!loading&&!loadError&&users.isEmpty()){Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){Text(if(q.isBlank())"Пользователей пока нет" else "Ничего не найдено",color=MaterialTheme.colorScheme.onSurfaceVariant)}}
  LazyColumn(Modifier.fillMaxSize()){
   items(users,key={it.id}){u->
    Row(Modifier.fillMaxWidth().clickable{open(u)}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
     Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Text(u.displayName.take(1).uppercase(),style=MaterialTheme.typography.titleLarge)}
     Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(u.displayName,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium);Text("@"+u.username,color=MaterialTheme.colorScheme.onSurfaceVariant)}
     Text("›",style=MaterialTheme.typography.headlineSmall)
    };HorizontalDivider()
   }
  }
 }
}

@Composable fun Profile(token:String,me:User,profileChanged:(User)->Unit,darkMode:Boolean,onDarkModeChange:(Boolean)->Unit,logout:()->Unit){
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 var blockedUsers by remember(token){mutableStateOf<List<User>>(emptyList())}
 var blocksError by remember{mutableStateOf("")}
 var unblockingId by remember{mutableStateOf("")}
 LaunchedEffect(token){runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.blocks(token)}}.onSuccess{blockedUsers=it}.onFailure{blocksError="Не удалось загрузить список блокировок"}}
 var editing by remember{mutableStateOf(false)};var name by remember(me.displayName){mutableStateOf(me.displayName)};var saving by remember{mutableStateOf(false)};var profileError by remember{mutableStateOf("")}
 var loggingOut by remember{mutableStateOf(false)}
 var logoutError by remember{mutableStateOf("")}
 var update by remember{mutableStateOf<UpdateInfo?>(null)};var checking by remember{mutableStateOf(true)};var updateText by remember{mutableStateOf("Проверяем обновления…")};var progress by remember{mutableIntStateOf(-1)}
 LaunchedEffect(Unit){runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.latestRelease()}}.onSuccess{info->update=info.takeIf{it.versionCode>BuildConfig.VERSION_CODE};updateText=if(update!=null)"Доступна новая версия Lumo" else "Установлена последняя версия"}.onFailure{updateText="Не удалось проверить обновления"};checking=false}
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
  Spacer(Modifier.height(24.dp));Box(Modifier.size(92.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Text(me.displayName.take(1).uppercase(),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold)}
  Spacer(Modifier.height(16.dp));Text(me.displayName,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("@"+me.username,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(20.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){
   Text("Профиль",fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(8.dp))
   if(editing){
    OutlinedTextField(name,{name=it;profileError=""},label={Text("Имя")},singleLine=true,modifier=Modifier.fillMaxWidth())
    if(profileError.isNotEmpty())Text(profileError,color=MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(10.dp));Row{Button({saving=true;scope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.updateMe(token,name)}}.onSuccess{profileChanged(it);editing=false}.onFailure{profileError="Не удалось сохранить"};saving=false}},enabled=!saving&&name.isNotBlank()){Text(if(saving)"Сохраняем…" else "Сохранить")};Spacer(Modifier.width(8.dp));TextButton({name=me.displayName;editing=false}){Text("Отмена")}}
   }else Button({editing=true},modifier=Modifier.fillMaxWidth()){Text("Редактировать профиль")}
  }}
  Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Text("Тёмная тема",modifier=Modifier.weight(1f));Switch(checked=darkMode,onCheckedChange=onDarkModeChange)}}
  Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){
   Text("Заблокированные пользователи",fontWeight=FontWeight.SemiBold)
   if(blocksError.isNotBlank())Text(blocksError,color=MaterialTheme.colorScheme.error)
   if(blocksError.isBlank()&&blockedUsers.isEmpty())Text("Список пуст",style=MaterialTheme.typography.bodySmall)
   blockedUsers.forEach{u->
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
     Text(u.displayName+" (@"+u.username+")",modifier=Modifier.weight(1f))
     TextButton(onClick={unblockingId=u.id;scope.launch{
      runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.setBlocked(token,u.id,false)}}
       .onSuccess{blockedUsers=blockedUsers.filter{it.id!=u.id};blocksError=""}
       .onFailure{blocksError="Не удалось снять блокировку"}
      unblockingId=""
     }},enabled=unblockingId.isEmpty()){Text("Снять блок")}
    }
   }
  }}
  Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text("Обновление",fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(6.dp));Text(updateText);Text("Версия "+BuildConfig.VERSION_NAME,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(checking)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp));if(progress>=0){Spacer(Modifier.height(12.dp));LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth());Text("Загрузка: $progress%",modifier=Modifier.padding(top=6.dp))};update?.let{u->if(progress<0){Spacer(Modifier.height(12.dp));Button({startUpdate(context,u.downloadUrl){p->scope.launch{progress=p;updateText=if(p<0)"Не удалось загрузить обновление" else if(p<100)"Загружаем обновление…" else "Устанавливаем обновление…"}}},modifier=Modifier.fillMaxWidth()){Text("Обновить Lumo")}}}}}
  Spacer(Modifier.height(14.dp))
  OutlinedButton(onClick={
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
  },enabled=!loggingOut,modifier=Modifier.fillMaxWidth()){
   Text(if(loggingOut)"Завершаем сессию…" else "Выйти из аккаунта")
  }
  if(logoutError.isNotEmpty()){
   Text(logoutError,color=MaterialTheme.colorScheme.error)
   TextButton(onClick=logout){Text("Выйти только с устройства")}
   Text("При локальном выходе серверная сессия остаётся активной до истечения срока.",
    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
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
   else->{android.widget.Toast.makeText(context,"Не удалось установить обновление. Проверьте, что APK подписан тем же ключом, что установленная версия.",android.widget.Toast.LENGTH_LONG).show()}
  }
 }
}

fun mergeChatMessages(current:List<Msg>,incoming:List<Msg>):List<Msg>{
 val merged=LinkedHashMap<String,Msg>()
 for(m in current+incoming){
  val old=merged[m.id]
  merged[m.id]=if(old==null)m else m.copy(
   deliveredAt=old.deliveredAt.ifBlank{m.deliveredAt},
   readAt=old.readAt.ifBlank{m.readAt},
   clientMessageId=m.clientMessageId.ifBlank{old.clientMessageId}
  )
 }
 return merged.values.sortedBy{it.createdAt}
}

@Composable fun Chat(token:String,me:User,peer:User,back:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val queuePrefs=remember{context.getSharedPreferences("lumo_pending",Context.MODE_PRIVATE)};val queueKey="pending_"+me.id+"_"+peer.id
 var blockedByMe by remember(peer.id){mutableStateOf(false)}
 var blockBusy by remember{mutableStateOf(false)}
 LaunchedEffect(token,peer.id){runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.blocks(token)}}.onSuccess{blockedByMe=it.any{u->u.id==peer.id}}}
 val msgs=remember{mutableStateListOf<Msg>()};var input by remember{mutableStateOf("")};var ws by remember{mutableStateOf<WebSocket?>(null)};var socketGeneration by remember{mutableIntStateOf(0)};var connected by remember{mutableStateOf(false)};var socketError by remember{mutableStateOf("")};var historyError by remember{mutableStateOf(false)};val pending=remember{mutableStateListOf<PendingMessage>().apply{val a=runCatching{JSONArray(queuePrefs.getString(queueKey,"[]"))}.getOrNull();if(a!=null)for(i in 0 until a.length()){val o=a.optJSONObject(i);if(o!=null){val id=o.optString("clientMessageId");val text=o.optString("text");if(id.isNotBlank()&&text.isNotBlank())add(PendingMessage(id,text))}else{val text=a.optString(i);if(text.isNotBlank())add(PendingMessage(java.util.UUID.randomUUID().toString(),text))}}}}
 fun savePending(){val a=JSONArray();pending.forEach{a.put(JSONObject().put("clientMessageId",it.clientMessageId).put("text",it.text))};queuePrefs.edit().putString(queueKey,a.toString()).apply()}
 DisposableEffect(peer.id){
  scope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.history(token,peer.id)}}.onSuccess{historyError=false;val merged=mergeChatMessages(msgs,it);msgs.clear();msgs.addAll(merged);val unread=it.filter{m->m.from==peer.id&&m.readAt.isBlank()}.map{m->m.id};if(unread.isNotEmpty())ws?.send(JSONObject().put("type","read").put("ids",JSONArray(unread)).toString())}.onFailure{historyError=true}}
  fun syncHistory(){scope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.history(token,peer.id)}}.onSuccess{fresh->historyError=false;val byId=mergeChatMessages(msgs,fresh);msgs.clear();msgs.addAll(byId);val unread=fresh.filter{m->m.from==peer.id&&m.readAt.isBlank()}.map{m->m.id};if(unread.isNotEmpty())ws?.send(JSONObject().put("type","read").put("ids",JSONArray(unread)).toString())}.onFailure{historyError=true}}};fun connect(){val generation=++socketGeneration;ws=Api.socket(token,{m->scope.launch{if(m.from==peer.id||m.to==peer.id){if(m.from==me.id&&m.clientMessageId.isNotBlank()){pending.removeAll{it.clientMessageId==m.clientMessageId};savePending()};val existing=msgs.indexOfFirst{it.id==m.id};if(existing>=0){msgs[existing]=mergeChatMessages(listOf(msgs[existing]),listOf(m)).first()}else msgs.add(m);if(m.from==peer.id)ws?.send(JSONObject().put("type","read").put("ids",JSONArray().put(m.id)).toString())}}},{r->scope.launch{val i=msgs.indexOfFirst{it.id==r.messageId};if(i>=0){val old=msgs[i];msgs[i]=old.copy(deliveredAt=old.deliveredAt.ifBlank{r.deliveredAt},readAt=old.readAt.ifBlank{r.readAt})}}},{e->scope.launch{socketError=when(e){"service_unavailable"->"Сервер временно недоступен. Переподключаемся…";"recipient_not_found"->"Получатель больше не найден.";"message_too_long"->"Сообщение слишком длинное.";"empty_message"->"Пустое сообщение не отправлено.";"client_message_id_conflict"->"Конфликт повторной отправки. Сообщение сохранено.";"invalid_client_message_id"->"Ошибка идентификатора сообщения.";"invalid_recipient_id"->"Некорректный получатель.";"invalid_server_message"->"Получен некорректный ответ сервера.";"cannot_message_self"->"Нельзя отправить сообщение самому себе.";"user_blocked"->"Сообщение отклонено: один из участников заблокировал переписку." ;else->"Не удалось отправить сообщение"};if(e=="user_blocked"){pending.clear();savePending()};if(e=="service_unavailable"){connected=false;ws?.close(1012,"retry")}}},{scope.launch{connected=true;socketError="";for(p in pending.toList()){val sent=ws?.send(JSONObject().put("type","message").put("to",peer.id).put("text",p.text).put("clientMessageId",p.clientMessageId).toString())==true;if(!sent){connected=false;ws?.close(1012,"retry");break}};syncHistory()}},{scope.launch{if(generation==socketGeneration){connected=false;kotlinx.coroutines.delay(2000);if(generation==socketGeneration)connect()}}})};connect()
  onDispose{socketGeneration++;ws?.close(1000,"bye")}
 }
 // Reconcile through PostgreSQL-backed HTTP because Vercel peers may use different function instances.
 LaunchedEffect(token,peer.id){
  while(true){
   kotlinx.coroutines.delay(5000)
   // Retry unacknowledged messages even if the socket looks connected:
   // HTTP and WebSocket share the same PostgreSQL idempotency key.
   for(p in pending.toList()){
    if(blockedByMe)break
    if(pending.none{it.clientMessageId==p.clientMessageId})continue
    val saved=runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.sendMessage(token,peer.id,p)}}
    if(saved.isFailure){if(saved.exceptionOrNull()?.message?.contains("403")==true){pending.remove(p);savePending();socketError="Невозможно отправить сообщение: переписка заблокирована.";continue};break}
    val m=saved.getOrThrow()
    pending.removeAll{it.clientMessageId==p.clientMessageId};savePending()
    val merged=mergeChatMessages(msgs,listOf(m));msgs.clear();msgs.addAll(merged)
   }
   val refreshed=runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
    val fresh=Api.history(token,peer.id)
    val unread=fresh.filter{it.from==peer.id&&it.readAt.isBlank()}.map{it.id}
    if(unread.isNotEmpty())runCatching{Api.readMessages(token,unread)}
    fresh
   }}
   refreshed.onSuccess{fresh->
    historyError=false
    val acknowledged=fresh.filter{it.from==me.id&&it.clientMessageId.isNotBlank()}.map{it.clientMessageId}.toSet()
    if(pending.removeAll{it.clientMessageId in acknowledged})savePending()
    val merged=mergeChatMessages(msgs,fresh);msgs.clear();msgs.addAll(merged)
   }.onFailure{historyError=true}
  }
 }
 Scaffold(
  topBar={Surface(shadowElevation=2.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
   TextButton(back){Text("‹ Назад")};Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Text(peer.displayName.take(1).uppercase())};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(peer.displayName,fontWeight=FontWeight.Bold);Text("@"+peer.username,style=MaterialTheme.typography.bodySmall)};TextButton(onClick={
     if(!blockBusy){blockBusy=true;scope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.setBlocked(token,peer.id,!blockedByMe)}}.onSuccess{blockedByMe=!blockedByMe;socketError=""}.onFailure{socketError="Не удалось изменить блокировку"};blockBusy=false}}
    },enabled=!blockBusy){Text(if(blockedByMe)"Снять блок" else "Блок")}
  }}}
 ){pad->
  Column(Modifier.padding(pad).fillMaxSize()){
    if(blockedByMe)Text("Вы заблокировали этого пользователя. Отправка отключена.",modifier=Modifier.padding(10.dp),color=MaterialTheme.colorScheme.error)
   if(socketError.isNotEmpty()){Surface(color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.fillMaxWidth()){Text(socketError,modifier=Modifier.padding(10.dp),color=MaterialTheme.colorScheme.onErrorContainer)}}
   if(!connected){
    Surface(color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.fillMaxWidth()){
     Text("Нет прямого соединения. Сообщения синхронизируются через сервер…",modifier=Modifier.padding(10.dp),color=MaterialTheme.colorScheme.onErrorContainer)
    }
   }
   if(historyError){Surface(color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.fillMaxWidth()){Text("Не удалось загрузить историю. Повторим после подключения.",modifier=Modifier.padding(10.dp),color=MaterialTheme.colorScheme.onErrorContainer)}}
   LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    items(msgs,key={it.id}){m->
     Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.from==me.id)Arrangement.End else Arrangement.Start){
      Surface(shape=RoundedCornerShape(18.dp),color=if(m.from==me.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.widthIn(max=300.dp)){
       Column(Modifier.padding(14.dp,8.dp)){Text(m.text);if(m.attachmentId.isNotBlank())MediaAttachmentButton(token,m.attachmentId);Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically){if(m.createdAt.isNotBlank())Text(formatMessageTime(m.createdAt),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(m.from==me.id){Spacer(Modifier.width(5.dp));Text(if(m.readAt.isNotBlank())"✓✓" else if(m.deliveredAt.isNotBlank())"✓✓" else "✓",style=MaterialTheme.typography.labelSmall,color=if(m.readAt.isNotBlank())MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}}
      }
     }
    }
    items(pending.filter{p->msgs.none{it.from==me.id&&it.clientMessageId==p.clientMessageId}},key={"pending-"+it.clientMessageId}){p->
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
      Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.widthIn(max=300.dp)){
       Column(Modifier.padding(14.dp,8.dp)){
        Text(p.text)
        Text("Отправляется…",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
       }
      }
     }
    }
   }
   MediaComposer(token,me,peer,!blockedByMe){attached->
    val merged=mergeChatMessages(msgs,listOf(attached));msgs.clear();msgs.addAll(merged)
   }
   Surface(shadowElevation=4.dp){Row(Modifier.fillMaxWidth().imePadding().padding(10.dp),verticalAlignment=Alignment.Bottom){
    OutlinedTextField(input,{input=it},placeholder={Text("Сообщение")},modifier=Modifier.weight(1f),maxLines=4,shape=RoundedCornerShape(24.dp))
    Spacer(Modifier.width(8.dp));Button({val text=input.trim();if(text.isNotEmpty()){val p=PendingMessage(java.util.UUID.randomUUID().toString(),text);pending.add(p);savePending();if(connected){val sent=ws?.send(JSONObject().put("type","message").put("to",peer.id).put("text",p.text).put("clientMessageId",p.clientMessageId).toString())==true;if(!sent){connected=false;ws?.close(1012,"retry")}};input=""}},enabled=input.isNotBlank()&&!blockedByMe,contentPadding=PaddingValues(horizontal=18.dp,vertical=16.dp)){Text("➤")}
   }}
  }
 }
}

fun formatMessageTime(iso:String):String=runCatching{java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(iso))}.getOrDefault("")

object Api{
 internal const val HTTP="https://lumo-gamma-seven.vercel.app";private const val WS="wss://lumo-gamma-seven.vercel.app/ws";val httpClient=OkHttpClient.Builder().connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS).readTimeout(30,java.util.concurrent.TimeUnit.SECONDS).writeTimeout(30,java.util.concurrent.TimeUnit.SECONDS).pingInterval(25,java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(true).build();private val c=httpClient
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
 fun updateMe(t:String,name:String):User{val j=JSONObject().put("displayName",name);val r=Request.Builder().url(HTTP+"/api/me").header("Authorization","Bearer "+t).patch(j.toString().toRequestBody("application/json".toMediaType())).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Профиль: "+x.code);return user(JSONObject(x.body!!.string()))}}
 fun me(t:String):User{val r=Request.Builder().url(HTTP+"/api/me").header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(x.code==401)throw SessionExpiredException();if(!x.isSuccessful)error("Сессия: "+x.code);return user(JSONObject(x.body!!.string()))}}
 fun users(t:String,q:String):List<User>{val url=(HTTP+"/api/users").toHttpUrl().newBuilder().addQueryParameter("q",q).build();val r=Request.Builder().url(url).header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Поиск: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{user(a.getJSONObject(it))}}}
 fun conversations(t:String):List<Conversation>{val r=Request.Builder().url(HTTP+"/api/conversations").header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Чаты: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{val o=a.getJSONObject(it);Conversation(user(o.getJSONObject("peer")),o.getString("lastMessage"),o.optString("lastAt"),o.optInt("unreadCount",0),o.optBoolean("pinned",false))}}}
 fun pin(t:String,peerId:String,enabled:Boolean):Boolean{
 val builder=Request.Builder().url(HTTP+"/api/conversations/"+peerId+"/pin").header("Authorization","Bearer "+t)
 val request=if(enabled)builder.put("".toRequestBody(null)).build() else builder.delete().build()
 c.newCall(request).execute().use{response->if(!response.isSuccessful)error("Закрепление: "+response.code);return JSONObject(response.body!!.string()).getBoolean("pinned")}
}
fun blocks(t:String):List<User>{val request=Request.Builder().url(HTTP+"/api/blocks").header("Authorization","Bearer "+t).build();c.newCall(request).execute().use{response->if(!response.isSuccessful)error("Блокировки: "+response.code);val a=JSONArray(response.body!!.string());return (0 until a.length()).map{user(a.getJSONObject(it))}}}
fun setBlocked(t:String,peerId:String,blocked:Boolean){
 val builder=Request.Builder().url(HTTP+"/api/blocks/"+peerId).header("Authorization","Bearer "+t)
 val request=if(blocked)builder.put("".toRequestBody(null)).build() else builder.delete().build()
 c.newCall(request).execute().use{response->if(!response.isSuccessful)error("Блокировка: "+response.code)}
}
fun history(t:String,p:String):List<Msg>{val r=Request.Builder().url(HTTP+"/api/messages/"+p).header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("История: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{msg(a.getJSONObject(it))}}}
 fun sendMessage(t:String,to:String,p:PendingMessage):Msg{
  val body=JSONObject().put("to",to).put("text",p.text).put("clientMessageId",p.clientMessageId)
  val request=Request.Builder().url(HTTP+"/api/messages").header("Authorization","Bearer "+t).post(body.toString().toRequestBody("application/json".toMediaType())).build()
  c.newCall(request).execute().use{response->if(!response.isSuccessful)error("Отправка: "+response.code);return msg(JSONObject(response.body!!.string()))}
 }
 fun readMessages(t:String,ids:List<String>){
  if(ids.isEmpty())return
  val body=JSONObject().put("ids",JSONArray(ids.take(200)))
  val request=Request.Builder().url(HTTP+"/api/messages/read").header("Authorization","Bearer "+t).post(body.toString().toRequestBody("application/json".toMediaType())).build()
  c.newCall(request).execute().use{response->if(!response.isSuccessful)error("Прочтение: "+response.code)}
 }
 fun latestRelease():UpdateInfo{val r=Request.Builder().url("https://api.github.com/repos/89681505031/Lumo/releases/tags/lumo-latest").header("Accept","application/vnd.github+json").build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Обновление: "+x.code);val o=JSONObject(x.body!!.string());val code=Regex("versionCode=(\\d+)").find(o.optString("body"))?.groupValues?.get(1)?.toIntOrNull()?:0;val a=o.getJSONArray("assets");for(i in 0 until a.length()){val asset=a.getJSONObject(i);if(asset.optString("name")=="app-debug.apk" || asset.optString("name")=="app-release.apk" || asset.optString("label")=="Lumo.apk")return UpdateInfo(code,asset.getString("browser_download_url"))};error("APK не найден")}}
 fun socket(t:String,onMessage:(Msg)->Unit,onReceipt:(Receipt)->Unit,onError:(String)->Unit,onReady:()->Unit,onDisconnected:()->Unit):WebSocket{return c.newWebSocket(Request.Builder().url(WS).header("Authorization","Bearer "+t).build(),object:WebSocketListener(){override fun onOpen(w:WebSocket,response:Response){};override fun onMessage(w:WebSocket,s:String){runCatching{val o=JSONObject(s);when(o.optString("type")){"ready"->onReady();"message"->onMessage(msg(o.getJSONObject("message")));"receipt"->onReceipt(Receipt(o.getString("messageId"),nullableJsonText(o,"deliveredAt"),nullableJsonText(o,"readAt")));"error"->onError(o.optString("error"));else->Unit}}.onFailure{onError("invalid_server_message")}};override fun onClosed(w:WebSocket,code:Int,reason:String)=onDisconnected();override fun onFailure(w:WebSocket,t:Throwable,response:Response?)=onDisconnected()})}
 private fun user(o:JSONObject)=User(o.getString("id"),o.getString("username"),o.getString("displayName"))
 private fun msg(o:JSONObject)=Msg(o.getString("id"),o.getString("from"),o.getString("to"),o.getString("text"),nullableJsonText(o,"createdAt"),nullableJsonText(o,"deliveredAt"),nullableJsonText(o,"readAt"),nullableJsonText(o,"clientMessageId"),nullableJsonText(o,"attachmentId"))
}
