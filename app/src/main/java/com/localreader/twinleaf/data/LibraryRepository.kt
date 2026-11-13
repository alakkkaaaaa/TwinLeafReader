package com.localreader.twinleaf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import java.util.UUID
import com.localreader.twinleaf.note.NoteStore
import com.localreader.twinleaf.sync.CloudSyncScheduler

class LibraryRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("twinleaf_library", Context.MODE_PRIVATE)
    private val coverDirectory = File(context.filesDir, "covers").apply { mkdirs() }

    @Synchronized
    private fun ensureCategoryMigration() {
        val stored = runCatching {
            preferences.getString(KEY_CATEGORIES, null)?.let { source ->
                val array = JSONArray(source)
                List(array.length()) { index -> array.getJSONObject(index).let {
                    LibraryCategory(it.optString("id"), it.optString("name"))
                } }
            }
        }.getOrNull()
        val categories = normalizedCategories(stored ?: DEFAULT_LIBRARY_CATEGORIES).toMutableList()
        val source = preferences.getString(KEY_BOOKS, null)
        var booksChanged = false
        val books = source?.let { runCatching { JSONArray(it) }.getOrNull() }
        if (books != null) for (index in 0 until books.length()) {
            val item = books.optJSONObject(index) ?: continue
            val oldValue = item.optString("category", "未分类")
            var resolved = legacyCategoryId(oldValue, categories)
            if (resolved == null) {
                val legacyName = oldValue.trim().take(30).ifBlank { "未分类" }
                resolved = if (legacyName == "未分类") UNCATEGORIZED_CATEGORY_ID else {
                    val created = LibraryCategory(UUID.randomUUID().toString(), legacyName)
                    categories += created
                    created.id
                }
            }
            if (oldValue != resolved) {
                item.put("category", resolved)
                booksChanged = true
            }
        }
        val normalized = normalizedCategories(categories)
        val categoriesText = categoriesJson(normalized).toString()
        if (stored == null || preferences.getString(KEY_CATEGORIES, null) != categoriesText || booksChanged) {
            preferences.edit().putString(KEY_CATEGORIES, categoriesText).apply {
                if (booksChanged && books != null) putString(KEY_BOOKS, books.toString())
            }.apply()
        }
    }

    fun categories(): List<LibraryCategory> {
        ensureCategoryMigration()
        return runCatching {
            val array = JSONArray(preferences.getString(KEY_CATEGORIES, "[]"))
            normalizedCategories(List(array.length()) { index -> array.getJSONObject(index).let {
                LibraryCategory(it.getString("id"), it.getString("name"))
            } })
        }.getOrDefault(DEFAULT_LIBRARY_CATEGORIES)
    }

    fun createCategory(name: String): LibraryCategory {
        val existing = categories()
        val checked = validatedCategoryName(name, existing)
        val category = LibraryCategory(UUID.randomUUID().toString(), checked)
        saveCategories(existing + category)
        return category
    }

    fun renameCategory(id: String, name: String) {
        require(id != UNCATEGORIZED_CATEGORY_ID) { "未分类不可重命名" }
        val existing = categories()
        require(existing.any { it.id == id }) { "分类已不存在" }
        val checked = validatedCategoryName(name, existing, id)
        saveCategories(existing.map { if (it.id == id) it.copy(name = checked) else it })
    }

    /** Books keep their folder membership; only their primary category is reset. */
    fun deleteCategory(id: String): List<LibraryBook> {
        require(id != UNCATEGORIZED_CATEGORY_ID) { "未分类不可删除" }
        val existing = categories()
        require(existing.any { it.id == id }) { "分类已不存在" }
        val books = load().map { if (it.category == id) it.copy(category = UNCATEGORIZED_CATEGORY_ID) else it }
        preferences.edit().putString(KEY_CATEGORIES, categoriesJson(existing.filterNot { it.id == id }).toString())
            .putString(KEY_BOOKS, booksJson(books).toString()).apply()
        return books
    }

    fun reorderCategories(ids: List<String>) {
        saveCategories(reorderedCategories(categories(), ids))
    }

    fun categoryName(id: String): String = categories().firstOrNull { it.id == id }?.name ?: "未分类"

    private fun validatedCategoryName(name: String, existing: List<LibraryCategory>, editingId: String? = null): String {
        val checked = name.trim().take(30)
        require(checked.isNotEmpty()) { "分类名称不能为空" }
        require(!checked.contains('\n') && !checked.contains('\r')) { "分类名称不能换行" }
        require(existing.none { it.id != editingId && it.name.equals(checked, ignoreCase = true) }) { "分类名称已存在" }
        return checked
    }

    private fun categoriesJson(categories: List<LibraryCategory>) = JSONArray().apply {
        categories.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
    }

    private fun saveCategories(categories: List<LibraryCategory>) {
        preferences.edit().putString(KEY_CATEGORIES, categoriesJson(normalizedCategories(categories)).toString()).apply()
    }

    fun rootBookOrder(): List<String>? = runCatching {
        preferences.getString("rootBookOrder", null)?.let { text ->
            val array = JSONArray(text)
            List(array.length()) { array.getString(it) }
        }
    }.getOrNull()

    private fun savedShelfOrder(): List<String>? = runCatching {
        preferences.getString("rootShelfOrder", null)?.let { source ->
            val array = JSONArray(source)
            List(array.length()) { array.getString(it) }
        }
    }.getOrNull()

    /** Legacy book order migrates behind existing folders until the user mixes them. */
    fun rootShelfOrder(): List<String> {
        val groups = folders()
        val roots = load().filter { book -> groups.none { it.id == book.folderId } }
        val legacy = groups.map { "folder:${it.id}" } + orderedRootBooks(roots, rootBookOrder()).map { "book:${it.id}" }
        return normalizedShelfOrder(savedShelfOrder() ?: legacy, groups.map { it.id },
            roots.sortedBy { it.importedAt }.map { it.id })
    }

    fun reorderRootEntries(visibleOrder: List<String>) {
        val order = reorderVisibleSlots(rootShelfOrder(), visibleOrder)
        preferences.edit().putString("rootShelfOrder", JSONArray(order).toString())
            .putString("rootBookOrder", JSONArray(order.filter { it.startsWith("book:") }.map { it.removePrefix("book:") }).toString()).apply()
    }

    /** A drop commits once, using the current full list to preserve filtered-out books. */
    fun reorderBooks(folderId: String?, visibleOrder: List<String>) {
        val all = load()
        val groups = folders()
        if (folderId == null) {
            reorderRootEntries(visibleOrder.map { "book:$it" })
        } else {
            val folder = groups.firstOrNull { it.id == folderId } ?: return
            val order = reorderVisibleSlots(sortedFolderBooks(all.filter { it.folderId == folderId }, folder).map { it.id }, visibleOrder)
            updateFolderSort(folderId, FolderSort.MANUAL, false, order)
        }
    }

    fun folders(): List<LibraryFolder> = runCatching {
        val array = JSONArray(preferences.getString("folders", "[]"))
        List(array.length()) { i -> array.getJSONObject(i).let {
            val order=it.optJSONArray("manualOrder") ?: JSONArray()
            LibraryFolder(it.getString("id"), it.getString("name"), it.optString("coverPath").takeUnless { p -> p.isBlank() || p == "null" },
                runCatching { FolderSort.valueOf(it.optString("sort")) }.getOrDefault(FolderSort.MANUAL),
                it.optBoolean("descending"), List(order.length()) { i -> order.getString(i) })
        } }
    }.getOrDefault(emptyList())

    fun createFolder(name: String): LibraryFolder {
        val existing = folders()
        val folder = LibraryFolder(UUID.randomUUID().toString(), validatedFolderName(name, existing))
        saveFolders(existing + folder)
        return folder
    }

    fun renameFolder(id: String, name: String) {
        val existing = folders()
        require(existing.any { it.id == id }) { "文件夹已不存在" }
        val checked = validatedFolderName(name, existing, id)
        saveFolders(existing.map { if (it.id == id) it.copy(name = checked) else it })
    }

    fun setFolderCover(id: String, path: String?) {
        val existing = folders()
        require(existing.any { it.id == id }) { "文件夹已不存在" }
        saveFolders(existing.map { if (it.id == id) it.copy(coverPath = path) else it })
    }

    fun moveToFolder(ids: Set<String>, folderId: String?) {
        require(folderId == null || folders().any { it.id == folderId }) { "文件夹已不存在" }
        val before=load()
        saveMembership(before,before.map { if (it.id in ids) it.copy(folderId = folderId) else it })
    }

    /** Assign selected books here, returning deselected members to the shelf in one write. */
    fun setFolderBooks(folderId: String, ids: Set<String>) {
        require(folders().any { it.id == folderId }) { "文件夹已不存在" }
        val before=load()
        saveMembership(before,before.map {
            when { it.id in ids -> it.copy(folderId = folderId)
                it.folderId == folderId -> it.copy(folderId = null)
                else -> it }
        })
    }

    private fun saveMembership(before:List<LibraryBook>,after:List<LibraryBook>) {
        val previous=before.associateBy { it.id }
        val moved=after.filter { previous[it.id]?.folderId!=it.folderId }.map { it.id }.toSet()
        // A removed-and-readded member is a new entry at the end, not its stale old slot.
        val updatedFolders=folders().map { folder ->
            val members=after.filter { it.folderId==folder.id }.sortedBy { it.importedAt }.map { it.id }
            folder.copy(manualOrder=(folder.manualOrder.filter { it !in moved && it in members }+members).distinct())
        }
        val editor = preferences.edit().putString(KEY_BOOKS,booksJson(after).toString())
            .putString("folders",foldersJson(updatedFolders).toString())
        rootBookOrder()?.let { order ->
            val roots = after.filter { book -> updatedFolders.none { it.id == book.folderId } }.map { it.id }
            editor.putString("rootBookOrder", JSONArray((order.filter { it !in moved && it in roots } + roots).distinct()).toString())
        }
        savedShelfOrder()?.let { order ->
            val roots = after.filter { book -> updatedFolders.none { it.id == book.folderId } }.sortedBy { it.importedAt }.map { it.id }
            editor.putString("rootShelfOrder", JSONArray(normalizedShelfOrder(
                order.filterNot { it.startsWith("book:") && it.removePrefix("book:") in moved },
                updatedFolders.map { it.id }, roots)).toString())
        }
        editor.apply()
    }

    private fun appendManualOrder(folderId:String?) {
        if(folderId==null) return
        val ids=load().filter { it.folderId==folderId }.sortedBy { it.importedAt }.map { it.id }
        saveFolders(folders().map { if(it.id==folderId) it.copy(manualOrder=(it.manualOrder.filter { id -> id in ids }+ids).distinct()) else it })
    }

    fun updateFolderSort(id:String,sort:FolderSort,descending:Boolean,order:List<String>?=null) {
        appendManualOrder(id)
        saveFolders(folders().map { if(it.id==id) it.copy(sort=sort,descending=descending,
            manualOrder=order?.distinct() ?: it.manualOrder) else it })
    }

    /** Delete only the virtual folder; original books, progress and outlines stay intact. */
    fun deleteFolder(id: String) {
        val books = load().map { if (it.folderId == id) it.copy(folderId = null) else it }
        val remaining = folders().filterNot { it.id == id }
        preferences.edit().putString(KEY_BOOKS, booksJson(books).toString())
            .putString("folders", foldersJson(remaining).toString()).apply()
    }

    private fun foldersJson(folders: List<LibraryFolder>) = JSONArray().apply {
        folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("coverPath", it.coverPath)
            .put("sort",it.sort.name).put("descending",it.descending).put("manualOrder",JSONArray(it.manualOrder))) }
    }
    private fun saveFolders(folders: List<LibraryFolder>) {
        preferences.edit().putString("folders", foldersJson(folders).toString()).apply()
    }

    fun load(): List<LibraryBook> {
        ensureCategoryMigration()
        val source = preferences.getString(KEY_BOOKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(source)
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toBook())
            }.sortedByDescending(LibraryBook::lastOpenedAt)
        }.getOrDefault(emptyList())
    }

    suspend fun addOrRefresh(session: BookSession): LibraryBook {
        val books = load().toMutableList()
        val existingIndex = books.indexOfFirst { it.uri == session.uri.toString() }
        val existing = books.getOrNull(existingIndex)
        val id = existing?.id ?: stableId(session.uri.toString())
        val coverPath = existing?.coverPath?.takeIf { File(it).isFile }
            ?: createCover(session, id)
        val refreshed = (existing ?: LibraryBook(
            id = id,
            uri = session.uri.toString(),
            title = session.title,
            format = if (session is PdfDocument) "PDF" else "EPUB",
            coverPath = coverPath,
        )).copy(
            title = session.title,
            coverPath = coverPath,
            lastOpenedAt = System.currentTimeMillis(),
        )
        if (existingIndex >= 0) books[existingIndex] = refreshed else books.add(refreshed)
        save(books)
        CloudSyncScheduler.requestSoon(context)
        return refreshed
    }

    /** Keep a human-readable title after a managed cloud download whose file name is hash based. */
    fun renameSyncedBook(id:String,title:String):LibraryBook {
        val checked=title.trim().ifBlank { "云端书籍" }.take(200)
        val books=load().map { if(it.id==id && !it.format.equals("NOTE",true)) it.copy(title=checked) else it }
        save(books)
        return books.first { it.id==id }
    }

    fun createNote(title:String,folderId:String?):LibraryBook {
        require(folderId==null || folders().any { it.id==folderId }) { "文件夹已不存在" }
        val checked=title.trim().ifBlank { "未命名笔记" }.take(80)
        val id=UUID.randomUUID().toString()
        val now=System.currentTimeMillis()
        val preview=File(context.filesDir,"covers/note-$id.jpg").absolutePath
        val record=LibraryBook(id,"note://local/$id",checked,"NOTE",preview,
            folderId=folderId,lastOpenedAt=now,importedAt=now)
        save(load()+record)
        appendManualOrder(folderId)
        return record
    }

    fun renameNote(id:String,title:String):List<LibraryBook> {
        val checked=title.trim().take(80)
        require(checked.isNotEmpty()) { "笔记名称不能为空" }
        val books=load().map { book ->
            if(book.id==id && book.format.equals("NOTE",true)) book.copy(title=checked) else book
        }
        save(books)
        return books
    }

    fun updatePosition(bookId: String, transform: (LibraryBook) -> LibraryBook): List<LibraryBook> {
        val books = load().map { book ->
            if (book.id == bookId) transform(book).copy(lastOpenedAt = System.currentTimeMillis()) else book
        }
        save(books)
        CloudSyncScheduler.requestSoon(context)
        return books.sortedByDescending(LibraryBook::lastOpenedAt)
    }

    /** Apply a cloud position only after the sync layer has resolved timestamps. */
    fun applySyncedPosition(bookId:String,remote:LibraryBook):List<LibraryBook> {
        val books=load().map { local -> if(local.id==bookId) local.copy(
            progress=remote.progress,
            pdfSpread=remote.pdfSpread,pdfPage=remote.pdfPage,pdfOffset=remote.pdfOffset,
            epubChapter=remote.epubChapter,epubSpread=remote.epubSpread,epubFraction=remote.epubFraction,
            lastOpenedAt=remote.lastOpenedAt,
        ) else local }
        save(books)
        return books.sortedByDescending(LibraryBook::lastOpenedAt)
    }

    /** Infinite notes have no external source file, so they can be restored on a new device. */
    fun upsertSyncedNote(id:String,title:String,updatedAt:Long):LibraryBook {
        val existing=load().firstOrNull { it.id==id && it.format.equals("NOTE",true) }
        if(existing!=null) return existing
        val preview=File(context.filesDir,"covers/note-$id.jpg").absolutePath
        val record=LibraryBook(id,"note://local/$id",title.trim().ifBlank { "云端笔记" }.take(80),"NOTE",preview,
            lastOpenedAt=updatedAt,importedAt=updatedAt)
        save(load()+record)
        return record
    }

    fun categorize(ids:Set<String>,category:String):List<LibraryBook> {
        val existing = categories()
        val categoryId = legacyCategoryId(category, existing) ?: error("分类已不存在")
        val books=load().map { if(it.id in ids) it.copy(category=categoryId) else it };save(books);return books
    }
    fun setCustomCover(id:String,path:String?):List<LibraryBook> {
        val books=load().map { if(it.id==id) it.copy(customCoverPath=path) else it };save(books);return books
    }

    /** Remove only the app record; never delete the user's document or revoke shared access. */
    fun remove(bookId: String): List<LibraryBook> {
        load().firstOrNull { it.id==bookId }?.let {
            PageShortcutRepository(context).clear(it.uri)
            BookOutlineRepository(context).clear(it.uri)
            if(it.format.equals("NOTE",true)) {
                NoteStore.delete(context,it.id)
                it.coverPath?.let(::File)?.delete()
                it.customCoverPath?.let(::File)?.takeIf { file -> file.absolutePath.startsWith(context.filesDir.absolutePath) }?.delete()
            }
        }
        val remaining = load().filterNot { it.id == bookId }
        save(remaining)
        rootBookOrder()?.let { order -> preferences.edit().putString("rootBookOrder", JSONArray(order.filterNot { it == bookId }).toString()).apply() }
        savedShelfOrder()?.let { order -> preferences.edit().putString("rootShelfOrder", JSONArray(order.filterNot { it == "book:$bookId" }).toString()).apply() }
        return remaining
    }

    private suspend fun createCover(session: BookSession, id: String): String? = runCatching {
        val destination = File(coverDirectory, "$id.jpg")
        val bitmap = when (session) {
            is PdfDocument -> session.renderPage(0, 360, 520)
            is EpubDocument -> decodeSampled(session.coverImage, 360, 520)
        } ?: return@runCatching null
        destination.outputStream().buffered().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
        }
        destination.absolutePath
    }.getOrNull()

    private fun decodeSampled(file: File?, width: Int, height: Int): Bitmap? {
        if (file?.isFile != true) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = max(1, sample)
        })
    }

    private fun save(books: List<LibraryBook>) {
        preferences.edit().putString(KEY_BOOKS, booksJson(books).toString()).apply()
    }

    private fun booksJson(books: List<LibraryBook>) = JSONArray().apply { books.forEach { put(it.toJson()) } }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(10)
        .joinToString("") { "%02x".format(it) }

    private fun LibraryBook.toJson() = JSONObject().apply {
        put("id", id)
        put("uri", uri)
        put("title", title)
        put("format", format)
        put("coverPath", coverPath)
        put("progress", progress.toDouble())
        put("pdfSpread", pdfSpread)
        put("epubChapter", epubChapter)
        put("epubSpread", epubSpread)
        put("pdfPage", pdfPage)
        put("pdfOffset", pdfOffset.toDouble())
        put("epubFraction", epubFraction.toDouble())
        put("lastOpenedAt", lastOpenedAt)
        put("importedAt", importedAt)
        put("category",category);put("customCoverPath",customCoverPath)
        put("folderId", folderId)
    }

    private fun JSONObject.toBook() = LibraryBook(
        id = getString("id"),
        uri = getString("uri"),
        title = getString("title"),
        format = optString("format", "PDF"),
        coverPath = optString("coverPath").takeUnless { it.isBlank() || it == "null" },
        progress = optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
        pdfSpread = optInt("pdfSpread", 0),
        epubChapter = optInt("epubChapter", 0),
        epubSpread = optInt("epubSpread", 0),
        pdfPage = optInt("pdfPage", -1),
        pdfOffset = optDouble("pdfOffset", 0.0).toFloat().coerceIn(0f, 1f),
        epubFraction = optDouble("epubFraction", -1.0).toFloat().coerceIn(-1f, 1f),
        lastOpenedAt = optLong("lastOpenedAt", 0L),
        importedAt = optLong("importedAt", optLong("lastOpenedAt",0L)),
        category=optString("category", UNCATEGORIZED_CATEGORY_ID).ifBlank { UNCATEGORIZED_CATEGORY_ID },
        customCoverPath=optString("customCoverPath").takeUnless { it.isBlank() || it=="null" },
        folderId=optString("folderId").takeUnless { it.isBlank() || it=="null" },
    )

    private companion object {
        const val KEY_BOOKS = "books"
        const val KEY_CATEGORIES = "categories_v2"
    }
}
