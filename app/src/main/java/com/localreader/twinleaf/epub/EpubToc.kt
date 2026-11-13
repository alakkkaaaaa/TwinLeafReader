package com.localreader.twinleaf.epub

import android.net.Uri
import com.localreader.twinleaf.data.EpubSpineItem
import com.localreader.twinleaf.data.OutlineEntry
import com.localreader.twinleaf.data.normalizeLevels
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal object EpubToc {
    private fun xml(file:File):Element {
        require(file.length()<=24*1024*1024)
        require(!file.readText().replace("\u0000","").contains(Regex("<!ENTITY",RegexOption.IGNORE_CASE))) { "目录不允许自定义 XML 实体" }
        val factory=DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware=true;isExpandEntityReferences=false
            // Android XML implementations do not all expose these optional SAX features.
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities",false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities",false) }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _,_->InputSource(StringReader("")) }
        }.parse(file).documentElement
    }
    private fun Element.children(name:String?=null):List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.filter { name==null || (it.localName ?: it.tagName)==name }
    private fun Element.all(name:String):List<Element> = getElementsByTagNameNS("*",name).let { nodes->
        (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
    fun read(root:File,spine:List<EpubSpineItem>,nav:File?,ncx:File?):List<OutlineEntry> {
        val result=mutableListOf<OutlineEntry>()
        fun add(base:File,href:String,label:String,level:Int) {
            if(label.isBlank() || result.size>=5000) return
            runCatching {
                val uri=java.net.URI(href.trim());require(!uri.isAbsolute && uri.rawAuthority==null)
                val path=Uri.decode(href.substringBefore('#').substringBefore('?'))
                val destination=if(path.isEmpty()) base else File(base.parentFile,path)
                val file=EpubParser.safeFile(root,root.toPath().relativize(destination.toPath()).toString())
                val chapter=spine.indexOfFirst { it.file.canonicalFile==file.canonicalFile };require(chapter>=0)
                val anchor=Uri.decode(href.substringAfter('#',""))
                result.add(OutlineEntry("toc:${result.size}:$chapter:$anchor",label.trim().take(100),1,chapter,level,anchor))
            }
        }
        nav?.let { file->runCatching {
            val document=xml(file)
            val toc=document.all("nav").firstOrNull { node->
                node.getAttributeNS("http://www.idpf.org/2007/ops","type").split(' ').contains("toc") || node.getAttribute("role")=="doc-toc"
            }
            fun walk(list:Element,level:Int) {
                if(level>12 || result.size>=5000) return
                list.children("li").forEach { li->
                    li.children("a").firstOrNull()?.let { add(file,it.getAttribute("href"),it.textContent,level) }
                    li.children("ol").forEach { walk(it,level+1) }
                }
            }
            toc?.children("ol")?.forEach { walk(it,1) }
        }.onFailure { android.util.Log.w("ReaderToc","NAV parse failed",it) } }
        if(result.isEmpty()) ncx?.let { file->runCatching {
            fun walk(parent:Element,level:Int) {
                if(level>12 || result.size>=5000) return
                parent.children("navPoint").forEach { point->
                    val label=point.children("navLabel").firstOrNull()?.textContent.orEmpty()
                    point.children("content").firstOrNull()?.let { add(file,it.getAttribute("src"),label,level) }
                    walk(point,level+1)
                }
            }
            xml(file).all("navMap").firstOrNull()?.let { walk(it,1) }
        }.onFailure { android.util.Log.w("ReaderToc","NCX parse failed",it) } }
        if(result.isEmpty()) spine.forEachIndexed { chapter,item ->
            val label=runCatching {
                val document=xml(item.file)
                listOf("h1","h2","title").firstNotNullOfOrNull { tag->document.all(tag).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() } }
            }.getOrNull() ?: "章节 ${chapter+1}"
            result.add(OutlineEntry("toc:fallback:$chapter",label.take(100),1,chapter,anchor=""))
        }
        return normalizeLevels(result)
    }
}
