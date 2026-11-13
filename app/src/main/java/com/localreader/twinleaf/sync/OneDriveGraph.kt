package com.localreader.twinleaf.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

internal data class RemoteSnapshot(val id:String,val name:String,val modifiedAt:String)
internal data class RemoteCloudItem(val id:String,val name:String,val eTag:String,val size:Long)

/** App-folder-only transport. Preauthenticated URLs never receive the Graph bearer token. */
internal class OneDriveGraph(private val auth:OneDriveAuth,private val stateDir:File?=null) {
    suspend fun accountKey():String {
        val root=JSONObject(request("GET","$GRAPH/me/drive/special/approot?\$select=id,parentReference").text)
        val drive=root.getJSONObject("parentReference").getString("driveId")
        require(drive.isNotBlank() && root.getString("id").isNotBlank()) { "OneDrive 未返回账户标识。" }
        return "${auth.clientId.lowercase()}:$drive:${root.getString("id")}"
    }

    suspend fun listFiles():List<RemoteCloudItem> = listItems().map(::remoteItem)

    suspend fun getFile(name:String):RemoteCloudItem? {
        OneDriveTransportRules.requireName(name)
        return try {
            remoteItem(JSONObject(request("GET","$GRAPH/me/drive/special/approot:/$name?\$select=id,name,eTag,size").text))
        } catch(error:CloudHttpException) { if(error.status==404) null else throw error }
    }

    suspend fun putText(name:String,text:String):RemoteCloudItem {
        OneDriveTransportRules.requireName(name)
        val bytes=text.toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_TEXT_BYTES) { "同步数据超过大小限制。" }
        return remoteItem(JSONObject(request("PUT","$GRAPH/me/drive/special/approot:/$name:/content",
            bytes,contentType="application/json").text))
    }

    suspend fun downloadText(id:String,maxBytes:Long=MAX_TEXT_BYTES):String {
        require(maxBytes in 0..MAX_TEXT_BYTES) { "同步数据大小限制无效。" }
        return request("GET",contentUrl(id),maxBytes=maxBytes,allowDownloadRedirect=true).text
    }

    suspend fun listSnapshots():List<RemoteSnapshot> = listItems().mapNotNull { item ->
        val name=item.optString("name")
        if(OneDriveTransportRules.isLegacyName(name))
            RemoteSnapshot(item.getString("id"),name,item.optString("lastModifiedDateTime")) else null
    }

    suspend fun download(itemId:String):String=downloadText(itemId)
    suspend fun upload(name:String,json:String) { require(OneDriveTransportRules.isLegacyName(name));putText(name,json) }

    private suspend fun listItems():List<JSONObject> {
        request("GET","$GRAPH/me/drive/special/approot")
        var next:String?="$GRAPH/me/drive/special/approot/children?\$select=id,name,eTag,size,file,lastModifiedDateTime&\$top=200"
        val seen=mutableSetOf<String>()
        val items=mutableListOf<JSONObject>()
        while(next!=null) {
            currentCoroutineContext().ensureActive()
            check(seen.size<100) { "OneDrive 返回的分页数量异常。" }
            check(seen.add(next)) { "OneDrive 返回重复分页链接。" }
            OneDriveTransportRules.requireGraphUrl(next)
            val page=JSONObject(request("GET",next).text)
            val values=page.getJSONArray("value")
            for(index in 0 until values.length()) {
                val item=values.getJSONObject(index)
                if(item.has("file")) items.add(item)
                check(items.size<=10_000) { "OneDrive 应用文件夹项目过多。" }
            }
            next=page.optString("@odata.nextLink").takeIf { it.isNotBlank() }
        }
        return items
    }

    suspend fun uploadFile(name:String,file:File,onProgress:(Long,Long)->Unit={_,_->}):RemoteCloudItem=withContext(Dispatchers.IO) {
        OneDriveTransportRules.requireName(name)
        require(file.isFile) { "找不到待上传原文件。" }
        val size=file.length()
        require(size>0) { "无法上传空原文件。" }
        val fingerprint=sha256(file)
        if(name.startsWith("source-")) require(name.substringAfter("source-").substringBeforeLast('.')==fingerprint) { "原文件校验失败，无法上传。" }
        val scope=accountKey()
        val cache=stateDir?.let { directory ->
            check(directory.isDirectory || directory.mkdirs()) { "无法建立传输状态目录。" }
            File(directory,"upload-${digest("$scope:$name:$fingerprint")}.json")
        }
        var session=loadSession(cache)
        if(session!=null) {
            val previousUrl=session.getString("uploadUrl")
            session=try { JSONObject(request("GET",previousUrl,authenticated=false).text).put("uploadUrl",previousUrl) }
            catch(error:CloudHttpException) {
                if(error.status==404 || error.status==410) { cache?.delete();null } else throw error
            }
        }
        if(session==null) {
            val body=JSONObject().put("item",JSONObject().put("@microsoft.graph.conflictBehavior","replace").put("name",name))
            session=JSONObject(request("POST","$GRAPH/me/drive/special/approot:/$name:/createUploadSession",body.toString().toByteArray(),contentType="application/json").text)
            if(!session.has("nextExpectedRanges")) session.put("nextExpectedRanges",JSONArray().put("0-"))
        }
        val uploadUrl=session.getString("uploadUrl")
        OneDriveTransportRules.requireHttps(uploadUrl)
        saveSession(cache,session)
        var offset=nextOffset(session,size)
        onProgress(offset,size)
        var recoveries=0
        RandomAccessFile(file,"r").use { input ->
            while(offset<size) {
                currentCoroutineContext().ensureActive()
                val length=minOf(CHUNK_BYTES.toLong(),size-offset).toInt()
                val bytes=ByteArray(length)
                input.seek(offset);input.readFully(bytes)
                val response=try {
                    request("PUT",uploadUrl,bytes,authenticated=false,
                        headers=mapOf("Content-Range" to "bytes $offset-${offset+length-1}/$size"))
                } catch(error:CloudHttpException) {
                    // Lost acknowledgements may leave a chunk already committed. Ask the server.
                    if(error.status==416 && recoveries++<3) {
                        val recovered=JSONObject(request("GET",uploadUrl,authenticated=false).text).put("uploadUrl",uploadUrl)
                        offset=nextOffset(recovered,size);saveSession(cache,recovered);continue
                    }
                    if(error.status==404 || error.status==410) {
                        cache?.delete()
                        val completed=getFile(name)
                        if(completed!=null && completed.size==size && name.startsWith("source-")) {
                            onProgress(size,size);return@withContext completed
                        }
                    }
                    throw error
                }
                val json=JSONObject(response.text)
                if(response.status==200 || response.status==201) {
                    val completed=remoteItem(json)
                    check(completed.size==size) { "OneDrive 上传大小校验失败。" }
                    cache?.delete();onProgress(size,size);return@withContext completed
                }
                check(response.status==202) { "OneDrive 返回未知的上传状态。" }
                json.put("uploadUrl",uploadUrl)
                val next=nextOffset(json,size)
                check(next>offset) { "OneDrive 上传未取得进展，请重试。" }
                offset=next;saveSession(cache,json);onProgress(offset,size)
            }
        }
        error("OneDrive 上传尚未确认完成，请重试。")
    }

    suspend fun downloadFile(id:String,destination:File,expectedSize:Long,onProgress:(Long,Long)->Unit={_,_->}):Unit=withContext(Dispatchers.IO) {
        require(expectedSize>=0) { "原文件大小无效。" }
        val parent=destination.absoluteFile.parentFile ?: error("下载路径无效。")
        check(parent.isDirectory || parent.mkdirs()) { "无法建立下载目录。" }
        val partial=File.createTempFile(".${destination.name.take(60)}-",".part",parent)
        try {
            retrying {
                openResponse("GET",contentUrl(id),authenticated=true,allowDownloadRedirect=true) { connection ->
                    val reported=connection.contentLengthLong
                    check(reported<0 || reported==expectedSize) { "OneDrive 原文件大小已变化，请重新同步。" }
                    var count=0L
                    partial.outputStream().use { output ->
                        connection.inputStream.use { input ->
                            val buffer=ByteArray(64*1024)
                            while(true) {
                                currentCoroutineContext().ensureActive()
                                val read=input.read(buffer)
                                if(read<0) break
                                count+=read
                                check(count<=expectedSize) { "OneDrive 原文件超出声明大小。" }
                                output.write(buffer,0,read);onProgress(count,expectedSize)
                            }
                        }
                        output.fd.sync()
                    }
                    if(count!=expectedSize) throw IOException("OneDrive 原文件下载不完整。")
                }
            }
            currentCoroutineContext().ensureActive()
            Files.move(partial.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
    }

    private data class Response(val status:Int,val text:String)

    private suspend fun request(method:String,url:String,body:ByteArray?=null,contentType:String="application/octet-stream",
        authenticated:Boolean=true,headers:Map<String,String> = emptyMap(),maxBytes:Long=MAX_TEXT_BYTES,
        allowDownloadRedirect:Boolean=false):Response=withContext(Dispatchers.IO) {
        retrying {
            openResponse(method,url,body,contentType,authenticated,headers,allowDownloadRedirect) { connection ->
                Response(connection.responseCode,connection.inputStream.use { readBounded(it,maxBytes).toString(Charsets.UTF_8) })
            }
        }
    }

    private suspend fun <T> openResponse(method:String,url:String,body:ByteArray?=null,contentType:String="application/octet-stream",
        authenticated:Boolean,headers:Map<String,String> = emptyMap(),allowDownloadRedirect:Boolean=false,
        consume:suspend (HttpURLConnection)->T):T {
        var target=url
        var bearer=authenticated
        repeat(6) {
            currentCoroutineContext().ensureActive()
            if(bearer) OneDriveTransportRules.requireGraphUrl(target) else OneDriveTransportRules.requireHttps(target)
            val token=if(bearer) auth.validAccessToken() else null
            val connection=(URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod=method;connectTimeout=20_000;readTimeout=45_000;instanceFollowRedirects=false
                setRequestProperty("Accept","application/json");setRequestProperty("Accept-Encoding","identity")
                if(token!=null) setRequestProperty("Authorization","Bearer $token")
                headers.forEach { (key,value)->setRequestProperty(key,value) }
                if(body!=null) { doOutput=true;setRequestProperty("Content-Type",contentType);setFixedLengthStreamingMode(body.size) }
            }
            try {
                if(body!=null) connection.outputStream.use { output ->
                    var offset=0
                    while(offset<body.size) {
                        currentCoroutineContext().ensureActive()
                        val count=minOf(64*1024,body.size-offset);output.write(body,offset,count);offset+=count
                    }
                }
                val status=connection.responseCode
                if(allowDownloadRedirect && method=="GET" && status in listOf(301,302,303,307,308)) {
                    val location=connection.getHeaderField("Location") ?: error("OneDrive 下载链接缺失。")
                    target=URL(URL(target),location).toString()
                    OneDriveTransportRules.requireHttps(target)
                    bearer=false
                } else {
                    if(status !in 200..299) throw CloudHttpException(status,OneDriveTransportRules.retryAfterMillis(connection.getHeaderField("Retry-After")))
                    return consume(connection)
                }
            } finally { connection.disconnect() }
        }
        error("OneDrive 下载重定向次数过多。")
    }

    private suspend fun <T> retrying(block:suspend ()->T):T {
        repeat(4) { attempt ->
            currentCoroutineContext().ensureActive()
            try { return block() }
            catch(error:IOException) {
                currentCoroutineContext().ensureActive()
                val retryable=error !is CloudHttpException || error.status==429 || error.status in 500..599
                val wait=(error as? CloudHttpException)?.retryAfterMillis ?: (1_000L shl attempt)
                if(!retryable || attempt==3 || wait>60_000L) {
                    if(error is CloudHttpException) throw error
                    // Platform IO exception messages can contain preauthenticated URLs.
                    throw IOException("OneDrive 网络传输失败，请检查网络后重试。")
                }
                delay(wait.coerceAtLeast(0))
            }
        }
        error("OneDrive 重试失败。")
    }

    private suspend fun readBounded(input:InputStream,maxBytes:Long):ByteArray {
        val output=ByteArrayOutputStream()
        val buffer=ByteArray(64*1024)
        var total=0L
        while(true) {
            currentCoroutineContext().ensureActive()
            val count=input.read(buffer)
            if(count<0) return output.toByteArray()
            total+=count
            check(total<=maxBytes) { "OneDrive 同步数据超过大小限制。" }
            output.write(buffer,0,count)
        }
    }

    private fun remoteItem(json:JSONObject):RemoteCloudItem {
        val size=json.getLong("size")
        require(size>=0) { "OneDrive 文件大小无效。" }
        return RemoteCloudItem(json.getString("id"),json.getString("name"),json.optString("eTag"),size)
    }

    private fun loadSession(file:File?):JSONObject? {
        if(file==null || !file.isFile) return null
        return try {
            check(file.length()<=64*1024)
            JSONObject(file.readText()).also {
                OneDriveTransportRules.requireHttps(it.getString("uploadUrl"))
                check(Instant.parse(it.getString("expirationDateTime")).toEpochMilli()>System.currentTimeMillis()+30_000)
            }
        } catch(_:Exception) { file.delete();null }
    }

    private fun saveSession(file:File?,json:JSONObject) {
        if(file==null) return
        val temporary=File.createTempFile("upload-",".tmp",file.parentFile)
        try {
            temporary.outputStream().use { output -> output.write(json.toString().toByteArray());output.fd.sync() }
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun nextOffset(json:JSONObject,size:Long):Long {
        val ranges=json.getJSONArray("nextExpectedRanges")
        require(ranges.length()>0) { "OneDrive 未返回上传位置。" }
        return (0 until ranges.length()).minOf { OneDriveTransportRules.rangeStart(ranges.getString(it),size) }
    }

    private suspend fun sha256(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(64*1024)
            while(true) {
                currentCoroutineContext().ensureActive()
                val count=input.read(buffer);if(count<0) break;hash.update(buffer,0,count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun contentUrl(id:String):String {
        require(id.isNotBlank()) { "OneDrive 文件标识为空。" }
        return "$GRAPH/me/drive/items/${URLEncoder.encode(id,"UTF-8").replace("+","%20")}/content"
    }

    companion object {
        private const val GRAPH="https://graph.microsoft.com/v1.0"
        private const val MAX_TEXT_BYTES=64L*1024*1024
        private const val CHUNK_BYTES=16*320*1024
    }
}

/** Excludes response bodies and URLs, which can contain credentials. */
internal class CloudHttpException(val status:Int,val retryAfterMillis:Long?=null):IOException(
    when(status) {
        401 -> "OneDrive 登录已失效，请重新登录。"
        403 -> "OneDrive 拒绝访问应用文件夹，请检查应用权限或账户策略。"
        429 -> "OneDrive 请求过于频繁，请稍后重试。"
        507 -> "OneDrive 可用空间不足。"
        else -> "OneDrive 请求失败（HTTP $status），请重试。"
    }
)
