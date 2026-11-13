package com.localreader.twinleaf.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class OutlineEntry(val id:String, val title:String, val page:Int, val chapter:Int=-1, val level:Int=1, val anchor:String?=null) {
    val locationLabel:String get()=if(chapter<0) "$page" else "${chapter+1} · $page"
}

internal fun outlineEntry(id:String,title:String,page:String,chapter:String,chapterCount:Int,pageLimit:Int):OutlineEntry? {
    val name=title.trim().takeIf { it.isNotEmpty() && it.length<=100 } ?: return null
    val number=validPage(page,pageLimit) ?: return null
    val section=if(chapterCount>0) (validPage(chapter,chapterCount) ?: return null)-1 else -1
    return OutlineEntry(id,name,number,section)
}

class BookOutlineRepository(context:Context) {
    private val prefs=context.getSharedPreferences("reader_book_outline",Context.MODE_PRIVATE)
    private fun key(uri:String)=MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
    fun load(uri:String):List<OutlineEntry> = runCatching {
        val array=JSONArray(prefs.getString(key(uri),"[]"))
        (0 until array.length()).mapNotNull { index -> runCatching {
            val item=array.getJSONObject(index)
            OutlineEntry(item.getString("id"),item.getString("title"),item.getInt("page"),item.optInt("chapter",-1),item.optInt("level",1).coerceIn(1,12))
                .takeIf { it.id.isNotBlank() && it.title.isNotBlank() && it.page>0 && it.chapter>=-1 }
        }.getOrNull() }.distinctBy { it.id }
    }.getOrDefault(emptyList())
    fun put(uri:String,entry:OutlineEntry):List<OutlineEntry> {
        require(entry.title.isNotBlank() && entry.page>0 && entry.chapter>=-1)
        val saved=entry.copy(id=entry.id.ifBlank { UUID.randomUUID().toString() },title=entry.title.trim().take(100))
        val entries=load(uri).toMutableList();val index=entries.indexOfFirst { it.id==saved.id }
        if(index<0) entries.add(saved) else entries[index]=saved
        return save(uri,normalizeLevels(entries))
    }
    fun remove(uri:String,id:String):List<OutlineEntry> {
        val entries=load(uri).toMutableList();val index=entries.indexOfFirst { it.id==id }
        if(index<0) return entries
        val level=entries.removeAt(index).level
        var i=index
        while(i<entries.size && entries[i].level>level) { entries[i]=entries[i].copy(level=entries[i].level-1);i++ }
        return save(uri,entries)
    }
    fun importEntries(uri:String,entries:List<OutlineEntry>,replace:Boolean)=save(uri,
        if(replace) entries else mergeOutline(load(uri),entries))
    fun collapsed(uri:String):Set<String> = prefs.getStringSet(key(uri)+"-fold",emptySet()).orEmpty().toSet()
    fun builtinMode(uri:String,default:Boolean)=prefs.getBoolean(key(uri)+"-mode",default)
    fun setBuiltinMode(uri:String,builtin:Boolean) { prefs.edit().putBoolean(key(uri)+"-mode",builtin).apply() }
    fun setCollapsed(uri:String,ids:Set<String>) { prefs.edit().putStringSet(key(uri)+"-fold",ids.toSet()).apply() }
    fun clear(uri:String) { prefs.edit().remove(key(uri)).remove(key(uri)+"-fold").remove(key(uri)+"-mode").apply() }
    private fun save(uri:String,entries:List<OutlineEntry>):List<OutlineEntry> {
        val array=JSONArray()
        entries.forEach { e->array.put(JSONObject().apply {
            put("id",e.id);put("title",e.title);put("page",e.page);put("chapter",e.chapter);put("level",e.level)
        }) }
        prefs.edit().putString(key(uri),array.toString()).apply()
        return load(uri)
    }
}
