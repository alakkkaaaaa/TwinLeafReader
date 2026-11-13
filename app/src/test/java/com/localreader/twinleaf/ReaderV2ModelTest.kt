package com.localreader.twinleaf

import com.localreader.twinleaf.data.*
import com.localreader.twinleaf.ink.*
import com.localreader.twinleaf.pdf.*
import com.localreader.twinleaf.epub.EpubRenderView
import com.localreader.twinleaf.epub.EpubScrollView
import org.junit.Assert.*
import org.junit.Test

class ReaderV2ModelTest {
    private fun book(id:String,title:String,imported:Long=0,last:Long=0)=LibraryBook(id,id,title,"PDF",null,importedAt=imported,lastOpenedAt=last)
    @Test fun naturalSortDoesNotOverflowAndIgnoresLeadingZeroes() {
        assertTrue(naturalTitleCompare("第2卷","第10卷")<0)
        assertEquals(0,naturalTitleCompare("BOOK002","book2"))
        assertTrue(naturalTitleCompare("卷99999999999999999999999","卷100000000000000000000000")<0)
    }
    @Test fun manualOrderRetainsUnlistedNewBooksAtEnd() {
        val books=listOf(book("new","a",30),book("1","b",10),book("2","c",20))
        val folder=LibraryFolder("f","系列",manualOrder=listOf("2","stale","1"),descending=true)
        assertEquals(listOf("2","1","new"),sortedFolderBooks(books,folder).map { it.id })
        assertEquals(listOf("new","1","2"),sortedFolderBooks(books,folder.copy(sort=FolderSort.TITLE,descending=false)).map { it.id })
        assertEquals(listOf("2","1","new"),sortedFolderBooks(books,folder).map { it.id })
    }
    @Test fun timeSortsAreIndependentAndReversible() {
        val books=listOf(book("1","a",1,3),book("2","b",2,1),book("3","c",3,2))
        val f=LibraryFolder("f","系列",sort=FolderSort.IMPORTED,descending=true)
        assertEquals(listOf("3","2","1"),sortedFolderBooks(books,f).map { it.id })
        assertEquals(listOf("1","3","2"),sortedFolderBooks(books,f.copy(sort=FolderSort.RECENT)).map { it.id })
    }
    @Test fun normalizedCoordinatesRespectCropAndRotation() {
        val p=InkPoint(.2f,.3f)
        listOf(0 to (30f to 160f),90 to (40f to 60f),180 to (90f to 80f),270 to (80f to 180f)).forEach { (r,expected) ->
            val actual=pdfPoint(p,10f,20f,100f,200f,r)
            assertEquals(expected.first,actual.first,.001f);assertEquals(expected.second,actual.second,.001f)
        }
    }
    @Test fun eraserHitsSegmentsNotJustSamplePoints() {
        val stroke=InkStroke(listOf(InkPoint(.1f,.5f),InkPoint(.9f,.5f)),0,.003f)
        assertTrue(hitsStroke(stroke,InkPoint(.5f,.5f),.7f,.01f))
        assertFalse(hitsStroke(stroke,InkPoint(.5f,.8f),.7f,.01f))
    }
    @Test fun pressureIsBoundedAndHighlighterConstant() {
        val stroke=InkStroke(listOf(),0,.01f)
        assertEquals(.004f,pressureWidth(stroke,InkPoint(0f,0f,-1f)),.0001f)
        assertEquals(.01f,pressureWidth(stroke,InkPoint(0f,0f,4f)),.0001f)
        assertEquals(.01f,pressureWidth(stroke.copy(highlight=true),InkPoint(0f,0f,.1f)),.0001f)
    }
    @Test fun nightMatrixInvertsLuminanceAndSupportsIdentity() {
        val identity=pdfDarkMatrix(PdfDarkStyle.GENTLE,0f)
        assertEquals(1f,identity[0],0f);assertEquals(0f,identity[4],0f)
        val gentle=pdfDarkMatrix(PdfDarkStyle.GENTLE,1f)
        assertTrue(gentle[4]>200f)
        assertTrue((gentle[0]+gentle[1]+gentle[2])*255+gentle[4]<40)
        val invert=pdfDarkMatrix(PdfDarkStyle.INVERT,1f)
        assertEquals(-1f,invert[0],0f);assertEquals(255f,invert[4],0f)
    }

    @Test fun inkV2ModelPreservesStylusAxesAndBrushConfiguration() {
        val point=InkPoint(.2f,.3f,.55f,.4f,-.2f,1234L,.8f)
        val stroke=InkStroke(listOf(point),0xFF123456.toInt(),.005f,brush=InkBrush.PENCIL,
            opacity=.7f,curve=PressureCurve.SOFT)
        assertEquals(InkBrush.PENCIL,stroke.brush)
        assertEquals(PressureCurve.SOFT,stroke.curve)
        assertEquals(.7f,stroke.opacity,.0001f)
        assertEquals(point,stroke.points.single())
        assertEquals(.4f,point.tilt,.0001f)
        assertEquals(-.2f,point.orientation,.0001f)
        assertEquals(1234L,point.timeMillis)
        assertEquals(.8f,point.rawPressure,.0001f)
    }

    @Test fun epubNightScriptsKeepImagesOutOfTheColorConversionPath() {
        val paged=EpubRenderView.layoutScript(1f,true)
        val scrolling=EpubScrollView.scrollScript(1f,true)
        listOf(paged,scrolling).forEach { script ->
            assertFalse(script.contains("replaceChildren"))
            assertFalse(script.contains("filter:invert"))
            assertFalse(script.contains("filter: invert"))
            assertTrue(script.contains("filter:none"))
            assertFalse(script.contains("body svg *"))
            assertTrue(script.contains("preservedSvgColors"))
            assertTrue(script.contains(".decode()"))
            assertTrue(script.contains("sourceW"))
            assertTrue(script.contains("Math.min"))
        }
        assertTrue(paged.contains("window.__readerRtl=rtl"))
    }
}
