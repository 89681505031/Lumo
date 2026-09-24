package app.lumo

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class LumoReaction(val messageId:String,val userId:String,val emoji:String)

/**
 * Experimental opt-in reactions. The caller checks capabilities before
 * showing the controls; current production stays text-only.
 */
object LumoReactionApi {
    val choices=listOf("👍","❤️","😂","😮","👏","🚀")
    fun enabled(token:String):Boolean {
        val request=Request.Builder()
            .url(Api.HTTP+"/api/reactions/capabilities")
            .header("Authorization","Bearer "+token).get().build()
        Api.httpClient.newCall(request).execute().use { response ->
            if(response.code==401)throw SessionExpiredException()
            if(!response.isSuccessful)return false
            return JSONObject(response.body?.string().orEmpty()).optBoolean("enabled",false)
        }
    }

    fun list(token:String,peerId:String):List<LumoReaction> {
        val request=Request.Builder()
            .url(Api.HTTP+"/api/reactions/with/"+peerId)
            .header("Authorization","Bearer "+token).get().build()
        Api.httpClient.newCall(request).execute().use { response ->
            if(response.code==401)throw SessionExpiredException()
            if(!response.isSuccessful)error("Не удалось обновить реакции")
            val items=JSONArray(response.body?.string().orEmpty())
            return (0 until items.length()).map { i ->
                items.getJSONObject(i).let {
                    LumoReaction(it.getString("messageId"),it.getString("userId"),it.getString("emoji"))
                }
            }
        }
    }

    fun set(token:String,messageId:String,emoji:String,active:Boolean) {
        require(emoji in choices){"Неподдерживаемая реакция"}
        val requestBody=JSONObject().put("emoji",emoji)
            .toString().toRequestBody("application/json".toMediaType())
        val builder=Request.Builder()
            .url(Api.HTTP+"/api/reactions/"+messageId)
            .header("Authorization","Bearer "+token)
        val request=if(active)builder.put(requestBody).build()
            else builder.delete(requestBody).build()
        Api.httpClient.newCall(request).execute().use { response ->
            if(response.code==401)throw SessionExpiredException()
            if(!response.isSuccessful)error("Не удалось изменить реакцию ("+response.code+")")
        }
    }
}

@Composable
fun LumoReactionBadges(
    entries:List<LumoReaction>,
    meId:String,
    onTap:(emoji:String,add:Boolean)->Unit
){
    if(entries.isEmpty())return
    val groups=entries.groupBy{it.emoji}
    Column {
        groups.keys.chunked(3).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                row.forEach { emoji ->
                    val own=groups[emoji].orEmpty().any{it.userId==meId}
                    Text(
                        "$emoji ${groups[emoji]?.size ?: 0}",
                        modifier=Modifier.lumoGlass(16).clickable{onTap(emoji,!own)}
                            .padding(horizontal=9.dp,vertical=5.dp),
                        style=MaterialTheme.typography.labelMedium,
                        color=if(own)LumoCyan else Color.White
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
        }
    }
}

@Composable
fun LumoMessageOptions(
    message:Msg,
    reactionsEnabled:Boolean,
    aiEnabled:Boolean,
    canEdit:Boolean,
    canDelete:Boolean,
    onDismiss:()->Unit,
    onReply:()->Unit,
    onForward:()->Unit,
    onEdit:()->Unit,
    onDelete:()->Unit,
    onAskAi:()->Unit,
    onReact:(emoji:String)->Unit
) {
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("Действия с сообщением")},
        text={
            Column {
                Text(
                    if(message.text.isBlank())"Вложение" else message.text.take(150),
                    style=MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(13.dp))
                if(message.deletedAt.isBlank()){
                    LumoNeonButton("↩ Ответить",onReply,Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    if(message.text.isNotBlank() || message.attachmentId.isNotBlank()){
                        OutlinedButton(
                            onClick=onForward,shape=RoundedCornerShape(22.dp),
                            modifier=Modifier.fillMaxWidth()
                        ){
                            Text(if(message.attachmentId.isNotBlank())
                                "↗ Переслать вложение" else "↗ Переслать текст")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if(canEdit){
                        OutlinedButton(
                            onClick=onEdit,shape=RoundedCornerShape(22.dp),
                            modifier=Modifier.fillMaxWidth()
                        ){Text("✎ Изменить")}
                        Spacer(Modifier.height(8.dp))
                    }
                    if(canDelete){
                        OutlinedButton(
                            onClick=onDelete,shape=RoundedCornerShape(22.dp),
                            modifier=Modifier.fillMaxWidth()
                        ){Text("Удалить сообщение")}
                        Spacer(Modifier.height(8.dp))
                    }
                    if(aiEnabled && message.text.isNotBlank()){
                        OutlinedButton(
                            onClick=onAskAi,
                            shape=RoundedCornerShape(22.dp),
                            modifier=Modifier.fillMaxWidth()
                        ){Text("✦ Подготовить для Lumo AI")}
                        Text(
                            "Текст не отправится ИИ, пока ты сам не нажмёшь отправить.",
                            style=MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if(reactionsEnabled && message.deletedAt.isBlank()){
                    Text("Реакция",fontWeight=FontWeight.SemiBold,
                        style=MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    LumoReactionApi.choices.chunked(3).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.SpaceEvenly
                        ) {
                            row.forEach {emoji->
                                TextButton(onClick={onReact(emoji)}){
                                    Text(emoji,style=MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton={TextButton(onClick=onDismiss){Text("Закрыть")}}
    )
}

/**
 * Explicitly forwards plain text (never attachments or media URLs) to a
 * chosen recipient using the existing idempotent API. Before making the
 * request, enqueue the exact UUID for the recipient's normal chat retry.
 */
@Composable
fun LumoForwardDialog(
    token:String,me:User,source:Msg,onDismiss:()->Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val prefs=remember(me.id){
        context.getSharedPreferences("lumo_pending",Context.MODE_PRIVATE)
    }
    val forwardText=remember(source.id) {
        ("↪ Пересланное сообщение\n"+source.text.trim()).take(4000)
    }
    val clientId=remember(source.id){UUID.randomUUID().toString()}
    var query by remember{mutableStateOf("")}
    var results by remember{mutableStateOf<List<User>>(emptyList())}
    var fetching by remember{mutableStateOf(false)}
    var chosen by remember{mutableStateOf<User?>(null)}
    var sending by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("")}
    var sent by remember{mutableStateOf(false)}
    LaunchedEffect(token,query,chosen){
        if(chosen!=null)return@LaunchedEffect
        fetching=true
        delay(300)
        runCatching{withContext(Dispatchers.IO){Api.users(token,query.trim())}}
            .onSuccess{results=it;status=""}
            .onFailure{status="Не удалось получить список контактов"}
        fetching=false
    }
    fun attempt(recipient:User){
        if(sending)return
        chosen=recipient
        sending=true
        status="Отправляем…"
        scope.launch {
            if(source.attachmentId.isNotBlank()){
                runCatching{
                    withContext(Dispatchers.IO){
                        forwardExistingAttachment(
                            token=token,
                            assetId=source.attachmentId,
                            to=recipient.id,
                            clientMessageId=clientId,
                            caption=source.text.trim().take(1000)
                        )
                    }
                }.onSuccess{
                    sent=true
                    status="Вложение переслано: "+recipient.displayName
                }.onFailure{
                    status="Не удалось переслать вложение. Можно повторить с тем же ID."
                }
                sending=false
                return@launch
            }
            val key="pending_"+me.id+"_"+recipient.id
            val queued=PendingMessage(clientId,forwardText)
            // Text forwarding keeps the normal destination-chat retry queue.
            val stored=runCatching {
                val old=JSONArray(prefs.getString(key,"[]"))
                var exists=false
                for(i in 0 until old.length()){
                    if(old.optJSONObject(i)?.optString("clientMessageId")==clientId){
                        exists=true;break
                    }
                }
                if(!exists){
                    if(old.length()>=60)error("Очередь сообщений заполнена")
                    old.put(JSONObject().put("clientMessageId",clientId).put("text",forwardText))
                    if(!prefs.edit().putString(key,old.toString()).commit())
                        error("Не удалось сохранить сообщение")
                }
            }
            if(stored.isFailure){
                status=stored.exceptionOrNull()?.message ?: "Не удалось сохранить отправку"
                sending=false
                return@launch
            }
            runCatching{
                withContext(Dispatchers.IO){Api.sendMessage(token,recipient.id,queued)}
            }.onSuccess{
                val current=JSONArray(prefs.getString(key,"[]"))
                val remaining=JSONArray()
                for(i in 0 until current.length()){
                    val item=current.optJSONObject(i)
                    if(item?.optString("clientMessageId")!=clientId)remaining.put(current.get(i))
                }
                prefs.edit().putString(key,remaining.toString()).apply()
                sent=true
                status="Сообщение переслано: "+recipient.displayName
            }.onFailure {
                status="Нет подтверждения. Сохранено для повтора в чате получателя."
            }
            sending=false
        }
    }
    AlertDialog(
        onDismissRequest={if(!sending)onDismiss()},
        title={Text(if(source.attachmentId.isNotBlank())"Переслать вложение" else "Переслать сообщение")},
        text={
            Column {
                Text(
                    if(source.attachmentId.isNotBlank())
                        "Lumo не делает вложение публичным: доступ получит только выбранный получатель через отдельное сообщение."
                    else "Будет отправлен только текст выбранному контакту.",
                    style=MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    if(source.attachmentId.isNotBlank())
                        source.text.ifBlank{"Вложение"}.take(160)
                    else forwardText.take(160),
                    style=MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(13.dp))
                val target=chosen
                if(target==null){
                    LumoSearchField(
                        query,{query=it},"Имя или логин",
                        modifier=Modifier.fillMaxWidth()
                    )
                    if(fetching)LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max=235.dp)){
                        items(results,key={it.id}){user->
                            Row(
                                Modifier.fillMaxWidth().clickable{attempt(user)}
                                    .padding(vertical=9.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ){
                                LumoNeonAvatar(user.displayName,size=38.dp)
                                Spacer(Modifier.width(9.dp))
                                Column {
                                    Text(user.displayName,fontWeight=FontWeight.SemiBold)
                                    Text("@"+user.username,style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }else{
                    Text("Получатель: "+target.displayName,
                        style=MaterialTheme.typography.titleSmall)
                    if(!sending && !sent) {
                        TextButton(onClick={attempt(target)}){
                            Text("Повторить с тем же ID")
                        }
                    }
                }
                if(status.isNotBlank()){
                    Spacer(Modifier.height(10.dp))
                    Text(status,style=MaterialTheme.typography.bodySmall,
                        color=if(sent)LumoCyan else MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        confirmButton={
            TextButton(onClick=onDismiss,enabled=!sending){
                Text(if(sent)"Готово" else "Закрыть")
            }
        }
    )
}
