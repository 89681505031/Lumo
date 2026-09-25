package app.lumo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.json.JSONObject

class PhoneProfileRequiredException:Exception("Нужно имя профиля")

object LumoPhoneAuth {
    val configured:Boolean
        get()=BuildConfig.SUPABASE_URL.startsWith("https://") &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    private fun supabaseRequest(path:String,body:JSONObject):JSONObject {
        if(!configured)error("Регистрация по номеру пока не настроена.")
        val request=Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/')+path)
            .header("apikey",BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            .header("Accept","application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Api.httpClient.newCall(request).execute().use{response->
            val raw=response.body?.string().orEmpty()
            if(!response.isSuccessful){
                val message=runCatching{
                    val json=JSONObject(raw)
                    json.optString("msg").ifBlank{json.optString("message")}
                }.getOrDefault("")
                val friendly=when(response.code){
                    429->"Код запрашивали слишком часто. Попробуйте чуть позже."
                    400->if(message.isNotBlank())message else "Проверьте номер или код."
                    else->"Сервис подтверждения номера временно недоступен."
                }
                error(friendly)
            }
            return if(raw.isBlank())JSONObject() else JSONObject(raw)
        }
    }

    fun requestOtp(phone:String){
        supabaseRequest("/auth/v1/otp",JSONObject().put("phone",phone))
    }

    fun verifyOtp(phone:String,code:String):String {
        val response=supabaseRequest(
            "/auth/v1/verify",
            JSONObject().put("phone",phone).put("token",code).put("type","sms")
        )
        return response.optString("access_token")
            .takeIf{it.isNotBlank()}
            ?:error("Supabase не вернул сессию после подтверждения кода.")
    }

    fun exchangeWithLumo(accessToken:String,displayName:String=""):Pair<String,User>{
        val payload=JSONObject().put("accessToken",accessToken)
        if(displayName.isNotBlank())payload.put("displayName",displayName.trim())
        val request=Request.Builder()
            .url(Api.HTTP+"/api/auth/phone/exchange")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Api.httpClient.newCall(request).execute().use{response->
            val raw=response.body?.string().orEmpty()
            val errorCode=runCatching{JSONObject(raw).optString("error")}.getOrDefault("")
            if(response.code==409 && errorCode=="profile_required")
                throw PhoneProfileRequiredException()
            if(!response.isSuccessful){
                error(when(errorCode){
                    "invalid_phone_session"->"Сессия подтверждения номера недействительна."
                    "phone_auth_unavailable"->"Регистрация по номеру ещё не включена на сервере."
                    "phone_identity_conflict"->"Этот номер уже связан с другим аккаунтом Lumo."
                    else->"Не удалось завершить вход по номеру (HTTP "+response.code+")."
                })
            }
            val json=JSONObject(raw)
            val u=json.getJSONObject("user")
            val user=User(
                id=u.getString("id"),
                username=u.getString("username"),
                displayName=u.getString("displayName"),
                bio=if(u.isNull("bio"))"" else u.optString("bio"),
                bioSupported=u.has("bio"),
                hasAvatar=u.optBoolean("hasAvatar",false),
                avatarVersion=if(u.isNull("avatarVersion"))"" else u.optString("avatarVersion")
            )
            return json.getString("token") to user
        }
    }
}

@Composable
fun LumoPhoneRegister(
    onLegacy:()->Unit,
    done:(String,User)->Unit
){
    val scope=rememberCoroutineScope()
    var stage by remember{mutableIntStateOf(0)}
    var phone by remember{mutableStateOf("")}
    var code by remember{mutableStateOf("")}
    var name by remember{mutableStateOf("")}
    var accessToken by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var errorText by remember{mutableStateOf("")}
    val phoneValid=Regex("^\\+[1-9][0-9]{7,14}$").matches(phone.trim())
    val codeValid=Regex("^[0-9]{6,10}$").matches(code.trim())

    LumoBackdrop(Modifier.fillMaxSize()){
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal=24.dp,vertical=30.dp),
            verticalArrangement=Arrangement.Center,
            horizontalAlignment=Alignment.CenterHorizontally
        ){
            LumoPlanetIcon(140.dp)
            Spacer(Modifier.height(15.dp))
            Text("Lumo",style=MaterialTheme.typography.displayLarge,
                fontWeight=FontWeight.ExtraBold,color=Color.White)
            Text(
                when(stage){
                    0->"Вход по номеру телефона"
                    1->"Введите код из SMS"
                    else->"Ваш профиль"
                },
                style=MaterialTheme.typography.titleMedium,color=Color.White
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth().lumoGlass(28).padding(18.dp)){
                when(stage){
                    0->{
                        Text(
                            "Введите номер в международном формате, например +48123456789.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                            style=MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value=phone,
                            onValueChange={
                                phone=it.filter{ch->ch.isDigit()||ch=='+'}.take(16)
                                errorText=""
                            },
                            label={Text("Номер телефона")},
                            singleLine=true,
                            modifier=Modifier.fillMaxWidth(),
                            shape=RoundedCornerShape(20.dp)
                        )
                    }
                    1->{
                        Text("Код отправлен на "+phone,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value=code,
                            onValueChange={code=it.filter(Char::isDigit).take(10);errorText=""},
                            label={Text("Код подтверждения")},
                            singleLine=true,
                            modifier=Modifier.fillMaxWidth(),
                            shape=RoundedCornerShape(20.dp)
                        )
                    }
                    else->{
                        Text(
                            "Номер подтверждён. Укажите имя, которое увидят ваши контакты в Lumo.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                            style=MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value=name,
                            onValueChange={name=it.take(50);errorText=""},
                            label={Text("Имя")},
                            singleLine=true,
                            modifier=Modifier.fillMaxWidth(),
                            shape=RoundedCornerShape(20.dp)
                        )
                    }
                }
                if(errorText.isNotBlank()){
                    Spacer(Modifier.height(8.dp))
                    Text(errorText,color=MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                LumoNeonButton(
                    text=if(busy)"Подключаем…" else when(stage){
                        0->"Получить код"
                        1->"Подтвердить"
                        else->"Продолжить"
                    },
                    enabled=!busy && when(stage){
                        0->phoneValid
                        1->codeValid
                        else->name.trim().isNotBlank()
                    },
                    modifier=Modifier.fillMaxWidth(),
                    onClick={
                        busy=true
                        errorText=""
                        scope.launch{
                            runCatching<Pair<String,User>?>{
                                withContext(Dispatchers.IO){
                                    when(stage){
                                        0->{
                                            LumoPhoneAuth.requestOtp(phone.trim())
                                            null
                                        }
                                        1->{
                                            val verified=LumoPhoneAuth.verifyOtp(phone.trim(),code.trim())
                                            accessToken=verified
                                            LumoPhoneAuth.exchangeWithLumo(verified)
                                        }
                                        else->LumoPhoneAuth.exchangeWithLumo(accessToken,name.trim())
                                    }
                                }
                            }.onSuccess{session->
                                if(stage==0)stage=1
                                else if(session!=null)done(session.first,session.second)
                            }.onFailure{error->
                                if(error is PhoneProfileRequiredException)stage=2
                                else errorText=error.message?:"Не удалось выполнить вход."
                            }
                            busy=false
                        }
                    }
                )
                if(stage==1){
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        enabled=!busy,
                        onClick={
                            busy=true
                            errorText=""
                            scope.launch{
                                runCatching{
                                    withContext(Dispatchers.IO){
                                        LumoPhoneAuth.requestOtp(phone.trim())
                                    }
                                }.onFailure{
                                    errorText=it.message?:"Не удалось отправить код повторно."
                                }
                                busy=false
                            }
                        }
                    ){Text("Отправить код ещё раз",color=LumoCyan)}
                    TextButton(
                        enabled=!busy,
                        onClick={stage=0;code="";errorText=""}
                    ){Text("Изменить номер",color=Color.White)}
                }
                Spacer(Modifier.height(10.dp))
                TextButton(
                    enabled=!busy,
                    onClick=onLegacy,
                    modifier=Modifier.align(Alignment.CenterHorizontally)
                ){Text("Войти старым способом",color=Color.White)}
            }
        }
    }
}
