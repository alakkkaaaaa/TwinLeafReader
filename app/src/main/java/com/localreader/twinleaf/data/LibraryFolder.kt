package com.localreader.twinleaf.data

data class LibraryFolder(val id: String, val name: String, val coverPath: String? = null,
    val sort: FolderSort = FolderSort.MANUAL, val descending: Boolean = false,
    val manualOrder: List<String> = emptyList())

enum class FolderSort(val label: String) { MANUAL("手动排序"), TITLE("书名"), IMPORTED("导入时间"), RECENT("最近阅读") }

/** Numeric runs compare by magnitude without overflow (volume 2 precedes volume 10). */
fun naturalTitleCompare(a: String, b: String): Int {
    val chunks = Regex("[0-9]+|[^0-9]+")
    val x = chunks.findAll(a.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
    val y = chunks.findAll(b.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val u=x[i]; val v=y[i]
        val result=if(u[0].isDigit() && v[0].isDigit()) {
            val p=u.trimStart('0').ifEmpty { "0" }; val q=v.trimStart('0').ifEmpty { "0" }
            p.length.compareTo(q.length).takeIf { it!=0 } ?: p.compareTo(q)
        } else u.compareTo(v)
        if(result!=0) return result
    }
    return x.size.compareTo(y.size)
}

fun sortedFolderBooks(books: List<LibraryBook>, folder: LibraryFolder): List<LibraryBook> {
    val manual=folder.manualOrder.withIndex().associate { it.value to it.index }
    val comparator=Comparator<LibraryBook> { a,b ->
        when(folder.sort) {
            FolderSort.MANUAL -> (manual[a.id] ?: Int.MAX_VALUE).compareTo(manual[b.id] ?: Int.MAX_VALUE)
                .takeIf { it!=0 } ?: a.importedAt.compareTo(b.importedAt)
            FolderSort.TITLE -> naturalTitleCompare(a.title,b.title)
            FolderSort.IMPORTED -> a.importedAt.compareTo(b.importedAt)
            FolderSort.RECENT -> a.lastOpenedAt.compareTo(b.lastOpenedAt)
        }.takeIf { it!=0 } ?: a.id.compareTo(b.id)
    }
    return books.sortedWith(if(folder.descending && folder.sort!=FolderSort.MANUAL) comparator.reversed() else comparator)
}

fun validatedFolderName(name: String, folders: List<LibraryFolder>, exceptId: String? = null): String {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty()) { "请输入文件夹名称" }
    require(trimmed.length <= 40) { "名称不能超过40个字符" }
    require(trimmed.none { it.isISOControl() }) { "名称不能含换行或控制字符" }
    require(folders.none { it.id != exceptId && it.name.equals(trimmed, ignoreCase = true) }) { "已存在同名文件夹" }
    return trimmed
}
