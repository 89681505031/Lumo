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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LumoAiMessage(
    val id:String=UUID.randomUUID().toString(),
    val role:String,
    val text:String
)

object LumoAiApi {
    fun enabled(token:String):Boolean {
        val request=Request.Builder()
            .url(Api.HTTP+"/api/ai/capabilities")
            .header("Authorization","Bearer "+token)
            .get().build()
        Api.httpClient.newCall(request).execute().use{response->
            if(response.code==401)throw SessionExpiredException()
            if(!response.isSuccessful)return false
            return JSONObject(response.body?.string().orEmpty())
                .optBoolean("enabled",false)
        }
    }

    fun send(
        token:String,
        history:List<LumoAiMessage>,
        message:String
    ):String {
        val body=JSONObject().put("message",message)
        val array=JSONArray()
        history.takeLast(8).forEach{item->
            if(item.role in listOf("user","assistant")&&item.text.isNotBlank()){
                array.put(JSONObject()
                    .put("role",item.role)
                    .put("content",item.text.take(1500)))
            }
        }
        body.put("history",array)
        val request=Request.Builder()
            .url(Api.HTTP+"/api/ai/chat")
            .header("Authorization","Bearer "+token)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Api.httpClient.newCall(request).execute().use{response->
            val raw=response.body?.string().orEmpty()
            if(response.code==401)throw SessionExpiredException()
            if(!response.isSuccessful){
                val code=runCatching{JSONObject(raw).optString("error")}
                    .getOrDefault("")
                error(when(code){
                    "feature_unavailable"->"Lumo AI на этом сервере выключен."
                    "ai_provider_rate_limited"->"ИИ временно перегружен. Попробуй позже."
                    "invalid_ai_message"->"Сообщение слишком длинное или пустое."
                    else->"Lumo AI временно недоступен."
                })
            }
            val reply=JSONObject(raw).optString("reply").trim()
            if(reply.isBlank())error("Lumo AI вернул пустой ответ.")
            return reply.take(8000)
        }
    }
}

@Composable
fun LumoAiScreen(token:String,back:()->Unit){
    val scope=rememberCoroutineScope()
    val messages=remember{mutableStateListOf<LumoAiMessage>()}
    var supported by remember(token){mutableStateOf<Boolean?>(null)}
    var input by remember{mutableStateOf("")}
    var sending by remember{mutableStateOf(false)}
    var errorText by remember{mutableStateOf("")}
    var retry by remember{mutableIntStateOf(0)}

    LaunchedEffect(token,retry){
        supported=runCatching{
            withContext(Dispatchers.IO){LumoAiApi.enabled(token)}
        }.getOrElse{
            errorText="Не удалось проверить Lumo AI."
            false
        }
    }

    LumoBackdrop(Modifier.fillMaxSize()){
        Scaffold(
            containerColor=Color.Transparent,
            topBar={
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding()
                        .padding(horizontal=10.dp,vertical=8.dp)
                        .lumoGlass(24).padding(horizontal=8.dp,vertical=6.dp),
                    verticalAlignment=Alignment.CenterVertically
                ){
                    TextButton(onClick=back){
                        Text("‹",color=Color.White,
                            style=MaterialTheme.typography.headlineSmall)
                    }
                    LumoPlanetIcon(44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)){
                        Text("Lumo AI",color=Color.White,
                            style=MaterialTheme.typography.titleLarge,
                            fontWeight=FontWeight.Bold)
                        Text("Отдельный ИИ-диалог",
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                            style=MaterialTheme.typography.labelMedium)
                    }
                    if(messages.isNotEmpty()){
                        TextButton(
                            enabled=!sending,
                            onClick={messages.clear();errorText=""}
                        ){Text("Очистить",color=Color.White)}
                    }
                }
            }
        ){pad->
            Column(Modifier.fillMaxSize().padding(pad)){
                Box(
                    Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)
                        .lumoGlass(20).padding(12.dp)
                ){
                    Text(
                        "Lumo AI получает только текст, который ты отправляешь в этом экране. " +
                            "Обычные личные и групповые чаты, контакты, файлы, камера и микрофон " +
                            "автоматически ему не передаются.",
                        color=Color.White,
                        style=MaterialTheme.typography.bodySmall
                    )
                }

                when(supported){
                    null->Box(
                        Modifier.fillMaxSize(),
                        contentAlignment=Alignment.Center
                    ){CircularProgressIndicator(color=LumoCyan)}
                    false->Column(
                        Modifier.fillMaxSize().padding(18.dp),
                        verticalArrangement=Arrangement.Center,
                        horizontalAlignment=Alignment.CenterHorizontally
                    ){
                        Box(
                            Modifier.fillMaxWidth().lumoGlass(26).padding(20.dp)
                        ){
                            Column(horizontalAlignment=Alignment.CenterHorizontally){
                                Text(
                                    "Lumo AI на этом сервере пока выключен.",
                                    color=Color.White,
                                    fontWeight=FontWeight.Bold,
                                    style=MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Для тестирования нужен отдельный серверный AI-провайдер; " +
                                        "ключ провайдера не хранится в Android-приложении.",
                                    color=MaterialTheme.colorScheme.onSurfaceVariant,
                                    style=MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(14.dp))
                                LumoNeonButton(
                                    "Проверить снова",
                                    onClick={supported=null;retry++},
                                    modifier=Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    true->{
                        if(messages.isEmpty()){
                            Box(
                                Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment=Alignment.Center
                            ){
                                Column(
                                    Modifier.fillMaxWidth().padding(24.dp)
                                        .lumoGlass(26).padding(22.dp),
                                    horizontalAlignment=Alignment.CenterHorizontally
                                ){
                                    LumoPlanetIcon(82.dp)
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Чем помочь?",
                                        color=Color.White,
                                        style=MaterialTheme.typography.headlineSmall,
                                        fontWeight=FontWeight.Bold
                                    )
                                    Text(
                                        "Спроси что-нибудь, попроси объяснить тему, " +
                                            "написать текст или перевести сообщение.",
                                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                                        style=MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }else{
                            LazyColumn(
                                Modifier.weight(1f).fillMaxWidth(),
                                contentPadding=PaddingValues(
                                    horizontal=13.dp,vertical=12.dp
                                ),
                                verticalArrangement=Arrangement.spacedBy(10.dp)
                            ){
                                items(messages,key={it.id}){item->
                                    val mine=item.role=="user"
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement=if(mine)
                                            Arrangement.End else Arrangement.Start
                                    ){
                                        if(!mine){
                                            LumoPlanetIcon(32.dp)
                                            Spacer(Modifier.width(7.dp))
                                        }
                                        Box(
                                            Modifier.widthIn(max=300.dp)
                                                .lumoBubble(mine)
                                                .padding(horizontal=14.dp,vertical=10.dp)
                                        ){
                                            Text(item.text,color=Color.White)
                                        }
                                    }
                                }
                                if(sending){
                                    item("ai-thinking"){
                                        Row(verticalAlignment=Alignment.CenterVertically){
                                            LumoPlanetIcon(32.dp)
                                            Spacer(Modifier.width(7.dp))
                                            Box(
                                                Modifier.lumoGlass(18)
                                                    .padding(horizontal=13.dp,vertical=9.dp)
                                            ){
                                                Text("Думаю…",color=LumoCyan)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if(errorText.isNotBlank()){
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)
                                    .lumoGlass(16).padding(8.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ){
                                Text(
                                    errorText,
                                    color=Color(0xFFFFD5E4),
                                    style=MaterialTheme.typography.bodySmall,
                                    modifier=Modifier.weight(1f)
                                )
                                TextButton(onClick={errorText=""}){
                                    Text("×",color=Color.White)
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().imePadding()
                                .padding(horizontal=11.dp,vertical=8.dp)
                                .lumoGlass(30).padding(7.dp),
                            verticalAlignment=Alignment.Bottom
                        ){
                            OutlinedTextField(
                                value=input,
                                onValueChange={input=it.take(2000)},
                                placeholder={Text("Сообщение Lumo AI")},
                                modifier=Modifier.weight(1f),
                                maxLines=5,
                                shape=RoundedCornerShape(22.dp)
                            )
                            Spacer(Modifier.width(7.dp))
                            LumoNeonButton(
                                text="➤",
                                enabled=!sending&&input.trim().isNotEmpty(),
                                modifier=Modifier.width(56.dp),
                                onClick={
                                    val prompt=input.trim()
                                    if(prompt.isBlank()||sending)return@LumoNeonButton
                                    val prior=messages.toList()
                                    messages.add(LumoAiMessage(role="user",text=prompt))
                                    input=""
                                    errorText=""
                                    sending=true
                                    scope.launch{
                                        runCatching{
                                            withContext(Dispatchers.IO){
                                                LumoAiApi.send(token,prior,prompt)
                                            }
                                        }.onSuccess{
                                            messages.add(
                                                LumoAiMessage(role="assistant",text=it)
                                            )
                                        }.onFailure{
                                            errorText=it.message
                                                ?:"Lumo AI временно недоступен."
                                        }
                                        sending=false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
