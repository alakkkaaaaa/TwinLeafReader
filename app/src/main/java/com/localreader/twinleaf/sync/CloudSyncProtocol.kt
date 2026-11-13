package com.localreader.twinleaf.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class CloudBook(val key:String,val title:String,val format:String,val hasSource:Boolean,
    val sourceBytes:Long,val deviceName:String)
data class SyncConflictInfo(val id:String,val title:String,val channel:String,val deviceName:String,val createdAt:Long)
internal data class CloudHead(val hash:String,val index:String,val updatedAt:Long,val ancestors:List<String> = emptyList())
internal data class CloudSource(val name:String,val hash:String,val size:Long)
internal data class CloudEntry(val key:String,val title:String,val format:String,val deviceName:String,
    val channels:Map<String,CloudHead>,val source:CloudSource?=null)
internal data class CloudManifest(val deviceId:String,val records:List<CloudEntry>)
internal data class CloudPayload(val text:String,val hash:String,val index:String,val blobs:Map<String,String>)
internal val CLOUD_HASH=Regex("[a-f0-9]{64}")
private val CLOUD_DATA_NAME=Regex("data-[a-f0-9]{64}\\.json")
private const val MAX_CLOUD_JSON_BYTES=64L*1024*1024

/** Bound input before JSONObject's recursive parser, including the sum of all shards. */
internal class CloudJsonBudget(private val maxBytes:Long=MAX_CLOUD_JSON_BYTES,private val maxDepth:Int=64) {
    private var used=0L
    fun consume(text:String) {
        require(text.length.toLong()<=maxBytes-used) { "云端同步数据超过大小限制。" }
        val bytes=text.toByteArray(Charsets.UTF_8).size.toLong()
        require(bytes<=maxBytes-used) { "云端同步数据超过大小限制。" }
        used+=bytes
        var depth=0;var quote:Char?=null;var escaped=false
        for(char in text) {
            if(quote!=null) {
                if(escaped) escaped=false
                else if(char=='\\') escaped=true
                else if(char==quote) quote=null
            } else when(char) {
                '"' -> quote=char
                // JSONTokener accepts comments/single quotes; disallow them so they cannot
                // hide nesting from this standards-based preflight scanner.
                '\'','/','#' -> throw IllegalArgumentException("云端数据必须使用标准 JSON。")
                '{','[' -> { depth++;require(depth<=maxDepth) { "云端 JSON 嵌套过深。" } }
                '}',']' -> { depth--;require(depth>=0) { "云端 JSON 结构无效。" } }
            }
        }
        require(depth==0 && quote==null) { "云端 JSON 结构不完整。" }
    }
}

internal fun cloudHash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** Deterministic JSON makes hashes portable across platforms and independent of key order. */
internal fun canonicalJson(value:Any?):String = when(value) {
    null,JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonicalJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonicalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Number -> JSONObject.numberToString(value)
    is Boolean -> value.toString()
    else -> error("不支持的 JSON 类型")
}

internal enum class HeadRelation { SAME, REMOTE_NEWER, LOCAL_NEWER, CONFLICT }
internal fun headRelation(local:CloudHead,remote:CloudHead):HeadRelation = when {
    local.hash==remote.hash -> HeadRelation.SAME
    local.hash in remote.ancestors -> HeadRelation.REMOTE_NEWER
    remote.hash in local.ancestors -> HeadRelation.LOCAL_NEWER
    else -> HeadRelation.CONFLICT
}

internal object CloudSyncProtocol {
    val channels=setOf("progress","ink","note")
    fun validKey(key:String)=key.matches(Regex("book:[a-f0-9]{64}|note:[a-zA-Z0-9-]{1,100}"))
    fun headJson(head:CloudHead)=JSONObject().put("hash",head.hash).put("index",head.index)
        .put("updatedAt",head.updatedAt).put("ancestors",JSONArray(head.ancestors))
    fun readHead(json:JSONObject):CloudHead {
        val hash=json.getString("hash");val index=json.getString("index")
        require(CLOUD_HASH.matches(hash) && CLOUD_DATA_NAME.matches(index))
        val ancestors=json.optJSONArray("ancestors") ?: JSONArray();require(ancestors.length()<=64)
        val updatedAt=json.getLong("updatedAt");require(updatedAt>=0)
        return CloudHead(hash,index,updatedAt,List(ancestors.length()) { ancestors.getString(it).also { value -> require(CLOUD_HASH.matches(value)) } })
    }
    fun encode(manifest:CloudManifest):String=canonicalJson(JSONObject().put("version",2).put("deviceId",manifest.deviceId)
        .put("records",JSONArray().apply { manifest.records.sortedBy { it.key }.forEach { entry ->
            put(JSONObject().put("key",entry.key).put("title",entry.title).put("format",entry.format).put("deviceName",entry.deviceName)
                .put("channels",JSONObject().apply { entry.channels.forEach { (key,value) -> put(key,headJson(value)) } })
                .apply { entry.source?.let {
                    require(it.size in 1..20L*1024*1024*1024 && CLOUD_HASH.matches(it.hash))
                    require(it.name=="source-${it.hash}.${entry.format.lowercase()}" && entry.key=="book:${it.hash}")
                    put("source",JSONObject().put("name",it.name).put("hash",it.hash).put("size",it.size))
                } })
        } }))
    fun decode(text:String):CloudManifest {
        CloudJsonBudget().consume(text)
        val root=JSONObject(text);require(root.getInt("version")==2)
        val device=root.getString("deviceId");require(device.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        val records=root.getJSONArray("records");require(records.length()<=10000)
        val entries=List(records.length()) { i ->
            val obj=records.getJSONObject(i);val key=obj.getString("key");require(validKey(key))
            val format=obj.getString("format");require(format in setOf("PDF","EPUB","NOTE"))
            require((format=="NOTE")==key.startsWith("note:"))
            val values=obj.getJSONObject("channels")
            val heads=values.keys().asSequence().associateWith { channel -> require(channel in channels);readHead(values.getJSONObject(channel)) }
            val source=obj.optJSONObject("source")?.let {
                val hash=it.getString("hash");val name=it.getString("name");val size=it.getLong("size")
                require(format!="NOTE" && CLOUD_HASH.matches(hash) && size in 1..20L*1024*1024*1024)
                require(name=="source-$hash.${format.lowercase()}" && key=="book:$hash")
                CloudSource(name,hash,size)
            }
            CloudEntry(key,obj.getString("title").take(200),format,obj.optString("deviceName","其他设备").take(100),heads,source)
        }
        require(entries.map { it.key }.distinct().size==entries.size)
        return CloudManifest(device,entries)
    }
    fun payload(channel:String,text:String):CloudPayload {
        require(channel in channels)
        CloudJsonBudget().consume(text)
        val root=JSONObject(text);val normalized=canonicalJson(root);val blobs=linkedMapOf<String,String>()
        CloudJsonBudget().consume(normalized)
        val budget=CloudJsonBudget()
        fun blob(value:Any):String {
            val data=canonicalJson(value);budget.consume(data)
            val name="data-${cloudHash(data)}.json";blobs[name]=data;return name
        }
        val index=JSONObject().put("version",1).put("channel",channel)
        when(channel) {
            "ink" -> {
                val pages=root.getJSONObject("pages");root.remove("pages")
                index.put("header",root).put("pages",JSONObject().apply {
                    require(pages.length()<=100000)
                    pages.keys().asSequence().toList().sorted().forEach { page -> requirePage(page);put(page,blob(pages.getJSONArray(page))) }
                })
            }
            "note" -> {
                val strokes=root.getJSONArray("strokes");root.remove("strokes")
                require(strokes.length()<=32*100000)
                index.put("header",root).put("blocks",JSONArray().apply {
                    for(start in 0 until strokes.length() step 32) put(blob(JSONArray().apply {
                        for(i in start until minOf(start+32,strokes.length())) put(strokes.get(i))
                    }))
                })
            }
            else -> index.put("value",root)
        }
        val name=blob(index)
        return CloudPayload(normalized,cloudHash(normalized),name,blobs)
    }
    suspend fun unpack(channel:String,head:CloudHead,read:suspend (String)->String):String {
        require(channel in channels && CLOUD_HASH.matches(head.hash))
        val budget=CloudJsonBudget()
        suspend fun readBlob(name:String):String {
            currentCoroutineContext().ensureActive()
            require(CLOUD_DATA_NAME.matches(name)) { "云端分片文件名无效。" }
            return read(name).also { text ->
                currentCoroutineContext().ensureActive()
                budget.consume(text)
                require(name=="data-${cloudHash(text)}.json") { "云端分片校验不一致。" }
            }
        }
        val index=JSONObject(readBlob(head.index));require(index.getInt("version")==1 && index.getString("channel")==channel)
        val result=when(channel) {
            "ink" -> index.getJSONObject("header").apply {
                val pages=index.getJSONObject("pages");require(pages.length()<=100000)
                put("pages",JSONObject().apply { for(page in pages.keys()) {
                    requirePage(page);put(page,JSONArray(readBlob(pages.getString(page))))
                } })
            }
            "note" -> index.getJSONObject("header").apply {
                val blocks=index.getJSONArray("blocks");require(blocks.length()<=100000)
                put("strokes",JSONArray().apply { for(i in 0 until blocks.length()) {
                    val data=JSONArray(readBlob(blocks.getString(i)));require(data.length()<=32)
                    for(j in 0 until data.length()) put(data.get(j))
                } })
            }
            "progress" -> index.getJSONObject("value")
            else -> error("未知同步类型")
        }
        currentCoroutineContext().ensureActive()
        return canonicalJson(result).also {
            CloudJsonBudget().consume(it)
            currentCoroutineContext().ensureActive()
            require(cloudHash(it)==head.hash) { "云端数据校验不一致，未覆盖本机" }
        }
    }
    private fun requirePage(page:String) {
        val number=page.toIntOrNull()
        require(number!=null && number>=0 && page==number.toString()) { "云端页码无效。" }
    }
    fun advance(payload:CloudPayload,previous:CloudHead?,time:Long):CloudHead =
        if(previous?.hash==payload.hash) previous else CloudHead(payload.hash,payload.index,time,
            previous?.let { (listOf(it.hash)+it.ancestors).distinct().take(64) }.orEmpty())
}
