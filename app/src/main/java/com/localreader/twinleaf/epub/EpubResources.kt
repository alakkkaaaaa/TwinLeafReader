package com.localreader.twinleaf.epub

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import com.localreader.twinleaf.data.EpubDocument
import java.io.ByteArrayInputStream
import java.io.File

/** Shared origin and resource policy for scrolling and paginated EPUB readers. */
internal object EpubResources {
    fun url(document: EpubDocument, file: File): String {
        val base = "https://reader.local/" +
            Uri.encode(file.canonicalFile.relativeTo(document.root.canonicalFile).invariantSeparatorsPath,"/")
        return if(file.extension.equals("svg",true)) "$base?readerSpine=1" else base
    }

    fun response(document: EpubDocument?, uri: Uri): WebResourceResponse {
        fun denied()=WebResourceResponse("text/plain","UTF-8",403,"Forbidden",emptyMap(),ByteArrayInputStream(byteArrayOf()))
        if(document==null || uri.scheme!="https" || uri.host!="reader.local") return denied()
        return try {
            val file=EpubParser.safeFile(document.root,uri.path.orEmpty().removePrefix("/"))
            if(!file.isFile) return denied()
            val extension=file.extension.lowercase()
            if(extension=="svg" && uri.getQueryParameter("readerSpine")=="1") {
                val (width,height)=svgViewport(file)
                val source="https://reader.local${uri.encodedPath}?readerAsset=1"
                val html="""<!doctype html><html><head><meta name="viewport" content="width=$width,height=$height"></head><body><img src="$source" alt=""></body></html>"""
                return WebResourceResponse("text/html","UTF-8",200,"OK",mapOf("Content-Security-Policy" to
                    "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'none'; frame-src 'none'"),ByteArrayInputStream(html.toByteArray()))
            }
            val html=extension in setOf("html","xhtml","htm","xml")
            val type=if(html) "text/html" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: if(extension=="css") "text/css" else "application/octet-stream"
            val stream=if(html) ByteArrayInputStream(readHtml(file).toByteArray()) else file.inputStream()
            WebResourceResponse(type,"UTF-8",200,"OK",mapOf("Content-Security-Policy" to
                "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'none'; frame-src 'none'"),stream)
        } catch(_:Exception) { denied() }
    }

    private fun readHtml(file: File): String {
        val bytes=file.readBytes()
        val declaration=String(bytes.take(512).toByteArray(),Charsets.ISO_8859_1)
        val encoding=Regex("(?i)(?:encoding|charset)\\s*=\\s*[\"']?([a-z0-9_-]+)").find(declaration)?.groupValues?.get(1)
        val charset=runCatching { charset(encoding ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        return String(bytes,charset).replace(Regex("(?is)<\\?xml.*?\\?>"),"")
    }

    private fun svgViewport(file:File):Pair<Float,Float> {
        val head=file.inputStream().buffered().use { input->
            val bytes=ByteArray(32*1024);val count=input.read(bytes)
            if(count<=0) "" else String(bytes,0,count,Charsets.UTF_8)
        }
        val viewBox=Regex("(?i)viewBox\\s*=\\s*['\"]\\s*[-+.0-9e]+[ ,]+[-+.0-9e]+[ ,]+([-+.0-9e]+)[ ,]+([-+.0-9e]+)").find(head)
        fun clean(value:String?)=value?.let { Regex("[-+.0-9eE]+").find(it)?.value?.toFloatOrNull() }
        val width=clean(viewBox?.groupValues?.getOrNull(1))
            ?: clean(Regex("(?i)\\bwidth\\s*=\\s*['\"]([^'\"]+)").find(head)?.groupValues?.getOrNull(1)) ?: 1000f
        val height=clean(viewBox?.groupValues?.getOrNull(2))
            ?: clean(Regex("(?i)\\bheight\\s*=\\s*['\"]([^'\"]+)").find(head)?.groupValues?.getOrNull(1)) ?: 1000f
        return width.coerceAtLeast(1f) to height.coerceAtLeast(1f)
    }
}
