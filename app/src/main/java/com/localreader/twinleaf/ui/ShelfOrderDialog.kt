package com.localreader.twinleaf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localreader.twinleaf.data.*

/** Accessible alternative to dragging mixed folder/book cards on the shelf. */
@Composable
internal fun ShelfOrderDialog(order: List<String>, books: List<LibraryBook>, folders: List<LibraryFolder>,
    onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(order) }
    val labels = books.associate { "book:${it.id}" to it.title } + folders.associate { "folder:${it.id}" to "文件夹 · ${it.name}" }
    fun move(key: String, delta: Int) {
        val index = draft.indexOf(key)
        draft.getOrNull(index + delta)?.let { draft = moveShelfItem(draft, key, it) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("书架排序") }, text = {
        Column {
            Text("文件夹与图书可以混合排列。也可直接在书架长按拖动。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(draft, key = { it }) { key ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(labels[key].orEmpty(), maxLines = 2, modifier = Modifier.weight(1f))
                        IconButton(onClick = { move(key, -1) }, enabled = draft.firstOrNull() != key) {
                            Icon(Icons.Rounded.KeyboardArrowUp, "上移书架项目：$key")
                        }
                        IconButton(onClick = { move(key, 1) }, enabled = draft.lastOrNull() != key) {
                            Icon(Icons.Rounded.KeyboardArrowDown, "下移书架项目：$key")
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("保存排序") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
