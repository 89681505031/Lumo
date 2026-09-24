package app.lumo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small encrypted recent-history cache for offline reading.
 *
 * - app-private storage only;
 * - one Android Keystore AES-GCM key per Lumo account;
 * - no session/login tokens are written here;
 * - only recent direct-message metadata/text and chat summaries are cached;
 * - attachment *IDs* may be cached, but never signed media URLs or file bytes.
 *
 * This is encryption at rest on this Android device, not end-to-end encryption.
 */
object LumoOfflineStore {
    private const val VERSION:Byte=1
    private const val IV_BYTES=12
    private const val MAX_CHATS=100
    private const val MAX_MESSAGES_PER_CHAT=150
    private const val MAX_PENDING_MESSAGES=100
    private const val FILE_PREFIX="lumo_offline_"
    private val lock=Any()

    private fun hash(value:String):String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(""){"%02x".format(it.toInt() and 0xff)}

    private fun accountHash(userId:String)=hash(userId).take(24)
    private fun peerHash(peerId:String)=hash(peerId).take(24)
    private fun alias(userId:String)="lumo.offline."+accountHash(userId)

    private fun key(userId:String):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val existing=store.getKey(alias(userId),null) as? SecretKey
        if(existing!=null)return existing
        val generator=KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias(userId),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun conversationsFile(context:Context,userId:String)=
        File(context.filesDir,FILE_PREFIX+accountHash(userId)+"_chats.bin")

    private fun historyFile(context:Context,userId:String,peerId:String)=
        File(context.filesDir,FILE_PREFIX+accountHash(userId)+"_peer_"+peerHash(peerId)+".bin")

    private fun pendingFile(context:Context,userId:String,peerId:String)=
        File(context.filesDir,FILE_PREFIX+accountHash(userId)+"_pending_"+peerHash(peerId)+".bin")

    private fun encrypt(userId:String,plain:ByteArray):ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(userId))
        val encrypted=cipher.doFinal(plain)
        val iv=cipher.iv
        require(iv.size==IV_BYTES)
        return ByteBuffer.allocate(1+IV_BYTES+encrypted.size)
            .put(VERSION).put(iv).put(encrypted).array()
    }

    private fun decrypt(userId:String,payload:ByteArray):ByteArray {
        require(payload.size>1+IV_BYTES+16){"Encrypted cache is too short"}
        val buffer=ByteBuffer.wrap(payload)
        require(buffer.get()==VERSION){"Unsupported offline cache version"}
        val iv=ByteArray(IV_BYTES).also{buffer.get(it)}
        val encrypted=ByteArray(buffer.remaining()).also{buffer.get(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(userId),GCMParameterSpec(128,iv))
        return cipher.doFinal(encrypted)
    }

    private fun write(context:Context,userId:String,file:File,json:String){
        synchronized(lock){
            val atomic=AtomicFile(file)
            var stream:java.io.FileOutputStream?=null
            try{
                val bytes=encrypt(userId,json.toByteArray(Charsets.UTF_8))
                stream=atomic.startWrite()
                stream.write(bytes)
                stream.fd.sync()
                atomic.finishWrite(stream)
                stream=null
            }catch(error:Throwable){
                if(stream!=null)atomic.failWrite(stream)
                throw error
            }
        }
    }

    private fun read(context:Context,userId:String,file:File):String? {
        if(!file.isFile)return null
        return synchronized(lock){
            runCatching{
                String(decrypt(userId,AtomicFile(file).readFully()),Charsets.UTF_8)
            }.getOrElse{
                // A corrupt/inaccessible ciphertext is never treated as valid history.
                // Delete only this cache blob; server history remains authoritative.
                runCatching{file.delete()}
                null
            }
        }
    }

    fun saveConversations(context:Context,userId:String,chats:List<Conversation>){
        val root=JSONObject().put("savedAt",System.currentTimeMillis())
        val array=JSONArray()
        chats.take(MAX_CHATS).forEach{chat->
            array.put(JSONObject()
                .put("peerId",chat.peer.id)
                .put("username",chat.peer.username)
                .put("displayName",chat.peer.displayName.take(50))
                .put("lastMessage",chat.lastMessage.take(4000))
                .put("lastAt",chat.lastAt))
        }
        root.put("items",array)
        write(context.applicationContext,userId,conversationsFile(context,userId),root.toString())
    }

    fun loadConversations(context:Context,userId:String):List<Conversation>{
        val raw=read(context.applicationContext,userId,conversationsFile(context,userId))
            ?:return emptyList()
        return runCatching{
            val root=JSONObject(raw)
            val a=root.getJSONArray("items")
            buildList{
                for(i in 0 until minOf(a.length(),MAX_CHATS)){
                    val o=a.getJSONObject(i)
                    val peer=User(
                        o.getString("peerId"),
                        o.getString("username"),
                        o.getString("displayName")
                    )
                    add(Conversation(
                        peer=peer,
                        lastMessage=o.optString("lastMessage"),
                        lastAt=o.optString("lastAt")
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveHistory(context:Context,userId:String,peerId:String,messages:List<Msg>){
        val recent=messages.sortedBy{it.createdAt}.takeLast(MAX_MESSAGES_PER_CHAT)
        val root=JSONObject().put("savedAt",System.currentTimeMillis())
        val a=JSONArray()
        recent.forEach{m->
            // Refuse unrelated records even if a UI bug passes a mixed list.
            if(!((m.from==userId&&m.to==peerId)||(m.from==peerId&&m.to==userId)))
                return@forEach
            a.put(JSONObject()
                .put("id",m.id)
                .put("from",m.from)
                .put("to",m.to)
                .put("text",m.text.take(4000))
                .put("createdAt",m.createdAt)
                .put("deliveredAt",m.deliveredAt)
                .put("readAt",m.readAt)
                .put("clientMessageId",m.clientMessageId)
                .put("attachmentId",m.attachmentId))
        }
        root.put("items",a)
        write(
            context.applicationContext,userId,
            historyFile(context,userId,peerId),root.toString()
        )
    }

    fun loadHistory(context:Context,userId:String,peerId:String):List<Msg>{
        val raw=read(
            context.applicationContext,userId,
            historyFile(context,userId,peerId)
        )?:return emptyList()
        return runCatching{
            val root=JSONObject(raw)
            val a=root.getJSONArray("items")
            buildList{
                for(i in 0 until minOf(a.length(),MAX_MESSAGES_PER_CHAT)){
                    val o=a.getJSONObject(i)
                    val m=Msg(
                        id=o.getString("id"),
                        from=o.getString("from"),
                        to=o.getString("to"),
                        text=o.optString("text").take(4000),
                        createdAt=o.optString("createdAt"),
                        deliveredAt=o.optString("deliveredAt"),
                        readAt=o.optString("readAt"),
                        clientMessageId=o.optString("clientMessageId"),
                        attachmentId=o.optString("attachmentId")
                    )
                    if((m.from==userId&&m.to==peerId)||(m.from==peerId&&m.to==userId))
                        add(m)
                }
            }.sortedBy{it.createdAt}
        }.getOrDefault(emptyList())
    }

    fun savePending(
        context:Context,userId:String,peerId:String,pending:List<PendingMessage>
    ){
        val file=pendingFile(context.applicationContext,userId,peerId)
        if(pending.isEmpty()){
            synchronized(lock){runCatching{file.delete()}}
            return
        }
        val root=JSONObject().put("savedAt",System.currentTimeMillis())
        val a=JSONArray()
        pending.takeLast(MAX_PENDING_MESSAGES).forEach{item->
            if(item.clientMessageId.isBlank()||item.text.isBlank())return@forEach
            a.put(JSONObject()
                .put("clientMessageId",item.clientMessageId)
                .put("text",item.text.take(4000)))
        }
        root.put("items",a)
        write(context.applicationContext,userId,file,root.toString())
    }

    fun loadPending(
        context:Context,userId:String,peerId:String
    ):List<PendingMessage>{
        val raw=read(
            context.applicationContext,userId,
            pendingFile(context,userId,peerId)
        )?:return emptyList()
        return runCatching{
            val root=JSONObject(raw)
            val a=root.getJSONArray("items")
            buildList{
                for(i in 0 until minOf(a.length(),MAX_PENDING_MESSAGES)){
                    val o=a.getJSONObject(i)
                    val id=o.optString("clientMessageId")
                    val text=o.optString("text").take(4000)
                    if(id.isNotBlank()&&text.isNotBlank())
                        add(PendingMessage(id,text))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun cacheFileCount(context:Context,userId:String):Int {
        val prefix=FILE_PREFIX+accountHash(userId)+"_"
        return context.filesDir.listFiles()
            ?.count{it.isFile&&it.name.startsWith(prefix)}
            ?:0
    }

    fun clearAccount(context:Context,userId:String):Int {
        val prefix=FILE_PREFIX+accountHash(userId)+"_"
        var removed=0
        synchronized(lock){
            context.filesDir.listFiles()?.forEach{file->
                if(file.isFile&&file.name.startsWith(prefix)&&file.delete())removed++
            }
            runCatching{
                val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
                if(store.containsAlias(alias(userId)))store.deleteEntry(alias(userId))
            }
        }
        return removed
    }
}
