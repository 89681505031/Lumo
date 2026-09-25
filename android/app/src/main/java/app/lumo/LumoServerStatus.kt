package app.lumo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

private data class LumoServerSnapshot(
    val backendRevision:String?,
    val healthOk:Boolean,
    val groupsReady:Boolean,
    val reactionsReady:Boolean,
    val callsReady:Boolean,
    val turnReady:Boolean,
    val mediaReady:Boolean,
    val sessionSecurityReady:Boolean,
    val passwordChangeReady:Boolean,
    val userBlockingReady:Boolean
)

private fun fetchLumoServerSnapshot():LumoServerSnapshot {
    val capsRequest=Request.Builder()
        .url(Api.HTTP+"/api/capabilities")
        .header("Cache-Control","no-store")
        .get().build()
    val healthRequest=Request.Builder()
        .url(Api.HTTP+"/health")
        .header("Cache-Control","no-store")
        .get().build()

    val caps=Api.httpClient.newCall(capsRequest).execute().use{response->
        if(!response.isSuccessful)error("capabilities_http_"+response.code)
        JSONObject(response.body?.string().orEmpty())
    }
    val healthOk=Api.httpClient.newCall(healthRequest).execute().use{response->
        if(!response.isSuccessful)return@use false
        runCatching{
            JSONObject(response.body?.string().orEmpty()).optBoolean("ok",false)
        }.getOrDefault(false)
    }

    return LumoServerSnapshot(
        backendRevision=caps.optString("backendRevision").takeIf{it.isNotBlank()},
        healthOk=healthOk,
        groupsReady=caps.optBoolean("groupsReady",false),
        reactionsReady=caps.optBoolean("groupReactions",false),
        callsReady=caps.optBoolean("callsReady",false),
        turnReady=caps.optBoolean("turnReady",false),
        mediaReady=caps.optBoolean("mediaReady",false),
        sessionSecurityReady=caps.optBoolean("sessionRevokeOthers",false),
        passwordChangeReady=caps.optBoolean("passwordChange",false),
        userBlockingReady=caps.optBoolean("userBlocking",false)
    )
}

@Composable
private fun ServerStatusRow(label:String,ready:Boolean){
    Row(
        Modifier.fillMaxWidth().padding(vertical=4.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Text(label,style=MaterialTheme.typography.bodyMedium,color=Color.White)
        Spacer(Modifier.weight(1f))
        Text(
            if(ready)"Готово" else "Ожидает",
            style=MaterialTheme.typography.labelLarge,
            color=if(ready)LumoCyan else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LumoServerStatusCard(){
    var snapshot by remember{mutableStateOf<LumoServerSnapshot?>(null)}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf("")}
    var refresh by remember{mutableIntStateOf(0)}

    LaunchedEffect(refresh){
        loading=true
        error=""
        runCatching{
            withContext(Dispatchers.IO){fetchLumoServerSnapshot()}
        }.onSuccess{snapshot=it}
         .onFailure{error="Не удалось проверить сервер Lumo"}
        loading=false
    }

    Column(Modifier.fillMaxWidth().lumoGlass(25).padding(17.dp)){
        Text(
            "Состояние сервера",
            style=MaterialTheme.typography.titleLarge,
            fontWeight=FontWeight.Bold,
            color=Color.White
        )
        Spacer(Modifier.height(5.dp))
        Text(
            snapshot?.backendRevision?.let{"Backend "+it.take(12)}
                ?: "Backend ещё не сообщает номер сборки",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant
        )
        if(loading){
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(),color=LumoCyan)
        }
        snapshot?.let{s->
            Spacer(Modifier.height(10.dp))
            ServerStatusRow("Сервер и база",s.healthOk)
            ServerStatusRow("Группы",s.groupsReady)
            ServerStatusRow("Реакции",s.reactionsReady)
            ServerStatusRow("Сигнализация звонков",s.callsReady)
            ServerStatusRow("TURN для аудио/видео",s.turnReady)
            ServerStatusRow("Фото, видео и файлы",s.mediaReady)
            ServerStatusRow("Безопасность сессий",s.sessionSecurityReady)
            ServerStatusRow("Смена пароля",s.passwordChangeReady)
            ServerStatusRow("Блокировка контактов",s.userBlockingReady)
        }
        if(error.isNotBlank()){
            Spacer(Modifier.height(8.dp))
            Text(error,style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(10.dp))
        LumoNeonButton(
            if(loading)"Проверяем…" else "Проверить сервер",
            onClick={refresh++},
            modifier=Modifier.fillMaxWidth(),
            enabled=!loading
        )
    }
}
