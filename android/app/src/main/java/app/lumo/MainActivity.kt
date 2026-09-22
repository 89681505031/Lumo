package app.lumo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

data class User(val id:String,val username:String,val displayName:String)
data class Msg(val id:String,val from:String,val to:String,val text:String)

class MainActivity:ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{MaterialTheme{App()}}}
}

@Composable fun App(){
 var token by remember{mutableStateOf<String?>(null)}
 var me by remember{mutableStateOf<User?>(null)}
 var peer by remember{mutableStateOf<User?>(null)}
 when {
  token==null -> Register{t,u->token=t;me=u}
  peer==null -> People(token!!,me!!){peer=it}
  else -> Chat(token!!,me!!,peer!!){peer=null}
 }
}

@Composable fun Register(done:(String,User)->Unit){
 var name by remember{mutableStateOf("")};var login by remember{mutableStateOf("")};var err by remember{mutableStateOf("")}
 Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center){
  Text("Lumo",style=MaterialTheme.typography.displayMedium)
  OutlinedTextField(name,{name=it},label={Text("Имя")},modifier=Modifier.fillMaxWidth())
  OutlinedTextField(login,{login=it},label={Text("Логин")},modifier=Modifier.fillMaxWidth())
  if(err.isNotEmpty()) Text(err)
  Button({thread{runCatching{Api.register(login,name)}.onSuccess{done(it.first,it.second)}.onFailure{err=it.message?:"Ошибка"}}},modifier=Modifier.fillMaxWidth()){Text("Создать аккаунт")}
 }
}

@Composable fun People(token:String,me:User,open:(User)->Unit){
 var users by remember{mutableStateOf<List<User>>(emptyList())};var q by remember{mutableStateOf("")}
 fun load(){thread{runCatching{Api.users(token,q)}.onSuccess{users=it}}}
 LaunchedEffect(Unit){load()}
 Column(Modifier.fillMaxSize().padding(16.dp)){
  Text("Lumo",style=MaterialTheme.typography.headlineLarge);Text("Привет, "+me.displayName)
  OutlinedTextField(q,{q=it;load()},label={Text("Найти человека")},modifier=Modifier.fillMaxWidth())
  LazyColumn{items(users){u->ListItem(headlineContent={Text(u.displayName)},supportingContent={Text("@"+u.username)})
   Button({open(u)}){Text("Открыть чат")};HorizontalDivider()}}
 }
}

@Composable fun Chat(token:String,me:User,peer:User,back:()->Unit){
 val msgs=remember{mutableStateListOf<Msg>()};var input by remember{mutableStateOf("")};var ws by remember{mutableStateOf<WebSocket?>(null)}
 DisposableEffect(peer.id){
  thread{runCatching{Api.history(token,peer.id)}.onSuccess{msgs.clear();msgs.addAll(it)}}
  ws=Api.socket(token){m->if(m.from==peer.id||m.to==peer.id)msgs.add(m)}
  onDispose{ws?.close(1000,"bye")}
 }
 Column(Modifier.fillMaxSize().padding(16.dp)){
  Row{TextButton(back){Text("Назад")};Text(peer.displayName,style=MaterialTheme.typography.titleLarge)}
  LazyColumn(Modifier.weight(1f)){items(msgs,key={it.id}){m->Text((if(m.from==me.id)"Вы: " else peer.displayName+": ")+m.text,Modifier.padding(8.dp))}}
  Row{OutlinedTextField(input,{input=it},modifier=Modifier.weight(1f));Button({if(input.isNotBlank()){ws?.send(JSONObject().put("type","message").put("to",peer.id).put("text",input).toString());input=""}}){Text("Отправить")}}
 }
}

object Api{
 private const val HTTP="http://10.0.2.2:3000";private const val WS="ws://10.0.2.2:3000/ws";private val c=OkHttpClient()
 fun register(login:String,name:String):Pair<String,User>{val j=JSONObject().put("username",login).put("displayName",name);val r=Request.Builder().url(HTTP+"/api/register").post(j.toString().toRequestBody("application/json".toMediaType())).build();c.newCall(r).execute().use{x->if(!x.isSuccessful)error("Регистрация: "+x.code);val o=JSONObject(x.body!!.string());return o.getString("token") to user(o.getJSONObject("user"))}}
 fun users(t:String,q:String):List<User>{val url=HttpUrl.get(HTTP+"/api/users").newBuilder().addQueryParameter("q",q).build();val r=Request.Builder().url(url).header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->val a=JSONArray(x.body!!.string());return(0 until a.length()).map{user(a.getJSONObject(it))}}}
 fun history(t:String,p:String):List<Msg>{val r=Request.Builder().url(HTTP+"/api/messages/"+p).header("Authorization","Bearer "+t).build();c.newCall(r).execute().use{x->val a=JSONArray(x.body!!.string());return(0 until a.length()).map{msg(a.getJSONObject(it))}}}
 fun socket(t:String,on:(Msg)->Unit):WebSocket{return c.newWebSocket(Request.Builder().url(WS+"?token="+t).build(),object:WebSocketListener(){override fun onMessage(w:WebSocket,s:String){val o=JSONObject(s);if(o.optString("type")=="message")on(msg(o.getJSONObject("message")))}})}
 private fun user(o:JSONObject)=User(o.getString("id"),o.getString("username"),o.getString("displayName"))
 private fun msg(o:JSONObject)=Msg(o.getString("id"),o.getString("from"),o.getString("to"),o.getString("text"))
}