package com.localreader.twinleaf

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.localreader.twinleaf.epub.AndroidEpubDocument
import com.localreader.twinleaf.epub.EpubRenderView
import com.localreader.twinleaf.ui.components.CurlBookView
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import android.view.accessibility.AccessibilityNodeInfo
import com.localreader.twinleaf.data.LibraryRepository
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import com.localreader.twinleaf.pdf.AndroidPdfDocument
import com.localreader.twinleaf.epub.EpubScrollView
import android.content.pm.ActivityInfo
import android.os.Bundle
import com.localreader.twinleaf.data.PageShortcutRepository
import com.localreader.twinleaf.data.BookOutlineRepository
import com.localreader.twinleaf.data.OutlineImport
import com.localreader.twinleaf.data.OutlineEntry
import com.localreader.twinleaf.data.CoverEditor

@RunWith(AndroidJUnit4::class)
class ReaderRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Before fun resetTestShelf() {
        context.getSharedPreferences("twinleaf_library",0).edit().clear().commit()
    }

    private fun epub(tocMode:Int=0): File {
        val file = File(context.cacheDir, "regression.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            add("mimetype", "application/epub+zip")
            add("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""")
            val toc=when(tocMode) { 1->"<item id='nav' href='nav.xhtml' properties='nav' media-type='application/xhtml+xml'/>";2->"<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>";else->"" }
            add("OPS/book.opf", """<package xmlns="http://www.idpf.org/2007/opf"><metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">回归测试</dc:title></metadata><manifest><item id="a" href="a.xhtml"/><item id="b" href="b.xhtml"/>$toc</manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""")
            if(tocMode==1) add("OPS/nav.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="a.xhtml">第一章原书</a><ol><li><a href="a.xhtml#p90">中间小节</a></li></ol></li><li><a href="b.xhtml">第二章原书</a></li><li><a href="https://example.com">不加载外链</a></li></ol></nav></body></html>""")
            if(tocMode==2) add("OPS/toc.ncx","""<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint><navLabel><text>第一章原书</text></navLabel><content src="a.xhtml"/><navPoint><navLabel><text>中间小节</text></navLabel><content src="a.xhtml#p90"/></navPoint></navPoint><navPoint><navLabel><text>第二章原书</text></navLabel><content src="b.xhtml"/></navPoint></navMap></ncx>""")
            for (name in listOf("a", "b")) add("OPS/$name.xhtml", "<html><head></head><body><h1>章节 $name</h1>" +
                (1..180).joinToString("") { "<p id='p$it'>段落 $it：读书让我们看见更广阔的世界。翻页时，文字应当沿着纸张一起弯曲，书页背面显示后续内容，底下露出下一页。The quick brown fox jumps over the lazy dog.</p>" } + "</body></html>")
        }
        return file
    }

    private fun pdf(landscape:Boolean=false): File {
        val file = File(context.cacheDir, "regression.pdf")
        val pdf = PdfDocument()
        try {
            repeat(8) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(if(landscape)842 else 595,if(landscape)595 else 842,index).create())
                page.canvas.drawColor(Color.rgb(250,247,239))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.DKGRAY; textSize=16f }
                page.canvas.drawText("Page ${index+1} — real page curl",35f,50f,paint)
                repeat(30) { line -> page.canvas.drawText("Line $line: Paper, text and pictures bend together.",35f,90f+line*22,paint) }
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        return file
    }

    private fun launch(file: File): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(context, MainActivity::class.java).setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun <T:View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index),type)?.let { return it }
        return null
    }

    private fun waitFor(scenario: ActivityScenario<MainActivity>, condition: (MainActivity)->Boolean) {
        val deadline=SystemClock.uptimeMillis()+30000
        while(SystemClock.uptimeMillis()<deadline) {
            var done=false
            scenario.onActivity { done=condition(it) }
            if(done) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for reader state")
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(250)
        val directory=File(context.getExternalFilesDir(null),"verification").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }

    private fun tapReturnButton() {
        val density = context.resources.displayMetrics.density
        val time = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,54*density,24*density,0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time,time+30,MotionEvent.ACTION_UP,54*density,24*density,0))
    }

    @Test fun epubFollowsSystemDarkWithoutLosingPosition() {
        com.localreader.twinleaf.ui.theme.setThemeMode(context,com.localreader.twinleaf.ui.theme.ThemeMode.SYSTEM)
        instrumentation.uiAutomation.executeShellCommand("cmd uimode night no").close()
        launch(epub()).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            var before:Bitmap?=null
            scenario.onActivity { before=find(it.window.decorView,CurlBookView::class.java)!!.current }
            val position=LibraryRepository(context).load().first()
            instrumentation.uiAutomation.executeShellCommand("cmd uimode night yes").close()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.let { view ->
                view.current!=null && view.current!==before && Color.red(view.current!!.getPixel(20,20))<100
            }==true }
            assertEquals(position.epubChapter,LibraryRepository(context).load().first().epubChapter)
            assertEquals(position.epubSpread,LibraryRepository(context).load().first().epubSpread)
            assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.contentDescription=="批注工具" })
            screenshot("v200-epub-system-dark")
            instrumentation.uiAutomation.executeShellCommand("cmd uimode night no").close()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.let { Color.red(it.current!!.getPixel(20,20))>180 }==true }
        }
    }

    @Test fun epubOpensPaginatesTurnsAndReturns() {
        launch(epub()).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next != null }
            screenshot("epub-open-3390")
            scenario.onActivity { activity ->
                val view=find(activity.window.decorView,CurlBookView::class.java)!!
                assertNotNull(view.current)
                assertEquals(3390, view.width)
                val bitmap=view.current!!
                // Both columns contain ink, not just a blank webpage or spinner.
                for(side in 0..1) {
                    var ink=0
                    for(y in 100 until bitmap.height-100 step 7) for(x in (side*bitmap.width/2+50) until ((side+1)*bitmap.width/2-50) step 7) {
                        if(Color.red(bitmap.getPixel(x,y))<160) ink++
                    }
                    assertTrue("Column $side contains body text",ink>200)
                }
                val time=SystemClock.uptimeMillis()
                view.dispatchTouchEvent(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,view.width*.94f,view.height*.9f,0))
                view.dispatchTouchEvent(MotionEvent.obtain(time,time+150,MotionEvent.ACTION_MOVE,view.width*.60f,view.height*.8f,0))
            }
            SystemClock.sleep(200); screenshot("epub-curl-oblique")
            scenario.onActivity { activity ->
                val view=find(activity.window.decorView,CurlBookView::class.java)!!
                view.dispatchTouchEvent(MotionEvent.obtain(0,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,view.width*.6f,view.height*.8f,0))
            }
            SystemClock.sleep(1200); screenshot("epub-next")
            val saved = LibraryRepository(context).load().first()
            assertTrue("Turn commits a new location", saved.epubSpread > 0)
            tapReturnButton()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
            screenshot("epub-return-shelf")
            scenario.recreate()
            scenario.onActivity { it.startActivity(Intent(context,MainActivity::class.java)
                .setData(Uri.fromFile(File(context.cacheDir,"regression.epub")))) }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next != null }
            assertEquals(saved.epubSpread, LibraryRepository(context).load().first().epubSpread)
            pressBack()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
        }
    }

    @Test fun pdfCanExitWhilePageIsTurning() {
        launch(pdf()).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            scenario.onActivity { activity ->
                val view=find(activity.window.decorView,CurlBookView::class.java)!!
                val t=SystemClock.uptimeMillis()
                view.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,view.width*.94f,view.height*.9f,0))
                view.dispatchTouchEvent(MotionEvent.obtain(t,t+20,MotionEvent.ACTION_UP,view.width*.94f,view.height*.9f,0))
            }
            pressBack()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
            screenshot("pdf-return-shelf")
        }
    }

    @Test fun allLocalEpubSamplesExtractAndParse() {
        val samples=File(context.cacheDir,"samples").listFiles { f -> f.extension=="epub" }.orEmpty()
        assertTrue("Real EPUB samples must be staged",samples.size>=10)
        for(file in samples) AndroidEpubDocument.open(context,Uri.fromFile(file),file.name).use { book ->
            assertTrue("${file.name}: spine",book.spine.isNotEmpty())
            assertTrue("${file.name}: chapters",book.spine.all { it.file.isFile })
        }
    }

    @Test fun allLocalEpubSamplesRenderInActualWebView() {
        val samples=File(context.cacheDir,"samples").listFiles { f -> f.extension=="epub" }.orEmpty().sortedBy { it.name }
        assertEquals(10,samples.size)
        for(file in samples) launch(file).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current != null }
            scenario.onActivity { activity ->
                val bitmap=find(activity.window.decorView,CurlBookView::class.java)!!.current!!
                var ink=0
                for(y in 0 until bitmap.height step 10) for(x in 0 until bitmap.width step 10) {
                    if(Color.red(bitmap.getPixel(x,y))<180) ink++
                }
                assertTrue("${file.name} should render text or artwork",ink>20)
            }
            screenshot("real-${file.nameWithoutExtension}")
            tapReturnButton()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
        }
    }

    @Test fun recordCurlDemonstration() {
        launch(epub()).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next != null }
            SystemClock.sleep(1500)
            repeat(3) { turn ->
                var stamp=0L
                scenario.onActivity { activity ->
                    val view=find(activity.window.decorView,CurlBookView::class.java)!!
                    stamp=SystemClock.uptimeMillis()
                    view.dispatchTouchEvent(MotionEvent.obtain(stamp,stamp,MotionEvent.ACTION_DOWN,view.width*.96f,view.height*.92f,0))
                }
                for(step in 1..35) {
                    scenario.onActivity { activity ->
                        val view=find(activity.window.decorView,CurlBookView::class.java)!!
                        val x=view.width*(.96f-step*.02f)
                        view.dispatchTouchEvent(MotionEvent.obtain(stamp,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x,view.height*.8f,0))
                    }
                    SystemClock.sleep(32)
                    if(turn==0 && step in listOf(7,16,25,34)) screenshot("curl-stage-$step")
                }
                scenario.onActivity { activity ->
                    val view=find(activity.window.decorView,CurlBookView::class.java)!!
                    view.dispatchTouchEvent(MotionEvent.obtain(stamp,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,view.width*.2f,view.height*.8f,0))
                }
                SystemClock.sleep(1500)
            }
            tapReturnButton()
        }
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }

    private fun tapDescription(description: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var node: AccessibilityNodeInfo? = null
        while (node == null && SystemClock.uptimeMillis() < deadline) {
            node = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                .firstOrNull { it.contentDescription?.toString() == description && it.isVisibleToUser }
            if (node == null) SystemClock.sleep(100)
        }
        assertNotNull("Accessible icon: $description", node)
        val rect = Rect().also { node!!.getBoundsInScreen(it) }
        val t = SystemClock.uptimeMillis()
        instrumentation.uiAutomation.injectInputEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0),true)
        instrumentation.uiAutomation.injectInputEvent(MotionEvent.obtain(t,t+30,MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY(),0),true)
        SystemClock.sleep(350)
    }

    private fun tapText(text: String) {
        val node=awaitNode(text) { it.text?.toString()==text && it.isVisibleToUser }
        val rect=Rect().also { node.getBoundsInScreen(it) }
        val t=SystemClock.uptimeMillis()
        instrumentation.uiAutomation.injectInputEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0),true)
        instrumentation.uiAutomation.injectInputEvent(MotionEvent.obtain(t,t+30,MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY(),0),true)
        SystemClock.sleep(400)
    }

    private fun inputPage(value:String) {
        val field=awaitNode("page input") { it.isEditable }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }))
        SystemClock.sleep(250)
    }

    private fun inputOutline(description:String,value:String) {
        val node=awaitNode(description) { it.isEditable && nodes(it).any { child->child.contentDescription?.toString()==description } }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }))
        SystemClock.sleep(200)
    }

    private fun stageDownload(name:String,bytes:ByteArray) {
        val file=File(context.getExternalFilesDir(null),name);file.writeBytes(bytes)
        instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/$name").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }
    private fun selectDownload(name:String) {
        tapDescription("Show roots");tapText("Downloads")
        SystemClock.sleep(400)
        if(nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.contentDescription?.toString()=="List view" }) tapDescription("List view")
        val node=awaitNode(name) { it.text?.toString()==name || it.contentDescription?.toString()?.startsWith("$name,")==true }
        var action=node
        while(!action.isClickable && action.parent!=null) action=action.parent
        assertTrue("Document must be enabled: $name",action.isEnabled)
        assertTrue("Select document: $name",action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun importOutlineThroughPickerPreservesTreeAndRejectsBadInput() {
        val file=pdf();val key=Uri.fromFile(file).toString();val repo=BookOutlineRepository(context);repo.clear(key)
        stageDownload("v180-outline.txt","# test\n1\t2\t第一章导入\n2\t3\t第一节导入\n1\t7\t第二章导入".toByteArray())
        stageDownload("v180-invalid.txt","1\t999\t不应写入".toByteArray())
        stageDownload("v180-outline.json","""[{"level":1,"page":4,"title":"JSON章"},{"level":2,"page":5,"title":"JSON节"}]""".toByteArray())
        launch(file).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            tapDescription("章节大纲");tapDescription("导入外部大纲");selectDownload("v180-outline.txt")
            tapText("合并导入");assertEquals(listOf(1,2,1),repo.load(key).map { it.level })
            screenshot("v180-imported-tree")
            tapDescription("展开折叠 第一章导入")
            assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.contentDescription?.toString()=="跳转大纲 第一节导入" })
            tapDescription("关闭大纲");tapDescription("章节大纲")
            assertTrue(repo.collapsed(key).isNotEmpty())
            tapDescription("展开折叠 第一章导入");tapDescription("跳转大纲 第一节导入")
            waitFor(scenario) { LibraryRepository(context).load().first().pdfPage==2 }
            tapDescription("章节大纲");tapDescription("导入外部大纲");selectDownload("v180-invalid.txt")
            tapText("知道了");assertEquals(3,repo.load(key).size)
            tapDescription("导入外部大纲");selectDownload("v180-outline.txt");tapText("合并导入")
            assertEquals("Repeated imports deduplicate",3,repo.load(key).size)
            tapDescription("导入外部大纲");selectDownload("v180-outline.json");tapText("替换大纲")
            assertEquals(listOf("JSON章","JSON节"),repo.load(key).map { it.title })
            tapDescription("关闭大纲");tapReturnButton()
        }
        val json=OutlineImport.parse("""[{"level":1,"page":4,"title":"JSON章"},{"level":2,"page":5,"title":"JSON节"}]""",8)
        assertEquals(listOf(1,2),json.map { it.level })
        repo.importEntries(key,json,true);assertEquals(2,repo.load(key).size)
    }

    @Test fun folderLifecycleAndBooksPersist() {
        val repo=LibraryRepository(context)
        val file=pdf()
        launch(file).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            tapReturnButton()
            runBlocking { AndroidEpubDocument.open(context,Uri.fromFile(epub()),"系列第二本").use { repo.addOrRefresh(it) } }
            scenario.recreate();SystemClock.sleep(700)
            val original=repo.load().associateBy { it.id }
            tapDescription("添加内容");tapText("新建文件夹");inputOutline("文件夹名称","测试系列");tapText("保存")
            val folder=repo.folders().single()
            tapDescription("添加内容");tapText("新建文件夹");inputOutline("文件夹名称","测试系列");tapText("保存")
            awaitNode("duplicate name") { it.text?.toString()=="已存在同名文件夹" };tapText("取消")
            tapText("测试系列");tapDescription("添加内容");tapText("从书架移入已有书籍")
            repo.load().forEach { tapDescription("选择书籍：${it.title}") };tapText("保存（2）")
            assertTrue(repo.load().all { it.folderId==folder.id })
            screenshot("v190-folder-mosaic-shelf")
            tapText("测试系列")
            awaitNode("folder back") { it.contentDescription?.toString()=="返回书架根目录" }
            tapDescription("管理：regression");tapText("移动到文件夹");tapText("书架（不放入文件夹）")
            assertNull(repo.load().first { it.format=="PDF" }.folderId)
            tapDescription("书架更多");tapText("文件夹设置");tapText("重命名文件夹");inputOutline("文件夹名称","阅读系列");tapText("保存")
            assertEquals("阅读系列",repo.folders().single().name)
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            SystemClock.sleep(600);screenshot("v190-folder-portrait")
            scenario.recreate();SystemClock.sleep(700)
            awaitNode("restored folder") { it.contentDescription?.toString()=="返回书架根目录" }
            tapDescription("返回书架根目录")
            tapDescription("书架更多");tapText("批量管理");tapText("regression");tapDescription("批量移入文件夹");tapText("阅读系列")
            assertTrue(repo.load().all { it.folderId==folder.id })
            tapDescription("管理文件夹：阅读系列");tapText("删除文件夹");tapText("取消")
            assertEquals(1,repo.folders().size)
            tapDescription("管理文件夹：阅读系列");tapText("删除文件夹");tapText("删除文件夹")
            assertTrue(repo.folders().isEmpty());assertTrue(repo.load().all { it.folderId==null })
            repo.load().forEach { assertEquals(original[it.id],it) }
            assertTrue(file.isFile)
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        }
    }

    @Test fun folderCoverPickerCropsRestoresAndPersists() {
        val repo=LibraryRepository(context);val folder=repo.createFolder("封面系列")
        val source=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
        Canvas(source).apply { drawColor(Color.CYAN);drawRect(200f,0f,400f,400f,Paint().apply { color=Color.RED }) }
        val output=java.io.ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.PNG,100,output);source.recycle()
        stageDownload("v190-folder-cover.png",output.toByteArray())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            tapDescription("管理文件夹：封面系列");tapText("更换文件夹封面");tapText("选择封面图片")
            selectDownload("v190-folder-cover.png")
            val zoom=awaitNode("folder cover zoom") { it.rangeInfo?.max==4f }
            assertTrue(zoom.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,2f) }))
            val pan=awaitNode("folder cover pan") { it.rangeInfo?.min==-1f }
            assertTrue(pan.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,1f) }))
            screenshot("v190-folder-cover-editor");tapText("保存封面")
            waitFor(scenario) { repo.folders().single().coverPath!=null }
            val path=repo.folders().single().coverPath!!
            BitmapFactory.decodeFile(path).let { bitmap ->
                assertEquals(720,bitmap.width);assertEquals(1000,bitmap.height)
                assertTrue(Color.red(bitmap.getPixel(100,500))>220 && Color.green(bitmap.getPixel(100,500))<30);bitmap.recycle()
            }
            scenario.recreate();SystemClock.sleep(700)
            assertEquals(path,LibraryRepository(context).folders().single().coverPath)
            awaitNode("folder cover") { it.contentDescription?.toString()=="封面系列文件夹封面" }
            screenshot("v190-folder-custom-cover")
            tapDescription("管理文件夹：封面系列");tapText("更换文件夹封面");tapText("恢复默认拼图")
            assertNull(repo.folders().single().coverPath);assertEquals(folder.id,repo.folders().single().id)
        }
    }

    @Test fun importingInsideFolderAndReturningKeepsFolder() {
        val file=pdf();stageDownload("v190-series-import.pdf",file.readBytes())
        val repo=LibraryRepository(context);val folder=repo.createFolder("导入系列")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            tapText("导入系列");tapDescription("添加内容");tapText("导入到此文件夹");tapText("教科书");selectDownload("v190-series-import.pdf")
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            val book=repo.load().single();assertEquals(folder.id,book.folderId);assertEquals("教科书",book.category)
            tapReturnButton();awaitNode("back in imported folder") { it.contentDescription?.toString()=="返回书架根目录" }
            tapText("v190-series-import")
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            tapReturnButton();awaitNode("return folder again") { it.contentDescription?.toString()=="返回书架根目录" }
            pressBack();awaitNode("root again") { it.text?.toString()=="我的书架" }
            assertEquals(folder.id,repo.load().single().folderId)
        }
    }

    @Test fun folderRepositoryPreservesBookMetadataAndCleansMembership() {
        context.getSharedPreferences("twinleaf_library",0).edit().putString("books","""[
            {"id":"a","uri":"file:///folder-a.pdf","title":"甲","format":"PDF","progress":0.6,"pdfPage":42,"category":"教科书","customCoverPath":"cover.jpg"},
            {"id":"b","uri":"file:///folder-b.epub","title":"乙","format":"EPUB","epubChapter":3,"epubFraction":0.2}]
        """).commit()
        val repo=LibraryRepository(context);val original=repo.load()
        assertTrue(original.all { it.folderId==null })
        val a=repo.createFolder("甲系列");val b=repo.createFolder("乙系列")
        repo.moveToFolder(setOf("a","b"),a.id);repo.setFolderCover(a.id,"folder.jpg")
        repo.setFolderBooks(b.id,setOf("b"));assertEquals(a.id,repo.load().first { it.id=="a" }.folderId)
        repo.setFolderBooks(b.id,emptySet());assertNull(repo.load().first { it.id=="b" }.folderId)
        repo.deleteFolder(a.id);assertEquals(original,LibraryRepository(context).load())
        assertEquals(listOf(b),LibraryRepository(context).folders())
        assertThrows(IllegalArgumentException::class.java) { repo.moveToFolder(setOf("a"),a.id) }
    }

    @Test fun launcherUsesAdaptiveMaskWithoutBakedBlackCorners() {
        val icon=context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is android.graphics.drawable.AdaptiveIconDrawable)
        val adaptive=icon as android.graphics.drawable.AdaptiveIconDrawable
        val bitmap=Bitmap.createBitmap(768,256,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.LTGRAY)
        for(index in 0..2) {
            canvas.save();canvas.translate(index*256f+32f,32f)
            val mask=Path().apply { when(index) {
                0->addCircle(96f,96f,96f,Path.Direction.CW)
                1->addRoundRect(0f,0f,192f,192f,38f,38f,Path.Direction.CW)
                else->addRect(0f,0f,192f,192f,Path.Direction.CW)
            } }
            canvas.clipPath(mask);adaptive.setBounds(0,0,192,192)
            adaptive.background.draw(canvas);adaptive.foreground.draw(canvas);canvas.restore()
        }
        // A full square exposes any baked black/transparent edge that adaptive masks would retain.
        for(point in listOf(3 to 3,188 to 3,3 to 188,188 to 188)) {
            val pixel=bitmap.getPixel(544+point.first,32+point.second)
            assertTrue("No black baked corner",Color.red(pixel)+Color.green(pixel)+Color.blue(pixel)>120)
        }
        val dir=File(context.getExternalFilesDir(null),"verification").apply { mkdirs() }
        File(dir,"v190-icon-masks.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }

    @Test fun legacyDataMigratesAndCoverCropStaysInsideImage() {
        val uri="file:///legacy-regression.pdf"
        context.getSharedPreferences("twinleaf_library",0).edit().putString("books","""[{"id":"legacy","uri":"$uri","title":"旧书","format":"PDF","progress":0.4,"pdfPage":12}]""").commit()
        val legacy=LibraryRepository(context).load().single()
        assertEquals("未分类",legacy.category);assertNull(legacy.customCoverPath);assertEquals(12,legacy.pdfPage)
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("reader_book_outline",0).edit().putString(key,"""[{"id":"old","title":"旧章节","page":13,"chapter":-1}]""").commit()
        val outline=BookOutlineRepository(context);assertEquals(1,outline.load(uri).single().level)
        outline.setCollapsed(uri,setOf("old"));assertEquals(setOf("old"),BookOutlineRepository(context).collapsed(uri))
        assertTrue(outline.load("file:///another.pdf").isEmpty())
        LibraryRepository(context).remove("legacy");assertTrue(outline.load(uri).isEmpty());assertTrue(outline.collapsed(uri).isEmpty())
        for(width in listOf(50,400,2048)) for(height in listOf(50,800,2048)) for(zoom in listOf(1f,2f,4f)) for(pan in listOf(-1f,0f,1f)) {
            val rect=CoverEditor.cropRect(width,height,zoom,pan,-pan)
            assertTrue(rect.width()>0 && rect.height()>0 && rect.left>=0 && rect.top>=0 && rect.right<=width && rect.bottom<=height)
        }
    }

    @Test fun builtinTocParsesNavAndNcxAndJumpsToFragment() {
        BookOutlineRepository(context).clear(Uri.fromFile(File(context.cacheDir,"regression.epub")).toString())
        for(mode in listOf(1,2)) AndroidEpubDocument.open(context,Uri.fromFile(epub(mode)),"目录测试").use { book ->
            assertEquals(listOf(1,2,1),book.outline.map { it.level })
            assertEquals("p90",book.outline[1].anchor)
            assertEquals(1,book.outline.last().chapter)
        }
        launch(epub(1)).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            tapDescription("章节大纲");tapDescription("跳转大纲 中间小节")
            waitFor(scenario) { LibraryRepository(context).load().first().epubSpread>0 }
            screenshot("v180-toc-landscape-anchor")
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor(scenario) { find(it.window.decorView,EpubScrollView::class.java)?.ready==true }
            tapDescription("章节大纲");tapDescription("跳转大纲 中间小节")
            waitFor(scenario) { find(it.window.decorView,EpubScrollView::class.java)?.let { v->v.ready && v.scrollY>5000 }==true }
            var top=Float.NaN
            scenario.onActivity { find(it.window.decorView,EpubScrollView::class.java)!!.evaluateJavascript("document.getElementById('p90').getBoundingClientRect().top") { top=it.toFloat() } }
            waitFor(scenario) { top.isFinite() };assertTrue("Fragment aligns with viewport top: $top",kotlin.math.abs(top)<4f)
            screenshot("v180-toc-portrait-anchor")
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null };tapReturnButton()
        }
    }

    @Test fun coverReplacementAndCategoriesPersistWithoutChangingProgress() {
        val file=pdf();val repo=LibraryRepository(context)
        val image=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
        Canvas(image).apply { drawColor(Color.CYAN);drawRect(200f,0f,400f,400f,Paint().apply { color=Color.RED }) }
        val output=java.io.ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,output);image.recycle()
        stageDownload("v180-cover.png",output.toByteArray())
        launch(file).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            tapReturnButton();waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
            val management=awaitNode("book management") { it.contentDescription?.toString()?.startsWith("管理：")==true }.contentDescription.toString()
            val original=repo.load().single()
            tapDescription(management);tapText("修改分类 · 未分类");tapText("教科书")
            assertEquals("教科书",repo.load().single().category)
            tapDescription(management);tapText("从图片更换封面");selectDownload("v180-cover.png")
            val zoom=awaitNode("cover zoom") { it.rangeInfo?.max==4f }
            assertTrue(zoom.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,2f) }))
            val pan=awaitNode("cover horizontal position") { it.rangeInfo?.min==-1f }
            assertTrue(pan.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,1f) }))
            screenshot("v180-cover-crop-editor")
            tapText("保存封面")
            waitFor(scenario) { repo.load().single().customCoverPath!=null }
            val cover=File(repo.load().single().customCoverPath!!);assertTrue(cover.isFile)
            assertEquals(original.progress,repo.load().single().progress)
            val loaded=BitmapFactory.decodeFile(cover.absolutePath);assertEquals(720,loaded.width);assertEquals(1000,loaded.height)
            assertTrue("Saved crop follows zoom and pan",Color.red(loaded.getPixel(100,500))>220 && Color.green(loaded.getPixel(100,500))<30);loaded.recycle()
            screenshot("v180-custom-cover-shelf")
            scenario.recreate();SystemClock.sleep(800)
            assertEquals(cover.absolutePath,LibraryRepository(context).load().single().customCoverPath)
            tapDescription(management);tapText("恢复原始封面");assertNull(repo.load().single().customCoverPath)
            tapDescription(management);tapText("选择 PDF 页面作封面");inputPage("2");tapText("预览");tapText("保存封面")
            waitFor(scenario) { repo.load().single().customCoverPath!=null }
            tapDescription("书架更多");tapText("批量管理");tapText("regression")
            tapText("分类（1）");tapText("漫画")
            assertEquals("漫画",repo.load().single().category)
            assertTrue(file.isFile)
        }
    }

    @Test fun customPdfOutlineEditsPersistsAndJumpsInPortrait() {
        val file=pdf();val uri=Uri.fromFile(file).toString();val repository=BookOutlineRepository(context)
        repository.clear(uri)
        launch(file).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            tapDescription("章节大纲");tapDescription("添加大纲章节")
            inputOutline("大纲章节名称","第一章");inputOutline("大纲起始页码","3");tapDescription("保存大纲章节")
            tapDescription("添加大纲章节")
            inputOutline("大纲章节名称","第二章");inputOutline("大纲起始页码","7");tapDescription("保存大纲章节")
            tapDescription("编辑大纲 第一章");inputOutline("大纲起始页码","4");tapDescription("保存大纲章节")
            assertEquals(listOf(4,7),repository.load(uri).map { it.page })
            screenshot("v170-pdf-outline")
            tapDescription("跳转大纲 第一章")
            waitFor(scenario) { LibraryRepository(context).load().first().pdfPage==3 }
            tapReturnButton()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
            scenario.onActivity { it.startActivity(Intent(context,MainActivity::class.java).setData(Uri.fromFile(file))) }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
            tapDescription("章节大纲");tapDescription("跳转大纲 第二章")
            waitFor(scenario) { LibraryRepository(context).load().first().pdfPage==6 }
            tapDescription("章节大纲");tapDescription("删除大纲 第二章");tapDescription("取消删除大纲")
            assertEquals(2,repository.load(uri).size)
            tapDescription("删除大纲 第二章");tapDescription("确认删除大纲")
            assertEquals("第一章",repository.load(uri).single().title)
            tapDescription("关闭大纲")
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            tapReturnButton()
        }
    }

    @Test fun epubOutlineCanJumpToOtherChapterInBothOrientations() {
        val file=epub();val uri=Uri.fromFile(file).toString();BookOutlineRepository(context).clear(uri)
        launch(file).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            tapDescription("章节大纲");tapDescription("添加大纲章节")
            inputOutline("大纲章节名称","第二章开头");inputOutline("大纲章节序号","2");inputOutline("大纲起始页码","1")
            screenshot("v170-epub-outline-editor")
            tapDescription("保存大纲章节");tapDescription("跳转大纲 第二章开头")
            waitFor(scenario) { LibraryRepository(context).load().first().let { it.epubChapter==1 && it.epubSpread==0 } }
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor(scenario) { find(it.window.decorView,EpubScrollView::class.java)?.ready==true }
            scenario.onActivity { find(it.window.decorView,EpubScrollView::class.java)!!.scrollTo(0,3000) }
            tapDescription("章节大纲");tapDescription("跳转大纲 第二章开头")
            waitFor(scenario) { find(it.window.decorView,EpubScrollView::class.java)?.let { v->v.ready && v.chapterIndex==1 && v.scrollY==0 }==true }
            screenshot("v170-epub-outline-chapter-start")
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            tapReturnButton()
        }
    }

    @Test fun consecutiveTurnsReusePreparedPages() {
        for(file in listOf(pdf(),epub())) {
            context.getSharedPreferences("twinleaf_library",0).edit().clear().commit()
            launch(file).use { scenario ->
                waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
                SystemClock.sleep(1800) // Warm the two-spread look-ahead once, not between turns.
                for(step in 1..3) {
                    var target:Bitmap?=null
                    waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.let { v->!v.isTurning && v.next!=null && v.next!==v.current }==true }
                    scenario.onActivity { activity ->
                        val v=find(activity.window.decorView,CurlBookView::class.java)!!;target=v.next
                        val t=SystemClock.uptimeMillis()
                        v.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,v.width*.9f,v.height*.8f,0))
                        v.dispatchTouchEvent(MotionEvent.obtain(t,t+20,MotionEvent.ACTION_UP,v.width*.9f,v.height*.8f,0))
                        assertTrue("Prepared next page starts immediately",v.isTurning)
                    }
                    waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.isTurning==false &&
                        LibraryRepository(context).load().first().let { if(file.extension=="pdf") it.pdfSpread==step else it.epubSpread==step } }
                    scenario.onActivity {
                        val v=find(it.window.decorView,CurlBookView::class.java)!!
                        assertSame("Committed page reuses the prepared bitmap",target,v.current)
                        if(step<3) assertTrue("Next turn is ready at the end of this turn",v.next!=null && v.next!==v.current)
                    }
                }
                screenshot("v170-continuous-${file.extension}")
                tapReturnButton()
                waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
            }
        }
    }

    private fun awaitNode(label:String, predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
        val deadline=SystemClock.uptimeMillis()+10000
        while(SystemClock.uptimeMillis()<deadline) {
            nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(150)
        }
        screenshot("v160-node-failure")
        val details=nodes(instrumentation.uiAutomation.rootInActiveWindow).joinToString("\n") {
            "${it.className}: ${it.text} / ${it.contentDescription} / editable=${it.isEditable}"
        }
        throw AssertionError("Missing $label: $details")
    }

    private fun assertToolbarPainted() {
        SystemClock.sleep(300)
        val icons=listOf("返回书架","书本设置").map { description ->
            description to awaitNode(description) { it.contentDescription?.toString()==description && it.isVisibleToUser }
        }
        val screenshot=instrumentation.uiAutomation.takeScreenshot()
        try {
            for((description,icon) in icons) {
                val rect=Rect().also { icon.getBoundsInScreen(it) }
                assertTrue("Toolbar icon has bounds",rect.width()>0 && rect.height()>0)
                val base=screenshot.getPixel((rect.left+2).coerceIn(0,screenshot.width-1),(rect.top+2).coerceIn(0,screenshot.height-1))
                var contrast=0
                for(y in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(screenshot.height))
                    for(x in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(screenshot.width)) {
                        val pixel=screenshot.getPixel(x,y)
                        if(kotlin.math.abs(Color.red(pixel)-Color.red(base))+kotlin.math.abs(Color.green(pixel)-Color.green(base))+
                            kotlin.math.abs(Color.blue(pixel)-Color.blue(base))>90) contrast++
                    }
                assertTrue("$description must be visibly painted, not just clickable",contrast>20)
            }
        } finally { screenshot.recycle() }
    }

    @Test fun pdfJumpShortcutsPersistAndToolbarIsVisible() {
        val file=pdf()
        val repository=PageShortcutRepository(context)
        repository.clear(Uri.fromFile(file).toString())
        launch(file).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            assertToolbarPainted()
            tapDescription("页码跳转"); inputPage("3")
            tapDescription("保存输入页码为快捷页")
            tapDescription("保存输入页码为快捷页")
            assertEquals(1,repository.load(Uri.fromFile(file).toString()).size)
            screenshot("v160-pdf-shortcuts")
            inputPage("9")
            tapDescription("跳到输入页码")
            assertEquals("Out-of-range input must not navigate",0,LibraryRepository(context).load().first().pdfPage)
            awaitNode("invalid input stays open") { it.isEditable }
            inputPage("8"); tapDescription("跳到输入页码")
            waitFor(scenario) { LibraryRepository(context).load().first().pdfPage==7 }
            assertToolbarPainted()
            tapDescription("快捷页"); tapDescription("跳转快捷页 3")
            waitFor(scenario) { LibraryRepository(context).load().first().pdfPage==2 }
            assertEquals(1,LibraryRepository(context).load().first().pdfSpread)
            screenshot("v160-pdf-page3")
            tapReturnButton()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
            scenario.onActivity { it.startActivity(Intent(context,MainActivity::class.java).setData(Uri.fromFile(file))) }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            tapDescription("快捷页"); tapDescription("跳转快捷页 3")
            waitFor(scenario) { LibraryRepository(context).load().first().pdfPage==2 }
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
            SystemClock.sleep(1000)
            assertToolbarPainted()
            tapDescription("页码跳转"); inputPage("7"); tapDescription("跳到输入页码")
            waitFor(scenario) { LibraryRepository(context).load().first().pdfPage==6 }
            screenshot("v160-pdf-portrait-page7")
            tapDescription("快捷页"); tapDescription("移除快捷页 3")
            assertTrue(repository.load(Uri.fromFile(file).toString()).isEmpty())
            tapDescription("关闭页码跳转")
            com.localreader.twinleaf.ui.theme.setThemeMode(context,com.localreader.twinleaf.ui.theme.ThemeMode.LIGHT)
            tapDescription("书本设置"); tapDescription("切换亮暗模式"); pressBack()
            SystemClock.sleep(800)
            assertToolbarPainted()
            screenshot("v160-pdf-toolbar-night")
            tapDescription("书本设置"); tapDescription("切换亮暗模式");tapDescription("切换亮暗模式"); pressBack()
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            tapReturnButton()
        }
    }

    @Test fun epubChapterPageJumpAndCrossOrientationShortcuts() {
        val file=epub()
        val repository=PageShortcutRepository(context)
        repository.clear(Uri.fromFile(file).toString())
        launch(file).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            tapDescription("页码跳转"); inputPage("4"); tapDescription("保存输入页码为快捷页")
            tapDescription("跳到输入页码")
            waitFor(scenario) { LibraryRepository(context).load().first().epubSpread==1 }
            screenshot("v160-epub-chapter-page4")
            val anchor=repository.load(Uri.fromFile(file).toString()).single().fraction
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor(scenario) { find(it.window.decorView,EpubScrollView::class.java)?.ready==true }
            tapDescription("快捷页"); tapDescription("跳转快捷页 1 · 4")
            waitFor(scenario) { kotlin.math.abs(LibraryRepository(context).load().first().epubFraction-anchor)<.03f }
            tapDescription("页码跳转"); inputPage("3"); tapDescription("保存输入页码为快捷页")
            tapDescription("跳到输入页码")
            waitFor(scenario) {
                val view=find(it.window.decorView,EpubScrollView::class.java)
                view?.ready==true && kotlin.math.abs(view.fraction-view.fractionForPage(3))<.005f
            }
            screenshot("v160-epub-portrait-page3")
            assertEquals(2,repository.load(Uri.fromFile(file).toString()).size)
            tapReturnButton()
            waitFor(scenario) { find(it.window.decorView,EpubScrollView::class.java)==null }
            scenario.onActivity { it.startActivity(Intent(context,MainActivity::class.java).setData(Uri.fromFile(file))) }
            waitFor(scenario) { find(it.window.decorView,EpubScrollView::class.java)?.ready==true }
            tapDescription("快捷页"); tapDescription("跳转快捷页 1 · 3")
            waitFor(scenario) { find(it.window.decorView,EpubScrollView::class.java)?.ready==true }
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            tapReturnButton()
        }
    }

    @Test fun shortPdfLastPageCanAlignAtTop() {
        launch(pdf(landscape=true)).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
            tapDescription("页码跳转"); inputPage("8"); tapDescription("跳到输入页码")
            waitFor(scenario) { LibraryRepository(context).load().first().pdfPage==7 }
            screenshot("v160-pdf-short-last-page")
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            tapReturnButton()
        }
    }

    @Test fun removeFromShelfKeepsOriginalAndSurvivesRecreation() {
        val file=pdf()
        launch(file).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            tapReturnButton()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)==null }
            SystemClock.sleep(600)
            val remove=nodes(instrumentation.uiAutomation.rootInActiveWindow)
                .first { it.contentDescription?.startsWith("管理：")==true }.contentDescription.toString()
            tapDescription(remove);tapText("移出书架")
            screenshot("v150-remove-confirm")
            tapText("取消")
            assertEquals(1,LibraryRepository(context).load().size)
            tapDescription(remove); tapText("移出书架");tapText("移除")
            assertEquals(0,LibraryRepository(context).load().size)
            assertTrue("User source must survive",file.isFile)
            scenario.recreate(); SystemClock.sleep(800)
            assertEquals("Removed book must not be resurrected",0,LibraryRepository(context).load().size)
            screenshot("v150-empty-shelf")
        }
    }

    @Test fun portraitPdfScrollsAndRotatesWithoutCurl() {
        launch(pdf()).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor(scenario) { it.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT &&
                find(it.window.decorView,CurlBookView::class.java)==null }
            SystemClock.sleep(2000)
            val screen=instrumentation.uiAutomation.takeScreenshot()
            val w=screen.width.toFloat(); val h=screen.height.toFloat(); screen.recycle()
            repeat(3) {
                val t=SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,w*.5f,h*.83f,0))
                for(step in 1..12) {
                    SystemClock.sleep(25)
                    instrumentation.sendPointerSync(MotionEvent.obtain(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,w*.5f,h*(.83f-.05f*step),0))
                }
                instrumentation.sendPointerSync(MotionEvent.obtain(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,w*.5f,h*.23f,0))
                SystemClock.sleep(500)
            }
            waitFor(scenario) { LibraryRepository(context).load().first().pdfPage>0 }
            val page=LibraryRepository(context).load().first().pdfPage
            screenshot("v150-pdf-portrait")
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            assertEquals((page+1)/2,LibraryRepository(context).load().first().pdfSpread)
            screenshot("v150-pdf-landscape")
            tapReturnButton()
        }
    }

    @Test fun portraitEpubScrollsCrossesChaptersAndRestoresLandscape() {
        launch(epub()).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next!=null }
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor(scenario) { find(it.window.decorView,EpubScrollView::class.java)?.ready==true }
            scenario.onActivity { activity ->
                assertNull(find(activity.window.decorView,CurlBookView::class.java))
                find(activity.window.decorView,EpubScrollView::class.java)!!.scrollTo(0,1800)
            }
            waitFor(scenario) { LibraryRepository(context).load().first().epubFraction>0 }
            screenshot("v150-epub-portrait")
            scenario.onActivity { find(it.window.decorView,EpubScrollView::class.java)!!.scrollTo(0,Int.MAX_VALUE) }
            SystemClock.sleep(300)
            scenario.onActivity { activity ->
                val v=find(activity.window.decorView,EpubScrollView::class.java)!!
                val t=SystemClock.uptimeMillis()
                v.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,v.width*.5f,v.height*.8f,0))
                v.dispatchTouchEvent(MotionEvent.obtain(t,t+50,MotionEvent.ACTION_MOVE,v.width*.5f,v.height*.75f,0))
                v.dispatchTouchEvent(MotionEvent.obtain(t,t+100,MotionEvent.ACTION_MOVE,v.width*.5f,v.height*.5f,0))
                v.dispatchTouchEvent(MotionEvent.obtain(t,t+130,MotionEvent.ACTION_UP,v.width*.5f,v.height*.5f,0))
            }
            waitFor(scenario) { val v=find(it.window.decorView,EpubScrollView::class.java); v?.ready==true && v.chapterIndex==1 }
            screenshot("v150-epub-next-chapter")
            scenario.onActivity { find(it.window.decorView,EpubScrollView::class.java)!!.scrollTo(0,3200) }
            SystemClock.sleep(250)
            val saved=LibraryRepository(context).load().first().epubFraction
            assertTrue(saved>0)
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.current!=null }
            assertEquals(1,LibraryRepository(context).load().first().epubChapter)
            assertTrue(kotlin.math.abs(LibraryRepository(context).load().first().epubFraction-saved)<.12f)
            screenshot("v150-epub-landscape")
            tapReturnButton()
        }
    }

    @Test fun shortDragTurnsButVerticalSwipeDoesNot() {
        launch(pdf()).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next != null }
            fun swipe(dx: Float, dy: Float) {
                var stamp = 0L
                scenario.onActivity { activity ->
                    val v = find(activity.window.decorView,CurlBookView::class.java)!!
                    stamp = SystemClock.uptimeMillis()
                    v.dispatchTouchEvent(MotionEvent.obtain(stamp,stamp,MotionEvent.ACTION_DOWN,v.width*.85f,v.height*.65f,0))
                }
                for (step in 1..8) {
                    SystemClock.sleep(35)
                    scenario.onActivity { activity ->
                        val v = find(activity.window.decorView,CurlBookView::class.java)!!
                        v.dispatchTouchEvent(MotionEvent.obtain(stamp,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,
                            v.width*.85f+dx*step/8,v.height*.65f+dy*step/8,0))
                    }
                }
                SystemClock.sleep(150) // Exercise distance threshold without a fast-flick shortcut.
                scenario.onActivity { activity ->
                    val v = find(activity.window.decorView,CurlBookView::class.java)!!
                    v.dispatchTouchEvent(MotionEvent.obtain(stamp,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,
                        v.width*.85f+dx,v.height*.65f+dy,0))
                }
                SystemClock.sleep(750)
            }
            swipe(0f,200f)
            assertEquals(0,LibraryRepository(context).load().first().pdfSpread)
            swipe(-150f,0f)
            waitFor(scenario) { LibraryRepository(context).load().first().pdfSpread == 1 }
            screenshot("v140-short-swipe")
            tapReturnButton()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java) == null }
        }
    }

    @Test fun redesignedShelfAndSymbolSettings() {
        val repository = LibraryRepository(context)
        val samples = File(context.cacheDir,"samples").listFiles { f -> f.extension == "epub" }.orEmpty()
        assertTrue(samples.size >= 10)
        runBlocking {
            for (file in samples) AndroidEpubDocument.open(context,Uri.fromFile(file),file.name).use {
                repository.addOrRefresh(it)
            }
            AndroidPdfDocument.open(context,Uri.fromFile(pdf()),"翻页测试.pdf").use { repository.addOrRefresh(it) }
        }
        assertEquals("大肥鱼阅读", context.applicationInfo.loadLabel(context.packageManager).toString())
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use {
            SystemClock.sleep(2000)
            screenshot("v140-bookshelf")
            tapDescription("书架更多");tapText("格式筛选")
            val pdfFilter = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                .filter { it.text?.toString() == "PDF" && it.isVisibleToUser }
                .minByOrNull { node -> Rect().also { node.getBoundsInScreen(it) }.top }
            assertNotNull("PDF format filter", pdfFilter)
            val rect = Rect().also { pdfFilter!!.getBoundsInScreen(it) }
            val t = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0))
            instrumentation.sendPointerSync(MotionEvent.obtain(t,t+30,MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY(),0))
            SystemClock.sleep(500)
            assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any {
                it.text?.toString() == "在细雨中呼喊" && it.isVisibleToUser
            })
            screenshot("v140-pdf-filter")
        }
        launch(epub()).use { scenario ->
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java)?.next != null }
            tapDescription("书本设置")
            screenshot("v140-settings")
            for (label in listOf("亮度", "字号")) {
                tapDescription(label)
                screenshot("v140-settings-${if(label=="亮度") "brightness" else "font"}")
                val all = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                val panel = all.firstOrNull { it.contentDescription?.toString() == "书本设置面板" }
                assertNotNull("Settings panel is accessible", panel)
                assertFalse("Settings has no visible Chinese labels", nodes(panel).any { node ->
                    node.text?.any { it in '\u4e00'..'\u9fff' } == true
                })
            }
            com.localreader.twinleaf.ui.theme.setThemeMode(context,com.localreader.twinleaf.ui.theme.ThemeMode.LIGHT)
            tapDescription("切换亮暗模式")
            waitFor(scenario) {
                val bitmap = find(it.window.decorView,CurlBookView::class.java)?.current
                bitmap != null && Color.red(bitmap.getPixel(0,0)) < 100
            }
            screenshot("v140-settings-night")
            assertEquals("DARK",context.getSharedPreferences("reader_visual_settings",0).getString("themeMode",null))
            tapDescription("切换亮暗模式");tapDescription("切换亮暗模式")
            pressBack() // Close settings before leaving the book.
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java) != null }
            tapReturnButton()
            waitFor(scenario) { find(it.window.decorView,CurlBookView::class.java) == null }
        }
    }
}
