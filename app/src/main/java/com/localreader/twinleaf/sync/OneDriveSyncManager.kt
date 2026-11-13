package com.localreader.twinleaf.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import com.localreader.twinleaf.data.BookSessionFactory
import com.localreader.twinleaf.data.LibraryBook
import com.localreader.twinleaf.data.LibraryRepository
import com.localreader.twinleaf.ink.InkStore
import com.localreader.twinleaf.note.NoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class SyncReport(val downloaded:Int,val uploaded:Int,val changed:Int,val skipped:Int,
    val errors:List<String> = emptyList(),val conflicts:Int=0)

private data class LocatedEntry(val deviceId:String,val entry:CloudEntry)
private data class SyncSession(
    val state:CloudSyncState,
    val directory:File,
    val files:MutableMap<String,RemoteCloudItem>,
    val entries:List<LocatedEntry>,
    val own:CloudManifest,
    val errors:MutableList<String>,
    val ownManifestBroken:Boolean,
    var downloaded:Int=0,
    var uploaded:Int=0,
)
private data class LocalChannel(val updatedAt:Long,val exactText:String,val payload:CloudPayload)

/**
 * OneDrive v2 synchronization. Every cloud object is immutable and content-addressed;
 * the per-device manifest is the only mutable remote file.
 */
class OneDriveSyncManager(context:Context) {
    private val app=context.applicationContext
    val auth=OneDriveAuth(app)
    val preferences=OneDrivePreferences(app)
    private val repository=LibraryRepository(app)
    private val graph=OneDriveGraph(auth,File(app.noBackupFilesDir,"onedrive-transfer"))

    suspend fun synchronize(onStatus:(String)->Unit={}):SyncReport=operation.withLock {
        withContext(Dispatchers.IO) {
            guardNetwork()
            val selected=preferences.selectedBookIds()
            check(selected.isNotEmpty()) { "请至少选择一本书或一份笔记。" }
            preferences.lastStatus="正在读取 OneDrive"
            onStatus("正在读取 OneDrive…")
            val session=openSession()
            if(session.ownManifestBroken) {
                val errors=(session.errors+"本机云端清单读取失败；为避免覆盖，已停止本次上传。").distinct()
                preferences.lastStatus="同步未完成：本机云端清单不可用"
                return@withContext SyncReport(session.downloaded,0,0,selected.size,errors,0)
            }
            // An incomplete remote view cannot safely establish ancestry. Keep all local data untouched.
            if(session.errors.isNotEmpty()) {
                preferences.lastStatus="同步未完成：云端清单读取不完整"
                return@withContext SyncReport(session.downloaded,0,0,selected.size,session.errors.toList(),0)
            }

            val books=withContext(Dispatchers.Main.immediate) { repository.load() }
            val ownRecords=session.own.records.associateBy { it.key }.toMutableMap()
            val remoteByKey=session.entries.groupBy { it.entry.key }
            val errors=mutableListOf<String>()
            var changed=0;var skipped=0;var conflicts=0
            onStatus("正在比较本机内容…")
            for(book in books) {
                currentCoroutineContext().ensureActive()
                val mode=preferences.bookMode(book.id)
                if(mode==BookSyncMode.LOCAL_ONLY) continue
                try {
                    guardNetwork()
                    val key=identity(book)
                    val aliases=buildList {
                        add(key)
                        if(!book.format.equals("NOTE",true)) {
                            // The URI behind a library id can be replaced in place. Recompute the
                            // old quick fingerprint instead of trusting an unbounded cache entry.
                            val legacy=legacyIdentity(book).also { preferences.cacheIdentity(book.id,it) }
                            if(legacy!=key) add(legacy)
                        }
                    }
                    val prior=aliases.firstNotNullOfOrNull { ownRecords[it] }
                    val sources=aliases.flatMap { remoteByKey[it].orEmpty() }
                    val channels=linkedMapOf<String,CloudHead>()
                    if(preferences.syncProgress) {
                        val result=mergeChannel(session,book,key,"progress",prior?.channels?.get("progress"),sources)
                        channels["progress"]=result.first;changed+=result.second.first;conflicts+=result.second.second
                    }
                    if(book.format.equals("PDF",true) && preferences.syncInk) {
                        val result=mergeChannel(session,book,key,"ink",prior?.channels?.get("ink"),sources)
                        channels["ink"]=result.first;changed+=result.second.first;conflicts+=result.second.second
                    }
                    if(book.format.equals("NOTE",true) && preferences.syncNotes) {
                        val result=mergeChannel(session,book,key,"note",prior?.channels?.get("note"),sources)
                        channels["note"]=result.first;changed+=result.second.first;conflicts+=result.second.second
                    }
                    // A disabled channel is omitted from the next manifest. Its immutable blobs are retained,
                    // but other devices can no longer discover it through this device's active record.
                    var source=prior?.source ?: sources.firstNotNullOfOrNull { it.entry.source }
                    if(mode==BookSyncMode.WITH_SOURCE && !book.format.equals("NOTE",true)) {
                        val hash=key.removePrefix("book:")
                        val name="source-$hash.${book.format.lowercase()}"
                        val declared=source?.takeIf { it.name==name && it.hash==hash }
                        val remote=session.files[name]
                        if(declared==null || remote==null || remote.size!=declared.size) {
                            onStatus("正在上传原书：${book.title}")
                            val staged=stageSource(book,session.directory,hash)
                            try {
                                check(staged.length() in 1..MAX_SOURCE_BYTES) { "原书大小必须在 1 字节到 20 GiB 之间。" }
                                val item=graph.uploadFile(name,staged) { _,_ -> guardNetwork() }
                                session.files[name]=item;session.uploaded++
                                source=CloudSource(name,hash,item.size)
                            } finally { staged.delete() }
                        } else source=declared
                    }
                    aliases.filter { it!=key }.forEach(ownRecords::remove)
                    ownRecords[key]=CloudEntry(key,book.title.take(200),book.format.uppercase(),Build.MODEL.take(100),channels,source)
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) {
                    errors+="${book.title}：${safeMessage(error)}";skipped++
                }
            }

            if(!session.ownManifestBroken) {
                try {
                    guardNetwork()
                    val manifest=CloudSyncProtocol.encode(CloudManifest(preferences.deviceId,ownRecords.values.toList()))
                    val old=CloudSyncProtocol.encode(session.own)
                    if(manifest!=old) {
                        onStatus("正在发布本机同步清单…")
                        val item=graph.putText(manifestName(),manifest)
                        session.files[item.name]=item;session.uploaded++
                    }
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) { errors+="同步清单：${safeMessage(error)}" }
            }
            val allErrors=(session.errors+errors).distinct()
            val unresolved=maxOf(conflicts,session.state.conflicts().size)
            if(allErrors.isEmpty() && unresolved==0) {
                preferences.lastSyncAt=System.currentTimeMillis()
                preferences.lastStatus="同步完成"
            } else preferences.lastStatus="同步部分完成：${allErrors.size} 项失败，$unresolved 项冲突"
            SyncReport(session.downloaded,session.uploaded,changed,skipped,allErrors,unresolved)
        }
    }

    suspend fun listCloudBooks():List<CloudBook> = operation.withLock { withContext(Dispatchers.IO) {
        guardNetwork();val session=openSession()
        check(session.errors.isEmpty()) { session.errors.joinToString("\n") }
        session.entries.groupBy { it.entry.key }.map { (key,values) ->
            val newest=values.maxByOrNull { it.entry.channels.values.maxOfOrNull(CloudHead::updatedAt) ?: 0L }!!.entry
            val source=values.firstNotNullOfOrNull { it.entry.source }
            CloudBook(key,newest.title,newest.format,source!=null,source?.size ?: 0L,newest.deviceName)
        }.sortedBy { it.title.lowercase() }
    } }

    suspend fun downloadCloudBook(key:String,onStatus:(String)->Unit={}):LibraryBook=operation.withLock {
        withContext(Dispatchers.IO) {
            guardNetwork();require(CloudSyncProtocol.validKey(key)) { "云端内容标识无效。" }
            val session=openSession();check(session.errors.isEmpty()) { session.errors.joinToString("\n") }
            val located=session.entries.filter { it.entry.key==key }
            check(located.isNotEmpty()) { "云端内容已不存在。" }
            val representative=located.maxByOrNull { it.entry.channels.values.maxOfOrNull(CloudHead::updatedAt) ?: 0L }!!.entry
            if(representative.format=="NOTE") check(preferences.syncNotes) { "请先打开无限笔记同步。" }
            val resolved=linkedMapOf<String,Pair<CloudHead,String>>()
            for(channel in enabledChannels(representative.format)) {
                val tips=channelTips(located.mapNotNull { it.entry.channels[channel] })
                if(tips.isEmpty()) continue
                check(tips.size==1) { "云端存在多个未合并的${channelLabel(channel)}版本，请先在原设备处理冲突。" }
                resolved[channel]=tips.single() to readPayload(session,channel,tips.single())
            }

            val book=if(representative.format=="NOTE") {
                val noteId=key.removePrefix("note:")
                val created=withContext(Dispatchers.Main.immediate) {
                    repository.upsertSyncedNote(noteId,representative.title,System.currentTimeMillis())
                }
                preferences.setBookMode(created.id,BookSyncMode.METADATA)
                created
            } else {
                val source=located.firstNotNullOfOrNull { it.entry.source }
                    ?: error("云端只有进度和批注，没有原书文件。")
                val remote=session.files[source.name] ?: graph.getFile(source.name) ?: error("云端原书文件缺失。")
                check(remote.size==source.size) { "云端原书大小与清单不一致。" }
                val directory=File(app.filesDir,"cloud-books").apply { mkdirs() }
                val target=File(directory,"${source.hash}.${representative.format.lowercase()}")
                if(!target.isFile || fileHash(target)!=source.hash) {
                    onStatus("正在下载原书：${representative.title}")
                    val staging=File(directory,".${source.hash}.download")
                    try {
                        graph.downloadFile(remote.id,staging,source.size) { _,_ -> guardNetwork() }
                        check(fileHash(staging)==source.hash) { "下载完成，但原书校验失败。" }
                        Files.move(staging.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
                    } finally { staging.delete() }
                }
                val imported=BookSessionFactory.open(app,Uri.fromFile(target)).let { document ->
                    try { withContext(Dispatchers.Main.immediate) { repository.addOrRefresh(document) } }
                    finally { document.close() }
                }
                val named=withContext(Dispatchers.Main.immediate) { repository.renameSyncedBook(imported.id,representative.title) }
                preferences.setBookMode(named.id,BookSyncMode.METADATA)
                named
            }

            // All remote data was validated before creating the local record.
            for((channel,value) in resolved) {
                val local=snapshot(book,channel)
                if(!isEmpty(channel,local.exactText) && local.payload.hash!=value.first.hash) {
                    session.state.cache(local.payload)
                    val entry=representative.copy(channels=mapOf(channel to value.first))
                    session.state.createConflict(book.id,entry,channel,CloudSyncProtocol.advance(local.payload,null,local.updatedAt),value.first)
                    continue
                }
                session.state.backup(key,channel,local.exactText)
                check(applyIfUnchanged(book,channel,value.second,value.first.updatedAt,local.exactText)) { "本机内容在下载期间已修改，请重试。" }
                session.state.saveHead(key,channel,value.first)
            }
            book
        }
    }

    suspend fun listConflicts():List<SyncConflictInfo> = operation.withLock { withContext(Dispatchers.IO) {
        stateFromPreferences()?.conflicts().orEmpty()
    } }

    suspend fun resolveConflict(id:String,useRemote:Boolean):Unit=operation.withLock { withContext(Dispatchers.IO) {
        val state=stateFromPreferences() ?: error("请先连接并读取一次 OneDrive。")
        val conflict=state.conflict(id);check(!conflict.optBoolean("resolved")) { "同步冲突已经处理。" }
        val key=conflict.getString("key");val channel=conflict.getString("channel")
        val localRecorded=CloudSyncProtocol.readHead(conflict.getJSONObject("local"))
        val remote=CloudSyncProtocol.readHead(conflict.getJSONObject("remote"))
        val bookId=conflict.getString("bookId")
        val book=withContext(Dispatchers.Main.immediate) { repository.load().firstOrNull { it.id==bookId } }
            ?: error("本机内容已删除，无法处理此冲突。")
        val current=snapshot(book,channel)
        check(current.payload.hash==localRecorded.hash) { "本机内容在冲突产生后又有修改，请先重新同步。" }
        state.cache(current.payload);state.backup(key,channel,current.exactText)
        val previous=state.head(key,channel)
        val mergedAncestors=(listOfNotNull(previous?.hash,localRecorded.hash,remote.hash)+
            previous?.ancestors.orEmpty()+localRecorded.ancestors+remote.ancestors).distinct()
        val selected=if(useRemote) {
            val text=CloudSyncProtocol.unpack(channel,remote) { name -> state.readBlob(name) ?: error("冲突备份不完整，请重新同步。") }
            check(applyIfUnchanged(book,channel,text,remote.updatedAt,current.exactText)) { "本机内容正在修改，请稍后重试。" }
            val after=snapshot(book,channel);state.cache(after.payload)
            CloudHead(after.payload.hash,after.payload.index,System.currentTimeMillis(),
                mergedAncestors.filter { it!=after.payload.hash }.take(64))
        } else CloudHead(current.payload.hash,current.payload.index,System.currentTimeMillis(),
            mergedAncestors.filter { it!=current.payload.hash }.take(64))
        state.saveHead(key,channel,selected);state.resolveConflict(id)
        preferences.lastStatus="冲突已处理，等待上传"
    } }

    private suspend fun mergeChannel(session:SyncSession,book:LibraryBook,key:String,channel:String,
        prior:CloudHead?,sources:List<LocatedEntry>):Pair<CloudHead,Pair<Int,Int>> {
        val local=snapshot(book,channel);session.state.cache(local.payload)
        val baseline=session.state.head(key,channel) ?: prior
        var localHead=CloudSyncProtocol.advance(local.payload,baseline,local.updatedAt)
        val remoteTips=channelTips(sources.mapNotNull { it.entry.channels[channel] })
        remoteTips.firstOrNull { it.hash==localHead.hash }?.let { same ->
            localHead=CloudHead(localHead.hash,localHead.index,maxOf(localHead.updatedAt,same.updatedAt),
                (localHead.ancestors+same.ancestors).distinct().filter { it!=localHead.hash }.take(64))
        }
        // A locally resolved merge descends from both old remote branches even before
        // this device has published its new manifest. Do not recreate the same conflict.
        val tips=remoteTips.filter { it.hash!=localHead.hash && it.hash !in localHead.ancestors }
        if(tips.isEmpty()) {
            putPayload(session,local.payload);session.state.saveHead(key,channel,localHead)
            return localHead to (0 to 0)
        }
        if(tips.size>1) {
            var conflicts=0
            for(remote in tips.filter { it.hash!=localHead.hash }) {
                readPayload(session,channel,remote)
                val entry=sources.first { it.entry.channels[channel]?.hash==remote.hash }.entry.copy(key=key)
                session.state.createConflict(book.id,entry,channel,localHead,remote);conflicts++
            }
            putPayload(session,local.payload);session.state.saveHead(key,channel,localHead)
            return localHead to (0 to conflicts)
        }
        val remote=tips.single()
        return when(headRelation(localHead,remote)) {
            HeadRelation.SAME -> { putPayload(session,local.payload);session.state.saveHead(key,channel,localHead);localHead to (0 to 0) }
            HeadRelation.LOCAL_NEWER -> {
                putPayload(session,local.payload);session.state.saveHead(key,channel,localHead);localHead to (0 to 0)
            }
            HeadRelation.REMOTE_NEWER -> {
                val text=readPayload(session,channel,remote)
                session.state.backup(key,channel,local.exactText)
                check(applyIfUnchanged(book,channel,text,remote.updatedAt,local.exactText)) { "本机${channelLabel(channel)}在同步期间已修改。" }
                val after=snapshot(book,channel);session.state.cache(after.payload)
                val accepted=normalizedRemoteHead(remote,after.payload)
                putPayload(session,after.payload)
                session.state.saveHead(key,channel,accepted);accepted to (1 to 0)
            }
            HeadRelation.CONFLICT -> {
                // A fresh, empty target is a restore rather than a concurrent edit.
                if(baseline==null && isEmpty(channel,local.exactText)) {
                    val text=readPayload(session,channel,remote)
                    session.state.backup(key,channel,local.exactText)
                    check(applyIfUnchanged(book,channel,text,remote.updatedAt,local.exactText)) { "本机${channelLabel(channel)}在同步期间已修改。" }
                    val after=snapshot(book,channel);session.state.cache(after.payload)
                    val accepted=normalizedRemoteHead(remote,after.payload)
                    putPayload(session,after.payload)
                    session.state.saveHead(key,channel,accepted);accepted to (1 to 0)
                } else {
                    readPayload(session,channel,remote)
                    val entry=sources.first { it.entry.channels[channel]?.hash==remote.hash }.entry.copy(key=key)
                    session.state.createConflict(book.id,entry,channel,localHead,remote)
                    putPayload(session,local.payload);session.state.saveHead(key,channel,localHead)
                    localHead to (0 to 1)
                }
            }
        }
    }

    private suspend fun openSession():SyncSession {
        guardNetwork()
        val account=graph.accountKey();val accountDirectory=CloudSyncState.digest(account)
        preferences.accountDirectory=accountDirectory
        val directory=File(app.noBackupFilesDir,"cloud-sync/$accountDirectory").apply { mkdirs() }
        val state=CloudSyncState(directory)
        val files=graph.listFiles().associateBy { it.name }.toMutableMap()
        val entries=mutableListOf<LocatedEntry>();val errors=mutableListOf<String>()
        var own=CloudManifest(preferences.deviceId,emptyList());var ownBroken=false;var downloaded=0
        val v2Devices=mutableSetOf<String>()
        for(item in files.values.filter { it.name.matches(Regex("v2-manifest-[a-zA-Z0-9-]{1,100}\\.json")) }) {
            currentCoroutineContext().ensureActive()
            val expectedOwn=item.name==manifestName()
            try {
                val cached=state.cachedManifest(item)
                var text=cached ?: graph.downloadText(item.id).also { state.cacheManifest(item,it);downloaded++ }
                val manifest=try { CloudSyncProtocol.decode(text) }
                catch(cachedError:Exception) {
                    if(cached==null) throw cachedError
                    // A damaged local cache is not evidence that the cloud manifest is damaged.
                    text=graph.downloadText(item.id);downloaded++
                    CloudSyncProtocol.decode(text).also { state.cacheManifest(item,text) }
                }
                require(item.name=="v2-manifest-${manifest.deviceId}.json") { "云端清单名称与设备不一致。" }
                v2Devices+=manifest.deviceId
                manifest.records.forEach { entries+=LocatedEntry(manifest.deviceId,it) }
                if(manifest.deviceId==preferences.deviceId) own=manifest
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                errors+="${item.name}：${safeMessage(error)}";if(expectedOwn) ownBroken=true
            }
        }
        // Version 1 is read-only migration input. It is never allowed to overwrite causally related v2 data.
        for(item in files.values.filter { it.name.matches(Regex("snapshot-[a-zA-Z0-9-]{1,100}\\.json")) }) {
            currentCoroutineContext().ensureActive()
            try {
                val text=graph.downloadText(item.id);downloaded++
                val legacy=decodeLegacy(text,state)
                if(legacy.deviceId !in v2Devices) legacy.records.forEach { entries+=LocatedEntry(legacy.deviceId,it) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) { errors+="${item.name}（旧版）：${safeMessage(error)}" }
        }
        return SyncSession(state,directory,files,entries,own,errors,ownBroken,downloaded)
    }

    private fun decodeLegacy(text:String,state:CloudSyncState):CloudManifest {
        CloudJsonBudget().consume(text);val root=JSONObject(text);require(root.getInt("version")==1)
        val device=root.optString("deviceId");require(device.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        val deviceName=root.optString("deviceName","旧版设备").take(100)
        val array=root.getJSONArray("records");require(array.length()<=10000)
        val records=buildList {
            for(i in 0 until array.length()) {
                val value=array.getJSONObject(i);val key=value.getString("key");if(!CloudSyncProtocol.validKey(key)) continue
                val format=value.getString("format").uppercase();if(format !in setOf("PDF","EPUB","NOTE")) continue
                val channels=linkedMapOf<String,CloudHead>()
                value.optJSONObject("position")?.let { position ->
                    val payload=CloudSyncProtocol.payload("progress",canonicalJson(position));state.cache(payload)
                    channels["progress"]=CloudHead(payload.hash,payload.index,value.optLong("positionAt"))
                }
                value.optJSONObject("ink")?.let { ink ->
                    val payload=CloudSyncProtocol.payload("ink",canonicalJson(ink.getJSONObject("data")));state.cache(payload)
                    channels["ink"]=CloudHead(payload.hash,payload.index,ink.optLong("updatedAt"))
                }
                value.optJSONObject("note")?.let { note ->
                    val payload=CloudSyncProtocol.payload("note",canonicalJson(note.getJSONObject("data")));state.cache(payload)
                    channels["note"]=CloudHead(payload.hash,payload.index,note.optLong("updatedAt"))
                }
                add(CloudEntry(key,value.optString("title","云端内容").take(200),format,deviceName,channels))
            }
        }
        return CloudManifest(device,records)
    }

    private suspend fun snapshot(book:LibraryBook,channel:String):LocalChannel=when(channel) {
        "progress" -> {
            val latest=withContext(Dispatchers.Main.immediate) { repository.load().firstOrNull { it.id==book.id } ?: book }
            val text=canonicalJson(positionJson(latest));LocalChannel(latest.lastOpenedAt,text,CloudSyncProtocol.payload(channel,text))
        }
        "ink" -> InkStore.get(app,book.uri).syncSnapshot().let { LocalChannel(it.first,it.second,CloudSyncProtocol.payload(channel,it.second)) }
        "note" -> NoteStore.get(app,book.id).syncSnapshot().let { LocalChannel(it.first,it.second,CloudSyncProtocol.payload(channel,it.second)) }
        else -> error("未知同步类型")
    }

    private suspend fun applyIfUnchanged(book:LibraryBook,channel:String,text:String,updatedAt:Long,expectedExact:String):Boolean=when(channel) {
        "ink" -> InkStore.get(app,book.uri).importForSyncIfUnchanged(text,updatedAt,cloudHash(expectedExact))
        "note" -> NoteStore.get(app,book.id).importForSyncIfUnchanged(text,updatedAt,cloudHash(expectedExact))
        "progress" -> withContext(Dispatchers.Main.immediate) {
            val current=repository.load().firstOrNull { it.id==book.id } ?: return@withContext false
            if(cloudHash(canonicalJson(positionJson(current)))!=cloudHash(expectedExact)) return@withContext false
            val remote=positionBook(JSONObject(text)).copy(id=current.id,uri=current.uri,title=current.title,format=current.format,
                coverPath=current.coverPath,lastOpenedAt=updatedAt,category=current.category,customCoverPath=current.customCoverPath,
                folderId=current.folderId,importedAt=current.importedAt)
            repository.applySyncedPosition(current.id,remote);true
        }
        else -> false
    }

    private suspend fun readPayload(session:SyncSession,channel:String,head:CloudHead):String =
        CloudSyncProtocol.unpack(channel,head) { name ->
            session.state.readBlob(name) ?: run {
                guardNetwork();val item=session.files[name] ?: error("云端同步分片缺失：$name")
                val text=graph.downloadText(item.id);session.downloaded++
                require(name=="data-${cloudHash(text)}.json") { "云端同步分片校验失败。" }
                session.state.write(name,text);text
            }
        }

    private suspend fun putPayload(session:SyncSession,payload:CloudPayload) {
        session.state.cache(payload)
        for((name,text) in payload.blobs) {
            val bytes=text.toByteArray(Charsets.UTF_8).size.toLong()
            if(session.files[name]?.size!=bytes) {
                guardNetwork();val item=graph.putText(name,text);session.files[name]=item;session.uploaded++
            }
        }
    }

    private fun normalizedRemoteHead(remote:CloudHead,payload:CloudPayload):CloudHead =
        if(remote.hash==payload.hash) remote.copy(index=payload.index)
        else CloudHead(payload.hash,payload.index,remote.updatedAt,
            (listOf(remote.hash)+remote.ancestors).distinct().filter { it!=payload.hash }.take(64))

    private suspend fun identity(book:LibraryBook):String = if(book.format.equals("NOTE",true)) "note:${book.id}" else {
        "book:${hashUri(Uri.parse(book.uri))}"
    }

    /** Version 1 used a quick size/head/tail fingerprint; retain it only as a read alias. */
    private suspend fun legacyIdentity(book:LibraryBook):String=withContext(Dispatchers.IO) {
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update(book.format.uppercase().toByteArray())
        val descriptor=app.contentResolver.openFileDescriptor(Uri.parse(book.uri),"r")
            ?: error("无法读取原书。")
        descriptor.use { pfd ->
            val size=pfd.statSize
            digest.update(ByteBuffer.allocate(8).putLong(size).array())
            FileInputStream(pfd.fileDescriptor).use { stream ->
                val channel=stream.channel;val buffer=ByteBuffer.allocate(256*1024)
                channel.read(buffer);digest.update(buffer.array(),0,buffer.position())
                if(size>buffer.capacity()) {
                    runCatching { channel.position((size-buffer.capacity()).coerceAtLeast(0));buffer.clear();channel.read(buffer) }
                    if(buffer.position()>0) digest.update(buffer.array(),0,buffer.position())
                }
            }
        }
        "book:"+digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun hashUri(uri:Uri):String=withContext(Dispatchers.IO) {
        val digest=MessageDigest.getInstance("SHA-256")
        app.contentResolver.openInputStream(uri)?.use { input ->
            val buffer=ByteArray(128*1024)
            while(true) { currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count) }
        } ?: error("无法读取原书。")
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun stageSource(book:LibraryBook,directory:File,expectedHash:String):File=withContext(Dispatchers.IO) {
        val staging=File.createTempFile("source-",".${book.format.lowercase()}",directory)
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            app.contentResolver.openInputStream(Uri.parse(book.uri))?.use { input -> staging.outputStream().use { output ->
                val buffer=ByteArray(128*1024)
                while(true) { currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0) break
                    digest.update(buffer,0,count);output.write(buffer,0,count) }
                output.fd.sync()
            } } ?: error("无法读取原书。")
            val actual=digest.digest().joinToString("") { "%02x".format(it) }
            check(actual==expectedHash) { "原书在同步期间发生变化，请重试。" }
            staging
        } catch(error:Throwable) { staging.delete();throw error }
    }

    private suspend fun fileHash(file:File):String=withContext(Dispatchers.IO) {
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use { input ->
            val buffer=ByteArray(128*1024);while(true) { currentCoroutineContext().ensureActive();val count=input.read(buffer)
                if(count<0) break;digest.update(buffer,0,count) }
        };digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun channelTips(values:List<CloudHead>):List<CloudHead> {
        val unique=values.distinctBy(CloudHead::hash)
        return unique.filter { candidate -> unique.none { other -> candidate.hash!=other.hash && candidate.hash in other.ancestors } }
    }

    private fun enabledChannels(format:String)=buildList {
        if(preferences.syncProgress) add("progress")
        if(format=="PDF" && preferences.syncInk) add("ink")
        if(format=="NOTE" && preferences.syncNotes) add("note")
    }

    private fun isEmpty(channel:String,text:String)=runCatching { when(channel) {
        "progress" -> JSONObject(text).let { it.optDouble("progress",0.0)==0.0 && it.optInt("pdfPage",-1)<0 &&
            it.optInt("pdfSpread",0)==0 && it.optInt("epubChapter",0)==0 && it.optInt("epubSpread",0)==0 }
        "ink" -> JSONObject(text).getJSONObject("pages").let { pages -> pages.keys().asSequence().all { pages.getJSONArray(it).length()==0 } }
        "note" -> JSONObject(text).getJSONArray("strokes").length()==0
        else -> false
    } }.getOrDefault(false)

    private fun positionJson(book:LibraryBook)=JSONObject().put("progress",book.progress.toDouble())
        .put("pdfSpread",book.pdfSpread).put("pdfPage",book.pdfPage).put("pdfOffset",book.pdfOffset.toDouble())
        .put("epubChapter",book.epubChapter).put("epubSpread",book.epubSpread).put("epubFraction",book.epubFraction.toDouble())

    private fun positionBook(source:JSONObject)=LibraryBook("","","","",null,
        source.optDouble("progress",0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f,
        source.optInt("pdfSpread").coerceIn(0,10_000_000),source.optInt("epubChapter").coerceIn(0,1_000_000),
        source.optInt("epubSpread").coerceIn(0,10_000_000),source.optInt("pdfPage",-1).coerceIn(-1,10_000_000),
        source.optDouble("pdfOffset",0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f,
        source.optDouble("epubFraction",-1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-1f,1f) ?: -1f,0L)

    private fun stateFromPreferences():CloudSyncState?=preferences.accountDirectory.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        ?.let { CloudSyncState(File(app.noBackupFilesDir,"cloud-sync/$it")) }

    private fun manifestName()="v2-manifest-${preferences.deviceId}.json"

    private fun guardNetwork() {
        check(preferences.enabled) { "请先打开 OneDrive 同步开关。" }
        check(auth.isSignedIn) { "请先登录 OneDrive。" }
        val manager=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities=manager.getNetworkCapabilities(manager.activeNetwork) ?: error("当前没有可用网络。")
        check(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) { "当前没有可用网络。" }
        if(preferences.wifiOnly) check(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) { "已设置仅通过 Wi-Fi 同步。" }
    }

    private fun channelLabel(channel:String)=when(channel) { "progress"->"阅读进度";"ink"->"PDF 批注";"note"->"无限笔记";else->channel }
    private fun safeMessage(error:Exception)=error.message?.take(240)?.replace(Regex("https?://\\S+"),"网络地址") ?: "未知错误"

    private companion object {
        const val MAX_SOURCE_BYTES=20L*1024*1024*1024
        val operation=Mutex()
    }
}
