package app.lumo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val MAX_AVATAR_SOURCE_BYTES=8*1024*1024
private const val MAX_SYNCED_JPEG_BYTES=350*1024
private val avatarEdge=Brush.linearGradient(listOf(LumoCyan,Color.White,LumoPink))

private object LumoAvatarMemory {
    val cache=LruCache<String,Bitmap>(24)
}

private fun avatarFile(context:Context,userId:String):File {
    val hashed=MessageDigest.getInstance("SHA-256")
        .digest(userId.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it.toInt() and 0xff)}
    return File(context.filesDir,"local-profile-$hashed.jpg")
}

private fun decodeBounded(bytes:ByteArray):Bitmap {
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    val w=bounds.outWidth
    val h=bounds.outHeight
    require(w in 1..10000 && h in 1..10000 && w.toLong()*h<=64_000_000L){
        "Фотография слишком большая или повреждена"
    }
    var sample=1
    while(w/sample>1024 || h/sample>1024)sample*=2
    val decoded=BitmapFactory.decodeByteArray(
        bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample}
    )?:error("Не удалось прочитать фотографию")
    val factor=minOf(1f,512f/maxOf(decoded.width,decoded.height).toFloat())
    val finalWidth=maxOf(1,(decoded.width*factor).toInt())
    val finalHeight=maxOf(1,(decoded.height*factor).toInt())
    if(finalWidth==decoded.width && finalHeight==decoded.height)return decoded
    val scaled=Bitmap.createScaledBitmap(decoded,finalWidth,finalHeight,true)
    if(scaled!==decoded)decoded.recycle()
    return scaled
}

private fun boundedJpeg(bitmap:Bitmap):ByteArray {
    var working=bitmap
    try{
        for(quality in listOf(84,74,64,54)){
            val output=ByteArrayOutputStream()
            check(working.compress(Bitmap.CompressFormat.JPEG,quality,output)){
                "Не удалось подготовить фотографию"
            }
            val bytes=output.toByteArray()
            if(bytes.size<=MAX_SYNCED_JPEG_BYTES)return bytes
        }
        val factor=minOf(1f,384f/maxOf(working.width,working.height).toFloat())
        val w=maxOf(1,(working.width*factor).toInt())
        val h=maxOf(1,(working.height*factor).toInt())
        val smaller=if(w==working.width&&h==working.height)working
            else Bitmap.createScaledBitmap(working,w,h,true)
        if(smaller!==working){
            if(working!==bitmap&&!working.isRecycled)working.recycle()
            working=smaller
        }
        val output=ByteArrayOutputStream()
        check(working.compress(Bitmap.CompressFormat.JPEG,58,output)){
            "Не удалось подготовить фотографию"
        }
        return output.toByteArray().also{
            require(it.size<=MAX_SYNCED_JPEG_BYTES){"Фотография слишком сложная для синхронизации"}
        }
    }finally{
        if(working!==bitmap&&!working.isRecycled)working.recycle()
    }
}

private fun saveAvatar(context:Context,userId:String,uri:Uri):File {
    val resolver=context.contentResolver
    val mime=resolver.getType(uri)?.lowercase()
    require(mime in setOf("image/jpeg","image/png","image/webp")){
        "Выбери JPEG, PNG или WebP"
    }
    val bytes=resolver.openInputStream(uri)?.use{source->
        val output=ByteArrayOutputStream()
        val buffer=ByteArray(64*1024)
        var total=0
        while(true){
            val read=source.read(buffer)
            if(read<0)break
            total+=read
            require(total<=MAX_AVATAR_SOURCE_BYTES){"Фотография превышает 8 МБ"}
            output.write(buffer,0,read)
        }
        output.toByteArray()
    }?:error("Не удалось открыть фотографию")
    val bitmap=decodeBounded(bytes)
    val compressed=try{boundedJpeg(bitmap)}finally{if(!bitmap.isRecycled)bitmap.recycle()}
    val target=avatarFile(context,userId)
    val temp=File.createTempFile("lumo-avatar-", ".jpg",context.cacheDir)
    try{
        temp.outputStream().use{
            it.write(compressed)
            if(it is java.io.FileOutputStream)it.fd.sync()
        }
        check(temp.renameTo(target)){"Не удалось заменить аватар"}
        return target
    }finally{
        temp.delete()
    }
}

private fun parseAvatarUser(raw:String):User {
    val o=JSONObject(raw)
    return User(
        id=o.getString("id"),
        username=o.getString("username"),
        displayName=o.getString("displayName"),
        bio=o.optString("bio"),
        bioSupported=o.has("bio"),
        hasAvatar=o.optBoolean("hasAvatar",false),
        avatarVersion=o.optString("avatarVersion")
    )
}

private object LumoAvatarApi {
    fun upload(token:String,file:File):User {
        val bytes=file.readBytes()
        require(bytes.size<=MAX_SYNCED_JPEG_BYTES){"Фото слишком большое для синхронизации"}
        val request=Request.Builder()
            .url(Api.HTTP+"/api/me/avatar")
            .header("Authorization","Bearer "+token)
            .put(bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        Api.httpClient.newCall(request).execute().use{response->
            val raw=response.body?.string().orEmpty()
            if(response.code==401)throw SessionExpiredException()
            if(!response.isSuccessful){
                val reason=runCatching{JSONObject(raw).optString("error")}.getOrDefault("")
                error(when(reason){
                    "avatar_too_large"->"Фото превышает лимит сервера"
                    "invalid_avatar"->"Сервер отклонил изображение"
                    else->"Не удалось синхронизировать фото"
                })
            }
            return parseAvatarUser(raw)
        }
    }

    fun remove(token:String):User {
        val request=Request.Builder()
            .url(Api.HTTP+"/api/me/avatar")
            .header("Authorization","Bearer "+token)
            .delete()
            .build()
        Api.httpClient.newCall(request).execute().use{response->
            val raw=response.body?.string().orEmpty()
            if(response.code==401)throw SessionExpiredException()
            if(!response.isSuccessful)error("Не удалось удалить публичное фото")
            return parseAvatarUser(raw)
        }
    }

    fun download(token:String,user:User):ByteArray? {
        if(!user.hasAvatar)return null
        val suffix=if(user.avatarVersion.isBlank())"" else "?v="+
            java.net.URLEncoder.encode(user.avatarVersion,Charsets.UTF_8.name())
        val request=Request.Builder()
            .url(Api.HTTP+"/api/users/"+user.id+"/avatar"+suffix)
            .header("Authorization","Bearer "+token)
            .get().build()
        Api.httpClient.newCall(request).execute().use{response->
            if(response.code==401)throw SessionExpiredException()
            if(response.code==404)return null
            if(!response.isSuccessful)error("Не удалось загрузить фото профиля")
            val bytes=response.body?.bytes()?:return null
            require(bytes.size<=512*1024){"Некорректный размер аватара"}
            return bytes
        }
    }
}

@Composable
fun LumoUserAvatar(token:String,user:User,size:Dp=52.dp,modifier:Modifier=Modifier){
    val key=user.id+":"+user.avatarVersion
    var bitmap by remember(key){mutableStateOf(LumoAvatarMemory.cache.get(key))}
    LaunchedEffect(token,key,user.hasAvatar){
        if(!user.hasAvatar){bitmap=null;return@LaunchedEffect}
        if(bitmap==null){
            val loaded=runCatching{
                withContext(Dispatchers.IO){
                    LumoAvatarApi.download(token,user)?.let(::decodeBounded)
                }
            }.getOrNull()
            if(loaded!=null){
                LumoAvatarMemory.cache.put(key,loaded)
                bitmap=loaded
            }
        }
    }
    val image=bitmap
    if(image==null)LumoNeonAvatar(user.displayName,size,modifier)
    else Image(
        bitmap=image.asImageBitmap(),
        contentDescription=user.displayName,
        contentScale=ContentScale.Crop,
        modifier=modifier.size(size).clip(CircleShape).border(1.6.dp,avatarEdge,CircleShape)
    )
}

@Composable
fun LumoEditableAvatar(
    token:String,
    user:User,
    onProfileChanged:(User)->Unit,
    size:Dp=122.dp
){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val syncPrefs=remember(user.id){
        context.getSharedPreferences("lumo_avatar_sync",Context.MODE_PRIVATE)
    }
    var reload by remember(user.id){mutableIntStateOf(0)}
    var bitmap by remember(user.id){mutableStateOf<Bitmap?>(null)}
    var localFile by remember(user.id){mutableStateOf<File?>(null)}
    var error by remember(user.id){mutableStateOf("")}
    var notice by remember(user.id){mutableStateOf("")}
    var syncing by remember(user.id){mutableStateOf(false)}
    var dirty by remember(user.id){mutableStateOf(false)}

    fun fingerprint(file:File)="${file.length()}:${file.lastModified()}"

    LaunchedEffect(user.id,reload){
        val file=avatarFile(context,user.id)
        localFile=if(file.isFile)file else null
        bitmap=withContext(Dispatchers.IO){
            runCatching{
                if(file.isFile)BitmapFactory.decodeFile(file.absolutePath) else null
            }.getOrNull()
        }
        dirty=file.isFile && syncPrefs.getString("fingerprint","")!=fingerprint(file)
    }

    val select=rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ){uri->
        if(uri!=null){
            scope.launch{
                error="";notice=""
                runCatching{
                    withContext(Dispatchers.IO){saveAvatar(context,user.id,uri)}
                }.onSuccess{
                    syncPrefs.edit().remove("fingerprint").apply()
                    reload++
                    notice="Фото сохранено только на этом телефоне. Для публикации нажми «Синхронизировать»."
                }.onFailure{error=it.message?:"Не удалось установить аватар"}
            }
        }
    }

    Column(horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.size(size)){
            val local=bitmap
            if(local!=null)Image(
                bitmap=local.asImageBitmap(),
                contentDescription="Мой аватар",
                contentScale=ContentScale.Crop,
                modifier=Modifier.size(size).clip(CircleShape).border(2.dp,avatarEdge,CircleShape)
            ) else LumoUserAvatar(token,user,size)

            Box(
                Modifier.align(Alignment.BottomEnd).size(42.dp)
                    .lumoGlass(22).clip(CircleShape)
                    .clickable(enabled=!syncing){
                        select.launch(PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        ))
                    }
                    .semantics{contentDescription="Выбрать новый аватар"},
                contentAlignment=Alignment.Center
            ){
                Text("✎",style=MaterialTheme.typography.titleLarge,color=Color.White)
            }
        }

        if(dirty&&localFile!=null){
            Spacer(Modifier.height(8.dp))
            Text(
                "После синхронизации фото будет видно другим авторизованным пользователям Lumo.",
                style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                enabled=!syncing,
                onClick={
                    val file=localFile?:return@TextButton
                    syncing=true;error="";notice=""
                    scope.launch{
                        runCatching{
                            withContext(Dispatchers.IO){LumoAvatarApi.upload(token,file)}
                        }.onSuccess{updated->
                            syncPrefs.edit().putString("fingerprint",fingerprint(file)).apply()
                            dirty=false
                            notice="Фото синхронизировано с аккаунтом."
                            onProfileChanged(updated)
                        }.onFailure{error=it.message?:"Не удалось синхронизировать фото"}
                        syncing=false
                    }
                }
            ){
                Text(if(syncing)"Синхронизируем…" else "Синхронизировать фото",color=LumoCyan)
            }
        }

        if(user.hasAvatar){
            TextButton(
                enabled=!syncing,
                onClick={
                    syncing=true;error="";notice=""
                    scope.launch{
                        runCatching{
                            withContext(Dispatchers.IO){LumoAvatarApi.remove(token)}
                        }.onSuccess{updated->
                            syncPrefs.edit().remove("fingerprint").apply()
                            dirty=localFile?.isFile==true
                            notice="Публичное фото удалено из аккаунта."
                            onProfileChanged(updated)
                        }.onFailure{error=it.message?:"Не удалось удалить публичное фото"}
                        syncing=false
                    }
                }
            ){
                Text("Удалить фото из аккаунта",color=Color.White)
            }
        }

        if(bitmap!=null)TextButton(
            enabled=!syncing,
            onClick={
                scope.launch{
                    error="";notice=""
                    runCatching{
                        withContext(Dispatchers.IO){
                            val file=avatarFile(context,user.id)
                            if(file.exists()&&!file.delete())
                                throw IllegalStateException("Не удалось удалить локальное фото")
                        }
                    }.onSuccess{
                        syncPrefs.edit().remove("fingerprint").apply()
                        reload++
                        notice=if(user.hasAvatar)
                            "Локальная копия удалена. Публичное фото аккаунта осталось."
                        else "Фото удалено с устройства."
                    }.onFailure{error=it.message?:"Не удалось удалить фото"}
                }
            }
        ){
            Text("Удалить копию с устройства",color=Color.White)
        }

        if(notice.isNotBlank())Text(
            notice,
            color=LumoCyan,
            style=MaterialTheme.typography.bodySmall
        )
        if(error.isNotBlank())Text(
            error,
            color=MaterialTheme.colorScheme.error,
            style=MaterialTheme.typography.bodySmall
        )
    }
}
