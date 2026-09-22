package app.lumo

import android.os.Bundle
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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

data class User(val id:String,val username:String,val displayName:String)
data class Msg(val id:String,val from:String,val to:String,val text:String)
data class Conversation(val peer:User,val lastMessage:String)

class MainActivity:ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{MaterialTheme{App()}}}
}

@Composable fun App(){
 var token by remember{mutableStateOf<String?>(null)}
 var me by remember{mutableStateOf<User?>(null)}
 var peer by remember{mutableStateOf<User?>(null)}
 when {
  token==null -> Register{t,u->token=t;me=u}
  peer==null -> Home(token!!,me!!){peer=it}
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
  Button({busy=true;thread{runCatching{Api.register(login,name)}.onSuccess{done(it.first,it.second)}.onFailure{err=it.message?:"Ошибка";busy=false}}},enabled=!busy&&name.isNotBlank()&&login.isNotBlank(),modifier=Modifier.fillMaxWidth().height(52.dp)){
   Text(if(busy)"Подключаем..." else "Создать аккаунт")
  }
 }
}

@Composable fun Home(token:String,me:User,open:(User)->Unit){
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
    else->Profile(me)
   }
  }
 }
}

@Composable fun Chats(token:String,find:()->Unit,open:(User)->Unit){
 var chats by remember{mutableStateOf<List<Conversation>>(emptyList())}
 var loading by remember{mutableStateOf(true)}
 LaunchedEffect(Unit){thread{runCatching{Api.conversations(token)}.onSuccess{chats=it}.also{loading=false}}}
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
     Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(chat.peer.displayName,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium);Text(chat.lastMessage,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    };HorizontalDivider()
   }
  }
 }
}

@Composable fun People(token:String,open:(User)->Unit){
 var users by remember{mutableStateOf<List<User>>(emptyList())};var q by remember{mutableStateOf("")};var loading by remember{mutableStateOf(false)}
 fun load(){loading=true;thread{runCatching{Api.users(token,q)}.onSuccess{users=it}.also{loading=false}}}
 LaunchedEffect(Unit){load()}
 Column(Modifier.fillMaxSize()){
  OutlinedTextField(q,{q=it;load()},label={Text("Поиск по имени или логину")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(16.dp))
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

@Composable fun Profile(me:User){
 Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
  Spacer(Modifier.height(32.dp));Box(Modifier.size(92.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Text(me.displayName.take(1).uppercase(),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold)}
  Spacer(Modifier.height(16.dp));Text(me.displayName,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("@"+me.username,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(32.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text("Аккаунт Lumo",fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(6.dp));Text("Профиль подключён к серверу Lumo.")}}
 }
}

@Composable fun Chat(token:String,me:User,peer:User,back:()->Unit){
 val msgs=remember{mutableStateListOf<Msg>()};var input by remember{mutableStateOf("")};var ws by remember{mutableStateOf<WebSocket?>(null)}
 DisposableEffect(peer.id){
  thread{runCatching{Api.history(token,peer.id)}.onSuccess{msgs.clear();msgs.addAll(it)}}
  ws=Api.socket(token){m->if(m.from==peer.id||m.to==peer.id)msgs.add(m)}
  onDispose{ws?.close(1000,"bye")}
 }
 Scaffold(
  topBar={Surface(shadowElevation=2.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
   TextButton(back){Text("‹ Назад")};Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Text(peer.displayName.take(1).uppercase())};Spacer(Modifier.width(10.dp));Column{Text(peer.displayName,fontWeight=FontWeight.Bold);Text("@"+peer.username,style=MaterialTheme.typography.bodySmall)}
  }}}
 ){pad->
  Column(Modifier.padding(pad).fillMaxSize()){
   LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    items(msgs,key={it.id}){m->
     Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.from==me.id)Arrangement.End else Arrangement.Start){
      Surface(shape=RoundedCornerShape(18.dp),color=if(m.from==me.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.widthIn(max=300.dp)){
       Text(m.text,Modifier.padding(14.dp,10.dp))
      }
     }
    }
   }
   Surface(shadowElevation=4.dp){Row(Modifier.fillMaxWidth().imePadding().padding(10.dp),verticalAlignment=Alignment.Bottom){
    OutlinedTextField(input,{input=it},placeholder={Text("Сообщение")},modifier=Modifier.weight(1f),maxLines=4,shape=RoundedCornerShape(24.dp))
    Spacer(Modifier.width(8.dp));Button({val text=input.trim();if(text.isNotEmpty()){ws?.send(JSONObject().put("type","message").put("to",peer.id).put("text",text).toString());input=""}},enabled=input.isNotBlank(),contentPadding=PaddingValues(horizontal=18.dp,vertical=16.dp)){Text("➤")}
   }}
  }
 }
}

object Api{
 private const val HTTP="https://lumo-gamma-seven.vercel.app";private const val WS="wss://lumo-gamma-seven.vercel.app/ws";private val c=OkHttpClient()
 fun register(login:String,name:String):Pair<String,User>{val j=JSONObject().put("username",login).put("displayName",name);val r=Request.Builder().url(HTTP+"/api/register").post(j.toString().toRequestBody("application/json".toMediaType())).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Регистрация: "+x.code);val o=JSONObject(x.body!!.string());return o.getString("token") to user(o.getJSONObject("user"))}}
 fun users(t:String,q:String):List<User>{val url=(HTTP+"/api/users").toHttpUrl().newBuilder().addQueryParameter("q",q).build();val r=Request.Builder().url(url).header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Поиск: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{user(a.getJSONObject(it))}}}
 fun conversations(t:String):List<Conversation>{val r=Request.Builder().url(HTTP+"/api/conversations").header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Чаты: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{val o=a.getJSONObject(it);Conversation(user(o.getJSONObject("peer")),o.getString("lastMessage"))}}}
 fun history(t:String,p:String):List<Msg>{val r=Request.Builder().url(HTTP+"/api/messages/"+p).header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("История: "+x.code);val a=JSONArray(x.body!!.string());return(0 until a.length()).map{msg(a.getJSONObject(it))}}}
 fun socket(t:String,on:(Msg)->Unit):WebSocket{return c.newWebSocket(Request.Builder().url(WS+"?token="+t).build(),object:WebSocketListener(){override fun onMessage(w:WebSocket,s:String){val o=JSONObject(s);if(o.optString("type")=="message")on(msg(o.getJSONObject("message")))}})}
 private fun user(o:JSONObject)=User(o.getString("id"),o.getString("username"),o.getString("displayName"))
 private fun msg(o:JSONObject)=Msg(o.getString("id"),o.getString("from"),o.getString("to"),o.getString("text"))
}
