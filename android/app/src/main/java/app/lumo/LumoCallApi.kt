package app.lumo

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only invitation client. This exchanges call state but deliberately never
 * accesses a microphone, camera or WebRTC media stream.
 */
data class LumoCall(
    val id: String,
    val callerId: String,
    val calleeId: String,
    val kind: String,
    val status: String,
    val expiresAt: String
)

data class LumoSignal(
    val seq: Int,
    val from: String,
    val type: String,
    val payload: JSONObject
)

class CallsUnavailableException : Exception("Call signaling is disabled")
class CallsApiException(val statusCode: Int, val code: String) : Exception(code)

data class LumoIceServer(val urls: List<String>, val username: String, val credential: String)

class LumoCallApi(private val base: String, private val client: OkHttpClient) {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun request(token: String, method: String, path: String, body: JSONObject? = null): String {
        require(path.startsWith("/api/calls")) { "Unexpected signaling endpoint" }
        val builder = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-store")
        when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonMedia))
            "GET" -> builder.get()
            else -> error("Unexpected signaling method")
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                if (response.code == 404 && code == "feature_unavailable") throw CallsUnavailableException()
                if (response.code == 401) throw SessionExpiredException()
                throw CallsApiException(response.code, code.ifBlank { "http_error" })
            }
            return text
        }
    }

    private fun parseCall(obj: JSONObject) = LumoCall(
        id = obj.getString("id"),
        callerId = obj.getString("callerId"),
        calleeId = obj.getString("calleeId"),
        kind = obj.getString("kind"),
        status = obj.getString("status"),
        expiresAt = obj.getString("expiresAt")
    )

    fun list(token: String): List<LumoCall> {
        val array = JSONArray(request(token, "GET", "/api/calls"))
        return (0 until array.length()).map { parseCall(array.getJSONObject(it)) }
    }

    fun invite(token: String, recipientId: String, kind: String): LumoCall {
        require(uuid.matches(recipientId) && kind in listOf("audio", "video"))
        val body = JSONObject().put("to", recipientId).put("kind", kind)
        return parseCall(JSONObject(request(token, "POST", "/api/calls", body)))
    }

    fun respond(token: String, id: String, action: String): LumoCall {
        require(uuid.matches(id) && action in listOf("accept", "decline", "end"))
        val body = JSONObject().put("action", action)
        return parseCall(JSONObject(request(token, "POST", "/api/calls/$id/respond", body)))
    }

    // Used by a future media engine, never by the invitation-only debug UI.
    // The caller must persist a clientSignalId for retries to avoid duplicate
    // offers or ICE candidates after an uncertain network response.
    fun sendSignal(token: String, id: String, clientSignalId: String, type: String, payload: JSONObject): Int {
        require(uuid.matches(id) && uuid.matches(clientSignalId) && type in listOf("offer", "answer", "ice"))
        val body = JSONObject().put("clientSignalId", clientSignalId).put("type", type).put("payload", payload)
        return JSONObject(request(token, "POST", "/api/calls/$id/signals", body)).getInt("seq")
    }

    fun iceConfig(token: String, id: String): List<LumoIceServer> {
        require(uuid.matches(id))
        val root = JSONObject(request(token, "GET", "/api/calls/$id/ice-config"))
        val array = root.getJSONArray("iceServers")
        require(array.length() in 1..3) { "No ICE relay configured" }
        return (0 until array.length()).map { index ->
            val server = array.getJSONObject(index)
            val urls = server.getJSONArray("urls")
            LumoIceServer(
                (0 until urls.length()).map { urls.getString(it) },
                server.getString("username"), server.getString("credential")
            )
        }
    }

    fun signals(token: String, id: String, after: Int): List<LumoSignal> {
        require(uuid.matches(id) && after >= 0)
        val url = (base + "/api/calls/$id/signals").toHttpUrl().newBuilder()
            .addQueryParameter("after", after.toString()).build()
        val array = JSONObject(request(token, "GET", url.toString().removePrefix(base))).getJSONArray("signals")
        return (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            LumoSignal(obj.getInt("seq"), obj.getString("from"), obj.getString("type"), obj.getJSONObject("payload"))
        }
    }
}
