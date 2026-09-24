package app.lumo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class LumoServerCapabilitySnapshot(
    val groupsReady:Boolean,
    val reactionsReady:Boolean,
    val mediaStorageReady:Boolean,
    val mediaUploadsReady:Boolean,
    val groupAttachmentsReady:Boolean,
    val callsReady:Boolean,
    val turnReady:Boolean,
    val pushRegistrationReady:Boolean
)

private object LumoServerCapabilityApi {
    fun load():LumoServerCapabilitySnapshot {
        val request=Request.Builder()
            .url(Api.HTTP+"/api/capabilities")
            .header("Cache-Control","no-store")
            .get()
            .build()
        Api.httpClient.newCall(request).execute().use { response ->
            if(!response.isSuccessful)error("HTTP "+response.code)
            val root=JSONObject(response.body?.string().orEmpty())
            val mediaReady=root.optBoolean("mediaReady",false)
            return LumoServerCapabilitySnapshot(
                groupsReady=root.optBoolean("groupsReady",false),
                reactionsReady=root.optBoolean("groupReactions",false),
                mediaStorageReady=if(root.has("mediaStorageReady"))
                    root.optBoolean("mediaStorageReady",false) else mediaReady,
                mediaUploadsReady=if(root.has("mediaUploadsEnabled"))
                    root.optBoolean("mediaUploadsEnabled",false) else mediaReady,
                groupAttachmentsReady=root.optBoolean("groupAttachments",false),
                callsReady=root.optBoolean("callsReady",false),
                turnReady=root.optBoolean("turnReady",false),
                pushRegistrationReady=root.optBoolean("pushRegistration",false)
            )
        }
    }
}

@Composable
private fun LumoServerStatusRow(
    title:String,
    ready:Boolean,
    readyText:String="Готово",
    missingText:String="Не настроено"
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical=5.dp),
        horizontalArrangement=Arrangement.SpaceBetween
    ){
        Text(
            title,
            modifier=Modifier.weight(1f),
            color=Color.White,
            style=MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if(ready)readyText else missingText,
            color=if(ready)LumoCyan else MaterialTheme.colorScheme.onSurfaceVariant,
            style=MaterialTheme.typography.labelMedium,
            fontWeight=FontWeight.SemiBold
        )
    }
}

@Composable
fun LumoServerStatusCard() {
    var snapshot by remember{mutableStateOf<LumoServerCapabilitySnapshot?>(null)}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf("")}
    var retry by remember{mutableIntStateOf(0)}

    LaunchedEffect(retry){
        loading=true
        error=""
        runCatching{
            withContext(Dispatchers.IO){LumoServerCapabilityApi.load()}
        }.onSuccess{snapshot=it}
         .onFailure{error="Не удалось получить состояние сервера"}
        loading=false
    }

    Column(Modifier.fillMaxWidth().lumoGlass(25).padding(17.dp)){
        Text(
            "Состояние сервера",
            style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.Bold,
            color=Color.White
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "Показывает, какие функции доступны прямо сейчас.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        if(loading){
            LinearProgressIndicator(Modifier.fillMaxWidth(),color=LumoCyan)
        }else{
            snapshot?.let{caps->
                LumoServerStatusRow("Группы",caps.groupsReady)
                LumoServerStatusRow("Реакции на сообщения",caps.reactionsReady)
                LumoServerStatusRow("Медиа-хранилище",caps.mediaStorageReady)
                LumoServerStatusRow(
                    "Фото, видео, голосовые, документы",
                    caps.mediaUploadsReady,
                    missingText=if(caps.mediaStorageReady)"Загрузка выключена" else "Нет хранилища"
                )
                LumoServerStatusRow(
                    "Вложения в группах",
                    caps.groupAttachmentsReady,
                    missingText=if(caps.mediaUploadsReady)"Недоступно" else "Ждёт медиа"
                )
                LumoServerStatusRow("Приглашения звонков",caps.callsReady)
                LumoServerStatusRow(
                    "Приватный TURN для аудио/видео",
                    caps.turnReady,
                    missingText="TURN не настроен"
                )
                LumoServerStatusRow(
                    "Push-регистрация",
                    caps.pushRegistrationReady,
                    missingText="Недоступно"
                )
                if(caps.callsReady && !caps.turnReady){
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Приглашения звонков могут работать, но звук и видео не запускаются без приватного TURN.",
                        style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if(error.isNotBlank()){
                Text(error,color=MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick={retry++},enabled=!loading){
            Text("Обновить статус",color=LumoCyan)
        }
    }
}
