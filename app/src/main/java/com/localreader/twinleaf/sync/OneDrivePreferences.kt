package com.localreader.twinleaf.sync

import android.content.Context
import java.util.UUID

enum class BookSyncMode { LOCAL_ONLY, METADATA, WITH_SOURCE }

class OneDrivePreferences(context:Context) {
    private val app=context.applicationContext
    private val preferences=context.applicationContext.getSharedPreferences("onedrive_sync",Context.MODE_PRIVATE)
    val deviceId:String get()=preferences.getString("device_id",null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("device_id",it).apply()
    }
    var enabled:Boolean
        get()=preferences.getBoolean("enabled",false)
        set(value) { setScheduledBoolean("enabled",value,false) }
    var syncProgress:Boolean
        get()=preferences.getBoolean("progress",true)
        set(value) { setScheduledBoolean("progress",value,true) }
    var syncInk:Boolean
        get()=preferences.getBoolean("ink",true)
        set(value) { setScheduledBoolean("ink",value,true) }
    var syncNotes:Boolean
        get()=preferences.getBoolean("notes",true)
        set(value) { setScheduledBoolean("notes",value,true) }
    var lastSyncAt:Long
        get()=preferences.getLong("last_sync_at",0L)
        set(value) { preferences.edit().putLong("last_sync_at",value).apply() }
    var lastStatus:String
        get()=preferences.getString("last_status","").orEmpty()
        set(value) { preferences.edit().putString("last_status",value).apply() }
    var wifiOnly:Boolean
        get()=preferences.getBoolean("wifi_only",true)
        set(value) { setScheduledBoolean("wifi_only",value,true) }
    var accountDirectory:String
        get()=preferences.getString("account_directory","").orEmpty()
        set(value) { preferences.edit().putString("account_directory",value).apply() }

    private fun setScheduledBoolean(key:String,value:Boolean,default:Boolean) {
        if(preferences.getBoolean(key,default)==value) return
        preferences.edit().putBoolean(key,value).apply()
        CloudSyncScheduler.schedule(app)
        CloudSyncScheduler.requestSoon(app)
    }

    fun bookMode(id:String):BookSyncMode = if(id !in selectedBookIds()) BookSyncMode.LOCAL_ONLY
        else if(id in preferences.getStringSet("source_books",emptySet()).orEmpty()) BookSyncMode.WITH_SOURCE else BookSyncMode.METADATA

    fun setBookMode(id:String,mode:BookSyncMode) {
        if(bookMode(id)==mode) return
        val selected=selectedBookIds().toMutableSet()
        val sources=preferences.getStringSet("source_books",emptySet()).orEmpty().toMutableSet()
        if(mode==BookSyncMode.LOCAL_ONLY) selected.remove(id) else selected.add(id)
        if(mode==BookSyncMode.WITH_SOURCE) sources.add(id) else sources.remove(id)
        preferences.edit().putStringSet("selected_books",selected).putStringSet("source_books",sources).apply()
        CloudSyncScheduler.schedule(app)
        CloudSyncScheduler.requestSoon(app)
    }

    fun selectedBookIds():Set<String> = preferences.getStringSet("selected_books",emptySet()).orEmpty().toSet()
    fun setBookSelected(id:String,selected:Boolean) {
        setBookMode(id,if(selected) BookSyncMode.METADATA else BookSyncMode.LOCAL_ONLY)
    }

    internal fun cachedIdentity(bookId:String):String?=preferences.getString("identity-$bookId",null)
    internal fun cacheIdentity(bookId:String,value:String) { preferences.edit().putString("identity-$bookId",value).apply() }
}
