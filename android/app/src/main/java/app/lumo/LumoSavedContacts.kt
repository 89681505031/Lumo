package app.lumo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class LumoContactMatch(
    val localName:String,
    val contactHash:String,
    val user:User
)

private fun sha256Phone(value:String):String=
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it)}

private fun defaultPhoneRegion(context:Context):String{
    val tm=context.getSystemService(TelephonyManager::class.java)
    return sequenceOf(tm?.simCountryIso,tm?.networkCountryIso,Locale.getDefault().country)
        .map{it.orEmpty().trim().uppercase(Locale.ROOT)}
        .firstOrNull{it.length==2}
        ?:"ZZ"
}

private fun readContactFingerprints(context:Context):Map<String,String>{
    if(ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CONTACTS)
        !=PackageManager.PERMISSION_GRANTED)return emptyMap()
    val phoneUtil=PhoneNumberUtil.getInstance()
    val region=defaultPhoneRegion(context)
    val result=LinkedHashMap<String,String>()
    val projection=arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
    )
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection,null,null,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" COLLATE NOCASE ASC"
    )?.use{cursor->
        val nameIndex=cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex=cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val normalizedIndex=cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
        while(cursor.moveToNext() && result.size<5000){
            val name=cursor.getString(nameIndex)?.trim().orEmpty()
            val normalized=cursor.getString(normalizedIndex)?.trim().orEmpty()
            val raw=if(normalized.isNotBlank())normalized
                else cursor.getString(numberIndex)?.trim().orEmpty()
            if(raw.isBlank())continue
            val parsed=runCatching{
                phoneUtil.parse(raw,if(raw.startsWith("+"))"ZZ" else region)
            }.getOrNull()?:continue
            if(!phoneUtil.isValidNumber(parsed))continue
            val e164=phoneUtil.format(parsed,PhoneNumberUtil.PhoneNumberFormat.E164)
            result.putIfAbsent(sha256Phone(e164),name.ifBlank{"Контакт"})
        }
    }
    return result
}

private fun contactUser(o:JSONObject)=User(
    id=o.getString("id"),
    username=o.getString("username"),
    displayName=o.getString("displayName"),
    bio=if(o.isNull("bio"))"" else o.optString("bio"),
    bioSupported=o.has("bio"),
    hasAvatar=o.optBoolean("hasAvatar",false),
    avatarVersion=if(o.isNull("avatarVersion"))"" else o.optString("avatarVersion")
)

private fun discoverContactBatch(token:String,hashes:List<String>):List<Pair<String,User>>{
    if(hashes.isEmpty())return emptyList()
    val payload=JSONObject().put("hashes",JSONArray(hashes))
    val request=Request.Builder().url(Api.HTTP+"/api/contacts/discover")
        .header("Authorization","Bearer "+token)
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()
    Api.httpClient.newCall(request).execute().use{response->
        val raw=response.body?.string().orEmpty()
        if(response.code==401)throw SessionExpiredException()
        if(!response.isSuccessful)error("Контакты: HTTP "+response.code)
        val array=JSONArray(raw)
        return (0 until array.length()).map{index->
            val item=array.getJSONObject(index)
            item.getString("contactHash") to contactUser(item.getJSONObject("user"))
        }
    }
}

private fun loadRegisteredContacts(context:Context,token:String):List<LumoContactMatch>{
    val local=readContactFingerprints(context)
    if(local.isEmpty())return emptyList()
    val matched=ArrayList<LumoContactMatch>()
    for(batch in local.keys.chunked(500)){
        for((hash,user) in discoverContactBatch(token,batch)){
            matched+=LumoContactMatch(local[hash]?:user.displayName,hash,user)
        }
    }
    return matched.distinctBy{it.user.id}
        .sortedBy{it.localName.lowercase(Locale.getDefault())}
}

@Composable
fun SavedContactsPeople(token:String,open:(User)->Unit){
    val context=LocalContext.current
    var permissionGranted by remember{
        mutableStateOf(
            ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CONTACTS)
                ==PackageManager.PERMISSION_GRANTED
        )
    }
    var requested by remember{mutableStateOf(false)}
    var matches by remember{mutableStateOf<List<LumoContactMatch>>(emptyList())}
    var query by remember{mutableStateOf("")}
    var loading by remember{mutableStateOf(false)}
    var errorText by remember{mutableStateOf("")}
    var retry by remember{mutableIntStateOf(0)}
    val launcher=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ){granted->
        permissionGranted=granted
        requested=true
        if(granted)retry++
    }

    LaunchedEffect(permissionGranted,retry,token){
        if(!permissionGranted)return@LaunchedEffect
        loading=true
        errorText=""
        runCatching{
            withContext(Dispatchers.IO){loadRegisteredContacts(context,token)}
        }.onSuccess{matches=it}
         .onFailure{errorText=it.message?:"Не удалось сопоставить контакты."}
        loading=false
    }

    Column(Modifier.fillMaxSize()){
        if(!permissionGranted){
            Column(
                Modifier.fillMaxWidth().padding(16.dp).lumoGlass(26).padding(20.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ){
                Text("Ваши контакты",style=MaterialTheme.typography.titleLarge,
                    fontWeight=FontWeight.Bold,color=Color.White)
                Spacer(Modifier.height(8.dp))
                Text(
                    if(requested)
                        "Доступ к контактам не разрешён. Без него Lumo не может показать, кто из сохранённых контактов уже зарегистрирован."
                    else
                        "Lumo читает номера из телефонной книги на этом устройстве. Имена контактов не отправляются на сервер.",
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                LumoNeonButton(
                    "Разрешить доступ к контактам",
                    onClick={launcher.launch(Manifest.permission.READ_CONTACTS)},
                    modifier=Modifier.fillMaxWidth()
                )
            }
            return@Column
        }

        LumoSearchField(
            value=query,
            onValueChange={query=it},
            placeholder="Поиск в сохранённых контактах",
            modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp)
        )
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth(),color=LumoCyan)
        if(errorText.isNotBlank()){
            Column(Modifier.fillMaxWidth().padding(16.dp).lumoGlass(22).padding(16.dp)){
                Text(errorText,color=MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick={retry++}){Text("Повторить")}
            }
        }

        val filtered=matches.filter{
            it.localName.contains(query,true) ||
                it.user.displayName.contains(query,true)
        }
        if(!loading&&errorText.isBlank()&&filtered.isEmpty()){
            Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center){
                Text(
                    if(matches.isEmpty())
                        "Среди сохранённых контактов пока нет зарегистрированных пользователей Lumo."
                    else "Совпадений не найдено.",
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }else{
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp)
            ){
                items(filtered,key={it.user.id}){match->
                    Row(
                        Modifier.fillMaxWidth().lumoGlass(22)
                            .clickable{open(match.user)}.padding(12.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        LumoUserAvatar(token,match.user,size=52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)){
                            Text(match.localName,fontWeight=FontWeight.Bold,
                                style=MaterialTheme.typography.titleMedium,
                                color=Color.White,maxLines=1)
                            if(match.user.displayName!=match.localName){
                                Text(
                                    "Lumo: "+match.user.displayName,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant,
                                    style=MaterialTheme.typography.bodySmall,
                                    maxLines=1
                                )
                            }
                        }
                        Text("›",style=MaterialTheme.typography.headlineSmall,color=Color.White)
                    }
                }
            }
        }
    }
}
