package com.localreader.twinleaf

import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.view.*
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.twinleaf.data.LandscapePageMode
import com.localreader.twinleaf.epub.AndroidEpubDocument
import com.localreader.twinleaf.epub.EpubRenderView
import com.localreader.twinleaf.epub.EpubScrollView
import com.localreader.twinleaf.pdf.AndroidPdfDocument
import com.localreader.twinleaf.ui.*
import com.localreader.twinleaf.ui.components.CardPageView
import com.localreader.twinleaf.ui.components.CurlBookView
import com.localreader.twinleaf.ui.theme.TwinLeafTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual activity dispatch -> scoped Compose viewport -> native animation -> saved position. */
@RunWith(AndroidJUnit4::class)
class ReaderExternalNavigationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun waitFor(scenario:ActivityScenario<MainActivity>,message:String,condition:(MainActivity)->Boolean) {
        val deadline=SystemClock.uptimeMillis()+30000
        while(SystemClock.uptimeMillis()<deadline) {
            var done=false
            scenario.onActivity {done=condition(it)}
            if(done) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
    private fun <T:View> find(root:View,type:Class<T>):T? {
        if(type.isInstance(root)) return type.cast(root)
        if(root is ViewGroup) for(i in 0 until root.childCount) find(root.getChildAt(i),type)?.let {return it}
        return null
    }
    private fun wheel(scenario:ActivityScenario<MainActivity>,direction:Int,horizontal:Boolean=false) {
        scenario.onActivity {activity->
            val p=arrayOf(MotionEvent.PointerProperties().apply {id=0;toolType=MotionEvent.TOOL_TYPE_MOUSE})
            val c=arrayOf(MotionEvent.PointerCoords().apply {
                x=activity.window.decorView.width*.6f;y=activity.window.decorView.height*.6f
                setAxisValue(if(horizontal) MotionEvent.AXIS_HSCROLL else MotionEvent.AXIS_VSCROLL,
                    if(horizontal) direction*4f else -direction*4f)
            })
            val event=MotionEvent.obtain(0,SystemClock.uptimeMillis(),MotionEvent.ACTION_SCROLL,1,p,c,0,0,1f,1f,
                29111,0,InputDevice.SOURCE_MOUSE,0)
            try {assertTrue("Reader must handle scroll without a preceding click",activity.dispatchGenericMotionEvent(event))}
            finally {event.recycle()}
        }
    }
    private fun key(scenario:ActivityScenario<MainActivity>,code:Int) {
        scenario.onActivity {activity->
            val time=SystemClock.uptimeMillis()
            assertTrue(activity.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0)))
            assertTrue(activity.dispatchKeyEvent(KeyEvent(time,time+1,KeyEvent.ACTION_UP,code,0)))
        }
    }
    private fun padSwipe(scenario:ActivityScenario<MainActivity>,pixels:Boolean) {
        scenario.onActivity {activity->
            val t=SystemClock.uptimeMillis()
            val x=activity.window.decorView.width*.6f
            val y=activity.window.decorView.height*.6f
            val distance=150f*activity.resources.displayMetrics.density
            fun send(action:Int,time:Long,position:Float,classification:Int,delta:Float=0f) {
                val event=touchpadEvent(t,time,action,x,position,classification,delta)
                try {activity.dispatchTouchEvent(event)} finally {event.recycle()}
            }
            send(MotionEvent.ACTION_DOWN,t,y,0)
            send(MotionEvent.ACTION_MOVE,t+20,if(pixels)y else y-distance,
                if(pixels)0 else MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE,if(pixels)distance else 0f)
            // The terminal event can carry neither the classification nor the scroll axes.
            send(MotionEvent.ACTION_UP,t+40,y,0)
        }
    }
    private fun pdf():File {
        val file=File(context.cacheDir,"external-${System.nanoTime()}.pdf")
        val pdf=android.graphics.pdf.PdfDocument()
        try {
            repeat(14) { index->
                val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595,842,index).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("External input page ${index+1}",35f,90f,
                    Paint().apply {color=Color.BLACK;textSize=24f})
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally {pdf.close()}
        return file
    }
    private fun epub():File {
        val file=File(context.cacheDir,"external-${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use {zip->
            fun entry(path:String,text:String) {zip.putNextEntry(ZipEntry(path));zip.write(text.toByteArray());zip.closeEntry()}
            entry("mimetype","application/epub+zip")
            entry("META-INF/container.xml","""<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""")
            entry("OPS/book.opf","""<package xmlns="http://www.idpf.org/2007/opf"><metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">External input</dc:title></metadata><manifest><item id="a" href="a.xhtml"/><item id="b" href="b.xhtml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""")
            for(chapter in listOf("a","b")) entry("OPS/$chapter.xhtml","<html><body><h1>$chapter</h1>"+
                (1..200).joinToString("") {"<p>Paragraph $it. Keyboard and touchpad navigate without pointer down. 阅读器连续翻页回归测试。</p>"}+"</body></html>")
        }
        return file
    }

    @Test fun pdfCardAndCurlContinueAfterEveryTurnWithoutClicks() {
        for(mode in listOf(LandscapePageMode.SINGLE,LandscapePageMode.DOUBLE)) {
            val file=pdf()
            val book=AndroidPdfDocument.open(context,Uri.fromFile(file),"input PDF")
            val page=AtomicInteger(-1)
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {activity->activity.setContent { TwinLeafTheme {
                    PdfReaderScreen(book,2,initialPage=3,onPositionChanged={_,_,p->page.set(p)},onClose={},layoutMode=mode)
                } } }
                fun ready(activity:MainActivity):Boolean = if(mode==LandscapePageMode.SINGLE)
                    find(activity.window.decorView,CardPageView::class.java)?.let {it.current!=null && it.next!=null}==true
                else find(activity.window.decorView,CurlBookView::class.java)?.let {it.current!=null && it.next!=null && !it.isTurning}==true
                waitFor(scenario,"PDF frames",::ready)
                repeat(3) {index->
                    val before=page.get()
                    if(Build.VERSION.SDK_INT>=34 && index>0) padSwipe(scenario,pixels=index==2)
                    else wheel(scenario,1,horizontal=index%2==1)
                    waitFor(scenario,"PDF wheel turn ${index+1}") {page.get()>before && ready(it)}
                    SystemClock.sleep(240)
                }
                val before=page.get();key(scenario,KeyEvent.KEYCODE_DPAD_LEFT)
                waitFor(scenario,"PDF keyboard previous") {page.get()<before}
            }
            book.close()
        }
    }

    @Test fun epubCardAndCurlContinueAfterEveryTurnWithoutClicks() {
        for(mode in listOf(LandscapePageMode.SINGLE,LandscapePageMode.DOUBLE)) {
            val book=AndroidEpubDocument.open(context,Uri.fromFile(epub()),"input EPUB")
            val page=AtomicInteger(-1)
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {activity->activity.setContent { TwinLeafTheme {
                    EpubReaderScreen(book,0,0,onPositionChanged={c,p,_,_->page.set(c*10000+p)},onClose={},layoutMode=mode)
                } } }
                fun ready(activity:MainActivity)=find(activity.window.decorView,EpubRenderView::class.java)?.curl?.let {
                    it.current!=null && it.next!=null && !it.isTurning}==true
                waitFor(scenario,"EPUB frames",::ready)
                repeat(3) {index->
                    val before=page.get()
                    if(Build.VERSION.SDK_INT>=34 && index>0) padSwipe(scenario,pixels=index==2)
                    else wheel(scenario,1,horizontal=index%2==1)
                    waitFor(scenario,"EPUB wheel turn ${index+1}") {page.get()>before && ready(it)}
                    SystemClock.sleep(300)
                }
                val before=page.get();key(scenario,KeyEvent.KEYCODE_DPAD_UP)
                waitFor(scenario,"EPUB keyboard previous") {page.get()<before}
            }
            book.close()
        }
    }

    @Test fun scrollingReadersHandleRepeatedWheelsAndArrowKeys() {
        val pdf=AndroidPdfDocument.open(context,Uri.fromFile(pdf()),"scroll input")
        val pdfPosition=AtomicReference(0f)
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {activity->activity.setContent {TwinLeafTheme {
                PdfScrollScreen(pdf,0,0f,{p,offset,_->pdfPosition.set(p+offset)},onClose={})
            }} }
            waitFor(scenario,"PDF scroll measured") {activity->
                activity.window.decorView.hasWindowFocus() && find(activity.window.decorView,com.localreader.twinleaf.ink.InkOverlayView::class.java)!=null
            }
            SystemClock.sleep(500)
            repeat(3) {
                val before=pdfPosition.get();wheel(scenario,1)
                waitFor(scenario,"PDF continuous wheel") {pdfPosition.get()>before}
            }
            val before=pdfPosition.get();key(scenario,KeyEvent.KEYCODE_DPAD_DOWN)
            waitFor(scenario,"PDF scroll down key") {pdfPosition.get()>before}
        }
        pdf.close()
        val epub=AndroidEpubDocument.open(context,Uri.fromFile(epub()),"scroll input")
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {activity->activity.setContent {TwinLeafTheme {
                EpubScrollScreen(epub,0,0f,{_,_,_,_->},onClose={})
            }} }
            waitFor(scenario,"EPUB scroll ready") {find(it.window.decorView,EpubScrollView::class.java)?.ready==true}
            repeat(3) {
                var before=0
                scenario.onActivity {before=find(it.window.decorView,EpubScrollView::class.java)!!.scrollY}
                wheel(scenario,1)
                waitFor(scenario,"EPUB continuous wheel") {find(it.window.decorView,EpubScrollView::class.java)!!.scrollY>before}
            }
            var before=0
            scenario.onActivity {before=find(it.window.decorView,EpubScrollView::class.java)!!.scrollY}
            key(scenario,KeyEvent.KEYCODE_DPAD_UP)
            waitFor(scenario,"EPUB scroll up key") {find(it.window.decorView,EpubScrollView::class.java)!!.scrollY<before}
        }
        epub.close()
    }

    @Test fun inactiveViewportAndTextEditorDoNotTurnPagesAndDisposalDetaches() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            var turns=0
            val mounted=AtomicInteger(0)
            scenario.onActivity {activity->activity.setContent {
                Box(Modifier.fillMaxSize().then(readerExternalInput(enabled=false,paginated=true,onTurn={turns++;true})))
                SideEffect { mounted.set(1) }
            }}
            waitFor(scenario,"disabled viewport composed") {mounted.get()==1}
            scenario.onActivity {activity->
                val event=KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)
                assertFalse(activity.readerInput.key(event))
                activity.setContent {
                    Box(Modifier.fillMaxSize().then(readerExternalInput(enabled=true,paginated=true,onTurn={turns++;true})))
                    SideEffect { mounted.set(2) }
                }
            }
            waitFor(scenario,"enabled viewport composed") {mounted.get()==2}
            scenario.onActivity {activity->
                val edit=EditText(activity)
                (activity.window.decorView as ViewGroup).addView(edit)
                edit.requestFocus()
                assertTrue(edit.onCheckIsTextEditor())
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
                (edit.parent as ViewGroup).removeView(edit)
            }
            assertEquals(0,turns)
            scenario.onActivity {it.setContent {
                Box(Modifier.fillMaxSize())
                SideEffect { mounted.set(3) }
            }}
            waitFor(scenario,"reading viewport disposed") {mounted.get()==3}
            scenario.onActivity {assertFalse(it.readerInput.key(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)))}
        }
    }
}
