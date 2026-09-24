package app.lumo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class LumoCall(
    val id:String,
    val callerId:String,
    val calleeId:String,
    val kind:String,
    val status:String,
    val expiresAt:String
)
data class LumoSignal(
    val seq:Int,
    val from:String,
    val type:String,
    val payload:JSONObject
)
data class LumoIceServer(
    val urls:List<String>,
    val username:String,
    val credential:String
)
data class LumoCallServiceCaps(
    val callsReady:Boolean?,
    val turnReady:Boolean?
)

class CallsUnavailableException:Exception("Call signaling is disabled")
class CallsApiException(val statusCode:Int,val code:String):Exception(code)

class LumoCallApi {
    private val uuid=Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )
    private val jsonMedia="application/json; charset=utf-8".toMediaType()

    private fun request(
        token:String,method:String,path:String,body:JSONObject?=null
    ):String{
        require(path.startsWith("/api/calls")){"Unexpected call endpoint"}
        val builder=Request.Builder()
            .url(Api.HTTP+path)
            .header("Authorization","Bearer "+token)
            .header("Cache-Control","no-store")
        val request=when(method){
            "POST"->builder.post(
                (body?:JSONObject()).toString().toRequestBody(jsonMedia)
            ).build()
            "GET"->builder.get().build()
            else->error("Unsupported method")
        }
        Api.httpClient.newCall(request).execute().use{response->
            val text=response.body?.string().orEmpty()
            if(response.code==401)throw SessionExpiredException()
            if(!response.isSuccessful){
                val code=runCatching{JSONObject(text).optString("error")}.getOrDefault("")
                if(response.code==404 || code=="feature_unavailable")
                    throw CallsUnavailableException()
                throw CallsApiException(response.code,code.ifBlank{"http_error"})
            }
            return text
        }
    }

    private fun parse(obj:JSONObject)=LumoCall(
        id=obj.getString("id"),
        callerId=obj.getString("callerId"),
        calleeId=obj.getString("calleeId"),
        kind=obj.getString("kind"),
        status=obj.getString("status"),
        expiresAt=obj.getString("expiresAt")
    )

    fun capabilities():LumoCallServiceCaps{
        val request=Request.Builder()
            .url(Api.HTTP+"/api/capabilities")
            .header("Cache-Control","no-store")
            .get().build()
        Api.httpClient.newCall(request).execute().use{response->
            if(!response.isSuccessful)return LumoCallServiceCaps(null,null)
            val obj=runCatching{JSONObject(response.body?.string().orEmpty())}.getOrNull()
                ?:return LumoCallServiceCaps(null,null)
            return LumoCallServiceCaps(
                if(obj.has("callsReady"))obj.optBoolean("callsReady") else null,
                if(obj.has("turnReady"))obj.optBoolean("turnReady") else null
            )
        }
    }

    fun list(token:String):List<LumoCall>{
        val a=JSONArray(request(token,"GET","/api/calls"))
        return (0 until a.length()).map{parse(a.getJSONObject(it))}
    }

    fun invite(token:String,to:String,kind:String):LumoCall{
        require(uuid.matches(to) && kind in listOf("audio","video"))
        return parse(JSONObject(request(
            token,"POST","/api/calls",
            JSONObject().put("to",to).put("kind",kind)
        )))
    }

    fun respond(token:String,id:String,action:String):LumoCall{
        require(uuid.matches(id)&&action in listOf("accept","decline","end"))
        return parse(JSONObject(request(
            token,"POST","/api/calls/$id/respond",
            JSONObject().put("action",action)
        )))
    }

    // Media controls call these only after explicit user action. The call list
    // itself never opens a microphone/camera or starts SDP/ICE automatically.
    fun sendSignal(
        token:String,id:String,clientSignalId:String,type:String,payload:JSONObject
    ):Int{
        require(uuid.matches(id)&&uuid.matches(clientSignalId))
        require(type in listOf("offer","answer","ice"))
        val body=JSONObject()
            .put("clientSignalId",clientSignalId)
            .put("type",type)
            .put("payload",payload)
        return JSONObject(request(token,"POST","/api/calls/$id/signals",body))
            .getInt("seq")
    }

    fun signals(token:String,id:String,after:Int):List<LumoSignal>{
        require(uuid.matches(id)&&after>=0)
        val url=(Api.HTTP+"/api/calls/$id/signals").toHttpUrl().newBuilder()
            .addQueryParameter("after",after.toString()).build()
        val path=url.toString().removePrefix(Api.HTTP)
        val a=JSONObject(request(token,"GET",path)).getJSONArray("signals")
        return (0 until a.length()).map{
            val o=a.getJSONObject(it)
            LumoSignal(
                o.getInt("seq"),o.getString("from"),
                o.getString("type"),o.getJSONObject("payload")
            )
        }
    }

    fun signalHistory(token:String,id:String):List<LumoSignal>{
        require(uuid.matches(id))
        val all=ArrayList<LumoSignal>(32)
        var cursor=0
        while(all.size<150){
            val page=signals(token,id,cursor)
            if(page.isEmpty())break
            all.addAll(page)
            val next=page.last().seq
            if(next<=cursor)break
            cursor=next
        }
        return all
    }

    fun iceConfig(token:String,id:String):List<LumoIceServer>{
        require(uuid.matches(id))
        val root=JSONObject(request(token,"GET","/api/calls/$id/ice-config"))
        val a=root.getJSONArray("iceServers")
        require(a.length() in 1..3){"ICE relay is not configured"}
        return (0 until a.length()).map{i->
            val o=a.getJSONObject(i)
            val urls=o.getJSONArray("urls")
            LumoIceServer(
                (0 until urls.length()).map{urls.getString(it)},
                o.getString("username"),
                o.getString("credential")
            )
        }
    }
}

val Api.callClient:LumoCallApi
    get()=LumoCallApi()

private fun callLabel(status:String)=when(status){
    "ringing"->"Ожидает ответа"
    "accepted"->"Принято"
    "declined"->"Отклонено"
    "ended"->"Завершено"
    "expired"->"Истекло"
    else->status
}

private fun callErrorText(error:Throwable)=when(error){
    is CallsUnavailableException->"Сервер звонков пока выключен."
    is SessionExpiredException->"Сессия истекла. Войдите заново."
    is CallsApiException->when(error.code){
        "call_busy"->"Один из участников уже занят."
        "user_blocked"->"Звонок недоступен для этой переписки."
        "call_rate_limited"->"Слишком много приглашений. Повтори позже."
        "recipient_not_found"->"Пользователь больше не найден."
        "invalid_call_state","call_inactive"->"Этот вызов уже завершён."
        else->"Ошибка звонков ("+error.statusCode+")."
    }
    else->"Не удалось подключиться к сервису звонков."
}

/**
 * Staging call surface. Signaling is authenticated; microphone/camera capture
 * remains opt-in inside the separate audio/video controls after acceptance.
 */
@Composable
fun LumoCallsLab(
    token:String,
    me:User,
    initialQuery:String="",
    initialAutoKind:String?=null,
    back:()->Unit
){
    val scope=rememberCoroutineScope()
    var supported by remember(token){mutableStateOf<Boolean?>(null)}
    var serviceCaps by remember(token){mutableStateOf<LumoCallServiceCaps?>(null)}
    var calls by remember(token){mutableStateOf<List<LumoCall>>(emptyList())}
    var people by remember(token){mutableStateOf<List<User>>(emptyList())}
    var error by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf<String?>(null)}
    var query by remember(initialQuery){mutableStateOf(initialQuery)}
    var refresh by remember{mutableIntStateOf(0)}
    var activeMediaId by remember(token){mutableStateOf<String?>(null)}
    var autoStarted by remember(initialQuery,initialAutoKind){mutableStateOf(false)}

    LaunchedEffect(token,refresh){
        serviceCaps=runCatching{
            withContext(Dispatchers.IO){Api.callClient.capabilities()}
        }.getOrNull()
        if(serviceCaps?.callsReady==false){
            supported=false
            error=""
            return@LaunchedEffect
        }
        while(true){
            val result=runCatching{
                withContext(Dispatchers.IO){Api.callClient.list(token)}
            }
            result.onSuccess{
                supported=true
                calls=it
                error=""
            }.onFailure{
                if(it is CallsUnavailableException){
                    supported=false
                    error=""
                }else{
                    if(supported==null)supported=true
                    error=callErrorText(it)
                }
            }
            if(supported==false)break
            delay(4_000)
        }
    }

    LaunchedEffect(token,supported,query){
        if(supported==true){
            delay(250)
            runCatching{
                withContext(Dispatchers.IO){Api.users(token,query.trim())}
            }.onSuccess{people=it.filter{u->u.id!=me.id}}
             .onFailure{error=callErrorText(it)}
        }
    }

    // A tap on the chat header's audio/video button is an explicit call action.
    // After the user list resolves the exact peer, place that invitation once.
    LaunchedEffect(token,supported,initialQuery,initialAutoKind,people){
        if(
            supported==true &&
            !autoStarted &&
            initialAutoKind in listOf("audio","video") &&
            initialQuery.isNotBlank()
        ){
            val person=people.firstOrNull{
                it.username.equals(initialQuery.trim(),ignoreCase=true)
            }
            if(person!=null){
                autoStarted=true
                busy="auto-"+person.id
                error=""
                runCatching{
                    withContext(Dispatchers.IO){
                        Api.callClient.invite(token,person.id,requireNotNull(initialAutoKind))
                    }
                }.onSuccess{updated->
                    calls=(listOf(updated)+calls.filterNot{it.id==updated.id})
                }.onFailure{error=callErrorText(it)}
                busy=null
            }
        }
    }

    fun perform(key:String,block:()->LumoCall){
        if(busy!=null)return
        busy=key
        error=""
        scope.launch{
            runCatching{withContext(Dispatchers.IO){block()}}
                .onSuccess{updated->
                    calls=(listOf(updated)+calls.filterNot{it.id==updated.id})
                }
                .onFailure{error=callErrorText(it)}
            busy=null
        }
    }

    LumoBackdrop(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().statusBarsPadding()){
            Row(
                Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp)
                    .lumoGlass(24).padding(horizontal=8.dp,vertical=6.dp),
                verticalAlignment=Alignment.CenterVertically
            ){
                TextButton(onClick=back){Text("‹",color=Color.White,
                    style=MaterialTheme.typography.headlineSmall)}
                Column(Modifier.weight(1f)){
                    Text("Звонки Lumo",color=Color.White,
                        style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("Приватные аудио- и видеозвонки",
                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                        style=MaterialTheme.typography.labelMedium)
                }
            }

            Box(
                Modifier.fillMaxWidth().padding(12.dp).lumoGlass(22).padding(14.dp)
            ){
                Text(
                    "После принятия вызова каждый участник отдельно включает аудио или видео. " +
                        "Экран показывает реальное состояние WebRTC и время соединения. Камера и микрофон не запускаются автоматически; " +
                        "медиатрафик остаётся в TURN-only режиме, а при сворачивании захват останавливается.",
                    color=Color.White,style=MaterialTheme.typography.bodySmall
                )
            }

            serviceCaps?.let{caps->
                if(caps.callsReady==true){
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)
                            .lumoGlass(18).padding(11.dp)
                    ){
                        Text(
                            if(caps.turnReady==true)
                                "Сервер звонков готов: signaling и приватный TURN доступны."
                            else if(caps.turnReady==false)
                                "Приглашения звонков доступны, но приватный TURN ещё не настроен. Аудио и видео не запустятся до настройки TURN."
                            else
                                "Сигнализация звонков доступна. Сервер пока не сообщает состояние TURN.",
                            color=if(caps.turnReady==true)LumoCyan else Color.White,
                            style=MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            when(supported){
                null->{
                    LinearProgressIndicator(Modifier.fillMaxWidth(),color=LumoCyan)
                }
                false->{
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp).lumoGlass(24).padding(18.dp),
                        horizontalAlignment=Alignment.CenterHorizontally
                    ){
                        Text("Сервис звонков на этом сервере выключен.",
                            color=Color.White)
                        Spacer(Modifier.height(10.dp))
                        LumoNeonButton("Проверить снова",onClick={refresh++})
                    }
                }
                true->{
                    if(error.isNotBlank())Text(
                        error,color=Color(0xFFFFD6E5),
                        modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp)
                    )
                    val active=calls.filter{it.status in listOf("ringing","accepted")}
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
                        verticalArrangement=Arrangement.spacedBy(10.dp)
                    ){
                        item{
                            Text("Активные",color=Color.White,
                                style=MaterialTheme.typography.titleMedium,
                                fontWeight=FontWeight.Bold)
                        }
                        if(active.isEmpty()){
                            item{
                                Box(
                                    Modifier.fillMaxWidth().lumoGlass(22).padding(14.dp)
                                ){
                                    Text("Активных приглашений нет.",
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        items(active,key={it.id}){call->
                            val incoming=call.calleeId==me.id
                            val otherId=if(incoming)call.callerId else call.calleeId
                            val person=people.firstOrNull{it.id==otherId}
                            Column(
                                Modifier.fillMaxWidth().lumoGlass(23).padding(14.dp)
                            ){
                                Row(verticalAlignment=Alignment.CenterVertically){
                                    LumoNeonAvatar(person?.displayName?:"L",size=44.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)){
                                        Text(
                                            person?.displayName ?: "Пользователь",
                                            color=Color.White,fontWeight=FontWeight.Bold
                                        )
                                        Text(
                                            (if(incoming)"Входящий" else "Исходящий")+
                                                " · "+if(call.kind=="video")"видео" else "аудио",
                                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                                            style=MaterialTheme.typography.bodySmall
                                        )
                                        Text(callLabel(call.status),color=LumoCyan,
                                            style=MaterialTheme.typography.labelMedium)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    if(incoming&&call.status=="ringing"){
                                        LumoNeonButton(
                                            "Принять",
                                            onClick={perform(call.id+"a"){
                                                Api.callClient.respond(token,call.id,"accept")
                                            }},
                                            enabled=busy==null,
                                            modifier=Modifier.weight(1f)
                                        )
                                        OutlinedButton(
                                            onClick={perform(call.id+"d"){
                                                Api.callClient.respond(token,call.id,"decline")
                                            }},
                                            enabled=busy==null,
                                            modifier=Modifier.weight(1f),
                                            shape=RoundedCornerShape(22.dp)
                                        ){Text("Отклонить")}
                                    }else{
                                        OutlinedButton(
                                            onClick={perform(call.id+"e"){
                                                Api.callClient.respond(token,call.id,"end")
                                            }},
                                            enabled=busy==null,
                                            modifier=Modifier.fillMaxWidth(),
                                            shape=RoundedCornerShape(22.dp)
                                        ){Text("Завершить")}
                                    }
                                }
                                if(call.kind=="audio" && call.status=="accepted"){
                                    Spacer(Modifier.height(10.dp))
                                    AudioPrototypeControls(
                                        token=token,
                                        me=me,
                                        call=call,
                                        activeAudioId=activeMediaId,
                                        onStart={id->activeMediaId=id},
                                        onStop={id->if(activeMediaId==id)activeMediaId=null}
                                    )
                                }else if(call.kind=="video" && call.status=="accepted"){
                                    Spacer(Modifier.height(10.dp))
                                    VideoPrototypeControls(
                                        token=token,
                                        me=me,
                                        call=call,
                                        activeMediaId=activeMediaId,
                                        onStart={id->activeMediaId=id},
                                        onStop={id->if(activeMediaId==id)activeMediaId=null}
                                    )
                                }
                            }
                        }

                        item{
                            Spacer(Modifier.height(5.dp))
                            Text("Новый вызов",color=Color.White,
                                style=MaterialTheme.typography.titleMedium,
                                fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(7.dp))
                            LumoSearchField(
                                query,{query=it},"Найти человека",
                                modifier=Modifier.fillMaxWidth()
                            )
                        }
                        items(people.take(40),key={it.id}){person->
                            Row(
                                Modifier.fillMaxWidth().lumoGlass(22).padding(12.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ){
                                LumoNeonAvatar(person.displayName,size=42.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)){
                                    Text(person.displayName,color=Color.White,
                                        fontWeight=FontWeight.SemiBold)
                                    Text("@"+person.username,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                                        style=MaterialTheme.typography.bodySmall)
                                }
                                TextButton(
                                    onClick={perform(person.id+"audio"){
                                        Api.callClient.invite(token,person.id,"audio")
                                    }},
                                    enabled=busy==null
                                ){Text("Аудио",color=LumoCyan)}
                                TextButton(
                                    onClick={perform(person.id+"video"){
                                        Api.callClient.invite(token,person.id,"video")
                                    }},
                                    enabled=busy==null
                                ){Text("Видео",color=LumoPink)}
                            }
                        }
                    }
                }
            }
        }
    }
}
