package com.localreader.twinleaf.epub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.MotionEvent
import com.localreader.twinleaf.input.ReaderNavigationKey
import com.localreader.twinleaf.input.ScrollPageGate
import android.webkit.*
import com.localreader.twinleaf.data.EpubDocument
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/** Portrait reader: native vertical scrolling, with edge swipes to adjacent chapters. */
@SuppressLint("SetJavaScriptEnabled")
class EpubScrollView(context: Context) : WebView(context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var document: EpubDocument?=null
    private var font=1f
    private var night=false
    private var job: Job?=null
    private var loaded: CompletableDeferred<Unit>?=null
    private var loadingUrl: String?=null
    var chapterIndex=0
        private set
    var fraction=0f
        private set
    var ready=false
        private set
    var onPosition: (Int,Float)->Unit={_,_->}
    var onPageMetrics: (Int,Int)->Unit={_,_->}
    var onOutlinePosition:(String?)->Unit={}
    private var tocPositions:List<Pair<String,Float>> = emptyList()
    private var pendingPage:Int?=null
    private var pendingAnchor:String?=null
    var onError: (String?)->Unit={}
    private var edgeY=Float.NaN
    private var lastY=0f
    private var downX=0f
    private var edgeDirection=0
    private val externalEdge=ScrollPageGate(48f*resources.displayMetrics.density)
    private var externalRemainder=0f

    init {
        contentDescription="EPUB 竖屏滚动阅读"
        settings.apply {
            javaScriptEnabled=true
            allowFileAccess=false; allowContentAccess=false; domStorageEnabled=false
            blockNetworkLoads=true; mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort=false; loadWithOverviewMode=false; textZoom=100
            cacheMode=WebSettings.LOAD_NO_CACHE
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                forceDark=WebSettings.FORCE_DARK_OFF
            }
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU) isAlgorithmicDarkeningAllowed=false
        }
        isHorizontalScrollBarEnabled=false
        isVerticalScrollBarEnabled=true
        webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=EpubResources.response(document,request.url)
            override fun onPageFinished(view:WebView,url:String?) { if(url==loadingUrl) loaded?.complete(Unit) }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                if(request.isForMainFrame) loaded?.completeExceptionally(IllegalStateException(error.description.toString()))
            }
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse) {
                if(request.isForMainFrame) loaded?.completeExceptionally(IllegalStateException("章节读取失败：${response.statusCode}"))
            }
        }
    }

    fun configure(book:EpubDocument,chapter:Int,offset:Float,fontScale:Float,nightMode:Boolean,page:Int?=null,anchor:String?=null) {
        val changed=document!==book || chapterIndex!=chapter || abs(fraction-offset)>.008f || font!=fontScale || night!=nightMode || pendingPage!=page || pendingAnchor!=anchor
        if(!changed) return
        document=book; chapterIndex=chapter.coerceIn(book.spine.indices)
        fraction=offset.coerceIn(0f,1f); font=fontScale; night=nightMode
        pendingPage=page
        pendingAnchor=anchor
        reloadChapter()
    }

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w!=oldw || h!=oldh) reloadChapter()
    }

    private fun reloadChapter() {
        val book=document ?: return
        if(width<=0 || height<=0) return
        job?.cancel(); ready=false;externalEdge.reset();externalRemainder=0f
        val restore=fraction
        val restorePage=pendingPage
        val restoreAnchor=pendingAnchor
        val index=chapterIndex
        job=scope.launch {
            try {
                onError(null)
                setBackgroundColor(if(night) Color.rgb(36,35,31) else Color.rgb(250,247,239))
                loaded=CompletableDeferred()
                loadingUrl=EpubResources.url(book,book.spine[index].file)
                loadUrl(loadingUrl!!)
                withTimeout(15000) { loaded!!.await() }
                val item=book.spine[index]
                js(scrollScript(
                    font, night,
                    item.layout ?: book.layout,
                    item.orientation ?: book.orientation,
                    item.spread ?: book.spread,
                    item.pageProgressionDirection ?: book.pageProgressionDirection,
                ))
                withTimeout(15000) { while(js("window.__scrollReady === true")!="true") delay(32) }
                suspendCancellableCoroutine<Unit> { continuation ->
                    postVisualStateCallback(System.nanoTime(),object:VisualStateCallback() {
                        override fun onComplete(requestId:Long) { postOnAnimation { if(continuation.isActive) continuation.resume(Unit) } }
                    })
                }
                val anchorRatio=restoreAnchor?.let { anchor->js("""(()=>{const id=${org.json.JSONObject.quote(anchor)};const e=id?document.getElementById(id)||Array.from(document.getElementsByName(id))[0]:null;return e?(e.getBoundingClientRect().top+window.scrollY)/Math.max(1,document.documentElement.scrollHeight-window.innerHeight):0;})()""").toFloatOrNull() ?: 0f }
                val items=book.outline.filter { it.chapter==index }
                val ids=org.json.JSONArray(items.map { it.anchor.orEmpty() }).toString()
                val positions=org.json.JSONArray(js("""$ids.map(id=>{const e=id?document.getElementById(id)||Array.from(document.getElementsByName(id))[0]:document.body;return e?(e.getBoundingClientRect().top+window.scrollY)/Math.max(1,document.documentElement.scrollHeight-window.innerHeight):-1;})"""))
                tocPositions=items.mapIndexed { i,item->item.id to positions.optDouble(i,-1.0).toFloat() }.filter { it.second>=0f }
                scrollTo(0,(maxScroll()*(anchorRatio ?: restorePage?.let { fractionForPage(it) } ?: restore).coerceIn(0f,1f)).roundToInt())
                pendingPage=null
                pendingAnchor=null
                ready=true
                report()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { onError(e.message ?: "章节显示失败") }
        }
    }

    private fun maxScroll()=(computeVerticalScrollRange()-height).coerceAtLeast(0)
    fun fractionForPage(page:Int):Float = if(maxScroll()==0) 0f else
        ((page-1).coerceAtLeast(0).toFloat()*height/maxScroll()).coerceIn(0f,1f)
    private fun report() {
        if(!ready) return
        fraction=if(maxScroll()==0) 0f else (scrollY.toFloat()/maxScroll()).coerceIn(0f,1f)
        onPosition(chapterIndex,fraction)
        onOutlinePosition(tocPositions.lastOrNull { it.second<=fraction+.001f }?.first)
        val pages=kotlin.math.ceil(computeVerticalScrollRange().toDouble()/height.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val page=if(maxScroll()>0 && scrollY>=maxScroll()) pages else (scrollY/height.coerceAtLeast(1)+1).coerceIn(1,pages)
        onPageMetrics(page,pages)
    }

    override fun onScrollChanged(l:Int,t:Int,oldl:Int,oldt:Int) {
        super.onScrollChanged(l,t,oldl,oldt)
        report()
    }

    private fun changeChapter(direction:Int):Boolean {
        val next=chapterIndex+direction
        if(!ready || next !in (document?.spine?.indices ?: IntRange.EMPTY)) return false
        chapterIndex=next; fraction=if(direction>0) 0f else 1f
        onPosition(chapterIndex,fraction)
        reloadChapter()
        return true
    }

    fun navigate(key:ReaderNavigationKey):Boolean {
        if(!ready) return true
        if(!canScrollVertically(key.direction)) changeChapter(key.direction)
        else {
            externalEdge.reset();externalRemainder=0f
            val distance=if(key==ReaderNavigationKey.UP || key==ReaderNavigationKey.DOWN)
                72f*resources.displayMetrics.density else height*.85f
            scrollBy(0,(distance*key.direction).roundToInt())
        }
        return true
    }

    fun scrollFromExternal(x:Float,y:Float,time:Long):Boolean {
        if(!ready) return true
        val delta=if(abs(x)>abs(y)) x else y
        if(!delta.isFinite() || delta==0f) return false
        val direction=if(delta>0f) 1 else -1
        if(canScrollVertically(direction)) {
            externalEdge.reset()
            externalRemainder+=delta
            val pixels=externalRemainder.toInt()
            externalRemainder-=pixels
            if(pixels!=0) scrollBy(0,pixels)
        } else {
            externalRemainder=0f
            externalEdge.scroll(x,y,time,::changeChapter)
        }
        return true
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=event.x; lastY=event.y; edgeY=Float.NaN; edgeDirection=0 }
            MotionEvent.ACTION_MOVE -> if(ready && event.pointerCount==1 && abs(event.x-downX)<width*.2f) {
                val dy=event.y-lastY
                val direction=when {
                    dy<0 && !canScrollVertically(1) -> 1
                    dy>0 && !canScrollVertically(-1) -> -1
                    else -> 0
                }
                if(direction==0 || direction!=edgeDirection) { edgeY=event.y; edgeDirection=direction }
                if(edgeY.isNaN()) edgeY=event.y
                if(direction!=0 && abs(event.y-edgeY)>48*resources.displayMetrics.density) {
                    val next=chapterIndex+direction
                    if(next in (document?.spine?.indices ?: IntRange.EMPTY)) {
                        val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel); cancel.recycle()
                        chapterIndex=next; fraction=if(direction>0) 0f else 1f
                        onPosition(chapterIndex,fraction)
                        reloadChapter(); edgeDirection=0; edgeY=Float.NaN
                        return true
                    }
                }
                lastY=event.y
            }
        }
        return super.onTouchEvent(event)
    }

    private suspend fun js(script:String):String=suspendCancellableCoroutine { c->
        evaluateJavascript(script) { if(c.isActive) c.resume(it ?: "null") }
    }
    fun release() { report(); scope.cancel(); stopLoading(); destroy() }

    companion object {
        internal fun scrollScript(
            font:Float,
            night:Boolean,
            layout:String?=null,
            orientation:String?=null,
            spread:String?=null,
            pageProgressionDirection:String?=null,
        ):String {
            val bg=if(night) "#24231f" else "#faf7ef"
            val fg=if(night) "#d8d0c2" else "#28231e"
            val fixed=layout.equals("pre-paginated",true)
            val landscape=orientation.equals("landscape",true)
            val noSpread=spread.equals("none",true)
            val rtl=pageProgressionDirection.equals("rtl",true)
            return """
                (()=>{
                  window.__scrollReady=false;
                  const fixed=$fixed,landscape=$landscape,noSpread=$noSpread,rtl=$rtl;
                  window.__readerFixed=fixed;
                  window.__readerRtl=rtl;
                  document.documentElement.dataset.readerLayout=fixed?'pre-paginated':'reflowable';
                  document.documentElement.dataset.readerOrientation=landscape?'landscape':'auto';
                  document.documentElement.dataset.readerSpread=noSpread?'none':'auto';
                  document.documentElement.dataset.readerProgression=rtl?'rtl':'ltr';
                  const preservedSvgColors=Array.from(document.querySelectorAll('svg')).map(e=>[e,getComputedStyle(e).color]);
                  if(!fixed){
                    let viewport=document.querySelector('meta[name=viewport]');
                    if(!viewport){viewport=document.createElement('meta');viewport.name='viewport';document.head.appendChild(viewport);}
                    viewport.content='width=device-width,initial-scale=1';
                  }
                  const style=document.createElement('style');
                  style.textContent=fixed?
                    'html{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background-color:$bg!important;color-scheme:only light!important;}body{margin:0!important;padding:0!important;overflow:hidden!important;transform-origin:top left!important;background-color:$bg!important;}img,picture,svg{filter:none!important;mix-blend-mode:normal!important;image-orientation:from-image!important;}':
                    'html{overflow-x:hidden!important;overflow-y:auto!important;background-color:$bg!important;color-scheme:only light!important;}'+
                    'body{margin:0!important;padding:24px 5%!important;width:auto!important;height:auto!important;min-height:90vh;max-width:none!important;overflow:visible!important;background-color:$bg!important;color:$fg!important;column-count:auto!important;column-width:auto!important;}'+
                    'body p,body div,body span,body li,body h1,body h2,body h3,body h4,body h5,body h6,body blockquote{font-size:${20*font}px!important;line-height:1.8!important;color:$fg!important;}'+
                    'body img,body picture img{display:block;max-width:100%!important;max-height:88vh!important;width:auto!important;height:auto!important;margin:.6em auto!important;object-fit:contain!important;image-orientation:from-image!important;break-inside:avoid!important;filter:none!important;mix-blend-mode:normal!important;}'+
                    'body picture,body figure{max-width:100%!important;break-inside:avoid!important;}'+
                    'body svg{display:block;max-width:100%!important;max-height:88vh!important;width:auto;height:auto;margin:.6em auto!important;break-inside:avoid!important;filter:none!important;mix-blend-mode:normal!important;}'+
                    'body p{margin-top:.3em!important;margin-bottom:.7em!important;}'+
                    'pre,table{white-space:pre-wrap;overflow-wrap:anywhere;}';
                  document.head.appendChild(style);
                  preservedSvgColors.forEach(([e,color])=>e.style.setProperty('color',color,'important'));
                  const assets=Array.from(document.images).map(i=>{
                    i.loading='eager';
                    return new Promise(resolve=>{
                      let done=false;
                      const finish=()=>{
                        if(done)return;done=true;
                        if(typeof i.decode==='function')i.decode().catch(()=>{}).then(resolve);else resolve();
                      };
                      if(i.complete)finish();
                      else{i.addEventListener('load',finish,{once:true});i.addEventListener('error',finish,{once:true});setTimeout(finish,4000);}
                    });
                  });
                  assets.push(document.fonts.ready);
                  Promise.all(assets).then(()=>requestAnimationFrame(()=>requestAnimationFrame(()=>{
                    if(fixed){
                      const flow=document.body||document.documentElement;
                      const meta=document.querySelector('meta[name=viewport]');
                      const content=meta?meta.getAttribute('content')||'':'';
                      const read=n=>{const m=content.match(new RegExp('(?:^|[,;\\s])'+n+'\\s*=\\s*([0-9.]+)','i'));return m?parseFloat(m[1]):0;};
                      const svg=document.querySelector('svg');const vb=svg&&svg.viewBox&&svg.viewBox.baseVal;
                      const w=window.innerWidth,h=window.innerHeight;
                      const sourceW=read('width')||(vb&&vb.width)||flow.scrollWidth||w;
                      const sourceH=read('height')||(vb&&vb.height)||flow.scrollHeight||h;
                      const scale=Math.min(w/Math.max(1,sourceW),h/Math.max(1,sourceH));
                      flow.style.width=sourceW+'px';flow.style.height=sourceH+'px';
                      flow.style.transform='translate('+(w-sourceW*scale)/2+'px,'+(h-sourceH*scale)/2+'px) scale('+scale+')';
                    }
                    window.__scrollReady=true;
                  })));
                  return true;
                })();
            """.trimIndent()
        }
    }
}
