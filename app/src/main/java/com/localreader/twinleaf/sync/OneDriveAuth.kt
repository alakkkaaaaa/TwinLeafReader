package com.localreader.twinleaf.sync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DeviceAuthorization(
    val deviceCode:String,
    val userCode:String,
    val verificationUri:String,
    val message:String,
    val expiresAt:Long,
    val intervalSeconds:Long,
)

internal data class OneDriveToken(val accessToken:String,val refreshToken:String,val expiresAt:Long)

/** OAuth public-client device flow. No client secret is stored in the APK. */
class OneDriveAuth(context:Context) {
    private val app=context.applicationContext
    private val preferences=context.applicationContext.getSharedPreferences("onedrive_sync",Context.MODE_PRIVATE)
    private val generation:Long get()=preferences.getLong("auth_generation",0L)
    var clientId:String
        get()=preferences.getString("client_id","").orEmpty()
        set(value) {
            val next=value.trim()
            if(next!=clientId) signOut()
            preferences.edit().putString("client_id",next).apply()
        }

    val isSignedIn:Boolean get()=preferences.getString("refresh_token",null)!=null

    suspend fun beginSignIn():DeviceAuthorization {
        currentCoroutineContext().ensureActive()
        val expectedGeneration=generation
        val id=clientId.requireClientId()
        val result=postForm(DEVICE_CODE_URL,mapOf("client_id" to id,"scope" to SCOPES))
        currentCoroutineContext().ensureActive()
        checkGeneration(expectedGeneration)
        if(result.status !in 200..299) throw graphError(result.body,"无法开始 OneDrive 登录")
        val json=JSONObject(result.body)
        return DeviceAuthorization(
            json.getString("device_code"),json.getString("user_code"),json.getString("verification_uri"),
            json.optString("message","请在浏览器中输入上方代码完成登录。"),
            System.currentTimeMillis()+json.getLong("expires_in")*1000L,
            json.optLong("interval",5L).coerceAtLeast(2L),
        )
    }

    suspend fun awaitSignIn(authorization:DeviceAuthorization) {
        val expectedGeneration=generation
        var interval=authorization.intervalSeconds
        while(System.currentTimeMillis()<authorization.expiresAt) {
            delay(interval*1000L)
            checkGeneration(expectedGeneration)
            val response=postForm(TOKEN_URL,mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to clientId.requireClientId(),"device_code" to authorization.deviceCode))
            currentCoroutineContext().ensureActive()
            checkGeneration(expectedGeneration)
            val json=runCatching { JSONObject(response.body) }.getOrElse { JSONObject() }
            if(response.status in 200..299) { saveToken(json,expectedGeneration);return }
            when(json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval+=5
                "authorization_declined" -> throw IllegalStateException("你取消了 Microsoft 授权。")
                "expired_token","bad_verification_code" -> throw IllegalStateException("登录代码已过期，请重新获取。")
                else -> throw graphError(response.body,"OneDrive 登录失败")
            }
        }
        throw IllegalStateException("登录代码已过期，请重新获取。")
    }

    internal suspend fun validAccessToken():String {
        currentCoroutineContext().ensureActive()
        val expectedGeneration=generation
        val access=preferences.getString("access_token",null)
        val expires=preferences.getLong("expires_at",0L)
        if(access!=null && expires-System.currentTimeMillis()>120_000L) return access
        val refresh=preferences.getString("refresh_token",null) ?: throw IllegalStateException("请先登录 OneDrive。")
        val response=postForm(TOKEN_URL,mapOf("client_id" to clientId.requireClientId(),
            "grant_type" to "refresh_token","refresh_token" to refresh,"scope" to SCOPES))
        currentCoroutineContext().ensureActive()
        checkGeneration(expectedGeneration)
        if(response.status !in 200..299) {
            if(response.status in listOf(400,401)) signOut()
            throw graphError(response.body,"OneDrive 登录已失效，请重新登录")
        }
        return saveToken(JSONObject(response.body),expectedGeneration).accessToken
    }

    fun signOut() {
        synchronized(AUTH_LOCK) {
            preferences.edit().remove("access_token").remove("refresh_token").remove("expires_at")
                .putLong("auth_generation",generation+1L)
                .putBoolean("enabled",false).remove("selected_books").remove("source_books")
                .remove("account_directory").remove("last_sync_at").remove("last_status").apply()
            CloudSyncScheduler.cancel(app)
        }
    }

    private fun checkGeneration(expected:Long) {
        if(generation!=expected) throw CancellationException("OneDrive 登录状态已改变")
    }

    private fun saveToken(json:JSONObject,expectedGeneration:Long):OneDriveToken = synchronized(AUTH_LOCK) {
        checkGeneration(expectedGeneration)
        val previousRefresh=preferences.getString("refresh_token","").orEmpty()
        val refresh=json.optString("refresh_token",previousRefresh).ifBlank { previousRefresh }
        require(refresh.isNotBlank()) { "Microsoft 未返回可续期授权，请确认已允许 offline_access 后重新登录。" }
        val token=OneDriveToken(json.getString("access_token"),refresh,
            System.currentTimeMillis()+json.optLong("expires_in",3600L)*1000L)
        preferences.edit().putString("access_token",token.accessToken).putString("refresh_token",token.refreshToken)
            .putLong("expires_at",token.expiresAt).apply()
        CloudSyncScheduler.schedule(app)
        token
    }

    private fun String.requireClientId():String {
        val value=trim()
        require(CLIENT_ID.matches(value)) { "请先填写有效的 Microsoft Entra 应用 Client ID。" }
        return value
    }

    companion object {
        private val AUTH_LOCK=Any()
        private const val DEVICE_CODE_URL="https://login.microsoftonline.com/common/oauth2/v2.0/devicecode?mkt=zh-CN"
        private const val TOKEN_URL="https://login.microsoftonline.com/common/oauth2/v2.0/token"
        private const val SCOPES="offline_access Files.ReadWrite.AppFolder"
        private val CLIENT_ID=Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

internal data class HttpResult(val status:Int,val body:String)

internal fun postForm(url:String,fields:Map<String,String>):HttpResult {
    val bytes=fields.entries.joinToString("&") { (key,value) ->
        "${URLEncoder.encode(key,"UTF-8") }=${URLEncoder.encode(value,"UTF-8") }"
    }.toByteArray(Charsets.UTF_8)
    val connection=(URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod="POST";connectTimeout=20_000;readTimeout=30_000;doOutput=true
        setRequestProperty("Content-Type","application/x-www-form-urlencoded")
        setRequestProperty("Accept","application/json")
        setFixedLengthStreamingMode(bytes.size)
    }
    return try {
        connection.outputStream.use { it.write(bytes) }
        val status=connection.responseCode
        val body=(if(status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        HttpResult(status,body)
    } finally { connection.disconnect() }
}

internal fun graphError(body:String,fallback:String):Exception {
    val message=runCatching {
        val root=JSONObject(body)
        root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?:root.optString("error_description").takeIf { it.isNotBlank() }
    }.getOrNull()
    return IllegalStateException(message ?: fallback)
}
