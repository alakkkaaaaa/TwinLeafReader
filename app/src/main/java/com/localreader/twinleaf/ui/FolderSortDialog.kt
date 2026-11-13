package com.localreader.twinleaf.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.localreader.twinleaf.data.*

@Composable
fun FolderSortDialog(folder:LibraryFolder,books:List<LibraryBook>,onSave:(FolderSort,Boolean,List<String>)->Unit,onDismiss:()->Unit) {
    var sort by remember { mutableStateOf(folder.sort) }
    var descending by remember { mutableStateOf(folder.descending) }
    var manual by remember { mutableStateOf(sortedFolderBooks(books,folder.copy(sort=FolderSort.MANUAL)).map { it.id }) }
    val step=with(LocalDensity.current) { 56.dp.toPx() }
    fun move(id:String,delta:Int) {
        val old=manual.indexOf(id);val next=(old+delta).coerceIn(0,manual.lastIndex)
        if(old>=0 && old!=next) manual=manual.toMutableList().apply { add(next,removeAt(old)) }
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("文件夹排序")},text={Column {
        Row { FolderSort.entries.take(2).forEach { item -> FilterChip(sort==item,{sort=item},{Text(item.label)},modifier=Modifier.padding(end=6.dp)) } }
        Row { FolderSort.entries.drop(2).forEach { item -> FilterChip(sort==item,{sort=item},{Text(item.label)},modifier=Modifier.padding(end=6.dp)) } }
        if(sort!=FolderSort.MANUAL) Row(verticalAlignment=Alignment.CenterVertically) {
            Switch(descending,{descending=it});Text(if(descending) "降序" else "升序")
        } else {
            Text("拖动右侧手柄调整顺序，也可点箭头微调。切换自动排序不会清除手动顺序。",style=MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max=300.dp)) {
                items(manual,key={it}) { id ->
                    Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(books.firstOrNull { it.id==id }?.title ?: "",maxLines=2,modifier=Modifier.weight(1f))
                        IconButton(onClick={move(id,-1)},enabled=manual.indexOf(id)>0,modifier=Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowUp,"上移：$id") }
                        IconButton(onClick={move(id,1)},enabled=manual.indexOf(id)<manual.lastIndex,modifier=Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowDown,"下移：$id") }
                        Box(Modifier.size(40.dp).semantics { contentDescription="拖动排序：$id" }.pointerInput(id) {
                            var distance=0f
                            detectDragGestures(onDragStart={distance=0f},onDragCancel={distance=0f},onDragEnd={distance=0f}) { change,amount ->
                                change.consume();distance+=amount.y
                                if(kotlin.math.abs(distance)>step*.65f) { move(id,if(distance>0) 1 else -1);distance=0f }
                            }
                        },contentAlignment=Alignment.Center) { Icon(Icons.Rounded.DragHandle,null) }
                    }
                }
            }
        }
    }},confirmButton={TextButton(onClick={onSave(sort,descending,manual)}) { Text("保存排序") }},dismissButton={TextButton(onClick=onDismiss) { Text("取消") }})
}
