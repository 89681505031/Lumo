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
data class Msg(val id:String,val from:String,val to:String,val text:String,val createdAt:String="",val deliveredAt:String="",val readAt:String="",val clientMessageId:String="")
data class Conversation(val peer:User,val lastMessage:String,val lastAt:String="")
data class UpdateInfo(val versionCode:Int,val downloadUrl:String)
data class Receipt(val messageId:String,val deliveredAt:String,val readAt:String)
data class PendingMessage(val clientMessageId:String,val text:String)

class MainActivity:ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{MaterialTheme{App()}}}
}

@Composable fun App(){
 val context=LocalContext.current
 val prefs=remember{context.getSharedPreferences("lumo_session",Context.MODE_PRIVATE)}
 var token by remember{mutableStateOf(prefs.getString("token",null))}
 var me by remember{mutableStateOf<User?>(null)}
 var peer by remember{mutableStateOf<User?>(null)}
 var logoutNonce by remember{mutableIntStateOf(0)}
 var restoring by remember{mutableStateOf(token!=null)}
 LaunchedEffect(token){
  val t=token
  if(t==null){restoring=false;me=null}
  else if(me==null){
   restoring=true
   runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.me(t)}}
    .onSuccess{me=it}
    .onFailure{prefs.edit().clear().apply();token=null}
   restoring=false
  }
 }
 when {
  restoring -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
  token==null || me==null -> Register{t,u->prefs.edit().putString("token",t).apply();token=t;me=u}
  peer==null -> Home(token!!,me!!,{peer=it},{me=it}){prefs.edit().clear().apply();token=null;me=null;peer=null;logoutNonce++}
  else -> Chat(token!!,me!!,peer!!){peer=null}
 }
}

@Composable fun Register(done:(String,User)->Unit){
 var name by remember{mutableStateOf("")};var login by remember{mutableStateOf("")};var err by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
 Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center){
  Text("Lumo",style=MaterialTheme.typography.displayLarge,fontWeight=FontWeight.Bold)
  Spacer(Modifier.height(8.dp));Text("Общайся просто",style=MaterialTheme.typography.titleMedium)
  Spacer(Modifier.height(28.dp))
  OutlinedTextField(name,{name=it},label={Text("Имя")},singleLine=true,modifier=Modifier.fillMaxWidth())
  Spacer(Modifier.height(10.dp))
  OutlinedTextField(login,{login=it},label={Text("Логин")},singleLine=true,modifier=Modifier.fillMaxWidth())
  if(err.isNotEmpty()) Text(err,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp))
  Spacer(Modifier.height(16.dp))
  val scope=rememberCoroutineScope()
  Button({busy=true;err="";scope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.register(login,name)}}.onSuccess{done(it.first,it.second)}.onFailure{err=it.message?:"Ошибка";busy=false}}},enabled=!busy&&name.isNotBlank()&&login.isNotBlank(),modifier=Modifier.fillMaxWidth().height(52.dp)){
   Text(if(busy)"Подключаем..." else "Создать аккаунт")
  }
 }
}

@Composable fun Home(token:String,me:User,open:(User)->Unit,profileChanged:(User)->Unit,logout:()->Unit){
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
    else->Profile(token,me,profileChanged,logout)
   }
  }
 }
}

@Composable fun Chats(token:String,find:()->Unit,open:(User)->Unit){
 var chats by remember{mutableStateOf<List<Conversation>>(emptyList())}
 var loading by remember{mutableStateOf(true)}
 LaunchedEffect(token){runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.conversations(token)}}.onSuccess{chats=it};loading=false}
 if(loading){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()};return}
 if(chats.isEmpty()){
  Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
   Text("Сообщений пока нет",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
   Spacer(Modifier.height(8.dp));Text("Найди человека и начни первый диалог",style=MaterialTheme.typography.bodyLarge)
   Spacer(Modifier.height(20.dp));Button(find){Text("Найти людей")}
  }
 } else {
  LazyColumn(Modifier.fillMaxSize()){
   items(chats,key={it.peer.id}){chat->
    Row(Modifier.fillMaxWidth().clickable{open(chat.peer)}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
     Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Text(chat.peer.displayName.take(1).uppercase(),style=MaterialTheme.typography.titleLarge)}
     Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(chat.peer.displayName,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium);Row(verticalAlignment=Alignment.CenterVertically){Text(chat.lastMessage,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f));if(chat.lastAt.isNotBlank()){Spacer(Modifier.width(8.dp));Text(formatMessageTime(chat.lastAt),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
    };HorizontalDivider()
   }
  }
 }
}

@Composable fun People(token:String,open:(User)->Unit){
 var users by remember{mutableStateOf<List<User>>(emptyList())};var q by remember{mutableStateOf("")};var loading by remember{mutableStateOf(false)}
 LaunchedEffect(q){loading=true;kotlinx.coroutines.delay(300);runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.users(token,q)}}.onSuccess{users=it};loading=false}
 Column(Modifier.fillMaxSize()){
  OutlinedTextField(q,{q=it},label={Text("Поиск по имени или логину")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(16.dp))
  if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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

@Composable fun Profile(token:String,me:User,profileChanged:(User)->Unit,logout:()->Unit){
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 var editing by remember{mutableStateOf(false)};var name by remember(me.displayName){mutableStateOf(me.displayName)};var saving by remember{mutableStateOf(false)};var profileError by remember{mutableStateOf("")}
 var update by remember{mutableStateOf<UpdateInfo?>(null)};var checking by remember{mutableStateOf(true)};var updateText by remember{mutableStateOf("Проверяем обновления…")};var progress by remember{mutableIntStateOf(-1)}
 LaunchedEffect(Unit){runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.latestRelease()}}.onSuccess{info->update=info.takeIf{it.versionCode>BuildConfig.VERSION_CODE};updateText=if(update!=null)"Доступна новая версия Lumo" else "Установлена последняя версия"}.onFailure{updateText="Не удалось проверить обновления"};checking=false}
 Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
  Spacer(Modifier.height(24.dp));Box(Modifier.size(92.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Text(me.displayName.take(1).uppercase(),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold)}
  Spacer(Modifier.height(16.dp));Text(me.displayName,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("@"+me.username,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(20.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){
   Text("Профиль",fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(8.dp))
   if(editing){
    OutlinedTextField(name,{name=it;profileError=""},label={Text("Имя")},singleLine=true,modifier=Modifier.fillMaxWidth())
    if(profileError.isNotEmpty())Text(profileError,color=MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(10.dp));Row{val scope=rememberCoroutineScope();Button({saving=true;scope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.updateMe(token,name)}}.onSuccess{profileChanged(it);editing=false}.onFailure{profileError="Не удалось сохранить"};saving=false}},enabled=!saving&&name.isNotBlank()){Text(if(saving)"Сохраняем…" else "Сохранить")};Spacer(Modifier.width(8.dp));TextButton({name=me.displayName;editing=false}){Text("Отмена")}}
   }else Button({editing=true},modifier=Modifier.fillMaxWidth()){Text("Редактировать профиль")}
  }}
  Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text("Обновление",fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(6.dp));Text(updateText);Text("Версия "+BuildConfig.VERSION_NAME,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(checking)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp));if(progress>=0){Spacer(Modifier.height(12.dp));LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth());Text("Загрузка: $progress%",modifier=Modifier.padding(top=6.dp))};update?.let{u->if(progress<0){Spacer(Modifier.height(12.dp));Button({startUpdate(context,u.downloadUrl){p->scope.launch{progress=p;updateText=if(p<0)"Не удалось загрузить обновление" else if(p<100)"Загружаем обновление…" else "Устанавливаем обновление…"}}},modifier=Modifier.fillMaxWidth()){Text("Обновить Lumo")}}}}}
  Spacer(Modifier.height(14.dp));OutlinedButton(logout,modifier=Modifier.fillMaxWidth()){Text("Выйти из аккаунта")}
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

@Composable fun Chat(token:String,me:User,peer:User,back:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val queuePrefs=remember{context.getSharedPreferences("lumo_pending",Context.MODE_PRIVATE)};val queueKey="pending_"+me.id+"_"+peer.id
 val msgs=remember{mutableStateListOf<Msg>()};var input by remember{mutableStateOf("")};var ws by remember{mutableStateOf<WebSocket?>(null)};var socketGeneration by remember{mutableIntStateOf(0)};var connected by remember{mutableStateOf(false)};var socketError by remember{mutableStateOf("")};val pending=remember{mutableStateListOf<PendingMessage>().apply{val a=runCatching{JSONArray(queuePrefs.getString(queueKey,"[]"))}.getOrNull();if(a!=null)for(i in 0 until a.length()){val o=a.optJSONObject(i);if(o!=null){val id=o.optString("clientMessageId");val text=o.optString("text");if(id.isNotBlank()&&text.isNotBlank())add(PendingMessage(id,text))}else{val text=a.optString(i);if(text.isNotBlank())add(PendingMessage(java.util.UUID.randomUUID().toString(),text))}}}}
 fun savePending(){val a=JSONArray();pending.forEach{a.put(JSONObject().put("clientMessageId",it.clientMessageId).put("text",it.text))};queuePrefs.edit().putString(queueKey,a.toString()).apply()}
 DisposableEffect(peer.id){
  scope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.history(token,peer.id)}}.onSuccess{msgs.clear();msgs.addAll(it);val unread=it.filter{m->m.from==peer.id&&m.readAt.isBlank()}.map{m->m.id};if(unread.isNotEmpty())ws?.send(JSONObject().put("type","read").put("ids",JSONArray(unread)).toString())}}
  fun syncHistory(){scope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){Api.history(token,peer.id)}}.onSuccess{fresh->val byId=(msgs+fresh).associateBy{it.id}.values.sortedBy{it.createdAt};msgs.clear();msgs.addAll(byId);val unread=fresh.filter{m->m.from==peer.id&&m.readAt.isBlank()}.map{m->m.id};if(unread.isNotEmpty())ws?.send(JSONObject().put("type","read").put("ids",JSONArray(unread)).toString())}}};fun connect(){val generation=++socketGeneration;ws=Api.socket(token,{m->scope.launch{if(m.from==peer.id||m.to==peer.id){if(m.from==me.id&&m.clientMessageId.isNotBlank()){pending.removeAll{it.clientMessageId==m.clientMessageId};savePending()};if(msgs.none{it.id==m.id})msgs.add(m);if(m.from==peer.id)ws?.send(JSONObject().put("type","read").put("ids",JSONArray().put(m.id)).toString())}}},{r->scope.launch{val i=msgs.indexOfFirst{it.id==r.messageId};if(i>=0){val old=msgs[i];msgs[i]=old.copy(deliveredAt=r.deliveredAt.ifBlank{old.deliveredAt},readAt=r.readAt.ifBlank{old.readAt})}}},{e->scope.launch{socketError=when(e){"service_unavailable"->"Сервер временно недоступен. Переподключаемся…";"recipient_not_found"->"Получатель больше не найден.";"message_too_long"->"Сообщение слишком длинное.";"empty_message"->"Пустое сообщение не отправлено.";"client_message_id_conflict"->"Конфликт повторной отправки. Сообщение сохранено.";"invalid_client_message_id"->"Ошибка идентификатора сообщения.";"invalid_recipient_id"->"Некорректный получатель.";"cannot_message_self"->"Нельзя отправить сообщение самому себе." ;else->"Не удалось отправить сообщение"};if(e=="service_unavailable"){connected=false;ws?.close(1012,"retry")}}},{scope.launch{connected=true;socketError="";pending.toList().forEach{p->ws?.send(JSONObject().put("type","message").put("to",peer.id).put("text",p.text).put("clientMessageId",p.clientMessageId).toString())};syncHistory()}},{scope.launch{connected=false;if(generation==socketGeneration){kotlinx.coroutines.delay(2000);if(generation==socketGeneration)connect()}}})};connect()
  onDispose{socketGeneration++;ws?.close(1000,"bye")}
 }
 Scaffold(
  topBar={Surface(shadowElevation=2.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
   TextButton(back){Text("‹ Назад")};Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Text(peer.displayName.take(1).uppercase())};Spacer(Modifier.width(10.dp));Column{Text(peer.displayName,fontWeight=FontWeight.Bold);Text("@"+peer.username,style=MaterialTheme.typography.bodySmall)}
  }}}
 ){pad->
  Column(Modifier.padding(pad).fillMaxSize()){
   if(socketError.isNotEmpty()){Surface(color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.fillMaxWidth()){Text(socketError,modifier=Modifier.padding(10.dp),color=MaterialTheme.colorScheme.onErrorContainer)}}
   if(!connected){Surface(color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.fillMaxWidth()){Text("Нет соединения. Переподключаемся…",modifier=Modifier.padding(10.dp),color=MaterialTheme.colorScheme.onErrorContainer)}}
   LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    items(msgs,key={it.id}){m->
     Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.from==me.id)Arrangement.End else Arrangement.Start){
      Surface(shape=RoundedCornerShape(18.dp),color=if(m.from==me.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.widthIn(max=300.dp)){
       Column(Modifier.padding(14.dp,8.dp)){Text(m.text);Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically){if(m.createdAt.isNotBlank())Text(formatMessageTime(m.createdAt),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(m.from==me.id){Spacer(Modifier.width(5.dp));Text(if(m.readAt.isNotBlank())"✓✓" else if(m.deliveredAt.isNotBlank())"✓✓" else "✓",style=MaterialTheme.typography.labelSmall,color=if(m.readAt.isNotBlank())MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}}
      }
     }
    }
   }
   Surface(shadowElevation=4.dp){Row(Modifier.fillMaxWidth().imePadding().padding(10.dp),verticalAlignment=Alignment.Bottom){
    OutlinedTextField(input,{input=it},placeholder={Text("Сообщение")},modifier=Modifier.weight(1f),maxLines=4,shape=RoundedCornerShape(24.dp))
    Spacer(Modifier.width(8.dp));Button({val text=input.trim();if(text.isNotEmpty()){val p=PendingMessage(java.util.UUID.randomUUID().toString(),text);pending.add(p);savePending();if(connected)ws?.send(JSONObject().put("type","message").put("to",peer.id).put("text",p.text).put("clientMessageId",p.clientMessageId).toString());input=""}},enabled=input.isNotBlank(),contentPadding=PaddingValues(horizontal=18.dp,vertical=16.dp)){Text("➤")}
   }}
  }
 }
}

fun formatMessageTime(iso:String):String=runCatching{java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(iso))}.getOrDefault("")

object Api{
 private const val HTTP="https://lumo-gamma-seven.vercel.app";private const val WS="wss://lumo-gamma-seven.vercel.app/ws";val httpClient=OkHttpClient();private val c=httpClient
 fun register(login:String,name:String):Pair<String,User>{val j=JSONObject().put("username",login).put("displayName",name);val r=Request.Builder().url(HTTP+"/api/register").post(j.toString().toRequestBody("application/json".toMediaType())).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Регистрация: "+x.code);val o=JSONObject(x.body!!.string());return o.getString("token") to user(o.getJSONObject("user"))}}
 fun updateMe(t:String,name:String):User{val j=JSONObject().put("displayName",name);val r=Request.Builder().url(HTTP+"/api/me").header("Authorization","Bearer "+t).patch(j.toString().toRequestBody("application/json".toMediaType())).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Профиль: "+x.code);return user(JSONObject(x.body!!.string()))}}
 fun me(t:String):User{val r=Request.Builder().url(HTTP+"/api/me").header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Сессия: "+x.code);return user(JSONObject(x.body!!.string()))}}
 fun users(t:String,q:String):List<User>{val url=(HTTP+"/api/users").toHttpUrl().newBuilder().addQueryParameter("q",q).build();val r=Request.Builder().url(url).header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Поиск: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{user(a.getJSONObject(it))}}}
 fun conversations(t:String):List<Conversation>{val r=Request.Builder().url(HTTP+"/api/conversations").header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Чаты: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{val o=a.getJSONObject(it);Conversation(user(o.getJSONObject("peer")),o.getString("lastMessage"),o.optString("lastAt"))}}}
 fun history(t:String,p:String):List<Msg>{val r=Request.Builder().url(HTTP+"/api/messages/"+p).header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("История: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{msg(a.getJSONObject(it))}}}
 fun latestRelease():UpdateInfo{val r=Request.Builder().url("https://api.github.com/repos/89681505031/Lumo/releases/tags/lumo-latest").header("Accept","application/vnd.github+json").build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Обновление: "+x.code);val o=JSONObject(x.body!!.string());val code=Regex("versionCode=(\\d+)").find(o.optString("body"))?.groupValues?.get(1)?.toIntOrNull()?:0;val a=o.getJSONArray("assets");for(i in 0 until a.length()){val asset=a.getJSONObject(i);if(asset.optString("name")=="app-debug.apk" || asset.optString("name")=="app-release.apk" || asset.optString("label")=="Lumo.apk")return UpdateInfo(code,asset.getString("browser_download_url"))};error("APK не найден")}}
 fun socket(t:String,onMessage:(Msg)->Unit,onReceipt:(Receipt)->Unit,onError:(String)->Unit,onReady:()->Unit,onDisconnected:()->Unit):WebSocket{return c.newWebSocket(Request.Builder().url(WS+"?token="+t).build(),object:WebSocketListener(){override fun onOpen(w:WebSocket,response:Response)=onReady();override fun onMessage(w:WebSocket,s:String){val o=JSONObject(s);when(o.optString("type")){"message"->onMessage(msg(o.getJSONObject("message")));"receipt"->onReceipt(Receipt(o.getString("messageId"),o.optString("deliveredAt"),o.optString("readAt")));"error"->onError(o.optString("error"))}};override fun onClosed(w:WebSocket,code:Int,reason:String)=onDisconnected();override fun onFailure(w:WebSocket,t:Throwable,response:Response?)=onDisconnected()})}
 private fun user(o:JSONObject)=User(o.getString("id"),o.getString("username"),o.getString("displayName"))
 private fun msg(o:JSONObject)=Msg(o.getString("id"),o.getString("from"),o.getString("to"),o.getString("text"),o.optString("createdAt"),o.optString("deliveredAt"),o.optString("readAt"),o.optString("clientMessageId"))
}
