package com.localreader.twinleaf.note

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.util.AtomicFile
import com.localreader.twinleaf.ink.InkBrush
import com.localreader.twinleaf.ink.BoundedInkHistory
import com.localreader.twinleaf.ink.InkBeautifyLevel
import com.localreader.twinleaf.ink.InkStroke
import com.localreader.twinleaf.ink.InkTool
import com.localreader.twinleaf.ink.PressureCurve
import com.localreader.twinleaf.ink.StraightenSensitivity
import com.localreader.twinleaf.ink.inkOpacity
import com.localreader.twinleaf.ink.pressureWidth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.min
import com.localreader.twinleaf.sync.CloudSyncScheduler

class NoteStore private constructor(context:Context,val id:String) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("note_ink",Context.MODE_PRIVATE)
    private val file=AtomicFile(File(app.filesDir,"notes/$id.json").also { it.parentFile?.mkdirs() })
    private val previewFile=File(app.filesDir,"covers/note-$id.jpg").also { it.parentFile?.mkdirs() }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private data class Save(val serial:Long,val version:Int,val snapshot:NoteSnapshot)
    private val saves=Channel<Save>(Channel.CONFLATED)
    private val writer=Mutex()
    private val loaded=CompletableDeferred<Unit>()
    private var saveSerial=0L
    private var flushedSerial=0L
    private var persistedSnapshot=NoteSnapshot()
    private var deleted=false
    private val history=BoundedInkHistory()

    var strokes by mutableStateOf<List<InkStroke>>(emptyList());private set
    var background by mutableStateOf(NoteBackground.BLANK);private set
    var paperSettings by mutableStateOf(NotePaperSettings());private set
    var viewport by mutableStateOf(NoteViewport());private set
    var tool by mutableStateOf(InkTool.PEN)
    var brush by mutableStateOf(InkBrush.BALLPOINT)
    var color by mutableIntStateOf(0xFF243A68.toInt())
    var widthDp by mutableFloatStateOf(3.2f)
    var opacity by mutableFloatStateOf(1f)
    var pressureCurve by mutableStateOf(PressureCurve.LINEAR)
    var beautifyLevel by mutableStateOf(runCatching {
        InkBeautifyLevel.valueOf(prefs.getString("beautify-$id",InkBeautifyLevel.LIGHT.name)!!)
    }.getOrDefault(InkBeautifyLevel.LIGHT));private set
    var autoStraighten by mutableStateOf(prefs.getBoolean("straighten-$id",true));private set
    var straightenSensitivity by mutableStateOf(runCatching {
        StraightenSensitivity.valueOf(prefs.getString("straighten-sensitivity-$id",StraightenSensitivity.STANDARD.name)!!)
    }.getOrDefault(StraightenSensitivity.STANDARD));private set
    var eraserRadiusDp by mutableFloatStateOf(16f)
    var ready by mutableStateOf(false);private set
    var drawing by mutableStateOf(false)
    var revision by mutableIntStateOf(0);private set
    var canUndo by mutableStateOf(false);private set
    var canRedo by mutableStateOf(false);private set
    var saveStatus by mutableStateOf("正在读取");private set
    var failure by mutableStateOf<String?>(null);private set

    init { scope.launch {
        try {
            val loaded=withContext(Dispatchers.IO) {
                if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) NoteSnapshot()
                else NoteCodec.decode(file.openRead().bufferedReader().use { it.readText() })
            }
            strokes=loaded.strokes;background=loaded.paperSettings.pattern
            paperSettings=loaded.paperSettings;viewport=loaded.viewport
            persistedSnapshot=loaded;ready=true;saveStatus="已保存";this@NoteStore.loaded.complete(Unit)
        } catch(error:Exception) {
            failure="笔记读取失败：${error.message}";saveStatus="读取失败";ready=true;loaded.completeExceptionally(error)
        }
        for((serial,version,snapshot) in saves) try {
            if(deleted) continue
            writer.withLock {
                if(deleted || serial<=flushedSerial) return@withLock
                withContext(Dispatchers.IO) {
                    writeText(NoteCodec.encode(snapshot));writePreview(snapshot)
                }
                persistedSnapshot=snapshot;flushedSerial=serial
                if(version==revision) { saveStatus="已保存";failure=null }
            }
            runCatching { CloudSyncScheduler.requestSoon(app) }
        } catch(error:Exception) { saveStatus="保存失败";failure="笔记尚未写入磁盘：${error.message}" }
    } }

    fun change(next:List<InkStroke>) {
        if(!ready || next==strokes) return
        history.record(0,strokes,next)
        apply(next)
    }
    private fun apply(next:List<InkStroke>) {
        strokes=next;revision++;canUndo=history.canUndo;canRedo=history.canRedo;persist()
    }
    fun undo() { if(!drawing) history.undo { strokes }?.let { apply(it.second) } }
    fun redo() { if(!drawing) history.redo { strokes }?.let { apply(it.second) } }
    fun updateBackground(value:NoteBackground) { updatePaperSettings(paperSettings.copy(pattern=value)) }
    fun updatePaperSettings(value:NotePaperSettings) {
        val next=normalizedNotePaperSettings(value)
        if(next==paperSettings) return
        paperSettings=next;background=next.pattern;revision++;persist()
    }
    fun updateViewport(value:NoteViewport,persist:Boolean=false) {
        if(!value.centerX.isFinite() || !value.centerY.isFinite() || !value.scale.isFinite()) return
        viewport=value.copy(scale=value.scale.coerceIn(.2f,8f))
        if(persist) { revision++;persist() }
    }
    fun updateEraserRadius(value:Float) { eraserRadiusDp=value.coerceIn(4f,64f) }
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
    fun persist() {
        if(!ready || deleted) return
        saveStatus="保存中"
        saves.trySend(Save(++saveSerial,revision,currentSnapshot()))
    }

    /** Flush the exact captured state, or fail; a slow writer must never yield stale ink. */
    suspend fun syncSnapshot():Pair<Long,String> = withTimeout(2_000) {
        loaded.await()
        writer.withLock {
            val captured=withContext(Dispatchers.Main.immediate) {
                check(!deleted) { "笔记已删除" }
                Save(saveSerial,revision,currentSnapshot())
            }
            val result=withContext(Dispatchers.IO) {
                val text=NoteCodec.encode(captured.snapshot)
                if(captured.snapshot!=persistedSnapshot) { writeText(text);writePreview(captured.snapshot) }
                file.baseFile.lastModified() to text
            }
            persistedSnapshot=captured.snapshot;flushedSerial=captured.serial
            withContext(Dispatchers.Main.immediate) {
                if(revision==captured.version) { saveStatus="已保存";failure=null }
            }
            result
        }
    }

    suspend fun exportForSync():Pair<Long,String>? = syncSnapshot().takeIf { it.first>0L }

    suspend fun importFromSync(text:String,updatedAt:Long) {
        val expected=syncSnapshot().second
        check(importForSyncIfUnchanged(text,updatedAt,hash(expected))) { "笔记在同步期间已更改，请重试" }
    }

    suspend fun importForSyncIfUnchanged(text:String,updatedAt:Long,expectedHash:String):Boolean {
        val decoded=withContext(Dispatchers.Default) { NoteCodec.decode(text) }
        withTimeout(2_000) { loaded.await() }
        return writer.withLock {
            val captured=withContext(Dispatchers.Main.immediate) { Save(saveSerial,revision,currentSnapshot()) }
            if(withContext(Dispatchers.Default) { hash(NoteCodec.encode(captured.snapshot)) }!=expectedHash) return@withLock false
            var output:FileOutputStream?=null
            var committed=false
            try {
                withContext(Dispatchers.IO) {
                    output=file.startWrite()
                    output!!.write(text.toByteArray(Charsets.UTF_8));output!!.fd.sync()
                }
                val imported=withContext(Dispatchers.Main.immediate) {
                    if(deleted || drawing || revision!=captured.version || currentSnapshot()!=captured.snapshot) false
                    else {
                        // Main-thread commit cannot interleave with a new stroke or viewport edit.
                        file.finishWrite(output);file.baseFile.setLastModified(updatedAt)
                        committed=true
                        strokes=decoded.strokes;background=decoded.paperSettings.pattern;paperSettings=decoded.paperSettings
                        viewport=decoded.viewport;persistedSnapshot=decoded;flushedSerial=saveSerial
                        revision++;history.clear();canUndo=false;canRedo=false
                        saveStatus="已从 OneDrive 更新";failure=null
                        true
                    }
                }
                if(!imported) withContext(Dispatchers.IO+NonCancellable) { file.failWrite(output) }
                // A thumbnail failure must not turn a committed import into a reported conflict.
                else withContext(Dispatchers.IO) { runCatching { writePreview(decoded) } }
                imported
            } catch(error:Throwable) {
                if(!committed && output!=null) withContext(Dispatchers.IO+NonCancellable) { file.failWrite(output) }
                throw error
            }
        }
    }

    private fun currentSnapshot()=NoteSnapshot(strokes,background,viewport,paperSettings)
    private fun writeText(text:String) {
        val output=file.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8));file.finishWrite(output) }
        catch(error:Throwable) { file.failWrite(output);throw error }
    }
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun writePreview(snapshot:NoteSnapshot) {
        val bitmap=Bitmap.createBitmap(360,500,Bitmap.Config.RGB_565)
        val canvas=Canvas(bitmap);canvas.drawColor(snapshot.paperSettings.backgroundColor)
        drawNotePaperGuides(canvas,NoteBounds(0f,0f,360f,500f),1f,snapshot.paperSettings,
            Paint(Paint.ANTI_ALIAS_FLAG))
        if(snapshot.strokes.isNotEmpty()) {
            val bounds=snapshot.strokes.map { it.noteBounds() }
            val left=bounds.minOf { it.left };val right=bounds.maxOf { it.right }
            val top=bounds.minOf { it.top };val bottom=bounds.maxOf { it.bottom }
            val contentWidth=(right-left).coerceAtLeast(1f);val contentHeight=(bottom-top).coerceAtLeast(1f)
            val scale=min(320f/contentWidth,450f/contentHeight).coerceIn(.02f,8f)
            canvas.save();canvas.translate(180f,250f);canvas.scale(scale,scale);canvas.translate(-(left+right)/2f,-(top+bottom)/2f)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
            snapshot.strokes.forEach { stroke ->
                if(stroke.points.isEmpty()) return@forEach
                paint.color=stroke.color;paint.alpha=(inkOpacity(stroke,stroke.points.first())*255).toInt().coerceIn(0,255)
                if(stroke.highlight || stroke.brush==InkBrush.HIGHLIGHTER) {
                    paint.strokeWidth=stroke.width
                    val path=Path();path.moveTo(stroke.points[0].x,stroke.points[0].y)
                    for(index in 1 until stroke.points.size) path.lineTo(stroke.points[index].x,stroke.points[index].y)
                    canvas.drawPath(path,paint)
                } else if(stroke.points.size==1) {
                    val point=stroke.points[0];paint.style=Paint.Style.FILL
                    canvas.drawCircle(point.x,point.y,pressureWidth(stroke,point)/2f,paint);paint.style=Paint.Style.STROKE
                } else for(index in 1 until stroke.points.size) {
                    val a=stroke.points[index-1];val b=stroke.points[index]
                    paint.strokeWidth=pressureWidth(stroke,b);paint.alpha=(inkOpacity(stroke,b)*255).toInt().coerceIn(0,255)
                    canvas.drawLine(a.x,a.y,b.x,b.y,paint)
                }
            }
            canvas.restore()
        }
        previewFile.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.JPEG,88,it) }
        bitmap.recycle()
    }
    companion object {
        private val stores=mutableMapOf<String,NoteStore>()
        @Synchronized fun get(context:Context,id:String)=stores.getOrPut(id) { NoteStore(context,id) }
        fun delete(context:Context,id:String) {
            stores.remove(id)?.deleted=true
            val file=File(context.filesDir,"notes/$id.json")
            file.delete();File(file.path+".bak").delete()
        }
    }
}
