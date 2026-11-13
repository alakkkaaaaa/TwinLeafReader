package com.localreader.twinleaf.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class PageShortcut(val page: Int, val chapter: Int = -1, val fraction: Float = 0f, val layout: String = "pdf",val anchor:String?=null) {
    val id: String get() = "$chapter:$page:$layout"
    val label: String get() = if(chapter<0) "$page" else "${chapter+1} · $page"
}

internal fun validPage(text:String,count:Int):Int? = text.toIntOrNull()?.takeIf { it in 1..count }

class PageShortcutRepository(context:Context) {
    private val prefs=context.getSharedPreferences("reader_page_shortcuts",Context.MODE_PRIVATE)
    private fun key(uri:String)=MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun load(uri:String):List<PageShortcut> = runCatching {
        val array=JSONArray(prefs.getString(key(uri),"[]"))
        (0 until array.length()).mapNotNull { i ->
            val item=array.optJSONObject(i) ?: return@mapNotNull null
            val page=item.optInt("page",0)
            if(page<1) null else PageShortcut(page,item.optInt("chapter",-1),
                item.optDouble("fraction",0.0).toFloat().coerceIn(0f,1f),item.optString("layout","pdf"))
        }
    }.getOrDefault(emptyList())
    fun add(uri:String,target:PageShortcut):List<PageShortcut> {
        require(target.page>=1)
        return save(uri,(load(uri).filterNot { it.id==target.id }+target).sortedWith(compareBy({it.chapter},{it.page})))
    }
    fun remove(uri:String,id:String)=save(uri,load(uri).filterNot { it.id==id })
    fun clear(uri:String) { prefs.edit().remove(key(uri)).apply() }
    private fun save(uri:String,items:List<PageShortcut>):List<PageShortcut> {
        val array=JSONArray()
        items.forEach { item -> array.put(JSONObject().apply {
            put("page",item.page); put("chapter",item.chapter); put("fraction",item.fraction.toDouble()); put("layout",item.layout)
        }) }
        prefs.edit().putString(key(uri),array.toString()).apply()
        return items
    }
}
