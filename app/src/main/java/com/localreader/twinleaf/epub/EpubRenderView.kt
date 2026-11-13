package com.localreader.twinleaf.epub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import com.localreader.twinleaf.data.EpubDocument
import com.localreader.twinleaf.ui.components.CurlBookView
import com.localreader.twinleaf.ui.components.PageTurnTarget
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.coroutines.resume

data class EpubLocation(val chapter: Int, val spread: Int, val fraction: Float? = null, val page: Int? = null,val anchor:String?=null)
private data class EpubFrame(
    val location: EpubLocation,
    val count: Int,
    val pageCount: Int,
    val columns: Int,
    val bitmap: Bitmap,
    val outlineId: String? = null,
)

/** A local-only WebView paginates and snapshots; the foreground view only renders paper textures. */
@SuppressLint("SetJavaScriptEnabled")
class EpubRenderView(context: Context) : FrameLayout(context), PageTurnTarget {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val web = WebView(context)
    val curl = CurlBookView(context)
    private val card = EpubCardTurnView(context)
    var onPosition: (EpubLocation, Int) -> Unit = { _, _ -> }
    var onPageCount: (Int) -> Unit = {}
    var onColumnsChanged: (Int) -> Unit = {}
    var onSpine: () -> Unit = {}
    var onOutlinePosition:(String?)->Unit = {}
    private var tocPositions:List<Pair<String,Float>> = emptyList()
    var onError: (String?) -> Unit = {}
    private var book: EpubDocument? = null
    private var position = EpubLocation(0, 0)
    private var font = 1f
    private var night = false
    private var job: Job? = null
    private var loadedKey: String? = null
    private var pageLoaded: CompletableDeferred<Unit>? = null
    private var loadingUrl: String? = null
    private var previousFrame: EpubFrame? = null
    private var nextFrame: EpubFrame? = null
    private val frames = LinkedHashMap<EpubLocation, EpubFrame>()
    private val chapterCounts = mutableMapOf<Int,Int>()
    private var turnDirection=1
    private var requestedColumns = 2
    private var cardRequested = false

    init {
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = false
            loadWithOverviewMode = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                forceDark = WebSettings.FORCE_DARK_OFF
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = false
            }
        }
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                return EpubResources.response(book, request.url)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (url == loadingUrl) pageLoaded?.complete(Unit)
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) pageLoaded?.completeExceptionally(IllegalStateException("无法读取本地章节（${response.statusCode}）"))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) pageLoaded?.completeExceptionally(IllegalStateException(error.description.toString()))
            }
        }
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(curl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.visibility = View.GONE
        val turn: (Int) -> Unit = { dir ->
            (if (dir > 0) nextFrame else previousFrame)?.let {
                position = it.location
                turnDirection=dir
                refresh()
            }
        }
        curl.onTurn = turn
        card.onTurn = turn
        curl.onSpine = { onSpine() }
        card.onCenter = { onSpine() }
    }

    override fun turnPage(direction: Int): Boolean =
        if (card.visibility == View.VISIBLE) card.turnPage(direction) else curl.turnPage(direction)

    fun configure(
        document: EpubDocument,
        desired: EpubLocation,
        fontScale: Float,
        nightMode: Boolean,
        columns: Int = 2,
        cardMode: Boolean = false,
    ) {
        val normalizedColumns = columns.coerceIn(1, 2)
        val changed = book !== document || font != fontScale || night != nightMode ||
            requestedColumns != normalizedColumns || cardRequested != cardMode
        val moved = position != desired
        book = document; position = desired; font = fontScale; night = nightMode
        requestedColumns = normalizedColumns; cardRequested = cardMode
        curl.night = night; card.night = night
        if (changed) { frames.clear(); chapterCounts.clear(); loadedKey = null }
        if (changed || moved) refresh()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) { frames.clear(); chapterCounts.clear(); loadedKey = null; refresh() }
    }

    private fun neighbor(frame:EpubFrame,dir:Int):EpubLocation? {
        val p=frame.location
        return when {
            p.spread+dir in 0 until frame.count -> EpubLocation(p.chapter,p.spread+dir)
            p.chapter+dir !in (book?.spine?.indices ?: IntRange.EMPTY) -> null
            dir>0 -> EpubLocation(p.chapter+1,0)
            else -> EpubLocation(p.chapter-1,chapterCounts[p.chapter-1]?.minus(1) ?: Int.MAX_VALUE)
        }
    }

    private fun publishNeighbors(current:EpubFrame) {
        previousFrame=neighbor(current,-1)?.let { frames[it] }
        nextFrame=neighbor(current,1)?.let { frames[it] }
        curl.previous=previousFrame?.bitmap;curl.next=nextFrame?.bitmap
        card.previous=previousFrame?.bitmap;card.next=nextFrame?.bitmap
        val useCard = cardRequested || current.columns == 1
        curl.visibility = if (useCard) View.GONE else View.VISIBLE
        card.visibility = if (useCard) View.VISIBLE else View.GONE
        onColumnsChanged(current.columns)
    }

    private fun refresh() {
        val document = book ?: return
        if (width < 2 || height < 1) return
        job?.cancel()
        // Cached destinations stay interactive while the next look-ahead page is rendered.
        frames[position]?.let { publishNeighbors(it) } ?: run {
            previousFrame=null;nextFrame=null;curl.previous=null;curl.next=null;card.previous=null;card.next=null
        }
        job = scope.launch {
            try {
                onError(null)
                val requested = position.copy(chapter = position.chapter.coerceIn(document.spine.indices))
                val current = frame(requested)
                position = current.location
                curl.current = current.bitmap; curl.invalidate()
                card.current = current.bitmap; card.invalidate()
                publishNeighbors(current)
                onPageCount(current.pageCount)
                onOutlinePosition(current.outlineId)
                onPosition(current.location, current.count)
                val near=neighbor(current,turnDirection)
                val reverse=neighbor(current,-turnDirection)
                frames.keys.retainAll(setOfNotNull(current.location,near,reverse,near?.let { frames[it] }?.let { neighbor(it,turnDirection) }))
                val ahead=near?.let { frame(it) }
                publishNeighbors(current)
                val far=ahead?.let { neighbor(it,turnDirection) }?.let { frame(it) }
                reverse?.let { frame(it) }
                publishNeighbors(current)
                frames.keys.retainAll(setOfNotNull(current.location,ahead?.location,far?.location,previousFrame?.location,nextFrame?.location))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: "EPUB 页面渲染失败") }
        }
    }

    private suspend fun frame(requested: EpubLocation): EpubFrame {
        frames[requested]?.let { return it }
        val document = requireNotNull(book)
        val file = document.spine[requested.chapter].file
        val key = "${file.absolutePath}:$width:$height:$font:$night:$requestedColumns"
        if (loadedKey != key) {
            loadedKey = null
            pageLoaded = CompletableDeferred()
            loadingUrl = EpubResources.url(document,file)
            web.loadUrl(loadingUrl!!)
            withTimeout(15000) { pageLoaded!!.await() }
            val item = document.spine[requested.chapter]
            js(layoutScript(
                font, night,
                layout = item.layout ?: document.layout,
                orientation = item.orientation ?: document.orientation,
                spread = item.spread ?: document.spread,
                pageProgressionDirection = item.pageProgressionDirection ?: document.pageProgressionDirection,
                columns = requestedColumns,
            ))
            withTimeout(15000) {
                while (js("window.__readerReady === true") != "true") delay(32)
            }
            loadedKey = key
            val items=document.outline.filter { it.chapter==requested.chapter }
            val ids=org.json.JSONArray(items.map { it.anchor.orEmpty() }).toString()
            val positions=org.json.JSONArray(js("""$ids.map(id=>{const e=id?document.getElementById(id)||Array.from(document.getElementsByName(id))[0]:document.body;if(!e)return -1;const x=e.getBoundingClientRect().left/window.innerWidth;return Math.max(0,window.__readerRtl?-x:x);})"""))
            tocPositions=items.mapIndexed { i,item->item.id to positions.optDouble(i,-1.0).toFloat() }.filter { it.second>=0f }
        }
        val count = js("window.__readerCount || 1").toIntOrNull()?.coerceAtLeast(1) ?: 1
        chapterCounts[requested.chapter]=count
        val pageCount = js("window.__readerPageCount || 1").toIntOrNull()?.coerceAtLeast(1) ?: 1
        val columns = js("window.__readerColumns || 1").toIntOrNull()?.coerceIn(1, 2) ?: 1
        val anchorSpread=requested.anchor?.let { anchor ->
            js("""(()=>{const flow=document.body;if(!window.__readerFixed)flow.style.transform='none';const id=${JSONObject.quote(anchor)};const e=id?document.getElementById(id)||Array.from(document.getElementsByName(id))[0]:flow;if(!e)return 0;const x=e.getBoundingClientRect().left/window.innerWidth;return Math.max(0,Math.floor(window.__readerRtl?-x:x));})()""").toIntOrNull() ?: 0
        }
        val mapped = anchorSpread ?: requested.page?.let { (it.coerceIn(1,pageCount)-1)/columns }
            ?: requested.fraction?.let { kotlin.math.round(it.coerceIn(0f,1f) * (count - 1)).toInt() } ?: requested.spread
        val location = EpubLocation(requested.chapter, mapped.coerceIn(0, count - 1))
        js("if(!window.__readerFixed) document.body.style.transform = 'translateX(' + ((window.__readerRtl?1:-1) * ${location.spread} * window.innerWidth) + 'px)'; true")
        awaitVisualState()
        currentCoroutineContext().ensureActive()
        val bitmap = Bitmap.createBitmap(width / 2 * 2, height, Bitmap.Config.RGB_565)
        web.draw(Canvas(bitmap))
        bitmap.prepareToDraw()
        val currentId=tocPositions.lastOrNull { it.second<=location.spread+.999f }?.first
        return EpubFrame(location, count, pageCount, columns, bitmap,currentId).also { frames[location] = it }
    }

    private suspend fun js(script: String): String = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it ?: "null") }
    }

    private suspend fun awaitVisualState() = withTimeout(5000) {
        suspendCancellableCoroutine<Unit> { continuation ->
            web.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    postOnAnimation { if (continuation.isActive) continuation.resume(Unit) }
                }
            })
        }
    }

    private fun resource(uri: Uri): WebResourceResponse {
        fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        val document = book ?: return denied()
        if (uri.scheme != "https" || uri.host != "reader.local") return denied()
        return try {
            val file = EpubParser.safeFile(document.root, uri.path.orEmpty().removePrefix("/"))
            if (!file.isFile) return denied()
            val extension = file.extension.lowercase()
            val html = extension in setOf("html", "xhtml", "htm", "xml")
            val type = if (html) "text/html" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: if (extension == "css") "text/css" else "application/octet-stream"
            val stream = if (html) ByteArrayInputStream(readHtml(file).toByteArray()) else file.inputStream()
            WebResourceResponse(type, "UTF-8", 200, "OK", mapOf(
                "Content-Security-Policy" to "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'none'; frame-src 'none'",
            ), stream)
        } catch (_: Exception) { denied() }
    }

    private fun readHtml(file: File): String {
        val bytes = file.readBytes()
        val declaration = String(bytes.take(512).toByteArray(), Charsets.ISO_8859_1)
        val encoding = Regex("(?i)(?:encoding|charset)\\s*=\\s*[\"']?([a-z0-9_-]+)").find(declaration)?.groupValues?.get(1)
        val charset = runCatching { charset(encoding ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        return String(bytes, charset).replace(Regex("(?is)<\\?xml.*?\\?>"), "")
    }

    fun release() { scope.cancel(); web.stopLoading(); removeAllViews(); web.destroy() }

    companion object {
        internal fun layoutScript(
            font: Float,
            night: Boolean,
            layout: String? = null,
            orientation: String? = null,
            spread: String? = null,
            pageProgressionDirection: String? = null,
            columns: Int = 2,
        ): String {
            val background = if (night) "#24231f" else "#faf7ef"
            val foreground = if (night) "#d8d0c2" else "#28231e"
            val fixedLayout = layout.equals("pre-paginated", true)
            val forceLandscape = orientation.equals("landscape", true)
            val rtl = pageProgressionDirection.equals("rtl", true)
            val spreadNone = spread.equals("none", true)
            val requestedColumns = columns.coerceIn(1, 2)
            return """
                (() => {
                  window.__readerReady = false;
                  const fixedLayout=$fixedLayout, forceLandscape=$forceLandscape, rtl=$rtl, spreadNone=$spreadNone;
                  const requestedColumns=$requestedColumns;
                  window.__readerFixed=fixedLayout;
                  window.__readerRtl=rtl;
                  document.documentElement.dataset.readerLayout=fixedLayout?'pre-paginated':'reflowable';
                  document.documentElement.dataset.readerOrientation=forceLandscape?'landscape':'auto';
                  document.documentElement.dataset.readerSpread=spreadNone?'none':'auto';
                  document.documentElement.dataset.readerProgression=rtl?'rtl':'ltr';
                  const preservedSvgColors=Array.from(document.querySelectorAll('svg')).map(e=>[e,getComputedStyle(e).color]);
                  if (!fixedLayout) {
                    let viewport = document.querySelector('meta[name=viewport]');
                    if (!viewport) { viewport = document.createElement('meta'); viewport.name='viewport'; document.head.appendChild(viewport); }
                    viewport.content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no';
                  }
                  const flow=document.body||document.documentElement;
                  flow.setAttribute('data-reader-flow','true');
                  const assets = Array.from(document.images).map(i => {
                    i.loading='eager';
                    return new Promise(resolve => {
                      let done=false;
                      const finish=()=>{
                        if(done)return; done=true;
                        if(typeof i.decode==='function') i.decode().catch(()=>{}).then(resolve); else resolve();
                      };
                      if(i.complete) finish();
                      else { i.addEventListener('load',finish,{once:true}); i.addEventListener('error',finish,{once:true}); setTimeout(finish,4000); }
                    });
                  });
                  assets.push(document.fonts.ready);
                  Promise.all(assets).then(() => {
                    const w=window.innerWidth,h=window.innerHeight,pad=Math.max(10,Math.round(w*0.012));
                    const imageArea=Array.from(document.images).reduce((sum,img)=>sum+Math.max(0,img.naturalWidth)*Math.max(0,img.naturalHeight),0);
                    const textLength=(document.body&&document.body.innerText||'').replace(/\s+/g,'').length;
                    const dominantWide=Array.from(document.images).some(img=>img.naturalWidth>img.naturalHeight*1.24 && img.naturalWidth*img.naturalHeight>imageArea*.38) && textLength<1200;
                    const effectiveColumns=(fixedLayout||forceLandscape||spreadNone||dominantWide)?1:requestedColumns;
                    const cw=w/effectiveColumns-pad*2,ch=h-pad*2;
                    const s=document.createElement('style');
                    if (fixedLayout) {
                      s.textContent='html{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background-color:$background!important;color-scheme:only light!important;}'+
                        'body{margin:0!important;padding:0!important;overflow:hidden!important;transform-origin:top left!important;background-color:$background!important;}';
                      document.documentElement.style.setProperty('--reader-fixed-layout','true');
                    } else {
                      s.textContent=
                        'html{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background-color:$background!important;color-scheme:only light!important;}'+
                        'body{margin:0!important;padding:0!important;position:absolute!important;left:'+pad+'px!important;top:'+pad+'px!important;width:'+cw+'px!important;height:'+ch+'px!important;max-width:none!important;overflow:visible!important;column-width:'+cw+'px!important;column-gap:'+(pad*2)+'px!important;column-fill:auto!important;direction:'+(rtl?'rtl':'ltr')+'!important;background-color:$background!important;color:$foreground!important;}'+
                        'body p,body div,body span,body li,body h1,body h2,body h3,body h4,body h5,body h6,body blockquote{font-size:'+(20*$font)+'px!important;line-height:1.65!important;color:$foreground!important;}'+
                        'body p{margin-top:0.25em!important;margin-bottom:0.55em!important;}'+
                        'body img,body picture img{max-width:100%!important;max-height:'+ch+'px!important;width:auto!important;height:auto!important;object-fit:contain!important;image-orientation:from-image!important;break-inside:avoid!important;filter:none!important;mix-blend-mode:normal!important;}'+
                        'body picture,body figure{max-width:100%!important;break-inside:avoid!important;}'+
                        'body svg{max-width:100%!important;max-height:'+ch+'px!important;width:auto;height:auto;break-inside:avoid!important;filter:none!important;mix-blend-mode:normal!important;}'+
                        'pre,table{white-space:pre-wrap;word-break:break-word;}';
                    }
                    document.head.appendChild(s);
                    preservedSvgColors.forEach(([e,color])=>e.style.setProperty('color',color,'important'));
                    requestAnimationFrame(() => requestAnimationFrame(() => {
                      if(fixedLayout){
                        const meta=document.querySelector('meta[name=viewport]');
                        const content=meta?meta.getAttribute('content')||'':'';
                        const read=n=>{const m=content.match(new RegExp('(?:^|[,;\\s])'+n+'\\s*=\\s*([0-9.]+)','i'));return m?parseFloat(m[1]):0;};
                        const svg=document.querySelector('svg');
                        const vb=svg&&svg.viewBox&&svg.viewBox.baseVal;
                        const sourceW=read('width')||(vb&&vb.width)||flow.scrollWidth||w;
                        const sourceH=read('height')||(vb&&vb.height)||flow.scrollHeight||h;
                        const scale=Math.min(w/Math.max(1,sourceW),h/Math.max(1,sourceH));
                        flow.style.width=sourceW+'px';flow.style.height=sourceH+'px';
                        flow.style.transform='translate('+(w-sourceW*scale)/2+'px,'+(h-sourceH*scale)/2+'px) scale('+scale+')';
                      }
                      const extent=fixedLayout?w:document.body.scrollWidth;
                      window.__readerColumns=effectiveColumns;
                      window.__readerPageCount=fixedLayout?1:Math.max(1,Math.ceil((extent+pad*2-1)/(w/effectiveColumns)));
                      window.__readerCount=fixedLayout?1:Math.ceil(window.__readerPageCount/effectiveColumns);
                      document.documentElement.dir=rtl?'rtl':'ltr';
                      if(!fixedLayout) document.body.style.transform='translateX(0px)';
                      window.__readerReady=true;
                    }));
                  });
                  return true;
                })();
            """.trimIndent()
        }
    }
}
