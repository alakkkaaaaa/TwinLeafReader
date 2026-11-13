package com.localreader.twinleaf.data

/** Typed keys let folders and books share one order without ID collisions. */
fun normalizedShelfOrder(saved: List<String>, folderIds: List<String>, bookIds: List<String>): List<String> {
    val available = folderIds.map { "folder:$it" } + bookIds.map { "book:$it" }
    val valid = available.toSet()
    return (saved.filter { it in valid } + available).distinct()
}

fun insertShelfItem(order: List<String>, source: String, target: String, after: Boolean): List<String> {
    if (source == target || source !in order || target !in order) return order
    return order.toMutableList().apply {
        remove(source)
        add(indexOf(target) + if (after) 1 else 0, source)
    }
}

/** Replace only visible slots; filtered-out entries keep their relative positions. */
fun reorderVisibleSlots(all: List<String>, visibleOrder: List<String>): List<String> {
    val valid = visibleOrder.distinct().filter { it in all }
    val visible = valid.toSet()
    val replacement = valid.iterator()
    return all.distinct().map { if (it in visible) replacement.next() else it }
}

fun moveShelfItem(order: List<String>, source: String, target: String): List<String> {
    val from = order.indexOf(source)
    val to = order.indexOf(target)
    if (from < 0 || to < 0 || from == to) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

fun orderedRootBooks(books: List<LibraryBook>, order: List<String>?): List<LibraryBook> {
    if (order == null) return books.sortedByDescending { it.lastOpenedAt }
    val ranks = order.withIndex().associate { it.value to it.index }
    return books.sortedWith(compareBy<LibraryBook> { ranks[it.id] ?: Int.MAX_VALUE }
        .thenBy { it.importedAt }.thenBy { it.id })
}
