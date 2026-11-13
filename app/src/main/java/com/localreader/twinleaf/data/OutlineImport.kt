package com.localreader.twinleaf.data

import org.json.JSONArray
import java.util.UUID

internal fun normalizeLevels(entries:List<OutlineEntry>):List<OutlineEntry> {
    var previous=0
    return entries.map { it.copy(level=it.level.coerceIn(1,(previous+1).coerceAtMost(12))).also { e->previous=e.level } }
}

internal fun visibleOutline(entries:List<OutlineEntry>,collapsed:Set<String>,query:String):List<OutlineEntry> {
    if(query.isNotBlank()) return entries.filter { it.title.contains(query.trim(),ignoreCase=true) }
    var hiddenBelow=Int.MAX_VALUE
    return entries.filter { e->
        if(e.level>hiddenBelow) false else { hiddenBelow=if(e.id in collapsed) e.level else Int.MAX_VALUE;true }
    }
}

internal fun mergeOutline(existing:List<OutlineEntry>,incoming:List<OutlineEntry>):List<OutlineEntry> {
    class Node(val entry:OutlineEntry,val children:MutableList<Node> = mutableListOf())
    fun tree(entries:List<OutlineEntry>):MutableList<Node> {
        val roots=mutableListOf<Node>();val stack=mutableListOf<Node>()
        normalizeLevels(entries).forEach { e->
            while(stack.size>=e.level) stack.removeAt(stack.lastIndex)
            val node=Node(e);if(stack.isEmpty()) roots.add(node) else stack.last().children.add(node)
            stack.add(node)
        };return roots
    }
    fun merge(a:MutableList<Node>,b:List<Node>) {
        b.forEach { item->
            val match=a.firstOrNull { it.entry.let { e-> e.title==item.entry.title && e.page==item.entry.page && e.chapter==item.entry.chapter } }
            if(match==null) a.add(item) else merge(match.children,item.children)
        }
    }
    val roots=tree(existing);merge(roots,tree(incoming))
    val result=mutableListOf<OutlineEntry>()
    fun flatten(nodes:List<Node>,level:Int) { nodes.forEach { result.add(it.entry.copy(level=level));flatten(it.children,level+1) } }
    flatten(roots,1);return result
}

/** Strict, atomic parsing: no entries are returned if any row is invalid. */
object OutlineImport {
    fun parse(source:String,pageCount:Int):List<OutlineEntry> {
        require(source.length<=1_000_000) { "目录文件超过 1 MB" }
        val text=source.removePrefix("\uFEFF").trim()
        val rows:List<List<String>> = if(text.startsWith("[")) {
            val array=try { JSONArray(text) } catch(e:Exception) { throw IllegalArgumentException("JSON 格式错误",e) }
            (0 until array.length()).map { i->
                val e=array.optJSONObject(i) ?: error("第 ${i+1} 项必须是对象")
                listOf(e.optString("level"),e.optString("page"),e.optString("title"))
            }
        } else text.lineSequence().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map { it.trimEnd('\r').split('\t') }.toList()
        require(rows.isNotEmpty()) { "目录为空" };require(rows.size<=5000) { "目录最多 5000 项" }
        var previous=0
        return rows.mapIndexed { i,row ->
            require(row.size==3) { "第 ${i+1} 项必须是三列，用 TAB 分隔" }
            val level=row[0].trim().toIntOrNull() ?: error("第 ${i+1} 项层级无效")
            require(level in 1..12 && level<=previous+1) { "第 ${i+1} 项层级跳跃（首项应为 1）" }
            val page=row[1].trim().toIntOrNull() ?: error("第 ${i+1} 项页码无效")
            require(page in 1..pageCount) { "第 ${i+1} 项页码必须在 1 – $pageCount" }
            val title=row[2].trim()
            require(title.isNotEmpty() && title.length<=100 && !title.contains('\n') && !title.contains('\t')) { "第 ${i+1} 项名称为空、过长或含换行" }
            previous=level;OutlineEntry(UUID.randomUUID().toString(),title,page,level=level)
        }
    }
}
