package app.lumo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

private const val MAX_AVATAR_SOURCE_BYTES=8*1024*1024
private val avatarEdge=Brush.linearGradient(listOf(LumoCyan,Color.White,LumoPink))

/** User-scoped private app storage. No server upload and no broad gallery permission. */
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

private fun saveAvatar(context:Context,userId:String,uri:Uri){
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
    val temp=File.createTempFile("lumo-avatar-", ".jpg",context.cacheDir)
    try{
        temp.outputStream().use{
            check(bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)){
                "Не удалось сохранить фотографию"
            }
        }
        check(temp.renameTo(avatarFile(context,userId))){
            "Не удалось заменить аватар"
        }
    }finally{
        if(!bitmap.isRecycled)bitmap.recycle()
        temp.delete()
    }
}

@Composable
fun LumoEditableAvatar(userId:String,displayName:String,size:Dp=122.dp){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var reload by remember(userId){mutableIntStateOf(0)}
    var bitmap by remember(userId){mutableStateOf<Bitmap?>(null)}
    var error by remember(userId){mutableStateOf("")}

    LaunchedEffect(userId,reload){
        bitmap=withContext(Dispatchers.IO){
            runCatching{
                val file=avatarFile(context,userId)
                if(file.isFile)BitmapFactory.decodeFile(file.absolutePath) else null
            }.getOrNull()
        }
    }

    val select=rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ){uri->
        if(uri!=null){
            scope.launch{
                error=""
                runCatching{
                    withContext(Dispatchers.IO){saveAvatar(context,userId,uri)}
                }.onSuccess{reload++}
                 .onFailure{error=it.message?:"Не удалось установить аватар"}
            }
        }
    }
    androidx.compose.foundation.layout.Column(
        horizontalAlignment=Alignment.CenterHorizontally
    ){
        Box(Modifier.size(size)){
            val photo=bitmap
            if(photo==null)LumoNeonAvatar(displayName,size)
            else Image(
                bitmap=photo.asImageBitmap(),contentDescription="Мой аватар",
                contentScale=ContentScale.Crop,
                modifier=Modifier.size(size).clip(CircleShape).border(2.dp,avatarEdge,CircleShape)
            )
            Box(
                Modifier.align(Alignment.BottomEnd).size(42.dp)
                    .lumoGlass(22)
                    .clip(CircleShape)
                    .clickable{
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
        if(error.isNotBlank())Text(
            error,color=MaterialTheme.colorScheme.error,
            style=MaterialTheme.typography.bodySmall
        )
    }
}
