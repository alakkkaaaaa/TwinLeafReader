package com.localreader.twinleaf.epub

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.localreader.twinleaf.data.EpubSpineItem
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal data class ExtractedEpub(
    val root: File,
    val title: String,
    val spine: List<EpubSpineItem>,
    val coverImage: File?,
    val outline:List<com.localreader.twinleaf.data.OutlineEntry>,
    val layout: String?,
    val orientation: String?,
    val spread: String?,
    val pageProgressionDirection: String?,
)

internal object EpubParser {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_UNCOMPRESSED_BYTES = 750L * 1024L * 1024L

    fun extractAndParse(context: Context, uri: Uri, fallbackTitle: String): ExtractedEpub {
        val id = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        val root = File(context.cacheDir, "epub/$id-${System.nanoTime()}")
        check(root.mkdirs()) { "无法创建 EPUB 临时目录" }

        try {
            context.contentResolver.openInputStream(uri)?.use { unzipSafely(it, root) }
                ?: error("无法读取 EPUB")

            val packagePath = parseContainer(File(root, "META-INF/container.xml"))
            val packageFile = safeFile(root, decodeEpubPath(packagePath))
            check(packageFile.isFile) { "EPUB 缺少内容清单" }
            val packageData = parsePackage(packageFile)
            val base = packageFile.parentFile ?: root
            val spine = packageData.spineRefs.mapNotNull { spineRef ->
                val idRef = spineRef.idRef
                val item = packageData.manifest[idRef] ?: return@mapNotNull null
                val href = item.href
                val cleanHref = decodeEpubPath(href)
                val file = safeFile(root, root.toPath().relativize(base.toPath().resolve(cleanHref)).toString())
                file.takeIf { it.isFile }?.let {
                    sanitizeMarkup(it)
                    val properties = spineRef.properties
                    EpubSpineItem(
                        it, idRef,
                        layout = renditionValue(properties, "layout") ?: packageData.layout
                            ?: if(it.extension.equals("svg",true)) "pre-paginated" else null,
                        orientation = renditionValue(properties, "orientation") ?: packageData.orientation,
                        spread = renditionValue(properties, "spread") ?: packageData.spread,
                        pageProgressionDirection = packageData.pageProgressionDirection,
                        properties = properties,
                    )
                }
            }
            check(spine.isNotEmpty()) { "EPUB 没有可阅读的章节" }
            val coverImage = packageData.coverHref?.let { href ->
                val cleanHref = decodeEpubPath(href)
                safeFile(root, root.toPath().relativize(base.toPath().resolve(cleanHref)).toString()).takeIf(File::isFile)
            }
            return ExtractedEpub(
                root = root,
                title = packageData.title?.takeIf(String::isNotBlank) ?: fallbackTitle,
                spine = spine,
                coverImage = coverImage,
                outline = EpubToc.read(root,spine,
                    packageData.manifest.values.firstOrNull { "nav" in it.properties.split(' ') }?.href?.let { safeFile(root,root.toPath().relativize(base.toPath().resolve(decodeEpubPath(it))).toString()) },
                    packageData.manifest.values.firstOrNull { it.mediaType=="application/x-dtbncx+xml" }?.href?.let { safeFile(root,root.toPath().relativize(base.toPath().resolve(decodeEpubPath(it))).toString()) }),
                layout = packageData.layout,
                orientation = packageData.orientation,
                spread = packageData.spread,
                pageProgressionDirection = packageData.pageProgressionDirection,
            )
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    internal fun unzipSafely(input: InputStream, root: File) {
        var entries = 0
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                check(entries <= MAX_ENTRIES) { "EPUB 文件条目过多" }
                val output = safeFile(root, entry.name.replace('\\', '/'))
                if (entry.isDirectory) {
                    check(output.mkdirs() || output.isDirectory)
                } else {
                    val parent = requireNotNull(output.parentFile)
                    check(parent.isDirectory || parent.mkdirs()) { "无法创建 EPUB 资源目录" }
                    output.outputStream().buffered().use { stream ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            check(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "EPUB 解压后过大" }
                            stream.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun parseContainer(file: File): String {
        check(file.isFile) { "不是有效的 EPUB：缺少 container.xml" }
        file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(input, null) }
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return parser.getAttributeValue(null, "full-path")
                        ?: error("container.xml 缺少 full-path")
                }
                parser.next()
            }
        }
        error("container.xml 没有内容清单")
    }

    private data class ManifestItem(
        val href: String,
        val properties: String,
        val mediaType:String,
    )

    private data class PackageData(
        val title: String?,
        val manifest: Map<String, ManifestItem>,
        val spineRefs: List<SpineRef>,
        val coverHref: String?,
        val layout: String?,
        val orientation: String?,
        val spread: String?,
        val pageProgressionDirection: String?,
    )

    private data class SpineRef(val idRef: String, val properties: Set<String>)

    private fun parsePackage(file: File): PackageData {
        val manifest = linkedMapOf<String, ManifestItem>()
        val spine = mutableListOf<SpineRef>()
        var title: String? = null
        var legacyCoverId: String? = null
        var layout: String? = null
        var orientation: String? = null
        var spread: String? = null
        var pageProgressionDirection: String? = null
        file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(input, null) }
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val properties = parser.getAttributeValue(null, "properties").orEmpty()
                            if (id != null && href != null) manifest[id] = ManifestItem(href, properties,parser.getAttributeValue(null,"media-type").orEmpty())
                        }
                        "itemref" -> parser.getAttributeValue(null, "idref")?.let { id ->
                            spine += SpineRef(id, parser.getAttributeValue(null, "properties")
                                .orEmpty().split(Regex("\\s+")).filter(String::isNotBlank).toSet())
                        }
                        "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                            legacyCoverId = parser.getAttributeValue(null, "content")
                        } else {
                            when (parser.getAttributeValue(null, "property")?.lowercase()) {
                                "rendition:layout" -> layout = parser.nextText().trim().lowercase()
                                "rendition:orientation" -> orientation = parser.nextText().trim().lowercase()
                                "rendition:spread" -> spread = parser.nextText().trim().lowercase()
                            }
                        }
                        "title" -> if (title == null) title = parser.nextText()
                    }
                }
                parser.next()
            }
        }
        val coverHref = legacyCoverId?.let(manifest::get)?.href
            ?: manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }?.href
        val progression = runCatching {
            file.inputStream().use { input ->
                val p = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(input, null) }
                var value: String? = null
                while (p.eventType != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name.equals("spine", true)) {
                        value = p.getAttributeValue(null, "page-progression-direction")?.lowercase()
                        break
                    }
                    p.next()
                }
                value
            }
        }.getOrNull()
        return PackageData(title, manifest, spine, coverHref, layout, orientation, spread, progression)
    }

    private fun renditionValue(properties: Set<String>, name: String): String? =
        properties.firstNotNullOfOrNull { token ->
            token.takeIf { it.startsWith("rendition:$name-") }
                ?.removePrefix("rendition:$name-")?.lowercase()
        }

    /** Removes active content before local chapters are loaded into WebView. */
    private fun sanitizeMarkup(file: File) {
        if (file.extension.lowercase() !in setOf("xhtml", "html", "htm", "xml")) return
        check(file.length() <= 24L * 1024L * 1024L) { "EPUB 单个章节过大" }
        val original = file.readText(Charsets.UTF_8)
        val cleaned = original
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script\\s*>"), "")
            .replace(Regex("(?is)<(?:iframe|object|embed)\\b[^>]*>.*?</(?:iframe|object)\\s*>"), "")
            .replace(Regex("(?is)<(?:iframe|object|embed)\\b[^>]*/?\\s*>"), "")
            .replace(Regex("(?is)\\s+on[a-z]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "")
            .replace(Regex("(?i)(href|src)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2")) {
                "${it.groupValues[1]}=\"#\""
            }
            .replace(Regex("(?i)(href|src)\\s*=\\s*([\"'])\\s*(?:file|content):[^\"']*\\2")) {
                "${it.groupValues[1]}=\"#\""
            }
        if (cleaned != original) file.writeText(cleaned, Charsets.UTF_8)
    }

    /** EPUB href values use URI escaping, where a literal '+' must stay a plus. */
    private fun decodeEpubPath(href: String): String = Uri.decode(
        href.substringBefore('#').substringBefore('?'),
    ).replace('\\', '/').removePrefix("/")

    internal fun safeFile(root: File, relativePath: String): File {
        check(relativePath.isNotBlank()) { "EPUB 包含空路径" }
        val rootPath = root.canonicalFile.toPath()
        val candidate = root.toPath().resolve(relativePath).normalize().toFile().canonicalFile.toPath()
        check(candidate.startsWith(rootPath)) { "EPUB 包含越界路径" }
        return candidate.toFile()
    }
}
