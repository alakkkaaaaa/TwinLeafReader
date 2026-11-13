package com.localreader.twinleaf.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubLayoutScriptTest {
    @Test
    fun pagedScriptCarriesRequestedColumnCountAndProtectsImages() {
        val single = EpubRenderView.layoutScript(
            font = 1f,
            night = true,
            layout = "reflowable",
            columns = 1,
        )
        val double = EpubRenderView.layoutScript(
            font = 1f,
            night = false,
            layout = "reflowable",
            columns = 2,
        )

        assertTrue(single.contains("const requestedColumns=1"))
        assertTrue(double.contains("const requestedColumns=2"))
        assertTrue(double.contains("window.__readerColumns=effectiveColumns"))
        assertTrue(double.contains("image-orientation:from-image"))
        assertTrue(double.contains("filter:none!important"))
        assertTrue(double.contains("dominantWide"))
    }

    @Test
    fun scrollScriptNeverAppliesDarkFilterToArtwork() {
        val script = EpubScrollView.scrollScript(font = 1.1f, night = true)

        assertTrue(script.contains("image-orientation:from-image"))
        assertTrue(script.contains("filter:none!important"))
        assertTrue(script.contains("object-fit:contain!important"))
        assertFalse(script.contains("filter:invert"))
    }
}
