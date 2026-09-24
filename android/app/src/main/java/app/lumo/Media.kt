package app.lumo

import android.Manifest
import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.widget.VideoView
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
 "audio/mp4" to MAX_VOICE_BYTES,
 "application/pdf" to 15L*1024*1024,
 "text/plain" to 2L*1024*1024,
 "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to 15L*1024*1024,
 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to 15L*1024*1024,
 "application/vnd.openxmlformats-officedocument.presentationml.presentation" to 20L*1024*1024
)
private val documentMimes=arrayOf(
 "application/pdf",
 "text/plain",
 "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
 "application/vnd.openxmlformats-officedocument.presentationml.presentation"
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
private data class MediaLink(val url:String,val mime:String,val filename:String,val bytes:Long)
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
private fun selectedDocument(context:Context,uri:Uri):ChosenMedia{
 val resolver=context.contentResolver
 val type=verifiedType(resolver.getType(uri)?:error("Неизвестный формат документа"))
 if(type !in documentMimes)error("Поддерживаются PDF, TXT, DOCX, XLSX и PPTX")
 val limit=mediaLimits[type]?:error("Этот формат не поддерживается")
 var bytes=-1L
 var name="document"
 resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{cursor->
  if(cursor.moveToFirst()){
   val nameColumn=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
   val sizeColumn=cursor.getColumnIndex(OpenableColumns.SIZE)
   if(nameColumn>=0&&!cursor.isNull(nameColumn))name=cursor.getString(nameColumn)
   if(sizeColumn>=0&&!cursor.isNull(sizeColumn))bytes=cursor.getLong(sizeColumn)
  }
 }
 if(bytes<=0)bytes=resolver.openAssetFileDescriptor(uri,"r")?.use{it.length}?:-1
 if(bytes<=0 || bytes>limit)error("Невозможно определить размер или документ превышает лимит")
 name=name.take(160).ifBlank{"document"}
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
 fun groupEnabled():Boolean{
  val req=Request.Builder().url(Api.HTTP+"/api/capabilities").build()
  client.newCall(req).execute().use{res->
   if(!res.isSuccessful)return false
   return JSONObject(res.body?.string().orEmpty()).optBoolean("groupAttachments",false)
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
 fun initiateGroup(token:String,groupId:String,item:ChosenMedia):UploadTicket{
  val json=authorized(token,"/api/groups/"+groupId+"/media/init","POST",JSONObject()
   .put("mime",item.mime).put("bytes",item.bytes).put("filename",item.filename))
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
 fun sendGroup(token:String,groupId:String,pending:PendingAttachment):LumoGroupMessage{
  val json=authorized(token,"/api/groups/"+groupId+"/media/"+pending.assetId+"/send","POST",
   JSONObject().put("clientMessageId",pending.clientId).put("caption",pending.caption))
  return LumoGroupMessage(
   id=json.getString("id"),
   from=json.getString("from"),
   text=json.getString("text"),
   createdAt=json.getString("createdAt"),
   clientMessageId=json.optString("clientMessageId"),
   attachmentId=json.optString("attachmentId"),
   replyToMessageId=json.optString("replyToMessageId"),
   replyPreviewText=json.optString("replyPreviewText"),
   replyPreviewFrom=json.optString("replyPreviewFrom"),
   editedAt=json.optString("editedAt"),
   deletedAt=json.optString("deletedAt")
  )
 }
 fun forward(token:String,assetId:String,to:String,clientMessageId:String,caption:String):Msg{
  val json=authorized(
   token,"/api/media/"+assetId+"/forward","POST",
   JSONObject().put("to",to).put("clientMessageId",clientMessageId)
    .put("caption",caption.take(1000))
  )
  return Msg(
   id=json.getString("id"),from=json.getString("from"),to=json.getString("to"),
   text=json.getString("text"),createdAt=json.optString("createdAt"),
   deliveredAt=if(json.isNull("deliveredAt"))"" else json.optString("deliveredAt"),
   readAt=if(json.isNull("readAt"))"" else json.optString("readAt"),
   clientMessageId=json.optString("clientMessageId"),
   attachmentId=json.optString("attachmentId"),
   editedAt=if(json.isNull("editedAt"))"" else json.optString("editedAt"),
   deletedAt=if(json.isNull("deletedAt"))"" else json.optString("deletedAt")
  )
 }
 fun link(token:String,id:String):MediaLink{
  val json=authorized(token,"/api/media/"+id+"/download","GET")
  val url=json.getString("url")
  if(Uri.parse(url).scheme!="https")error("Небезопасная ссылка на медиа")
  return MediaLink(url,json.getString("mime"),json.optString("filename","attachment"),json.optLong("bytes",0L))
 }
}

fun forwardExistingAttachment(
 token:String,assetId:String,to:String,clientMessageId:String,caption:String
):Msg = MediaApi.forward(token,assetId,to,clientMessageId,caption)

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
   r.setMaxDuration(120_000)
   r.setMaxFileSize(MAX_VOICE_BYTES)
   r.setOnInfoListener{_,code,_->
    if(code==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
       code==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)stopRecording(false)
   }
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
 val chooseDocument=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
  if(uri!=null){
   chosen?.file?.delete()
   runCatching{selectedDocument(context,uri)}
    .onSuccess{chosen=it;status="Документ выбран. Нажмите отправить."}
    .onFailure{status=it.message?:"Не удалось открыть документ";chosen=null}
  }
 }
 LaunchedEffect(token){
  checking=true
  available=runCatching{withContext(Dispatchers.IO){MediaApi.enabled()}}.getOrDefault(false)
  checking=false
 }
 val lifecycleHost=context as? LifecycleOwner
 DisposableEffect(lifecycleHost,peer.id){
  val observer=LifecycleEventObserver{_,event->
   if(event==Lifecycle.Event.ON_STOP && recorder!=null){
    stopRecording(true)
    status="Запись остановлена при сворачивании приложения"
   }
  }
  lifecycleHost?.lifecycle?.addObserver(observer)
  onDispose{lifecycleHost?.lifecycle?.removeObserver(observer)}
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
 // Do not show fake file/voice buttons on servers without private media support.
 if(checking||(!available&&pending==null))return
 Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=5.dp).lumoGlass(22).padding(12.dp)){
  Text("Вложения Lumo",style=MaterialTheme.typography.titleMedium,color=Color.White)
  Spacer(Modifier.height(6.dp))
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
   if(recorder==null){
    Row(Modifier.fillMaxWidth()){
     TextButton(
      onClick={chooseVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))},
      enabled=available&&allowSend&&!busy,
      modifier=Modifier.weight(1f)
     ){Text("◉ Фото / видео",color=LumoCyan,maxLines=1)}
     TextButton(
      onClick={chooseDocument.launch(documentMimes)},
      enabled=available&&allowSend&&!busy,
      modifier=Modifier.weight(1f)
     ){Text("📎 Документ",color=LumoCyan,maxLines=1)}
    }
    TextButton(
     onClick={askMicrophone.launch(Manifest.permission.RECORD_AUDIO)},
     enabled=available&&allowSend&&!busy&&chosen==null
    ){Text("🎙 Голосовое",color=LumoCyan)}
   }else{
    Row(Modifier.fillMaxWidth()){
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
fun GroupMediaComposer(
 token:String,
 me:User,
 groupId:String,
 allowSend:Boolean,
 onSent:(LumoGroupMessage)->Unit
){
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 val prefs=remember{context.getSharedPreferences("lumo_group_media_pending",Context.MODE_PRIVATE)}
 val key=remember(me.id,groupId){"group_media_"+me.id+"_"+groupId}
 var available by remember(token,groupId){mutableStateOf(false)}
 var checking by remember(token,groupId){mutableStateOf(true)}
 var chosen by remember(groupId){mutableStateOf<ChosenMedia?>(null)}
 var pending by remember(key){mutableStateOf(
  runCatching{
   val o=JSONObject(prefs.getString(key,null)?:return@runCatching null)
   PendingAttachment(
    o.getString("assetId"),o.getString("clientId"),
    o.optString("caption"),o.optString("filename")
   )
  }.getOrNull()
 )}
 var caption by remember(groupId){mutableStateOf("")}
 var busy by remember{mutableStateOf(false)}
 var status by remember{mutableStateOf("")}
 var recorder by remember(groupId){mutableStateOf<MediaRecorder?>(null)}
 var voiceFile by remember(groupId){mutableStateOf<File?>(null)}
 var startedAt by remember(groupId){mutableLongStateOf(0L)}

 fun rememberPending(value:PendingAttachment?){
  pending=value
  val edit=prefs.edit()
  if(value==null)edit.remove(key)
  else edit.putString(key,JSONObject()
   .put("assetId",value.assetId)
   .put("clientId",value.clientId)
   .put("caption",value.caption)
   .put("filename",value.filename)
   .toString())
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
  if(valid&&file!=null&&file.length() in 1..MAX_VOICE_BYTES){
   chosen=ChosenMedia("audio/mp4","voice.m4a",file.length(),file=file)
   status="Голосовое записано. Нажмите отправить."
  }else{
   file?.delete()
   if(!cancel)status="Не удалось записать голосовое. Попробуйте ещё раз."
  }
 }
 fun startRecording(){
  if(busy||recorder!=null||pending!=null||!available||!allowSend)return
  val file=File.createTempFile("lumo-group-voice-", ".m4a",context.cacheDir)
  val r=if(Build.VERSION.SDK_INT>=31)MediaRecorder(context)
   else @Suppress("DEPRECATION") MediaRecorder()
  try{
   r.setAudioSource(MediaRecorder.AudioSource.MIC)
   r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
   r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
   r.setAudioEncodingBitRate(64000)
   r.setAudioSamplingRate(44100)
   r.setOutputFile(file.absolutePath)
   r.setMaxDuration(120_000)
   r.setMaxFileSize(MAX_VOICE_BYTES)
   r.setOnInfoListener{_,code,_->
    if(code==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED||
       code==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
      stopRecording(false)
   }
   r.prepare();r.start()
   voiceFile=file
   recorder=r
   startedAt=SystemClock.elapsedRealtime()
   status="Идёт запись. Нажмите «Готово», чтобы завершить."
  }catch(_:Exception){
   r.release();file.delete()
   status="Микрофон не удалось включить"
  }
 }
 val askMicrophone=rememberLauncherForActivityResult(
  ActivityResultContracts.RequestPermission()
 ){granted->
  if(granted)startRecording()
  else status="Разрешите доступ к микрофону для голосовых"
 }

 val chooseVisual=rememberLauncherForActivityResult(
  ActivityResultContracts.PickVisualMedia()
 ){uri->
  if(uri!=null){
   runCatching{selectedVisual(context,uri)}
    .onSuccess{chosen=it;status="Файл выбран. Нажмите отправить."}
    .onFailure{chosen=null;status=it.message?:"Не удалось открыть файл"}
  }
 }
 val chooseDocument=rememberLauncherForActivityResult(
  ActivityResultContracts.OpenDocument()
 ){uri->
  if(uri!=null){
   runCatching{selectedDocument(context,uri)}
    .onSuccess{chosen=it;status="Документ выбран. Нажмите отправить."}
    .onFailure{chosen=null;status=it.message?:"Не удалось открыть документ"}
  }
 }

 LaunchedEffect(token,groupId){
  checking=true
  available=runCatching{
   withContext(Dispatchers.IO){MediaApi.groupEnabled()}
  }.getOrDefault(false)
  checking=false
 }
 val lifecycleHost=context as? LifecycleOwner
 DisposableEffect(lifecycleHost,groupId){
  val observer=LifecycleEventObserver{_,event->
   if(event==Lifecycle.Event.ON_STOP&&recorder!=null){
    stopRecording(true)
    status="Запись остановлена при сворачивании приложения"
   }
  }
  lifecycleHost?.lifecycle?.addObserver(observer)
  onDispose{lifecycleHost?.lifecycle?.removeObserver(observer)}
 }
 DisposableEffect(groupId){
  onDispose{
   recorder?.let{r->runCatching{r.stop()};runCatching{r.reset()};r.release()}
   recorder=null
   voiceFile?.delete()
   voiceFile=null
   chosen?.file?.delete()
  }
 }

 if(checking||(!available&&pending==null))return
 Column(
  Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=4.dp)
   .lumoGlass(20).padding(10.dp)
 ){
  Text("Вложение группе",style=MaterialTheme.typography.titleSmall,color=Color.White)
  if(status.isNotBlank())Text(
   status,style=MaterialTheme.typography.bodySmall,
   color=MaterialTheme.colorScheme.onSurfaceVariant
  )
  if(pending!=null){
   Text("Ожидает отправки: "+pending!!.filename,style=MaterialTheme.typography.bodySmall)
   Row{
    TextButton(
     enabled=allowSend&&!busy,
     onClick={
      val item=pending?:return@TextButton
      busy=true
      scope.launch{
       runCatching{withContext(Dispatchers.IO){
        MediaApi.sendGroup(token,groupId,item)
       }}.onSuccess{
        onSent(it);rememberPending(null);caption="";status="Вложение отправлено"
       }.onFailure{
        status="Отправка не подтверждена. Повторите с тем же ID."
       }
       busy=false
      }
     }
    ){Text("Повторить")}
    TextButton(
     enabled=!busy,
     onClick={rememberPending(null);status="Отправка отменена"}
    ){Text("Отменить")}
   }
  }else{
   if(recorder==null){
    Row(Modifier.fillMaxWidth()){
     TextButton(
      enabled=available&&allowSend&&!busy,
      modifier=Modifier.weight(1f),
      onClick={
       chooseVisual.launch(PickVisualMediaRequest(
        ActivityResultContracts.PickVisualMedia.ImageAndVideo
       ))
      }
     ){Text("◉ Фото / видео",color=LumoCyan,maxLines=1)}
     TextButton(
      enabled=available&&allowSend&&!busy,
      modifier=Modifier.weight(1f),
      onClick={chooseDocument.launch(documentMimes)}
     ){Text("📎 Документ",color=LumoCyan,maxLines=1)}
    }
    TextButton(
     enabled=available&&allowSend&&!busy&&chosen==null,
     onClick={askMicrophone.launch(Manifest.permission.RECORD_AUDIO)}
    ){Text("🎙 Голосовое",color=LumoCyan)}
   }else{
    Row(Modifier.fillMaxWidth()){
     TextButton(
      enabled=!busy,
      onClick={
       if(SystemClock.elapsedRealtime()-startedAt>=700L)stopRecording(false)
       else status="Запишите хотя бы одну секунду"
      }
     ){Text("■ Готово")}
     TextButton(
      enabled=!busy,
      onClick={stopRecording(true);status="Запись отменена"}
     ){Text("Отмена")}
    }
   }
   chosen?.let{selected->
    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
     Text(
      selected.filename+" ("+selected.bytes/1024+" КБ)",
      style=MaterialTheme.typography.bodySmall,
      modifier=Modifier.weight(1f)
     )
     TextButton(onClick={chosen=null;status=""},enabled=!busy){Text("×")}
    }
    OutlinedTextField(
     value=caption,onValueChange={caption=it.take(1000)},
     label={Text("Подпись (необязательно)")},
     modifier=Modifier.fillMaxWidth(),maxLines=2,enabled=!busy
    )
    Button(
     enabled=allowSend&&!busy&&recorder==null,
     onClick={
      val source=chosen?:return@Button
      busy=true;status="Загрузка..."
      scope.launch{
       runCatching{withContext(Dispatchers.IO){
        val ticket=MediaApi.initiateGroup(token,groupId,source)
        MediaApi.upload(context,ticket,source)
        MediaApi.complete(token,ticket.assetId)
        PendingAttachment(
         ticket.assetId,java.util.UUID.randomUUID().toString(),
         caption.trim(),source.filename
        )
       }}.onSuccess{item->
        rememberPending(item)
        chosen?.file?.delete()
        chosen=null
        runCatching{withContext(Dispatchers.IO){
         MediaApi.sendGroup(token,groupId,item)
        }}.onSuccess{
         onSent(it);rememberPending(null);caption="";status="Вложение отправлено"
        }.onFailure{
         status="Файл загружен, но отправка не подтверждена. Повторите."
        }
       }.onFailure{status=it.message?:"Не удалось загрузить файл"}
       busy=false
      }
     }
    ){Text(if(busy)"Подождите..." else "Отправить файл")}
   }
  }
 }
}


private fun fetchImageBitmap(url:String):ImageBitmap{
 val request=Request.Builder().url(url).get().build()
 Api.httpClient.newCall(request).execute().use{response->
  if(!response.isSuccessful)error("Не удалось получить изображение")
  val body=response.body?:error("Изображение недоступно")
  if(body.contentLength()>8L*1024*1024)error("Изображение слишком большое")
  val data=body.byteStream().use{input->
   val output=java.io.ByteArrayOutputStream()
   val buffer=ByteArray(65536)
   var total=0L
   while(true){
    val count=input.read(buffer)
    if(count<0)break
    total+=count
    if(total>8L*1024*1024)error("Изображение слишком большое")
    output.write(buffer,0,count)
   }
   output.toByteArray()
  }
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
  BitmapFactory.decodeByteArray(data,0,data.size,bounds)
  if(bounds.outWidth<=0 || bounds.outHeight<=0 ||
     bounds.outWidth.toLong()*bounds.outHeight.toLong()>100_000_000L)
    error("Неподдерживаемое изображение")
  val options=BitmapFactory.Options()
  var scale=1
  while(bounds.outWidth/scale>1600 || bounds.outHeight/scale>1600)scale*=2
  options.inSampleSize=scale
  val bitmap=BitmapFactory.decodeByteArray(data,0,data.size,options)
    ?:error("Изображение повреждено")
  return bitmap.asImageBitmap()
 }
}

@Composable
fun MediaAttachmentButton(token:String,assetId:String){
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 var busy by remember(assetId){mutableStateOf(false)}
 var error by remember(assetId){mutableStateOf("")}
 var player by remember(assetId){mutableStateOf<MediaPlayer?>(null)}
 var photo by remember(assetId){mutableStateOf<ImageBitmap?>(null)}
 var showPhoto by remember(assetId){mutableStateOf(false)}
 var videoUrl by remember(assetId){mutableStateOf<String?>(null)}
 var videoView by remember(assetId){mutableStateOf<VideoView?>(null)}
 DisposableEffect(assetId){
  onDispose{
   player?.release()
   player=null
   videoView?.stopPlayback()
   videoView=null
  }
 }
 if(showPhoto&&photo!=null)AlertDialog(
  onDismissRequest={showPhoto=false},
  title={Text("Фото")},
  text={Image(bitmap=photo!!,contentDescription="Вложенное изображение",modifier=Modifier.fillMaxWidth())},
  confirmButton={TextButton(onClick={showPhoto=false}){Text("Закрыть")}}
 )
 videoUrl?.let{url->
  Dialog(onDismissRequest={videoView?.stopPlayback();videoView=null;videoUrl=null}){
   Surface{
    Column(Modifier.padding(12.dp)){
     Text("Видео",style=MaterialTheme.typography.titleMedium)
     AndroidView(factory={ctx->
      VideoView(ctx).also{view->
       videoView=view
       view.setVideoURI(Uri.parse(url))
       view.setOnPreparedListener{it.isLooping=false;view.start()}
       view.setOnErrorListener{_,_,_->error="Не удалось воспроизвести видео";true}
       view.setMediaController(android.widget.MediaController(ctx).also{it.setAnchorView(view)})
      }
     },modifier=Modifier.fillMaxWidth().height(240.dp))
     TextButton(onClick={videoView?.stopPlayback();videoView=null;videoUrl=null}){
      Text("Закрыть")
     }
    }
   }
  }
 }
 Column{
  TextButton(onClick={
   busy=true
   error=""
   scope.launch{
    runCatching{withContext(Dispatchers.IO){MediaApi.link(token,assetId)}}
     .onSuccess{link->
      when{
       link.mime.startsWith("audio/")->{
        runCatching{
         player?.release()
         val mp=MediaPlayer()
         mp.setDataSource(link.url)
         mp.setOnPreparedListener{it.start();busy=false}
         mp.setOnCompletionListener{it.reset();it.release();if(player===it)player=null}
         mp.setOnErrorListener{it,_,_->it.release();if(player===it)player=null;busy=false;error="Не удалось воспроизвести аудио";true}
         player=mp
         mp.prepareAsync()
        }.onFailure{error="Не удалось воспроизвести аудио";busy=false}
       }
       link.mime.startsWith("image/")->{
        runCatching{withContext(Dispatchers.IO){fetchImageBitmap(link.url)}}
         .onSuccess{photo=it;showPhoto=true}
         .onFailure{error="Не удалось открыть фотографию"}
        busy=false
       }
       link.mime=="video/mp4"->{videoUrl=link.url;busy=false}
       link.mime in documentMimes.toSet()->{
        val uri=Uri.parse(link.url)
        val intent=Intent(Intent.ACTION_VIEW).apply{
         setDataAndType(uri,link.mime)
         addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
         addCategory(Intent.CATEGORY_BROWSABLE)
         putExtra(Intent.EXTRA_TITLE,link.filename)
        }
        runCatching{
         if(intent.resolveActivity(context.packageManager)==null)
          error("Нет приложения для открытия этого документа")
         context.startActivity(intent)
        }.onFailure{error="Не удалось открыть документ во внешнем приложении"}
        busy=false
       }
       else->{error="Неизвестный формат вложения";busy=false}
      }
     }
     .onFailure{error="Не удалось открыть вложение";busy=false}
   }
  },enabled=!busy){Text(if(busy)"Загрузка..." else "↗ Открыть вложение")}
  if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
 }
}
