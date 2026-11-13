package com.localreader.twinleaf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.localreader.twinleaf.data.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class AndroidPdfDocument private constructor(
    override val uri: Uri,
    override val title: String,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : PdfDocument {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    override val pageCount: Int = renderer.pageCount

    override suspend fun pageAspectRatio(index: Int): Float = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(!closed.get()) { "PDF 已关闭" }
            renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
        }
    }

    override suspend fun renderPage(index: Int, targetWidth: Int, targetHeight: Int): Bitmap =
        withContext(Dispatchers.IO) {
            require(index in 0 until pageCount)
            val width = targetWidth.coerceAtLeast(1)
            val height = targetHeight.coerceAtLeast(1)
            val key = "$index:$width:$height"
            cache.get(key)?.let { return@withContext it }

            synchronized(lock) {
                check(!closed.get()) { "PDF 已关闭" }
                cache.get(key) ?: renderer.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.rgb(250, 247, 239))
                    val scale = min(width.toFloat() / page.width, height.toFloat() / page.height)
                    val matrix = Matrix().apply {
                        postScale(scale, scale)
                        postTranslate(
                            (width - page.width * scale) / 2f,
                            (height - page.height * scale) / 2f,
                        )
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bitmap)
                    bitmap
                }
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            // Compose / the curl view may still own a displayed frame. Let the
            // bitmap be collected after its last consumer releases it.
            cache.evictAll()
            renderer.close()
            descriptor.close()
        }
    }

    companion object {
        fun open(context: Context, uri: Uri, title: String): AndroidPdfDocument {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法打开 PDF")
            return try {
                AndroidPdfDocument(uri, title, descriptor, PdfRenderer(descriptor))
            } catch (error: Throwable) {
                descriptor.close()
                throw error
            }
        }
    }
}
