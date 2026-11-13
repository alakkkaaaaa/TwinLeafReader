package com.localreader.twinleaf

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.*
import android.net.Uri
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.twinleaf.ink.*
import com.localreader.twinleaf.pdf.*
import com.localreader.twinleaf.data.*
import com.localreader.twinleaf.ui.components.CurlBookView
import com.localreader.twinleaf.ui.theme.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderV2Test {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val output=File(context.getExternalFilesDir(null),"verification").apply { mkdirs() }
    @Before fun clearAbandonedPickerRequest() {
        instrumentation.runOnMainSync { PdfExportJob.selected(context,null);PdfExportJob.dismiss() }
    }
    private fun waitFor(condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+30000
        while(SystemClock.uptimeMillis()<end) { if(condition()) return;SystemClock.sleep(100) };fail("Timed out")
    }
    private fun <T:View> find(view:View,type:Class<T>):T? {
        if(type.isInstance(view)) return type.cast(view)
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),type)?.let { return it }
        return null
    }
    private fun node(root:AccessibilityNodeInfo?,description:String):AccessibilityNodeInfo? {
        if(root==null) return null
        if(root.isVisibleToUser && (root.contentDescription?.toString()==description || root.text?.toString()==description)) return root
        for(i in 0 until root.childCount) node(root.getChild(i),description)?.let { return it }
        return null
    }
    private fun tap(description:String) {
        var target:AccessibilityNodeInfo?=null
        waitFor { target=node(instrumentation.uiAutomation.rootInActiveWindow,description);target!=null }
        val bounds=Rect();target!!.getBoundsInScreen(bounds)
        val time=SystemClock.uptimeMillis()
        instrumentation.uiAutomation.injectInputEvent(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0),true)
        instrumentation.uiAutomation.injectInputEvent(MotionEvent.obtain(time,time+40,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0),true)
        SystemClock.sleep(400)
    }
    private fun screenshot(name:String) {
        SystemClock.sleep(300)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(output,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
    }
    private fun fixture(rotations:Boolean=false):File {
        PDFBoxResourceLoader.init(context)
        val file=File(context.cacheDir,"v2-${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            repeat(4) { i ->
                val page=PDPage(PDRectangle(500f,700f));page.cropBox=PDRectangle(20f,30f,400f,600f)
                page.rotation=if(rotations) i*90 else 0;doc.addPage(page)
                PDPageContentStream(doc,page).use { stream ->
                    stream.setNonStrokingColor(250,247,239);stream.addRect(20f,30f,400f,600f);stream.fill()
                    stream.beginText();stream.setFont(PDType1Font.HELVETICA,18f);stream.setNonStrokingColor(25,25,25)
                    stream.newLineAtOffset(40f,590f);stream.showText("Retained vector text page ${i+1}");stream.endText()
                    stream.setStrokingColor(80,120,160);stream.addRect(60f,180f,280f,300f);stream.stroke()
                }
            };doc.save(file)
        };return file
    }
    private fun launch(file:File)=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun injectPinch(location:IntArray,width:Int,height:Int,firstTool:Int=MotionEvent.TOOL_TYPE_FINGER) {
        val start=SystemClock.uptimeMillis()
        val properties=arrayOf(MotionEvent.PointerProperties().apply { id=11;toolType=firstTool },
            MotionEvent.PointerProperties().apply { id=27;toolType=MotionEvent.TOOL_TYPE_FINGER })
        fun send(action:Int,count:Int,span:Float) {
            val coords=Array(count) { index -> MotionEvent.PointerCoords().apply {
                x=location[0]+width*.5f+(if(index==0) -span/2 else span/2)
                y=location[1]+height*.5f;pressure=.6f;size=.01f
            } }
            val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1f,1f,0,0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            instrumentation.sendPointerSync(event);event.recycle();SystemClock.sleep(25)
        }
        send(MotionEvent.ACTION_DOWN,1,width*.15f)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,width*.15f)
        repeat(12) { send(MotionEvent.ACTION_MOVE,2,width*(.15f+(it+1)*.015f)) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,width*.33f)
        send(MotionEvent.ACTION_UP,1,width*.33f)
    }
    @Test fun pinchWorksFromOneInBrowseAndWritingWithoutZoomButton() {
        context.getSharedPreferences("twinleaf_library",0).edit().clear().commit()
        val file=fixture();val uri=Uri.fromFile(file).toString()
        launch(file).use { scenario ->
            waitFor { var ready=false;scenario.onActivity { ready=find(it.window.decorView,CurlBookView::class.java)?.current!=null };ready }
            var location=IntArray(2);var width=0;var height=0
            scenario.onActivity { val view=find(it.window.decorView,CurlBookView::class.java)!!;view.getLocationOnScreen(location);width=view.width;height=view.height }
            injectPinch(location,width,height)
            tap("批注工具");tap("钢笔")
            waitFor { var ready=false;scenario.onActivity { ready=find(it.window.decorView,InkOverlayView::class.java)?.zoom?.let { z->z>1.4f }==true };ready }
            repeat(4) { tap("缩小页面") }
            scenario.onActivity { assertEquals(1f,find(it.window.decorView,InkOverlayView::class.java)!!.zoom,0f) }
            scenario.onActivity { val view=find(it.window.decorView,InkOverlayView::class.java)!!;view.getLocationOnScreen(location);width=view.width;height=view.height }
            injectPinch(location,width,height)
            waitFor { var ready=false;scenario.onActivity { ready=find(it.window.decorView,InkOverlayView::class.java)!!.zoom>1.4f };ready }
            assertTrue(InkStore.get(context,uri).pages.isEmpty())
            screenshot("v202-writing-pinch")
            repeat(4) { tap("缩小页面") }
            // Pen contact plus finger/palm must never activate zoom.
            scenario.onActivity { val view=find(it.window.decorView,InkOverlayView::class.java)!!;view.getLocationOnScreen(location);width=view.width;height=view.height }
            injectPinch(location,width,height,MotionEvent.TOOL_TYPE_STYLUS)
            scenario.onActivity { assertEquals(1f,find(it.window.decorView,InkOverlayView::class.java)!!.zoom,0f) }
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor { var ready=false;scenario.onActivity { ready=it.resources.configuration.orientation==1 && find(it.window.decorView,InkOverlayView::class.java)!=null };ready }
            SystemClock.sleep(300)
            scenario.onActivity { activity ->
                val visible=mutableListOf<Pair<InkOverlayView,Rect>>()
                fun collect(view:View) {
                    if(view is InkOverlayView) {
                        val r=Rect()
                        if(view.getGlobalVisibleRect(r)) {
                            // Global view coordinates exclude a letterboxed window's screen origin.
                            val origin=IntArray(2);view.rootView.getLocationOnScreen(origin)
                            r.offset(origin[0],origin[1]);visible.add(view to r)
                        }
                    }
                    if(view is ViewGroup) for(i in 0 until view.childCount) collect(view.getChildAt(i))
                }
                collect(activity.window.decorView)
                val (view,r)=visible.maxBy { it.second.width()*it.second.height() }
                assertEquals(1f,view.zoom,0f);location=intArrayOf(r.left,r.top);width=r.width();height=r.height()
            }
            val strokes=InkStore.get(context,uri).pages.values.sumOf { it.size }
            screenshot("v202-before-portrait-pinch")
            android.util.Log.i("V2Test","Portrait pinch origin=${location.toList()} size=$width x $height")
            injectPinch(location,width,height)
            waitFor { var ready=false;scenario.onActivity { ready=find(it.window.decorView,InkOverlayView::class.java)!!.zoom>1.4f };ready }
            assertEquals(strokes,InkStore.get(context,uri).pages.values.sumOf { it.size })
            screenshot("v202-writing-pinch-portrait")
        }
    }
    private fun stroke(view:InkOverlayView,tool:Int,cancel:Boolean=false) {
        val rect=view.pageRects.values.first();val down=SystemClock.uptimeMillis()
        val prop=arrayOf(MotionEvent.PointerProperties().apply { id=7;toolType=tool })
        for(i in 0..10) {
            val coords=arrayOf(MotionEvent.PointerCoords().apply {
                x=rect.left+rect.width()*(.2f+.06f*i);y=rect.top+rect.height()*.3f;pressure=.4f+.06f*i;size=.01f
            })
            val action=when(i) { 0 -> MotionEvent.ACTION_DOWN;10 -> if(cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP;else -> MotionEvent.ACTION_MOVE }
            val event=MotionEvent.obtain(down,down+i*15L,action,1,prop,coords,0,0,1f,1f,0,0,
                if(tool==MotionEvent.TOOL_TYPE_FINGER) android.view.InputDevice.SOURCE_TOUCHSCREEN else android.view.InputDevice.SOURCE_STYLUS,0)
            view.dispatchTouchEvent(event);event.recycle()
        }
    }
    @Test fun stylusInkPersistsHidesRotatesAndFollowsSystemDark() {
        context.getSharedPreferences("twinleaf_library",0).edit().clear().commit()
        setThemeMode(context,ThemeMode.SYSTEM)
        instrumentation.uiAutomation.executeShellCommand("cmd uimode night no").close()
        val file=fixture();val uri=Uri.fromFile(file).toString()
        launch(file).use { scenario ->
            waitFor { var ready=false;scenario.onActivity { ready=find(it.window.decorView,CurlBookView::class.java)?.current!=null };ready }
            tap("批注工具");tap("钢笔")
            waitFor { var ready=false;scenario.onActivity { ready=find(it.window.decorView,InkOverlayView::class.java)?.pageRects?.isNotEmpty()==true };ready }
            scenario.onActivity { activity ->
                val view=find(activity.window.decorView,InkOverlayView::class.java)!!
                stroke(view,MotionEvent.TOOL_TYPE_FINGER);assertTrue(view.store.pages.isEmpty())
                stroke(view,MotionEvent.TOOL_TYPE_STYLUS,true);assertTrue(view.store.pages.isEmpty())
                stroke(view,MotionEvent.TOOL_TYPE_STYLUS);assertEquals(1,view.store.pages[0]!!.size)
            }
            val ink=InkStore.get(context,uri)
            waitFor { ink.saveStatus=="已保存" }
            assertTrue(File(context.filesDir,"ink").listFiles()!!.any { f -> runCatching { InkStore.decode(f.readText())[0]?.size==1 }.getOrDefault(false) })
            screenshot("v200-ink-landscape")
            tap("撤销批注");assertTrue(ink.pages[0].isNullOrEmpty());tap("重做批注");assertEquals(1,ink.pages[0]!!.size)
            tap("隐藏批注");assertFalse(ink.visible);assertEquals(InkTool.BROWSE,ink.tool)
            tap("显示批注");tap("钢笔")
            tap("局部橡皮擦")
            scenario.onActivity { stroke(find(it.window.decorView,InkOverlayView::class.java)!!,MotionEvent.TOOL_TYPE_STYLUS) }
            assertTrue(ink.pages[0].isNullOrEmpty());tap("撤销批注");assertEquals(1,ink.pages[0]!!.size)
            tap("荧光笔")
            scenario.onActivity { stroke(find(it.window.decorView,InkOverlayView::class.java)!!,MotionEvent.TOOL_TYPE_STYLUS) }
            assertTrue(ink.pages[0]!!.last().highlight);tap("撤销批注")
            // Browse intentionally leaves the annotation toolbar in current releases.
            tap("浏览模式");tap("批注工具");tap("放大页面");tap("钢笔")
            // Real injected stylus events travel through the scaled Compose/native boundary.
            var location=IntArray(2);var rect=RectF();var viewportWidth=0;var viewportHeight=0
            scenario.onActivity { val view=find(it.window.decorView,InkOverlayView::class.java)!!
                view.getLocationOnScreen(location);rect=RectF(view.pageRects.values.first());viewportWidth=view.width;viewportHeight=view.height }
            android.util.Log.i("V2Test","Zoom native origin=${location.toList()} viewport=$viewportWidth x $viewportHeight rect=$rect")
            screenshot("v200-zoom-before")
            val down=SystemClock.uptimeMillis()
            for(i in 0..4) {
                val prop=arrayOf(MotionEvent.PointerProperties().apply { id=3;toolType=MotionEvent.TOOL_TYPE_STYLUS })
                val coords=arrayOf(MotionEvent.PointerCoords().apply {
                    x=location[0]+viewportWidth/2f+(rect.left+rect.width()*(.35f+i*.025f)-viewportWidth/2f)*1.5f
                    y=location[1]+viewportHeight/2f+(rect.top+rect.height()*.45f-viewportHeight/2f)*1.5f;pressure=.7f;size=.01f
                })
                val event=MotionEvent.obtain(down,down+i*20L,if(i==0) MotionEvent.ACTION_DOWN else if(i==4) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                    1,prop,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_STYLUS,0)
                instrumentation.sendPointerSync(event);event.recycle()
            }
            waitFor { ink.pages[0]?.size==2 }
            assertEquals(.35f,ink.pages[0]!!.last().points.first().x,.025f)
            screenshot("v200-zoom-ink")
            tap("撤销批注");tap("缩小页面")
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor { var ready=false;scenario.onActivity { ready=it.resources.configuration.orientation==1 && find(it.window.decorView,InkOverlayView::class.java)!=null };ready }
            assertEquals(1,ink.pages[0]!!.size)
            screenshot("v200-ink-portrait")
            instrumentation.uiAutomation.executeShellCommand("cmd uimode night yes").close()
            waitFor { context.resources.configuration.uiMode and 0x30==0x20 }
            screenshot("v200-pdf-system-dark")
            scenario.recreate()
            tap(file.name.removeSuffix(".pdf"))
            waitFor { var ready=false;scenario.onActivity { ready=find(it.window.decorView,InkOverlayView::class.java)!=null };ready }
            assertEquals(1,InkStore.get(context,uri).pages[0]!!.size)
            context.getSharedPreferences("v2_verification",0).edit().putString("persistedUri",uri).commit()
            tap("退出批注");tap("返回书架");screenshot("v200-shelf-system-dark")
        }
        instrumentation.uiAutomation.executeShellCommand("cmd uimode night no").close()
    }

    @Test fun canceledSecondaryStylusPointerDoesNotEraseOrSave() {
        lateinit var ink:InkStore
        instrumentation.runOnMainSync { ink=InkStore.get(context,"cancel-probe-${System.nanoTime()}") }
        waitFor { ink.ready }
        instrumentation.runOnMainSync {
            val view=InkOverlayView(context);view.store=ink;view.layout(0,0,1000,1500);view.pageRects=mapOf(0 to RectF(0f,0f,1000f,1500f));ink.choose(InkTool.PEN)
            val start=SystemClock.uptimeMillis()
            val props=arrayOf(MotionEvent.PointerProperties().apply { id=11;toolType=MotionEvent.TOOL_TYPE_FINGER },
                MotionEvent.PointerProperties().apply { id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS })
            val coords=arrayOf(MotionEvent.PointerCoords().apply { x=50f;y=50f;pressure=1f },MotionEvent.PointerCoords().apply { x=500f;y=600f;pressure=.8f })
            fun send(action:Int,count:Int,flags:Int=0) { val e=MotionEvent.obtain(start,start+10,action,count,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_STYLUS,flags)
                view.dispatchTouchEvent(e);e.recycle() }
            send(MotionEvent.ACTION_DOWN,1)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2)
            assertTrue(ink.drawing)
            coords[1].x=600f;send(MotionEvent.ACTION_MOVE,2)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,MotionEvent.FLAG_CANCELED)
            assertFalse(ink.drawing);assertTrue(ink.pages.isEmpty())
            send(MotionEvent.ACTION_UP,1);assertTrue(ink.pages.isEmpty())
        }
    }

    @Test fun hiddenInkExportsThroughSystemPickerAcrossRotation() {
        val file=fixture();val uri=Uri.fromFile(file)
        launch(file).use { scenario ->
            lateinit var ink:InkStore
            scenario.onActivity { ink=InkStore.get(context,uri.toString()) }
            waitFor { ink.ready }
            scenario.onActivity { ink.change(0,listOf(InkStroke(listOf(InkPoint(.2f,.3f),InkPoint(.8f,.3f)),Color.RED,.01f)));ink.updateVisibility(false) }
            tap("批注工具");tap("导出 PDF");tap("另存为")
            tap("Show roots")
            val root=instrumentation.uiAutomation.rootInActiveWindow
            val choices=root.findAccessibilityNodeInfosByText("Downloads").filter { it.text?.toString()=="Downloads" && it.isVisibleToUser }
            // DocumentsUI's ListView row uses item-click rather than a clickable TextView.
            // Its drawer is the last subtree; earlier matches are the obscured breadcrumb.
            val entry=choices.lastOrNull()
            assertNotNull("Downloads drawer entry",entry)
            val entryBounds=Rect();entry!!.getBoundsInScreen(entryBounds)
            val t=SystemClock.uptimeMillis()
            instrumentation.uiAutomation.injectInputEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,entryBounds.exactCenterX(),entryBounds.exactCenterY(),0),true)
            instrumentation.uiAutomation.injectInputEvent(MotionEvent.obtain(t,t+30,MotionEvent.ACTION_UP,entryBounds.exactCenterX(),entryBounds.exactCenterY(),0),true)
            SystemClock.sleep(500)
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            SystemClock.sleep(500)
            var saveLabel=""
            waitFor { saveLabel=listOf("SAVE","Save","保存").firstOrNull { node(instrumentation.uiAutomation.rootInActiveWindow,it)!=null }.orEmpty();saveLabel.isNotEmpty() }
            screenshot("v200-picker-before-save")
            val save=node(instrumentation.uiAutomation.rootInActiveWindow,saveLabel)!!
            assertTrue("Save document",save.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(1000);screenshot("v200-picker-after-save")
            waitFor { PdfExportJob.result!=null && !PdfExportJob.busy }
            assertTrue(PdfExportJob.result,PdfExportJob.result!!.startsWith("已导出"))
            screenshot("v200-export-success");tap("确定")
            assertFalse(ink.visible)
            val exportedName=file.name.removeSuffix(".pdf")+"-批注.pdf"
            val bytes=instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$exportedName").let { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
            assertTrue(bytes.size>1000)
            PDDocument.load(bytes).use { doc ->
                // Source stream + context reset + appended ink remain vector content.
                assertTrue(doc.getPage(0).contentStreams.asSequence().count()>1)
                assertEquals(4,doc.numberOfPages)
            }
        }
    }

    @Test fun exportPreservesTextAndCropRotationAndNeverChangesSource()=runBlocking {
        val source=fixture(true);val original=source.readBytes()
        val ink=InkStroke(listOf(InkPoint(.2f,.3f),InkPoint(.8f,.3f)),Color.RED,.015f)
        val result=File(output,"v200-rotated-ink.pdf")
        PdfInkExporter.export(context,Uri.fromFile(source),Uri.fromFile(result),(0..3).associateWith { listOf(ink) },true)
        assertArrayEquals(original,source.readBytes())
        PDDocument.load(result).use { doc ->
            assertEquals(4,doc.numberOfPages)
            val text=PDFTextStripper().getText(doc)
            PDDocument.load(source).use { originalDoc -> assertEquals(PDFTextStripper().getText(originalDoc),text) }
            assertTrue(text.replace(Regex("\\s"),"").contains("Retainedvectortextpage1"))
        }
        AndroidPdfDocument.open(context,Uri.fromFile(result),"export").use { doc ->
            repeat(4) { i ->
                val aspect=doc.pageAspectRatio(i);val bitmap=doc.renderPage(i,600,(600/aspect).toInt())
                val pixel=bitmap.getPixel(bitmap.width/2,(bitmap.height*.3f).toInt())
                assertTrue("Rotation ${i*90}: ink aligned, pixel=$pixel",Color.red(pixel)>200 && Color.green(pixel)<90)
                File(output,"v200-export-rotation-${i*90}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
        val plain=File(output,"v200-original-only.pdf")
        PdfInkExporter.export(context,Uri.fromFile(source),Uri.fromFile(plain),mapOf(0 to listOf(ink)),false)
        assertArrayEquals(original,plain.readBytes())
        try { PdfInkExporter.export(context,Uri.fromFile(source),Uri.fromFile(source),mapOf(0 to listOf(ink)),true);fail("Must refuse source overwrite") }
        catch(expected:IllegalArgumentException) { assertArrayEquals(original,source.readBytes()) }
    }

    @Test fun folderSortPersistsAcrossModesAndAppRecreation() {
        val array=JSONArray()
        listOf("系列第10卷","系列第2卷","系列第1卷").forEachIndexed { index,title ->
            array.put(JSONObject().put("id","v2-$index").put("uri","file:///missing-$index.pdf").put("title",title).put("format","PDF")
                .put("importedAt",index+1).put("lastOpenedAt",3-index))
        }
        context.getSharedPreferences("twinleaf_library",0).edit().clear().putString("books",array.toString()).commit()
        val repo=LibraryRepository(context);val folder=repo.createFolder("2.0 系列排序")
        repo.moveToFolder(repo.load().map { it.id }.toSet(),folder.id)
        repo.updateFolderSort(folder.id,FolderSort.MANUAL,false,listOf("v2-1","v2-0","v2-2"))
        repo.updateFolderSort(folder.id,FolderSort.TITLE,false)
        assertEquals(listOf("v2-2","v2-1","v2-0"),sortedFolderBooks(repo.load(),repo.folders().first()).map { it.id })
        assertEquals(listOf("v2-1","v2-0","v2-2"),repo.folders().first().manualOrder)
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            tap("2.0 系列排序");tap("书架更多");tap("文件夹排序");tap("手动排序")
            tap("上移：v2-0");tap("保存排序")
            assertEquals(listOf("v2-0","v2-1","v2-2"),repo.folders().first().manualOrder)
            scenario.recreate();tap("书架更多");tap("文件夹排序");screenshot("v200-folder-sort")
            val handle=node(instrumentation.uiAutomation.rootInActiveWindow,"拖动排序：v2-0")!!
            val rect=Rect();handle.getBoundsInScreen(rect);val t=SystemClock.uptimeMillis();val row=56*context.resources.displayMetrics.density
            instrumentation.sendPointerSync(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0))
            instrumentation.sendPointerSync(MotionEvent.obtain(t,t+100,MotionEvent.ACTION_MOVE,rect.exactCenterX(),rect.exactCenterY()+row,0))
            instrumentation.sendPointerSync(MotionEvent.obtain(t,t+150,MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY()+row,0))
            tap("保存排序")
            assertEquals(listOf("v2-1","v2-0","v2-2"),repo.folders().first().manualOrder)
            assertEquals(FolderSort.MANUAL,repo.folders().first().sort)
        }
    }
}
