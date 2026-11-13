package com.localreader.twinleaf.ink

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import com.localreader.twinleaf.sync.CloudSyncScheduler

/** Application-lived writer: leaving the reader cannot cancel a completed stroke's save. */
class InkStore private constructor(context:Context,val uri:String) {
    private val app=context.applicationContext
    private val id=MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
    private val file=AtomicFile(File(File(app.filesDir,"ink").apply { mkdirs() },"$id.json"))
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    // A pending snapshot already contains all previous strokes; coalesce slow-disk backlog.
    private data class Save(val serial:Long,val version:Long,val pages:Map<Int,List<InkStroke>>)
    private val queue=Channel<Save>(Channel.CONFLATED)
    private val writer=Mutex()
    private val loaded=CompletableDeferred<Unit>()
    private var saveSerial=0L
    private var flushedSerial=0L
    private var persistedPages:Map<Int,List<InkStroke>> = emptyMap()
    private val prefs=app.getSharedPreferences("pdf_ink",Context.MODE_PRIVATE)
    var pages by mutableStateOf<Map<Int,List<InkStroke>>>(emptyMap()); private set
    var ready by mutableStateOf(false); private set
    var revision by mutableLongStateOf(0); private set
    var saveStatus by mutableStateOf("加载批注"); private set
    var failure by mutableStateOf<String?>(null); private set
    var visible by mutableStateOf(prefs.getBoolean("visible-$id",true)); private set
    var tool by mutableStateOf(InkTool.BROWSE)
    var color by mutableIntStateOf(0xFF2C65CE.toInt())
    var width by mutableFloatStateOf(.0035f)
    var eraserRadiusDp by mutableFloatStateOf(
        prefs.getFloat("eraser-radius-$id",12f).takeIf { it.isFinite() }?.coerceIn(3f,48f) ?: 12f
    ); private set
    var brush by mutableStateOf(InkBrush.BALLPOINT)
    var opacity by mutableFloatStateOf(1f)
    var pressureCurve by mutableStateOf(PressureCurve.LINEAR)
    var beautifyLevel by mutableStateOf(runCatching {
        InkBeautifyLevel.valueOf(prefs.getString("beautify-$id",InkBeautifyLevel.LIGHT.name)!!)
    }.getOrDefault(InkBeautifyLevel.LIGHT)); private set
    var autoStraighten by mutableStateOf(prefs.getBoolean("straighten-$id",true)); private set
    var straightenSensitivity by mutableStateOf(runCatching {
        StraightenSensitivity.valueOf(prefs.getString("straighten-sensitivity-$id",StraightenSensitivity.STANDARD.name)!!)
    }.getOrDefault(StraightenSensitivity.STANDARD)); private set
    var releaseSensitivity by mutableStateOf(runCatching {
        ReleaseSensitivity.valueOf(prefs.getString("release-$id",ReleaseSensitivity.STANDARD.name)!!)
    }.getOrDefault(ReleaseSensitivity.STANDARD)); private set
    var primaryButtonAction by mutableStateOf(runCatching {
        StylusButtonAction.valueOf(prefs.getString("primary-$id",StylusButtonAction.ERASER.name)!!)
    }.getOrDefault(StylusButtonAction.ERASER)); private set
    var secondaryButtonAction by mutableStateOf(runCatching {
        StylusButtonAction.valueOf(prefs.getString("secondary-$id",StylusButtonAction.HIGHLIGHTER.name)!!)
    }.getOrDefault(StylusButtonAction.HIGHLIGHTER)); private set
    var stylusDiagnostic by mutableStateOf("等待触笔输入")
    var drawing by mutableStateOf(false)
    private val history=BoundedInkHistory()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    init {
        scope.launch {
            try { pages=withContext(Dispatchers.IO) {
                if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) emptyMap()
                else decode(file.openRead().bufferedReader().use { it.readText() })
            }; persistedPages=pages;ready=true; saveStatus="已保存";loaded.complete(Unit) }
            catch(e:Exception) { failure="批注读取失败，未覆盖原记录：${e.message}"; saveStatus="读取失败";loaded.completeExceptionally(e) }
            for((serial,version,snapshot) in queue) {
                try {
                    writer.withLock {
                        if(serial<=flushedSerial) return@withLock
                        withContext(Dispatchers.IO) { writeText(encode(snapshot)) }
                        persistedPages=snapshot;flushedSerial=serial
                        if(version==revision) { saveStatus="已保存";failure=null }
                    }
                    runCatching { CloudSyncScheduler.requestSoon(app) }
                } catch(e:Exception) { saveStatus="保存失败，点按重试";failure="批注尚未写入磁盘：${e.message}" }
            }
        }
    }
    fun updateVisibility(value:Boolean) { visible=value;prefs.edit().putBoolean("visible-$id",value).apply();if(!value) tool=InkTool.BROWSE }
    fun updateReleaseSensitivity(value:ReleaseSensitivity) {
        releaseSensitivity=value
        prefs.edit().putString("release-$id",value.name).apply()
    }
    fun updateBeautifyLevel(value:InkBeautifyLevel) {
        beautifyLevel=value;prefs.edit().putString("beautify-$id",value.name).apply()
    }
    fun updateAutoStraighten(value:Boolean) {
        autoStraighten=value;prefs.edit().putBoolean("straighten-$id",value).apply()
    }
    fun updateStraightenSensitivity(value:StraightenSensitivity) {
        if(drawing) return
        straightenSensitivity=value;prefs.edit().putString("straighten-sensitivity-$id",value.name).apply()
    }
    fun updateEraserRadiusDp(value:Float) {
        if(!value.isFinite()) return
        eraserRadiusDp=value.coerceIn(3f,48f)
        prefs.edit().putFloat("eraser-radius-$id",eraserRadiusDp).apply()
    }
    fun setButtonAction(primary:Boolean,action:StylusButtonAction) {
        if(primary) primaryButtonAction=action else secondaryButtonAction=action
        prefs.edit().putString((if(primary) "primary-" else "secondary-")+id,action.name).apply()
    }
    fun choose(value:InkTool) { if(drawing || !ready) return;tool=value;if(value!=InkTool.BROWSE) updateVisibility(true) }
    fun change(page:Int,strokes:List<InkStroke>) {
        if(!ready || strokes==pages[page].orEmpty()) return
        history.record(page,pages[page].orEmpty(),strokes)
        apply(page,strokes)
    }
    private fun apply(page:Int,strokes:List<InkStroke>) {
        pages=pages+(page to strokes);revision++;canUndo=history.canUndo;canRedo=history.canRedo;retry()
    }
    fun undo() { if(drawing) return;history.undo { pages[it].orEmpty() }?.let { apply(it.first,it.second) } }
    fun redo() { if(drawing) return;history.redo { pages[it].orEmpty() }?.let { apply(it.first,it.second) } }
    fun retry() { if(ready) { saveStatus="保存中";queue.trySend(Save(++saveSerial,revision,pages)) } }

    /** A timeout is an error: callers must never upload an older disk snapshot. */
    suspend fun syncSnapshot():Pair<Long,String> = withTimeout(2_000) {
        loaded.await()
        writer.withLock {
            val captured=withContext(Dispatchers.Main.immediate) { Save(saveSerial,revision,pages) }
            val result=withContext(Dispatchers.IO) {
                val text=encode(captured.pages)
                if(captured.pages!=persistedPages) writeText(text)
                file.baseFile.lastModified() to text
            }
            persistedPages=captured.pages;flushedSerial=captured.serial
            withContext(Dispatchers.Main.immediate) {
                if(revision==captured.version) { saveStatus="已保存";failure=null }
            }
            result
        }
    }

    suspend fun exportForSync():Pair<Long,String>? = syncSnapshot().takeIf { it.first>0L }

    suspend fun importFromSync(text:String,updatedAt:Long) {
        val expected=syncSnapshot().second
        check(importForSyncIfUnchanged(text,updatedAt,hash(expected))) { "批注在同步期间已更改，请重试" }
    }

    suspend fun importForSyncIfUnchanged(text:String,updatedAt:Long,expectedHash:String):Boolean {
        val decoded=withContext(Dispatchers.Default) { decode(text) }
        withTimeout(2_000) { loaded.await() }
        return writer.withLock {
            val captured=withContext(Dispatchers.Main.immediate) { Save(saveSerial,revision,pages) }
            if(withContext(Dispatchers.Default) { hash(encode(captured.pages)) }!=expectedHash) return@withLock false
            // Stage under the writer lock; edits remain responsive and are checked again at commit.
            var output:FileOutputStream?=null
            var committed=false
            try {
                withContext(Dispatchers.IO) {
                    output=file.startWrite()
                    output!!.write(text.toByteArray(Charsets.UTF_8));output!!.fd.sync()
                }
                withContext(Dispatchers.Main.immediate) {
                    if(drawing || revision!=captured.version || pages!=captured.pages) false
                    else {
                        // No suspension between the last check, disk commit and in-memory swap.
                        file.finishWrite(output);file.baseFile.setLastModified(updatedAt)
                        committed=true
                        pages=decoded;persistedPages=decoded;flushedSerial=saveSerial
                        revision++;history.clear();canUndo=false;canRedo=false
                        saveStatus="已从 OneDrive 更新";failure=null
                        true
                    }
                }.also { if(!it) withContext(Dispatchers.IO+NonCancellable) { file.failWrite(output) } }
            } catch(error:Throwable) {
                if(!committed && output!=null) withContext(Dispatchers.IO+NonCancellable) { file.failWrite(output) }
                throw error
            }
        }
    }

    private fun writeText(text:String) {
        val output=file.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8));file.finishWrite(output) }
        catch(error:Throwable) { file.failWrite(output);throw error }
    }
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    companion object {
        private val stores=mutableMapOf<String,InkStore>()
        @Synchronized fun get(context:Context,uri:String)=stores.getOrPut(uri) { InkStore(context,uri) }
        fun encode(pages:Map<Int,List<InkStroke>>):String=JSONObject().put("version",3).put("pages",JSONObject().apply {
            pages.forEach { (page,strokes) -> put(page.toString(),JSONArray().apply {
                strokes.forEach { stroke -> put(JSONObject().put("color",stroke.color).put("width",stroke.width.toDouble())
                    .put("highlight",stroke.highlight).put("brush",stroke.brush.name)
                    .put("opacity",stroke.opacity.toDouble()).put("curve",stroke.curve.name)
                    .put("beautify",stroke.beautifyLevel.name).put("autoStraightened",stroke.autoStraightened)
                    .put("points",JSONArray().apply {
                        stroke.points.forEach { put(JSONArray(listOf(it.x.toDouble(),it.y.toDouble(),it.pressure.toDouble(),
                            it.tilt.toDouble(),it.orientation.toDouble(),it.timeMillis,it.rawPressure.toDouble()))) }
                    }).apply { stroke.rawPoints?.let { raw -> put("rawPoints",JSONArray().apply {
                        raw.forEach { put(JSONArray(listOf(it.x.toDouble(),it.y.toDouble(),it.pressure.toDouble(),
                            it.tilt.toDouble(),it.orientation.toDouble(),it.timeMillis,it.rawPressure.toDouble()))) }
                    }) } }) }
            }) }
        }).toString()
        fun decode(text:String):Map<Int,List<InkStroke>> {
            val root=JSONObject(text);require(root.getInt("version") in 1..3)
            val pages=root.getJSONObject("pages")
            return pages.keys().asSequence().associate { key ->
                val list=pages.getJSONArray(key)
                key.toInt().also { require(it>=0) } to List(list.length()) { i ->
                    val stroke=list.getJSONObject(i);val points=stroke.getJSONArray("points")
                    fun decodePoints(source:JSONArray)=List(source.length()) { j -> val p=source.getJSONArray(j)
                        InkPoint(p.getDouble(0).toFloat(),p.getDouble(1).toFloat(),p.optDouble(2,1.0).toFloat(),
                            p.optDouble(3,0.0).toFloat(),p.optDouble(4,0.0).toFloat(),p.optLong(5,0L),
                            p.optDouble(6,p.optDouble(2,1.0)).toFloat()).also {
                            require(it.x in 0f..1f && it.y in 0f..1f && it.pressure in 0f..10f &&
                                it.tilt.isFinite() && it.orientation.isFinite() && it.rawPressure in 0f..10f)
                        }
                    }
                    InkStroke(decodePoints(points),stroke.getInt("color"),stroke.getDouble("width").toFloat().also { require(it>0 && it<=.1f) },
                        stroke.optBoolean("highlight"),
                        runCatching { InkBrush.valueOf(stroke.optString("brush",if(stroke.optBoolean("highlight")) "HIGHLIGHTER" else "LEGACY")) }.getOrDefault(InkBrush.LEGACY),
                        stroke.optDouble("opacity",if(stroke.optBoolean("highlight")) 80.0/255.0 else 1.0).toFloat().coerceIn(0f,1f),
                        runCatching { PressureCurve.valueOf(stroke.optString("curve","LINEAR")) }.getOrDefault(PressureCurve.LINEAR),
                        stroke.optJSONArray("rawPoints")?.let { decodePoints(it) },
                        runCatching { InkBeautifyLevel.valueOf(stroke.optString("beautify","OFF")) }.getOrDefault(InkBeautifyLevel.OFF),
                        stroke.optBoolean("autoStraightened",false))
                }
            }
        }
    }
}
