package com.localreader.twinleaf.sync

import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object OneDriveTransportRules {
    private val legacy=Regex("snapshot-[a-zA-Z0-9-]{1,100}\\.json")
    private val generated=Regex("(?:v2-manifest-[a-zA-Z0-9-]{1,100}\\.json|data-[a-f0-9]{64}\\.json|source-[a-f0-9]{64}\\.(?:pdf|epub))")

    fun isLegacyName(name:String)=legacy.matches(name)
    fun requireName(name:String) { require(generated.matches(name) || legacy.matches(name)) { "云端文件名无效。" } }

    fun requireHttps(value:String):URI {
        val uri=try { URI(value) } catch(_:Exception) { throw IllegalArgumentException("OneDrive 链接无效。") }
        require(uri.scheme.equals("https",ignoreCase=true) && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawFragment==null && (uri.port==-1 || uri.port==443)) { "OneDrive 必须使用安全 HTTPS 链接。" }
        return uri
    }

    fun requireGraphUrl(value:String) {
        val uri=requireHttps(value)
        require(uri.host.equals("graph.microsoft.com",ignoreCase=true) && uri.rawPath.startsWith("/v1.0/")) { "OneDrive Graph 链接无效。" }
    }

    fun retryAfterMillis(value:String?,nowMillis:Long=System.currentTimeMillis()):Long? {
        if(value==null) return null
        value.trim().toLongOrNull()?.let { seconds -> return seconds.coerceIn(0,Long.MAX_VALUE/1000)*1000 }
        return try { (ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-nowMillis).coerceAtLeast(0) }
        catch(_:Exception) { null }
    }

    fun rangeStart(value:String,size:Long):Long {
        require(Regex("[0-9]+-(?:[0-9]+)?").matches(value)) { "OneDrive 上传范围无效。" }
        val start=value.substringBefore('-').toLongOrNull()
        require(start!=null && start>=0 && start<size) { "OneDrive 上传位置无效。" }
        return start
    }
}
