package com.localreader.twinleaf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localreader.twinleaf.data.LibraryCategory
import com.localreader.twinleaf.data.LibraryRepository

/** Compatibility entry point used by the import flow; selection still returns a stable ID. */
@Composable
fun CategoryDialog(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "选择分类",
) {
    val context = LocalContext.current
    val categories = remember(context) { LibraryRepository(context).categories() }
    CategoryDialog(categories = categories, onSelect = onSelect, onDismiss = onDismiss, title = title)
}

@Composable
fun CategoryDialog(
    categories: List<LibraryCategory>,
    selectedId: String? = null,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "选择分类",
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            categories.forEach { category ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(category.id) }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = category.id == selectedId, onClick = { onSelect(category.id) })
                    Text(category.name, Modifier.padding(start = 8.dp))
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun CategoryManagerDialog(
    repository: LibraryRepository,
    categories: List<LibraryCategory>,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LibraryCategory?>(null) }
    var deleting by remember { mutableStateOf<LibraryCategory?>(null) }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("管理分类") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("使用上下箭头调整显示顺序；分类与文件夹互不影响。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
            categories.forEachIndexed { index, category ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(category.name, Modifier.weight(1f))
                    IconButton(onClick = {
                        val ids = categories.map { it.id }.toMutableList()
                        val moved = ids.removeAt(index); ids.add(index - 1, moved)
                        repository.reorderCategories(ids); onChanged()
                    }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "上移${category.name}") }
                    IconButton(onClick = {
                        val ids = categories.map { it.id }.toMutableList()
                        val moved = ids.removeAt(index); ids.add(index + 1, moved)
                        repository.reorderCategories(ids); onChanged()
                    }, enabled = index < categories.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "下移${category.name}") }
                    IconButton(onClick = { editing = category }, enabled = !category.isProtected) {
                        Icon(Icons.Rounded.Edit, "重命名${category.name}")
                    }
                    IconButton(onClick = { deleting = category }, enabled = !category.isProtected) {
                        Icon(Icons.Rounded.DeleteOutline, "删除${category.name}")
                    }
                }
                if (index < categories.lastIndex) HorizontalDivider()
            }
            TextButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Rounded.Add, null); Text("新建分类")
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })

    if (creating) CategoryNameDialog("新建分类", "", categories, null,
        onSave = { repository.createCategory(it); creating = false; onChanged() }, onDismiss = { creating = false })
    editing?.let { category -> CategoryNameDialog("重命名分类", category.name, categories, category.id,
        onSave = { repository.renameCategory(category.id, it); editing = null; onChanged() }, onDismiss = { editing = null }) }
    deleting?.let { category -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text("删除“${category.name}”？") },
        text = { Text("该分类中的书会移入“未分类”。文件夹、原文件、封面和阅读进度均保留。") },
        confirmButton = { TextButton(onClick = {
            repository.deleteCategory(category.id); deleting = null; onChanged()
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun CategoryNameDialog(
    title: String,
    initial: String,
    categories: List<LibraryCategory>,
    editingId: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    val checked = value.trim()
    val duplicate = categories.any { it.id != editingId && it.name.equals(checked, ignoreCase = true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(value, { value = it.take(30) }, singleLine = true, label = { Text("分类名称") },
            isError = duplicate, supportingText = { if (duplicate) Text("名称已存在") })
    }, confirmButton = { TextButton(onClick = { onSave(checked) }, enabled = checked.isNotEmpty() && !duplicate) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
