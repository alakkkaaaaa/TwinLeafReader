package com.localreader.twinleaf.pdf

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import com.localreader.twinleaf.ink.InkStroke
import kotlinx.coroutines.*

/** Orientation and reader disposal do not cancel a running local export. */
object PdfExportJob {
    data class Request(val id:String,val source:Uri,val name:String,val strokes:Map<Int,List<InkStroke>>,val includeInk:Boolean)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    var busy by mutableStateOf(false); private set
    var result by mutableStateOf<String?>(null); private set
    var pending by mutableStateOf<Request?>(null); private set
    fun request(source:Uri,name:String,strokes:Map<Int,List<InkStroke>>,includeInk:Boolean) {
        if(!busy && pending==null) pending=Request(java.util.UUID.randomUUID().toString(),source,name,strokes,includeInk)
    }
    fun selected(context:Context,destination:Uri?) {
        val request=pending;pending=null
        if(destination==null) return
        if(request==null) { result="应用已被系统重新启动，请重新选择导出。未写入内容。";return }
        start(context,request.source,destination,request.strokes,request.includeInk)
    }
    fun dismiss() { result=null }
    fun start(context:Context,source:Uri,destination:Uri,strokes:Map<Int,List<InkStroke>>,includeInk:Boolean) {
        if(busy) return
        busy=true;result=null
        scope.launch {
            try {
                PdfInkExporter.export(context.applicationContext,source,destination,strokes,includeInk)
                result="已导出。原文件未修改，软件内笔迹仍可编辑。"
            } catch(e:Exception) { result="导出失败：${e.message}。如目标位置出现不完整文件，请删除该导出副本后重试。" }
            finally { busy=false }
        }
    }
}
