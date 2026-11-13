package com.localreader.twinleaf.pdf

import android.content.Context
import android.net.Uri
import android.graphics.Color
import com.localreader.twinleaf.ink.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

object PdfInkExporter {
    suspend fun export(context:Context,source:Uri,destination:Uri,ink:Map<Int,List<InkStroke>>,includeInk:Boolean)=withContext(Dispatchers.IO) {
        require(!sameDocument(context,source,destination)) { "请选择新文件，不能覆盖原书" }
        PDFBoxResourceLoader.init(context.applicationContext)
        val temp=File.createTempFile("ink-export-",".pdf",context.cacheDir)
        try {
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "无法打开原 PDF" }
                if(!includeInk || ink.values.all { it.isEmpty() }) temp.outputStream().use { input.copyTo(it) }
                else PDDocument.load(input,MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { document ->
                    require(document.currentAccessPermission.canModify()) { "该 PDF 不允许修改，不能合并批注" }
                    require(document.signatureDictionaries.isEmpty()) { "该 PDF 带数字签名，请先另存为未签名副本后再合并批注" }
                    ink.forEach { (index,strokes) ->
                        if(index !in 0 until document.numberOfPages || strokes.isEmpty()) return@forEach
                        val page=document.getPage(index);val box=page.cropBox
                        val displayWidth=if(page.rotation%180==0) box.width else box.height
                        PDPageContentStream(document,page,PDPageContentStream.AppendMode.APPEND,true,true).use { out ->
                            out.saveGraphicsState()
                            out.addRect(box.lowerLeftX,box.lowerLeftY,box.width,box.height);out.clip()
                            out.setLineCapStyle(1);out.setLineJoinStyle(1)
                            fun point(p:InkPoint)=pdfPoint(p,box.lowerLeftX,box.lowerLeftY,box.width,box.height,page.rotation)
                            strokes.forEach { stroke ->
                                if(stroke.points.isEmpty()) return@forEach
                                fun alpha(point:InkPoint?=null)=out.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                                    val value=inkOpacity(stroke,point)
                                    strokingAlphaConstant=value
                                    nonStrokingAlphaConstant=value
                                })
                                alpha(stroke.points.first())
                                out.setStrokingColor(Color.red(stroke.color),Color.green(stroke.color),Color.blue(stroke.color))
                                if(stroke.brush==InkBrush.HIGHLIGHTER || stroke.highlight) {
                                    out.setLineWidth(stroke.width*displayWidth)
                                    val first=point(stroke.points.first());out.moveTo(first.first,first.second)
                                    if(stroke.points.size==1) out.lineTo(first.first+.001f,first.second)
                                    stroke.points.drop(1).forEach { val p=point(it);out.lineTo(p.first,p.second) };out.stroke()
                                } else {
                                    if(stroke.points.size==1 && stroke.brush==InkBrush.BRUSH) {
                                        val one=stroke.points[0]
                                        val width=pressureWidth(stroke,one)
                                        val displayHeight=if(page.rotation%180==0) box.height else box.width
                                        val dx=cos(one.orientation)*width*.225f
                                        val dy=sin(one.orientation)*width*.225f*displayWidth/displayHeight
                                        val p=point(one.copy(x=one.x-dx,y=one.y-dy));val q=point(one.copy(x=one.x+dx,y=one.y+dy))
                                        out.setLineWidth(width*displayWidth*.55f)
                                        out.moveTo(p.first,p.second);out.lineTo(q.first,q.second);out.stroke()
                                        return@forEach
                                    }
                                    val segments=if(stroke.points.size==1) listOf(stroke.points[0] to stroke.points[0]) else stroke.points.zipWithNext()
                                    segments.forEach { (a,b) ->
                                        val p=point(a);val q=point(b)
                                        alpha(b)
                                        out.setLineWidth(pressureWidth(stroke,b)*displayWidth)
                                        out.moveTo(p.first,p.second);out.lineTo(q.first+.0001f,q.second);out.stroke()
                                    }
                                }
                            };out.restoreGraphicsState()
                        }
                    };document.save(temp)
                }
            }
            // Build the full result first; parse/source errors never truncate the destination.
            context.contentResolver.openOutputStream(destination,"wt").use { output ->
                requireNotNull(output) { "无法写入导出位置" };temp.inputStream().use { it.copyTo(output) }
            }
        } finally { temp.delete() }
    }

    private fun sameDocument(context:Context,a:Uri,b:Uri):Boolean {
        if(a==b) return true
        if(a.scheme=="file" && b.scheme=="file" && File(a.path!!).canonicalFile==File(b.path!!).canonicalFile) return true
        if(a.authority==b.authority && a.scheme=="content" && runCatching {
            android.provider.DocumentsContract.getDocumentId(a)==android.provider.DocumentsContract.getDocumentId(b)
        }.getOrDefault(false)) return true
        return runCatching {
            context.contentResolver.openFileDescriptor(a,"r").use { left ->
                context.contentResolver.openFileDescriptor(b,"r").use { right ->
                    if(left==null || right==null) false else {
                        val x=android.system.Os.fstat(left.fileDescriptor);val y=android.system.Os.fstat(right.fileDescriptor)
                        android.system.OsConstants.S_ISREG(x.st_mode) && android.system.OsConstants.S_ISREG(y.st_mode) && x.st_dev==y.st_dev && x.st_ino==y.st_ino
                    }
                }
            }
        }.getOrDefault(false)
    }
}
