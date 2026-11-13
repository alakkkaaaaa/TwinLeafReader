package com.localreader.twinleaf.ink

/** Stores only the replaced span, rather than a full growing page for every pen lift. */
internal class BoundedInkHistory(private val maxOperations:Int=80,private val maxBytes:Long=8L*1024*1024) {
    private data class Edit(val page:Int,val start:Int,val before:List<InkStroke>,val after:List<InkStroke>,val bytes:Long)
    private val undo=ArrayDeque<Edit>()
    private val redo=ArrayDeque<Edit>()
    private var bytes=0L
    val canUndo get()=undo.isNotEmpty()
    val canRedo get()=redo.isNotEmpty()
    fun clear() { undo.clear();redo.clear();bytes=0 }
    fun record(page:Int,before:List<InkStroke>,after:List<InkStroke>) {
        for(edit in redo) bytes-=edit.bytes
        redo.clear()
        var start=0
        while(start<minOf(before.size,after.size) && before[start]===after[start]) start++
        var endBefore=before.size;var endAfter=after.size
        while(endBefore>start && endAfter>start && before[endBefore-1]===after[endAfter-1]) { endBefore--;endAfter-- }
        val removed=before.subList(start,endBefore).toList();val added=after.subList(start,endAfter).toList()
        fun weight(strokes:List<InkStroke>)=strokes.sumOf { 128L+64L*(it.points.size+(it.rawPoints?.size?:0)) }
        val edit=Edit(page,start,removed,added,weight(removed)+weight(added))
        if(edit.bytes>maxBytes) { clear();return }
        undo.addLast(edit);bytes+=edit.bytes
        while(undo.size>maxOperations || bytes>maxBytes) bytes-=undo.removeFirst().bytes
    }
    fun undo(source:(Int)->List<InkStroke>):Pair<Int,List<InkStroke>>? {
        val edit=undo.removeLastOrNull()?:return null
        redo.addLast(edit)
        return edit.page to replace(source(edit.page),edit.start,edit.after.size,edit.before)
    }
    fun redo(source:(Int)->List<InkStroke>):Pair<Int,List<InkStroke>>? {
        val edit=redo.removeLastOrNull()?:return null
        undo.addLast(edit)
        return edit.page to replace(source(edit.page),edit.start,edit.before.size,edit.after)
    }
    private fun replace(source:List<InkStroke>,start:Int,count:Int,replacement:List<InkStroke>)=
        buildList(source.size-count+replacement.size) { addAll(source.subList(0,start));addAll(replacement);addAll(source.subList(start+count,source.size)) }
}
