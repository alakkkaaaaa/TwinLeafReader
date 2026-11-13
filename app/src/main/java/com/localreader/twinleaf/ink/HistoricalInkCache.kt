package com.localreader.twinleaf.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Disposable rendering only: source vectors stay available to erasing, saving and export.
 * All calls belong to the view thread. Tile misses render synchronously, subsequent frames
 * composite tiles plus a short vector tail. Bounds must include the brush's full footprint.
 */
class HistoricalInkCache(private val maxBytes:Int=24*1024*1024,private val recentCount:Int=24) {
    private data class Key(val x:Int,val y:Int)
    private val tiles=LinkedHashMap<Key,Bitmap>(16,.75f,true)
    private val cells=HashMap<Key,MutableList<Int>>()
    private val large=ArrayList<Int>()
    private val strokeBounds=ArrayList<RectF>()
    private var source:List<InkStroke> = emptyList()
    private var historical=0
    private var sx=0f;private var sy=0f
    private var style:Any?=null
    private var usedBytes=0
    private val bitmapPaint=Paint(Paint.FILTER_BITMAP_FLAG)
    var renderedTileCount=0L;private set
    var renderedStrokeCount=0L;private set
    fun clear() {
        // Hardware Canvas may still reference a submitted bitmap; let GC reclaim it safely.
        tiles.clear();usedBytes=0;cells.clear();large.clear();strokeBounds.clear();source=emptyList();historical=0
    }
    fun draw(canvas:Canvas,strokes:List<InkStroke>,visible:RectF,pixelsPerUnitX:Float,
             pixelsPerUnitY:Float=pixelsPerUnitX,styleKey:Any?=null,
             bounds:(InkStroke)->RectF,render:(Canvas,InkStroke)->Unit) {
        if(!pixelsPerUnitX.isFinite() || !pixelsPerUnitY.isFinite() || pixelsPerUnitX<=0 || pixelsPerUnitY<=0 || visible.isEmpty) return
        // Fit the visible working set in the LRU, with space for adjacent pan tiles.
        // Half-octave buckets keep pinch movement from rebuilding on every event.
        val capacity=(maxBytes/(256*256*4)).coerceAtLeast(1)
        val target=(capacity*.75f).toInt().coerceAtLeast(1)
        val screenArea=visible.width().toDouble()*visible.height()*pixelsPerUnitX*pixelsPerUnitY
        val factor=minOf(1.0,sqrt(target*256.0*256.0/screenArea))
        fun bucket(value:Double)=2.0.pow(floor(ln(value)/ln(2.0)*2.0)/2.0).toFloat()
        var nextX=bucket(pixelsPerUnitX*factor)
        var nextY=nextX*(pixelsPerUnitY/pixelsPerUnitX)
        fun visibleTiles():Long {
            val columns=floor(visible.right*nextX/256.0).toLong()-floor(visible.left*nextX/256.0).toLong()+1
            val rows=floor(visible.bottom*nextY/256.0).toLong()-floor(visible.top*nextY/256.0).toLong()+1
            return columns*rows
        }
        if(!nextX.isFinite() || !nextY.isFinite() || nextX<=0f || nextY<=0f) return
        while(visibleTiles()>capacity && nextX>1e-30f) { nextX/=sqrt(2f);nextY/=sqrt(2f) }
        if(sx!=nextX || sy!=nextY || style!=styleKey) { clear();sx=nextX;sy=nextY;style=styleKey }
        if(source!==strokes) {
            val append=strokes.size>=source.size && source.indices.all { source[it]===strokes[it] }
            if(!append) clear()
            for(i in strokeBounds.size until strokes.size) strokeBounds.add(RectF(bounds(strokes[i])))
            val nextHistory=(strokes.size-recentCount).coerceAtLeast(0)
            for(i in historical until nextHistory) index(i)
            historical=nextHistory;source=strokes
        }
        val minX=cellX(visible.left);val maxX=cellX(visible.right)
        val minY=cellY(visible.top);val maxY=cellY(visible.bottom)
        // A malformed or enormous viewport must not allocate an unbounded tile grid.
        if((maxX.toLong()-minX+1)*(maxY.toLong()-minY+1)>256) {
            for(i in strokes.indices) if(RectF.intersects(strokeBounds[i],visible)) render(canvas,strokes[i])
            return
        }
        for(y in minY..maxY) for(x in minX..maxX) {
            val key=Key(x,y)
            val candidates=cells[key].orEmpty()
            val region=RectF(x*256f/sx,y*256f/sy,(x+1)*256f/sx,(y+1)*256f/sy)
            if(candidates.isEmpty() && large.none { RectF.intersects(strokeBounds[it],region) }) continue
            val bitmap=tiles[key]?:run {
                val created=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
                val tileCanvas=Canvas(created)
                tileCanvas.scale(sx,sy);tileCanvas.translate(-region.left,-region.top)
                val ordered=(candidates+large.filter { RectF.intersects(strokeBounds[it],region) }).sorted()
                for(i in ordered) { render(tileCanvas,strokes[i]);renderedStrokeCount++ }
                renderedTileCount++
                while(tiles.isNotEmpty() && usedBytes+created.allocationByteCount>maxBytes) {
                    val oldest=tiles.entries.iterator();usedBytes-=oldest.next().value.allocationByteCount;oldest.remove()
                }
                if(created.allocationByteCount<=maxBytes) { tiles[key]=created;usedBytes+=created.allocationByteCount }
                created
            }
            canvas.drawBitmap(bitmap,null,region,bitmapPaint)
        }
        for(i in historical until strokes.size) if(RectF.intersects(strokeBounds[i],visible)) render(canvas,strokes[i])
    }
    private fun cellX(x:Float)=floor(x*sx/256f).toInt()
    private fun cellY(y:Float)=floor(y*sy/256f).toInt()
    private fun index(i:Int) {
        val b=strokeBounds[i]
        val x0=cellX(b.left);val x1=cellX(b.right);val y0=cellY(b.top);val y1=cellY(b.bottom)
        if((x1.toLong()-x0+1)*(y1.toLong()-y0+1)>64) {
            large.add(i)
            val iterator=tiles.entries.iterator()
            while(iterator.hasNext()) {
                val entry=iterator.next();val key=entry.key
                if(RectF.intersects(b,RectF(key.x*256f/sx,key.y*256f/sy,(key.x+1)*256f/sx,(key.y+1)*256f/sy))) { usedBytes-=entry.value.allocationByteCount;iterator.remove() }
            }
        } else for(y in y0..y1) for(x in x0..x1) {
            val key=Key(x,y);cells.getOrPut(key) { ArrayList() }.add(i)
            tiles.remove(key)?.let { usedBytes-=it.allocationByteCount }
        }
    }
}
