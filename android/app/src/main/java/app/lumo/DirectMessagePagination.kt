package app.lumo

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

data class LumoDirectPage(
    val messages:List<Msg>,
    val hasMore:Boolean
)

data class LumoDirectHistoryWindow(
    val messages:List<Msg>,
    val pagination:Boolean,
    val hasMore:Boolean
)

class DirectPaginationUnavailableException:Exception("Direct pagination unavailable")

private fun directOptional(o:JSONObject,key:String):String=
    if(o.isNull(key))"" else o.optString(key)

private fun directMessage(o:JSONObject)=Msg(
    id=o.getString("id"),
    from=o.getString("from"),
    to=o.getString("to"),
    text=o.getString("text"),
    createdAt=directOptional(o,"createdAt"),
    deliveredAt=directOptional(o,"deliveredAt"),
    readAt=directOptional(o,"readAt"),
    clientMessageId=directOptional(o,"clientMessageId"),
    attachmentId=directOptional(o,"attachmentId"),
    replyToMessageId=directOptional(o,"replyToMessageId"),
    replyPreviewText=directOptional(o,"replyPreviewText"),
    replyPreviewFrom=directOptional(o,"replyPreviewFrom"),
    editedAt=directOptional(o,"editedAt"),
    deletedAt=directOptional(o,"deletedAt")
)

fun Api.directPaginationSupported(token:String):Boolean {
    val request=Request.Builder()
        .url(Api.HTTP+"/api/capabilities")
        .header("Authorization","Bearer "+token)
        .header("Cache-Control","no-store")
        .get().build()
    Api.httpClient.newCall(request).execute().use{response->
        if(response.code==401)throw SessionExpiredException()
        if(!response.isSuccessful)return false
        return runCatching{
            JSONObject(response.body?.string().orEmpty())
                .optBoolean("directPagination",false)
        }.getOrDefault(false)
    }
}

fun Api.directHistoryPage(
    token:String,
    peerId:String,
    beforeId:String?=null,
    limit:Int=100
):LumoDirectPage {
    require(limit in 1..100)
    val builder=(Api.HTTP+"/api/messages/"+peerId+"/page")
        .toHttpUrl().newBuilder()
        .addQueryParameter("limit",limit.toString())
    if(!beforeId.isNullOrBlank())builder.addQueryParameter("beforeId",beforeId)
    val request=Request.Builder().url(builder.build())
        .header("Authorization","Bearer "+token)
        .header("Cache-Control","no-store")
        .get().build()
    Api.httpClient.newCall(request).execute().use{response->
        val raw=response.body?.string().orEmpty()
        if(response.code==401)throw SessionExpiredException()
        if(response.code==404)throw DirectPaginationUnavailableException()
        if(!response.isSuccessful){
            val code=runCatching{JSONObject(raw).optString("error")}.getOrDefault("")
            error("История: "+response.code+" "+code)
        }
        val root=JSONObject(raw)
        val array=root.getJSONArray("messages")
        return LumoDirectPage(
            messages=(0 until array.length()).map{
                directMessage(array.getJSONObject(it))
            },
            hasMore=!root.isNull("next")
        )
    }
}

/**
 * Loads a bounded latest window on modern servers and falls back to the
 * legacy full-history endpoint on older Lumo backends.
 */
fun Api.directHistoryWindow(
    token:String,
    peerId:String,
    limit:Int=100
):LumoDirectHistoryWindow {
    val supported=runCatching{directPaginationSupported(token)}.getOrElse{
        if(it is SessionExpiredException)throw it
        false
    }
    if(supported){
        try{
            val page=directHistoryPage(token,peerId,null,limit)
            return LumoDirectHistoryWindow(page.messages,true,page.hasMore)
        }catch(error:DirectPaginationUnavailableException){
            // Capability and route can briefly disagree during a rolling deploy.
        }
    }
    return LumoDirectHistoryWindow(
        messages=history(token,peerId),
        pagination=false,
        hasMore=false
    )
}
