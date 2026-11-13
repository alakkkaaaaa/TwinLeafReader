package com.localreader.twinleaf.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.localreader.twinleaf.ui.theme.LocalReaderDark

data class ReaderChromePalette(
    val background:Color,
    val gradientTop:Color,
    val gradientBottom:Color,
    val border:Color,
    val content:Color,
    val muted:Color,
    val disabled:Color,
    val accent:Color,
    val selected:Color,
    val selectedBorder:Color,
)

/** One material vocabulary for the reader header and the floating annotation rail. */
@Composable
fun readerChromePalette(dark:Boolean=LocalReaderDark.current):ReaderChromePalette = if(dark) {
    ReaderChromePalette(
        background=Color(0xFF25272C),gradientTop=Color(0xFF3A3D44),gradientBottom=Color(0xFF202227),
        border=Color(0xFF565A63),content=Color(0xFFE7E9EF),muted=Color(0xFFADB2BC),disabled=Color(0xFF747984),
        accent=Color(0xFFD3A62D),selected=Color(0xFF5A4928),selectedBorder=Color(0xFFA98532),
    )
} else {
    ReaderChromePalette(
        background=Color(0xFFF5F6F8),gradientTop=Color(0xFFFFFFFF),gradientBottom=Color(0xFFE8EBEF),
        border=Color(0xFFC9CED6),content=Color(0xFF273241),muted=Color(0xFF687382),disabled=Color(0xFFA8AFB9),
        accent=Color(0xFFB78613),selected=Color(0xFFF5E9CC),selectedBorder=Color(0xFFD6B15A),
    )
}
