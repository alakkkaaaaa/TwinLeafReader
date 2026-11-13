package com.localreader.twinleaf.data

data class LibraryCategory(
    val id: String,
    val name: String,
) {
    val isProtected: Boolean get() = id == UNCATEGORIZED_CATEGORY_ID
}

const val TEXTBOOK_CATEGORY_ID = "textbook"
const val NOVEL_CATEGORY_ID = "novel"
const val COMIC_CATEGORY_ID = "comic"
const val UNCATEGORIZED_CATEGORY_ID = "uncategorized"

val DEFAULT_LIBRARY_CATEGORIES = listOf(
    LibraryCategory(TEXTBOOK_CATEGORY_ID, "教科书"),
    LibraryCategory(NOVEL_CATEGORY_ID, "小说"),
    LibraryCategory(COMIC_CATEGORY_ID, "漫画"),
    LibraryCategory(UNCATEGORIZED_CATEGORY_ID, "未分类"),
)

internal fun normalizedCategories(source: List<LibraryCategory>): List<LibraryCategory> {
    val usedIds = HashSet<String>()
    val usedNames = HashSet<String>()
    val result = source.mapNotNull { category ->
        val id = category.id.trim()
        val name = category.name.trim().take(30)
        if (id.isEmpty() || name.isEmpty() || !usedIds.add(id) || !usedNames.add(name.lowercase())) null
        else LibraryCategory(id, name)
    }.toMutableList()
    if (result.none { it.id == UNCATEGORIZED_CATEGORY_ID }) {
        result += LibraryCategory(UNCATEGORIZED_CATEGORY_ID, "未分类")
    }
    return result
}

internal fun reorderedCategories(
    categories: List<LibraryCategory>,
    requestedIds: List<String>,
): List<LibraryCategory> {
    val byId = categories.associateBy(LibraryCategory::id)
    return (requestedIds.mapNotNull(byId::get) + categories.filter { it.id !in requestedIds }).distinctBy { it.id }
}

internal fun legacyCategoryId(value: String, categories: List<LibraryCategory>): String? {
    val checked = value.trim()
    if (checked.isEmpty()) return UNCATEGORIZED_CATEGORY_ID
    categories.firstOrNull { it.id == checked }?.let { return it.id }
    categories.firstOrNull { it.name.equals(checked, ignoreCase = true) }?.let { return it.id }
    return when (checked) {
        "教科书" -> TEXTBOOK_CATEGORY_ID
        "小说" -> NOVEL_CATEGORY_ID
        "漫画" -> COMIC_CATEGORY_ID
        "未分类" -> UNCATEGORIZED_CATEGORY_ID
        else -> null
    }
}
