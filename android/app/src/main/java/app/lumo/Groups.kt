package app.lumo

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class LumoGroup(
 val id:String,val title:String,val ownerId:String,val role:String,
 val memberCount:Int,val lastMessage:String="",val lastAt:String=""
)
data class LumoGroupMember(val id:String,val username:String,val displayName:String,val role:String)
data class LumoGroupMessage(val id:String,val from:String,val text:String,val createdAt:String,val clientMessageId:String="",val replyToMessageId:String="",val replyPreviewText:String="",val replyPreviewFrom:String="")
data class LumoGroupPending(val clientId:String,val text:String,val replyToMessageId:String="",val replyPreviewText:String="",val replyPreviewFrom:String="")
data class LumoGroupDetail(val group:LumoGroup,val members:List<LumoGroupMember>)

private fun optional(o:JSONObject,key:String)=if(o.isNull(key))"" else o.optString(key)
private fun group(o:JSONObject)=LumoGroup(
 o.getString("id"),o.getString("title"),o.getString("ownerId"),
 o.getString("role"),o.optInt("memberCount",0),optional(o,"lastMessage"),optional(o,"lastAt")
)
private fun groupMessage(o:JSONObject)=LumoGroupMessage(
 o.getString("id"),o.getString("from"),o.getString("text"),o.getString("createdAt"),optional(o,"clientMessageId"),optional(o,"replyToMessageId"),optional(o,"replyPreviewText"),optional(o,"replyPreviewFrom")
)
private fun Api.groupCall(token:String,path:String,method:String="GET",body:JSONObject?=null):String{
 val req=Request.Builder().url(Api.HTTP+path).header("Authorization","Bearer "+token)
 val rb=body?.toString()?.toRequestBody("application/json".toMediaType())
 val request=when(method){
  "POST"->req.post(rb?:JSONObject().toString().toRequestBody("application/json".toMediaType())).build()
  "PATCH"->req.patch(rb?:JSONObject().toString().toRequestBody("application/json".toMediaType())).build()
  "DELETE"->req.delete().build()
  else->req.get().build()
 }
 Api.httpClient.newCall(request).execute().use{r->
  if(r.code==401)throw SessionExpiredException()
  if(!r.isSuccessful){
   val serverError=runCatching{JSONObject(r.body?.string().orEmpty()).optString("error")}.getOrDefault("")
   error("HTTP "+r.code+" "+serverError)
  }
  return r.body?.string().orEmpty()
 }
}
fun Api.listGroups(t:String):List<LumoGroup>{
 val a=JSONArray(groupCall(t,"/api/groups"))
 return(0 until a.length()).map{group(a.getJSONObject(it))}
}
fun Api.createGroup(t:String,title:String):LumoGroup=
 group(JSONObject(groupCall(t,"/api/groups","POST",JSONObject().put("title",title))))
fun Api.groupDetail(t:String,id:String):LumoGroupDetail{
 val o=JSONObject(groupCall(t,"/api/groups/"+id))
 val a=o.getJSONArray("members")
 val members=(0 until a.length()).map{
  val u=a.getJSONObject(it)
  LumoGroupMember(u.getString("id"),u.getString("username"),u.getString("displayName"),u.getString("role"))
 }
 return LumoGroupDetail(group(o),members)
}
fun Api.groupHistory(t:String,id:String):List<LumoGroupMessage>{
 val a=JSONArray(groupCall(t,"/api/groups/"+id+"/messages"))
 return(0 until a.length()).map{groupMessage(a.getJSONObject(it))}
}
fun Api.groupCapabilities(t:String):Pair<Boolean,Boolean>{
 val req=Request.Builder().url(Api.HTTP+"/api/capabilities")
  .header("Authorization","Bearer "+t).get().build()
 Api.httpClient.newCall(req).execute().use{r->
  if(r.code==401)throw SessionExpiredException()
  if(!r.isSuccessful)return false to false
  return runCatching{
   val o=JSONObject(r.body?.string().orEmpty())
   o.optBoolean("groupLinkedReplies",false) to o.optBoolean("groupSearch",false)
  }.getOrDefault(false to false)
 }
}
fun Api.groupSearch(t:String,id:String,q:String):List<LumoGroupMessage>{
 val encoded=java.net.URLEncoder.encode(q.trim(),Charsets.UTF_8.name())
 val a=JSONArray(groupCall(t,"/api/groups/"+id+"/messages/search?q="+encoded))
 return(0 until a.length()).map{groupMessage(a.getJSONObject(it))}
}
fun Api.groupSend(t:String,id:String,item:LumoGroupPending):LumoGroupMessage{
 val body=JSONObject().put("text",item.text).put("clientMessageId",item.clientId)
 if(item.replyToMessageId.isNotBlank())body.put("replyToMessageId",item.replyToMessageId)
 return groupMessage(JSONObject(groupCall(t,"/api/groups/"+id+"/messages","POST",body)))
}
fun Api.groupInvite(t:String,id:String,memberId:String){
 groupCall(t,"/api/groups/"+id+"/members","POST",JSONObject().put("userId",memberId))
}
fun Api.groupRole(t:String,id:String,memberId:String,role:String){
 groupCall(t,"/api/groups/"+id+"/members/"+memberId,"PATCH",JSONObject().put("role",role))
}
fun Api.groupRemove(t:String,id:String,memberId:String){groupCall(t,"/api/groups/"+id+"/members/"+memberId,"DELETE")}
fun Api.groupDelete(t:String,id:String){groupCall(t,"/api/groups/"+id,"DELETE")}

@Composable
fun GroupsScreen(token:String,openGroup:(LumoGroup)->Unit){
 val scope=rememberCoroutineScope()
 var groups by remember(token){mutableStateOf<List<LumoGroup>>(emptyList())}
 var loading by remember{mutableStateOf(true)}
 var error by remember{mutableStateOf("")}
 var createDialog by remember{mutableStateOf(false)}
 var title by remember{mutableStateOf("")}
 var creating by remember{mutableStateOf(false)}
 var refresh by remember{mutableIntStateOf(0)}
 LaunchedEffect(token,refresh){
  while(true){
   runCatching{withContext(Dispatchers.IO){Api.listGroups(token)}}
    .onSuccess{groups=it;error="";loading=false}
    .onFailure{error=it.message?:"Не удалось загрузить группы";loading=false}
   delay(10_000)
  }
 }
 if(createDialog){
  AlertDialog(onDismissRequest={if(!creating)createDialog=false},
   title={Text("Новая группа")},
   text={OutlinedTextField(title,{title=it.take(80)},label={Text("Название")},singleLine=true)},
   confirmButton={TextButton(onClick={
    creating=true
    scope.launch{
     runCatching{withContext(Dispatchers.IO){Api.createGroup(token,title.trim())}}
      .onSuccess{groups=listOf(it)+groups;createDialog=false;title="";openGroup(it)}
      .onFailure{error="Не удалось создать группу"}
     creating=false
    }
   },enabled=!creating&&title.trim().length in 2..80){Text("Создать")}},
   dismissButton={TextButton(onClick={createDialog=false},enabled=!creating){Text("Отмена")}}
  )
 }
 Column(Modifier.fillMaxSize()){
  Row(Modifier.fillMaxWidth().padding(16.dp).lumoGlass(24).padding(8.dp),verticalAlignment=Alignment.CenterVertically){
   Text("Мои группы",style=MaterialTheme.typography.titleLarge,color=Color.White,modifier=Modifier.weight(1f))
   LumoNeonButton("+ Группа",onClick={createDialog=true})
  }
  if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=16.dp))
  if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
  if(!loading&&groups.isEmpty()){
   Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,
    verticalArrangement=Arrangement.Center){
    Text("Групп пока нет",style=MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Button(onClick={refresh++}){Text("Обновить")}
   }
  }else LazyColumn(Modifier.fillMaxSize()){
   items(groups,key={it.id}){g->
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).lumoGlass(23).clickable{openGroup(g)}.padding(14.dp),verticalAlignment=Alignment.CenterVertically){
     LumoNeonAvatar(g.title,size=52.dp)
     Spacer(Modifier.width(12.dp))
     Column(Modifier.weight(1f)){
      Text(g.title,fontWeight=FontWeight.SemiBold,color=Color.White)
      Text(if(g.lastMessage.isNotBlank())g.lastMessage else g.memberCount.toString()+" участников",
       maxLines=1,style=MaterialTheme.typography.bodySmall)
     }
     Text("›",style=MaterialTheme.typography.titleLarge)
    }
   }
  }
 }
}

@Composable
fun GroupRoom(token:String,me:User,initial:LumoGroup,back:()->Unit){
 val scope=rememberCoroutineScope()
 val context=LocalContext.current
 val queuePrefs=remember{context.getSharedPreferences("lumo_group_pending",android.content.Context.MODE_PRIVATE)}
 val queueKey="group_"+me.id+"_"+initial.id
 var pending by remember(queueKey){mutableStateOf(
  runCatching{
   val raw=queuePrefs.getString(queueKey,null)?:return@runCatching null
   val data=JSONObject(raw)
   LumoGroupPending(
    data.getString("clientId"),
    data.getString("text"),
    data.optString("replyToMessageId"),
    data.optString("replyPreviewText"),
    data.optString("replyPreviewFrom")
   )
  }.getOrNull()
 )}
 fun savePending(value:LumoGroupPending?){
  pending=value
  val edit=queuePrefs.edit()
  if(value==null)edit.remove(queueKey)
  else edit.putString(queueKey,JSONObject()
   .put("clientId",value.clientId)
   .put("text",value.text)
   .put("replyToMessageId",value.replyToMessageId)
   .put("replyPreviewText",value.replyPreviewText)
   .put("replyPreviewFrom",value.replyPreviewFrom)
   .toString())
  edit.apply()
 }

 var detail by remember(initial.id){mutableStateOf<LumoGroupDetail?>(null)}
 var history by remember(initial.id){mutableStateOf<List<LumoGroupMessage>>(emptyList())}
 var input by remember(queueKey){mutableStateOf(pending?.text?:"")}
 var error by remember{mutableStateOf("")}
 var loading by remember{mutableStateOf(true)}
 var sending by remember{mutableStateOf(false)}
 var actionBusy by remember{mutableStateOf(false)}
 var inviteDialog by remember{mutableStateOf(false)}
 var inviteSearch by remember{mutableStateOf("")}
 var inviteUsers by remember{mutableStateOf<List<User>>(emptyList())}
 var removeUser by remember{mutableStateOf<LumoGroupMember?>(null)}
 var deleteGroupDialog by remember{mutableStateOf(false)}
 var groupLinkedReplies by remember(token){mutableStateOf(false)}
 var groupSearchEnabled by remember(token){mutableStateOf(false)}
 var replyTarget by remember(initial.id){mutableStateOf<LumoGroupMessage?>(null)}
 var showSearch by remember(initial.id){mutableStateOf(false)}
 var searchText by remember(initial.id){mutableStateOf("")}
 var searchResults by remember(initial.id){mutableStateOf<List<LumoGroupMessage>>(emptyList())}
 var searchBusy by remember{mutableStateOf(false)}
 var searchPerformed by remember{mutableStateOf(false)}
 var searchError by remember{mutableStateOf("")}
 val listState=rememberLazyListState()
 LaunchedEffect(token,initial.id){
  val caps=runCatching{withContext(Dispatchers.IO){Api.groupCapabilities(token)}}
   .getOrDefault(false to false)
  groupLinkedReplies=caps.first
  groupSearchEnabled=caps.second
 }
 fun reload(){
  scope.launch{
   runCatching{withContext(Dispatchers.IO){Api.groupDetail(token,initial.id)}}
    .onSuccess{detail=it;error=""}
    .onFailure{error="Не удалось обновить участников"}
  }
 }
 LaunchedEffect(token,initial.id){
  while(true){
   val response=runCatching{withContext(Dispatchers.IO){
    Api.groupDetail(token,initial.id) to Api.groupHistory(token,initial.id)
   }}
   response.onSuccess{pair->
    detail=pair.first;history=pair.second;error="";loading=false
    if(pair.second.any{it.from==me.id&&it.clientMessageId.isNotBlank()&&it.clientMessageId==pending?.clientId}){
     savePending(null);input=""
    }
   }
    .onFailure{error="Нет связи с группой или вы больше не участник";loading=false}
   delay(5_000)
  }
 }
 // Same-instance group events arrive immediately; polling remains the durable
 // fallback when devices connect through different Vercel function instances.
 DisposableEffect(token,initial.id){
  val socket=Api.httpClient.newWebSocket(
   Request.Builder().url(Api.WS).header("Authorization","Bearer "+token).build(),
   object:WebSocketListener(){
    override fun onMessage(ws:WebSocket,text:String){
     runCatching{
      val event=JSONObject(text)
      if(event.optString("type")!="group_message")return@runCatching
      val data=event.getJSONObject("message")
      if(data.optString("groupId")!=initial.id)return@runCatching
      val msg=groupMessage(data)
      scope.launch{
       history=(history.filterNot{it.id==msg.id}+msg).sortedBy{it.createdAt}
       if(msg.from==me.id&&msg.clientMessageId.isNotBlank()&&msg.clientMessageId==pending?.clientId){
        savePending(null);input=""
       }
      }
     }
    }
   })
  onDispose{socket.close(1000,"Group closed")}
 }
 LaunchedEffect(inviteSearch,inviteDialog){
  if(inviteDialog&&inviteSearch.trim().length>=2){
   delay(350)
   runCatching{withContext(Dispatchers.IO){Api.users(token,inviteSearch.trim())}}
    .onSuccess{inviteUsers=it.filter{u->u.id!=me.id&&detail?.members?.none{m->m.id==u.id}!=false}}
    .onFailure{inviteUsers=emptyList()}
  }else inviteUsers=emptyList()
 }
 if(inviteDialog){
  AlertDialog(onDismissRequest={if(!actionBusy)inviteDialog=false},
   title={Text("Пригласить участника")},
   text={
    Column{
     OutlinedTextField(inviteSearch,{inviteSearch=it},label={Text("Логин или имя")},
      singleLine=true,modifier=Modifier.fillMaxWidth())
     Spacer(Modifier.height(8.dp))
     inviteUsers.take(8).forEach{u->
      TextButton(onClick={
       actionBusy=true
       scope.launch{
        runCatching{withContext(Dispatchers.IO){Api.groupInvite(token,initial.id,u.id)}}
         .onSuccess{inviteDialog=false;inviteSearch="";reload()}
         .onFailure{error="Не удалось пригласить участника"}
        actionBusy=false
       }
      },enabled=!actionBusy){Text(u.displayName+" (@"+u.username+")")}
     }
    }
   },
   confirmButton={TextButton(onClick={inviteDialog=false},enabled=!actionBusy){Text("Закрыть")}}
  )
 }
 removeUser?.let{person->
  AlertDialog(onDismissRequest={if(!actionBusy)removeUser=null},
   title={Text(if(person.id==me.id)"Выйти из группы?" else "Удалить участника?")},
   text={Text(person.displayName)},
   confirmButton={TextButton(onClick={
    actionBusy=true
    scope.launch{
     runCatching{withContext(Dispatchers.IO){Api.groupRemove(token,initial.id,person.id)}}
      .onSuccess{removeUser=null;if(person.id==me.id)back() else reload()}
      .onFailure{error="Не удалось изменить состав группы";removeUser=null}
     actionBusy=false
    }
   },enabled=!actionBusy){Text(if(person.id==me.id)"Выйти" else "Удалить")}},
   dismissButton={TextButton(onClick={removeUser=null},enabled=!actionBusy){Text("Отмена")}}
  )
 }
 if(deleteGroupDialog){
  AlertDialog(onDismissRequest={if(!actionBusy)deleteGroupDialog=false},
   title={Text("Удалить группу?")},
   text={Text("Переписка и участники группы будут удалены без возможности восстановления.")},
   confirmButton={TextButton(onClick={
    actionBusy=true
    scope.launch{
     runCatching{withContext(Dispatchers.IO){Api.groupDelete(token,initial.id)}}
      .onSuccess{deleteGroupDialog=false;back()}
      .onFailure{error="Не удалось удалить группу";deleteGroupDialog=false}
     actionBusy=false
    }
   },enabled=!actionBusy){Text("Удалить")}},
   dismissButton={TextButton(onClick={deleteGroupDialog=false},enabled=!actionBusy){Text("Отмена")}}
  )
 }
 LumoBackdrop(Modifier.fillMaxSize()){Scaffold(containerColor=Color.Transparent,topBar={Surface(color=Color(0x882B43A0)){
  Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
   TextButton(onClick=back){Text("‹ Назад")}
   Column(Modifier.weight(1f)){
    Text(detail?.group?.title?:initial.title,fontWeight=FontWeight.Bold)
    Text((detail?.group?.memberCount?:initial.memberCount).toString()+" участников",
     style=MaterialTheme.typography.bodySmall)
   }
   if(groupSearchEnabled){
    TextButton(onClick={
     showSearch=!showSearch
     searchText=""
     searchResults=emptyList()
     searchPerformed=false
     searchError=""
    }){Text(if(showSearch)"×" else "⌕",color=LumoCyan)}
   }
   if(detail?.group?.role in listOf("owner","admin"))
    TextButton(onClick={inviteDialog=true}){Text("+ Люди")}
  }
 }}){pad->
  Column(Modifier.fillMaxSize().padding(pad)){
   if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,
    modifier=Modifier.padding(10.dp))
   if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
   detail?.let{g->
    var showMembers by remember{mutableStateOf(false)}
    TextButton(onClick={showMembers=!showMembers}){
     Text(if(showMembers)"Скрыть участников" else "Участники и управление")}
    if(showMembers){
     Column(Modifier.fillMaxWidth().padding(horizontal=12.dp)){
      g.members.forEach{m->
       Row(verticalAlignment=Alignment.CenterVertically){
        Text(m.displayName+" · "+m.role,modifier=Modifier.weight(1f),
         style=MaterialTheme.typography.bodySmall)
        if(g.group.role=="owner" && m.id!=me.id){
         TextButton(onClick={
          actionBusy=true
          scope.launch{
           runCatching{withContext(Dispatchers.IO){
            Api.groupRole(token,initial.id,m.id,if(m.role=="admin")"member" else "admin")
           }}.onSuccess{reload()}.onFailure{error="Не удалось изменить роль"}
           actionBusy=false
          }
         },enabled=!actionBusy){Text(if(m.role=="admin")"Снять админа" else "Админ")}
        }
        if((g.group.role=="owner"&&m.id!=me.id) ||
          (g.group.role=="admin"&&m.role=="member"&&m.id!=me.id))
         TextButton(onClick={removeUser=m},enabled=!actionBusy){Text("×")}
       }
      }
      if(g.group.role=="owner")TextButton(onClick={deleteGroupDialog=true}){Text("Удалить группу")}
      else TextButton(onClick={
       val myself=g.members.firstOrNull{it.id==me.id}
       if(myself!=null)removeUser=myself
      }){Text("Выйти из группы")}
     }
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
        searchResults=emptyList()
        searchPerformed=false
        searchError=""
       },
       placeholder="Найти в группе",
       modifier=Modifier.weight(1f)
      )
      Spacer(Modifier.width(6.dp))
      TextButton(
       enabled=!searchBusy&&searchText.trim().length in 2..100,
       onClick={
        searchBusy=true;searchError=""
        scope.launch{
         runCatching{withContext(Dispatchers.IO){
          Api.groupSearch(token,initial.id,searchText)
         }}.onSuccess{
          searchResults=it
          searchPerformed=true
         }.onFailure{
          searchError="Поиск временно недоступен"
         }
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
      if(searchResults.isEmpty())"Совпадений не найдено" else "Найдено: "+searchResults.size,
      style=MaterialTheme.typography.labelMedium,
      color=MaterialTheme.colorScheme.onSurfaceVariant
     )
    }
   }
   val visibleHistory=if(showSearch&&searchPerformed)searchResults else history
   LazyColumn(
    state=listState,
    modifier=Modifier.fillMaxWidth().weight(1f),
    contentPadding=PaddingValues(12.dp),
    verticalArrangement=Arrangement.spacedBy(10.dp)
   ){
    items(visibleHistory,key={it.id}){m->
     Row(Modifier.fillMaxWidth(),horizontalArrangement=
      if(m.from==me.id)Arrangement.End else Arrangement.Start){
      Surface(
       shape=RoundedCornerShape(20.dp),color=Color.Transparent,
       modifier=Modifier.widthIn(max=300.dp).lumoBubble(m.from==me.id)
        .clickable{replyTarget=m}
      ){
       Column(Modifier.padding(12.dp)){
        if(m.from!=me.id){
         Text(detail?.members?.firstOrNull{it.id==m.from}?.displayName?:"Участник",
          style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
        }
        if(m.replyToMessageId.isNotBlank()){
         Box(Modifier.fillMaxWidth().lumoGlass(13).padding(8.dp)){
          Column{
           val replyName=when{
            m.replyPreviewFrom==me.id->"Вы"
            m.replyPreviewFrom.isNotBlank()->
             detail?.members?.firstOrNull{it.id==m.replyPreviewFrom}?.displayName?:"Участник"
            else->"Более ранняя история"
           }
           Text(replyName,color=LumoCyan,
            style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.SemiBold)
           Text(
            m.replyPreviewText.ifBlank{"Исходное сообщение недоступно для этой истории"},
            color=Color.White.copy(alpha=.82f),
            style=MaterialTheme.typography.bodySmall,
            maxLines=2
           )
          }
         }
         Spacer(Modifier.height(7.dp))
        }
        Text(m.text,color=Color.White)
        Text(formatMessageTime(m.createdAt),style=MaterialTheme.typography.labelSmall,
         modifier=Modifier.align(Alignment.End))
       }
      }
     }
    }
   }
   Surface(color=Color.Transparent){
    val replyPreview=pending?.takeIf{it.replyToMessageId.isNotBlank()}?.let{
     Triple(it.replyToMessageId,it.replyPreviewText,it.replyPreviewFrom)
    } ?: replyTarget?.let{Triple(it.id,it.text,it.from)}
    if(replyPreview!=null){
     Row(
      Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=3.dp)
       .lumoGlass(16).padding(horizontal=10.dp,vertical=6.dp),
      verticalAlignment=Alignment.CenterVertically
     ){
      Column(Modifier.weight(1f)){
       Text("Ответ на сообщение",color=LumoCyan,
        style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold)
       Text(replyPreview.second.ifBlank{"Сообщение"},maxLines=2,
        style=MaterialTheme.typography.bodySmall,color=Color.White.copy(alpha=.82f))
      }
      if(pending==null)TextButton(onClick={replyTarget=null}){Text("×")}
     }
    }
    if(pending!=null){
     Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
      Text("Сообщение ожидает подтверждения",modifier=Modifier.weight(1f),
       style=MaterialTheme.typography.bodySmall)
      TextButton(onClick={savePending(null);input="";replyTarget=null},enabled=!sending){Text("Не повторять")}
     }
    }
    Row(Modifier.fillMaxWidth().imePadding().padding(8.dp).lumoGlass(23).padding(6.dp),verticalAlignment=Alignment.Bottom){
     OutlinedTextField(input,{input=it.take(4000)},enabled=pending==null,modifier=Modifier.weight(1f),
      label={Text("Сообщение группе")},maxLines=4)
     Spacer(Modifier.width(8.dp))
     val target=replyTarget
     val fallbackExtra=if(target!=null&&!groupLinkedReplies)
      ("↪ "+target.text.replace("\n"," ").take(120)+"\n").length else 0
     Button(onClick={
      val item=pending?:run{
       val selected=replyTarget
       val linked=groupLinkedReplies&&selected!=null
       val quote=if(!linked&&selected!=null)
        "↪ "+selected.text.replace("\n"," ").take(120)+"\n" else ""
       LumoGroupPending(
        clientId=java.util.UUID.randomUUID().toString(),
        text=quote+input.trim(),
        replyToMessageId=if(linked)selected?.id.orEmpty() else "",
        replyPreviewText=if(linked)selected?.text?.take(240).orEmpty() else "",
        replyPreviewFrom=if(linked)selected?.from.orEmpty() else ""
       )
      }
      savePending(item)
      sending=true
      scope.launch{
       runCatching{withContext(Dispatchers.IO){Api.groupSend(token,initial.id,item)}}
        .onSuccess{m->
         if(pending?.clientId==item.clientId){savePending(null);input=""}
         history=(history.filterNot{it.id==m.id}+m).sortedBy{it.createdAt}
         replyTarget=null
         error=""
        }
        .onFailure{error="Не удалось отправить сообщение; повторите с тем же идентификатором"}
       sending=false
      }
     },enabled=!sending&&!loading&&(
       pending!=null || (input.trim().isNotEmpty()&&input.trim().length+fallbackExtra<=4000)
      )){
      Text(if(pending==null)"➤" else "↻")
     }
    }
   }
  }
 }}
}
