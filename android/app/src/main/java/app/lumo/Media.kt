package app.lumo

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject

private const val MAX_VOICE_BYTES=10L*1024*1024
private val mediaLimits=mapOf(
 "image/jpeg" to 8L*1024*1024,
 "image/png" to 8L*1024*1024,
 "image/webp" to 8L*1024*1024,
 "video/mp4" to 25L*1024*1024,
 "audio/mp4" to MAX_VOICE_BYTES
)
private data class ChosenMedia(
 val mime:String,val filename:String,val bytes:Long,
 val uri:Uri?=null,val file:File?=null
){
 fun open(context:Context)=file?.inputStream()
  ?:requireNotNull(uri).let{context.contentResolver.openInputStream(it)
    ?:error("Не удалось открыть выбранный файл")}
}
private data class PendingAttachment(
 val assetId:String,val clientId:String,val caption:String,val filename:String
)
private data class UploadTicket(
 val assetId:String,val uploadUrl:String,val fields:JSONObject
)
private data class MediaLink(val url:String,val mime:String)
private fun verifiedType(mime:String)=when(mime.lowercase()){
 "image/jpg"->"image/jpeg"
 else->mime.lowercase()
}
private fun selectedVisual(context:Context,uri:Uri):ChosenMedia{
 val resolver=context.contentResolver
 val type=verifiedType(resolver.getType(uri)?:error("Неизвестный формат файла"))
 val limit=mediaLimits[type]?:error("Этот формат не поддерживается")
 if(!type.startsWith("image/")&&!type.startsWith("video/"))error("Выберите фото или видео")
 var bytes=-1L
 var name="photo"
 resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{cursor->
  if(cursor.moveToFirst()){
   val nameColumn=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
   val sizeColumn=cursor.getColumnIndex(OpenableColumns.SIZE)
   if(nameColumn>=0&&!cursor.isNull(nameColumn))name=cursor.getString(nameColumn)
   if(sizeColumn>=0&&!cursor.isNull(sizeColumn))bytes=cursor.getLong(sizeColumn)
  }
 }
 if(bytes<=0)bytes=resolver.openAssetFileDescriptor(uri,"r")?.use{it.length}?:-1
 if(bytes<=0 || bytes>limit)error("Невозможно определить размер или файл превышает лимит")
 name=name.take(160).ifBlank{"attachment"}
 return ChosenMedia(type,name,bytes,uri=uri)
}
private object MediaApi{
 private val client=Api.httpClient
 fun enabled():Boolean{
  val req=Request.Builder().url(Api.HTTP+"/api/capabilities").build()
  client.newCall(req).execute().use{res->
   if(!res.isSuccessful)return false
   return JSONObject(res.body?.string().orEmpty()).optBoolean("mediaReady",false)
  }
 }
 private fun authorized(token:String,url:String,method:String,body:JSONObject?=null):JSONObject{
  val b=Request.Builder().url(Api.HTTP+url).header("Authorization","Bearer "+token)
  val request=when(method){
   "POST"->b.post((body?:JSONObject()).toString().toRequestBody("application/json".toMediaType())).build()
   else->b.get().build()
  }
  client.newCall(request).execute().use{res->
   if(res.code==401)throw SessionExpiredException()
   val responseBody=res.body?.string().orEmpty()
   if(!res.isSuccessful){
    val reason=runCatching{JSONObject(responseBody).optString("error")}.getOrDefault("")
    error("Медиа: HTTP "+res.code+" "+reason)
   }
   return JSONObject(responseBody)
  }
 }
 fun initiate(token:String,peerId:String,item:ChosenMedia):UploadTicket{
  val json=authorized(token,"/api/media/init","POST",JSONObject()
   .put("to",peerId).put("mime",item.mime).put("bytes",item.bytes).put("filename",item.filename))
  return UploadTicket(json.getString("assetId"),json.getString("uploadUrl"),json.getJSONObject("fields"))
 }
 fun upload(context:Context,ticket:UploadTicket,item:ChosenMedia){
  if(Uri.parse(ticket.uploadUrl).scheme!="https")error("Небезопасный адрес загрузки")
  val form=MultipartBody.Builder().setType(MultipartBody.FORM)
  val keys=ticket.fields.keys()
  while(keys.hasNext()){
   val key=keys.next()
   form.addFormDataPart(key,ticket.fields.getString(key))
  }
  val fileBody=object:RequestBody(){
   override fun contentType()=item.mime.toMediaType()
   override fun contentLength()=item.bytes
   override fun writeTo(sink:BufferedSink){
    item.open(context).use{input->
     val buffer=ByteArray(65536)
     var total=0L
     while(true){
      val count=input.read(buffer)
      if(count<0)break
      total+=count
      if(total>item.bytes)error("Размер выбранного файла изменился")
      sink.write(buffer,0,count)
     }
     if(total!=item.bytes)error("Размер выбранного файла изменился")
    }
   }
  }
  form.addFormDataPart("file",item.filename,fileBody)
  client.newCall(Request.Builder().url(ticket.uploadUrl).post(form.build()).build()).execute().use{response->
   if(!response.isSuccessful)error("Хранилище отклонило загрузку (HTTP "+response.code+")")
  }
 }
 fun complete(token:String,assetId:String){
  authorized(token,"/api/media/"+assetId+"/complete","POST")
 }
 fun send(token:String,pending:PendingAttachment):Msg{
  val json=authorized(token,"/api/media/"+pending.assetId+"/send","POST",
   JSONObject().put("clientMessageId",pending.clientId).put("caption",pending.caption))
  return Msg(
   id=json.getString("id"),from=json.getString("from"),to=json.getString("to"),
   text=json.getString("text"),createdAt=json.optString("createdAt"),
   deliveredAt=if(json.isNull("deliveredAt"))"" else json.optString("deliveredAt"),
   readAt=if(json.isNull("readAt"))"" else json.optString("readAt"),
   clientMessageId=json.optString("clientMessageId"),
   attachmentId=json.optString("attachmentId")
  )
 }
 fun link(token:String,id:String):MediaLink{
  val json=authorized(token,"/api/media/"+id+"/download","GET")
  val url=json.getString("url")
  if(Uri.parse(url).scheme!="https")error("Небезопасная ссылка на медиа")
  return MediaLink(url,json.getString("mime"))
 }
}

@Composable
fun MediaComposer(token:String,me:User,peer:User,allowSend:Boolean,onSent:(Msg)->Unit){
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 val prefs=remember{context.getSharedPreferences("lumo_media_pending",Context.MODE_PRIVATE)}
 val key=remember(me.id,peer.id){"pending_"+me.id+"_"+peer.id}
 var available by remember(token){mutableStateOf(false)}
 var checking by remember(token){mutableStateOf(true)}
 var chosen by remember(peer.id){mutableStateOf<ChosenMedia?>(null)}
 var pending by remember(key){mutableStateOf(
  runCatching{
   val obj=JSONObject(prefs.getString(key,null)?:return@runCatching null)
   PendingAttachment(obj.getString("assetId"),obj.getString("clientId"),
    obj.optString("caption"),obj.optString("filename"))
  }.getOrNull()
 )}
 var caption by remember{mutableStateOf("")}
 var busy by remember{mutableStateOf(false)}
 var status by remember{mutableStateOf("")}
 var recorder by remember{mutableStateOf<MediaRecorder?>(null)}
 var voiceFile by remember{mutableStateOf<File?>(null)}
 var startedAt by remember{mutableLongStateOf(0L)}
 fun rememberPending(value:PendingAttachment?){
  pending=value
  val edit=prefs.edit()
  if(value==null)edit.remove(key)
  else edit.putString(key,JSONObject().put("assetId",value.assetId)
   .put("clientId",value.clientId).put("caption",value.caption)
   .put("filename",value.filename).toString())
  edit.apply()
 }
 fun stopRecording(cancel:Boolean){
  val r=recorder?:return
  recorder=null
  val file=voiceFile
  var valid=!cancel
  try{r.stop()}catch(_:RuntimeException){valid=false}
  finally{r.reset();r.release()}
  voiceFile=null
  if(valid && file!=null && file.length() in 1..MAX_VOICE_BYTES){
   chosen=ChosenMedia("audio/mp4","voice.m4a",file.length(),file=file)
   status="Голосовое записано. Нажмите отправить."
  }else{
   file?.delete()
   if(!cancel)status="Не удалось записать голосовое. Попробуйте ещё раз."
  }
 }
 fun startRecording(){
  if(busy || recorder!=null || pending!=null || !available || !allowSend)return
  val file=File.createTempFile("lumo-voice-", ".m4a",context.cacheDir)
  val r=if(Build.VERSION.SDK_INT>=31)MediaRecorder(context)else @Suppress("DEPRECATION") MediaRecorder()
  try{
   r.setAudioSource(MediaRecorder.AudioSource.MIC)
   r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
   r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
   r.setAudioEncodingBitRate(64000)
   r.setAudioSamplingRate(44100)
   r.setOutputFile(file.absolutePath)
   r.prepare()
   r.start()
   voiceFile=file
   recorder=r
   startedAt=SystemClock.elapsedRealtime()
   status="Идёт запись. Нажмите «Готово», чтобы завершить."
  }catch(_:Exception){
   r.release()
   file.delete()
   status="Микрофон не удалось включить"
  }
 }
 val askMicrophone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
  if(granted)startRecording() else status="Разрешите доступ к микрофону для голосовых"
 }
 val chooseVisual=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->
  if(uri!=null){
   chosen?.file?.delete()
   runCatching{selectedVisual(context,uri)}
    .onSuccess{chosen=it;status="Файл выбран. Нажмите отправить."}
    .onFailure{status=it.message?:"Не удалось открыть файл";chosen=null}
  }
 }
 LaunchedEffect(token){
  checking=true
  available=runCatching{withContext(Dispatchers.IO){MediaApi.enabled()}}.getOrDefault(false)
  checking=false
 }
 DisposableEffect(peer.id){
  onDispose{
   recorder?.let{r->runCatching{r.stop()};runCatching{r.reset()};r.release()}
   recorder=null
   voiceFile?.delete()
   voiceFile=null
   chosen?.file?.delete()
  }
 }
 if(checking||(!available&&pending==null))return
 Column(Modifier.fillMaxWidth().padding(horizontal=8.dp)){
  if(!available)Text("Медиа временно недоступны",style=MaterialTheme.typography.bodySmall)
  if(status.isNotBlank())Text(status,style=MaterialTheme.typography.bodySmall,
   color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(pending!=null){
   Text("Ожидает отправки: "+pending!!.filename,style=MaterialTheme.typography.bodySmall)
   Row{
    Button(onClick={
     val item=pending?:return@Button
     busy=true
     scope.launch{
      runCatching{withContext(Dispatchers.IO){MediaApi.send(token,item)}}
       .onSuccess{onSent(it);rememberPending(null);status="Медиа отправлено";caption=""}
       .onFailure{status="Отправка не подтверждена. Повторите с тем же ID."}
      busy=false
     }
    },enabled=allowSend&&!busy){Text("Повторить отправку")}
    TextButton(onClick={rememberPending(null);status="Отправка отменена"},enabled=!busy){
     Text("Отменить")
    }
   }
  }else{
   Row(Modifier.fillMaxWidth()){
    TextButton(onClick={
     chooseVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    },enabled=available&&allowSend&&!busy&&recorder==null){Text("📷 Фото / видео")}
    if(recorder==null){
     TextButton(onClick={askMicrophone.launch(Manifest.permission.RECORD_AUDIO)},
      enabled=available&&allowSend&&!busy&&chosen==null){Text("🎤 Голосовое")}
    }else{
     TextButton(onClick={
      if(SystemClock.elapsedRealtime()-startedAt>=700L)stopRecording(false)
      else status="Запишите хотя бы одну секунду"
     },enabled=!busy){Text("■ Готово")}
     TextButton(onClick={stopRecording(true);status="Запись отменена"},enabled=!busy){
      Text("Отмена")
     }
    }
   }
   chosen?.let{selected->
    Row(Modifier.fillMaxWidth()){
     Text(selected.filename+" ("+selected.bytes/(1024)+" КБ)",
      style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
     TextButton(onClick={chosen=null;selected.file?.delete();status=""},enabled=!busy){
      Text("×")
     }
    }
    OutlinedTextField(caption,{caption=it.take(1000)},label={Text("Подпись (необязательно)")},
     modifier=Modifier.fillMaxWidth(),maxLines=2,enabled=!busy)
    Button(onClick={
     val source=chosen?:return@Button
     busy=true
     status="Загрузка..."
     scope.launch{
      runCatching{withContext(Dispatchers.IO){
       val ticket=MediaApi.initiate(token,peer.id,source)
       MediaApi.upload(context,ticket,source)
       MediaApi.complete(token,ticket.assetId)
       PendingAttachment(ticket.assetId,java.util.UUID.randomUUID().toString(),
        caption.trim(),source.filename)
      }}.onSuccess{item->
       // Persist the exact ID BEFORE the send, because a lost HTTP response
       // must never cause a second distinct message on retry.
       rememberPending(item)
       chosen?.file?.delete();chosen=null
       runCatching{withContext(Dispatchers.IO){MediaApi.send(token,item)}}
        .onSuccess{onSent(it);rememberPending(null);caption="";status="Медиа отправлено"}
        .onFailure{status="Файл загружен, но отправка не подтверждена. Повторите."}
      }.onFailure{status=it.message?:"Не удалось загрузить файл"}
      busy=false
     }
    },enabled=allowSend&&!busy&&recorder==null){Text(if(busy)"Подождите..." else "Отправить файл")}
   }
  }
 }
}

@Composable
fun MediaAttachmentButton(token:String,assetId:String){
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf("")}
 var player by remember{mutableStateOf<MediaPlayer?>(null)}
 DisposableEffect(assetId){onDispose{player?.release();player=null}}
 Column{
  TextButton(onClick={
   busy=true
   error=""
   scope.launch{
    runCatching{withContext(Dispatchers.IO){MediaApi.link(token,assetId)}}
     .onSuccess{link->
      if(link.mime.startsWith("audio/")){
       runCatching{
        player?.release()
        val mp=MediaPlayer()
        mp.setDataSource(link.url)
        mp.setOnPreparedListener{it.start();busy=false}
        mp.setOnCompletionListener{it.reset();it.release();if(player===it)player=null}
        mp.setOnErrorListener{it,_,_->it.release();if(player===it)player=null;busy=false;true}
        player=mp
        mp.prepareAsync()
       }.onFailure{error="Не удалось воспроизвести голосовое";busy=false}
      }else{
       try{
        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link.url)).apply{
         addCategory(Intent.CATEGORY_BROWSABLE)
        })
       }catch(_:ActivityNotFoundException){error="Нет приложения для открытия файла"}
       busy=false
      }
     }
     .onFailure{error="Не удалось открыть вложение";busy=false}
   }
  },enabled=!busy){Text(if(busy)"Загрузка..." else "↗ Открыть вложение")}
  if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
 }
}
