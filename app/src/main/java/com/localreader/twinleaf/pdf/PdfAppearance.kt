package com.localreader.twinleaf.pdf

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.runtime.*

enum class PdfDarkStyle { GENTLE, INVERT }
data class PdfAppearance(val original:Boolean=false,val style:PdfDarkStyle=PdfDarkStyle.GENTLE,val strength:Float=1f) {
    fun filter(night:Boolean):ColorMatrixColorFilter? {
        if(!night || original) return null
        return ColorMatrixColorFilter(ColorMatrix(pdfDarkMatrix(style,strength)))
    }
}

/** Blend a real luminance transform with the original, never an opaque black overlay. */
fun pdfDarkMatrix(style:PdfDarkStyle,strength:Float):FloatArray {
    val target=if(style==PdfDarkStyle.INVERT) floatArrayOf(-1f,0f,0f,0f,255f,0f,-1f,0f,0f,255f,0f,0f,-1f,0f,255f,0f,0f,0f,1f,0f)
    else floatArrayOf(-.166f,-.558f,-.056f,0f,224f,-.166f,-.558f,-.056f,0f,229f,-.166f,-.558f,-.056f,0f,239f,0f,0f,0f,1f,0f)
    val t=strength.coerceIn(0f,1f)
    return FloatArray(20) { i -> val identity=if(i==0 || i==6 || i==12 || i==18) 1f else 0f;identity+(target[i]-identity)*t }
}

class PdfAppearanceState(context:Context,uri:String) {
    private val prefs=context.getSharedPreferences("pdf_appearance",Context.MODE_PRIVATE)
    private val key=uri
    var value by mutableStateOf(PdfAppearance(prefs.getBoolean("$key.original",false),
        runCatching { PdfDarkStyle.valueOf(prefs.getString("$key.style","GENTLE")!!) }.getOrDefault(PdfDarkStyle.GENTLE),prefs.getFloat("$key.strength",1f)))
        private set
    var temporaryOriginal by mutableStateOf(false)
    val effective get()=if(temporaryOriginal) value.copy(original=true) else value
    fun update(value:PdfAppearance) { this.value=value;prefs.edit().putBoolean("$key.original",value.original)
        .putString("$key.style",value.style.name).putFloat("$key.strength",value.strength).apply() }
}
