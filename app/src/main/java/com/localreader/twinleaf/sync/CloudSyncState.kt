package com.localreader.twinleaf.sync

import androidx.core.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Account-scoped, app-private cache. It contains no access or refresh tokens. */
internal class CloudSyncState(private val directory:File) {
    init { check(directory.isDirectory || directory.mkdirs()) { "无法建立云同步缓存目录。" } }

    private fun safe(name:String):File {
        require(name.matches(Regex("[a-zA-Z0-9._-]{1,180}"))) { "云同步缓存文件名无效。" }
        return File(directory,name)
    }

    fun read(name:String):String?=safe(name).takeIf(File::isFile)?.readText(Charsets.UTF_8)

    fun write(name:String,text:String) {
        val file=AtomicFile(safe(name));val output=file.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8));output.fd.sync();file.finishWrite(output) }
        catch(error:Throwable) { file.failWrite(output);throw error }
    }

    fun readBlob(name:String):String? {
        require(name.matches(Regex("data-[a-f0-9]{64}\\.json")))
        return read(name)?.also { require(name=="data-${cloudHash(it)}.json") { "本地同步分片校验失败。" } }
    }

    fun cache(payload:CloudPayload) {
        payload.blobs.forEach { (name,text) -> if(readBlob(name)==null) write(name,text) }
    }

    fun head(key:String,channel:String):CloudHead?=read("head-${digest("$key:$channel")}.json")?.let {
        runCatching { CloudSyncProtocol.readHead(JSONObject(it)) }.getOrNull()
    }

    fun saveHead(key:String,channel:String,head:CloudHead) {
        write("head-${digest("$key:$channel")}.json",canonicalJson(CloudSyncProtocol.headJson(head)))
    }

    fun cachedManifest(item:RemoteCloudItem):String? {
        if(item.eTag.isBlank()) return null
        val tag=read("manifest-${digest(item.id)}.etag") ?: return null
        if(tag!=item.eTag) return null
        return read("manifest-${digest(item.id)}.json")
    }

    fun cacheManifest(item:RemoteCloudItem,text:String) {
        if(item.eTag.isBlank()) return
        val key=digest(item.id)
        write("manifest-$key.json",text)
        write("manifest-$key.etag",item.eTag)
    }

    fun createConflict(bookId:String,entry:CloudEntry,channel:String,local:CloudHead,remote:CloudHead):String {
        val id=digest("${entry.key}:$channel:${local.hash}:${remote.hash}")
        val name="conflict-$id.json"
        if(read(name)==null) write(name,canonicalJson(JSONObject()
            .put("id",id).put("bookId",bookId).put("key",entry.key).put("title",entry.title)
            .put("channel",channel).put("deviceName",entry.deviceName)
            .put("createdAt",System.currentTimeMillis()).put("resolved",false)
            .put("local",CloudSyncProtocol.headJson(local)).put("remote",CloudSyncProtocol.headJson(remote))))
        return id
    }

    fun conflictFiles():List<File> = directory.listFiles { file ->
        file.isFile && file.name.matches(Regex("conflict-[a-f0-9]{64}\\.json"))
    }?.toList().orEmpty()

    fun conflicts():List<SyncConflictInfo> = conflictFiles().mapNotNull { file -> runCatching {
        val value=JSONObject(file.readText(Charsets.UTF_8));if(value.optBoolean("resolved")) null else SyncConflictInfo(
            value.getString("id"),value.optString("title","云端内容"),value.getString("channel"),
            value.optString("deviceName","其他设备"),value.getLong("createdAt"))
    }.getOrNull() }.sortedByDescending(SyncConflictInfo::createdAt)

    fun conflict(id:String):JSONObject {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return JSONObject(read("conflict-$id.json") ?: error("同步冲突已不存在。"))
    }

    fun resolveConflict(id:String) {
        val value=conflict(id).put("resolved",true).put("resolvedAt",System.currentTimeMillis())
        write("conflict-$id.json",canonicalJson(value))
    }

    fun backup(key:String,channel:String,text:String) {
        val prefix="backup-${digest("$key:$channel")}-"
        val name="$prefix${cloudHash(text)}.json"
        if(read(name)==null) write(name,text)
        directory.listFiles { file -> file.isFile && file.name.startsWith(prefix) }
            ?.sortedByDescending(File::lastModified)?.drop(10)?.forEach(File::delete)
    }

    companion object {
        fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
