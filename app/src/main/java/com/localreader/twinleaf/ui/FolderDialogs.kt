package com.localreader.twinleaf.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.localreader.twinleaf.data.*
import kotlinx.coroutines.*

@Composable
fun FolderNameDialog(folder: LibraryFolder?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(folder?.name ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=onDismiss, title={Text(if(folder==null) "新建文件夹" else "重命名文件夹")},
        text={Column {
            OutlinedTextField(name,{name=it;error=null},singleLine=true,label={Text("文件夹名称")},
                modifier=Modifier.semantics { contentDescription="文件夹名称" },isError=error!=null)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }},confirmButton={TextButton(onClick={try { onSave(name) } catch(e:IllegalArgumentException) { error=e.message }}) { Text("保存") }},
        dismissButton={TextButton(onClick=onDismiss) { Text("取消") }})
}

@Composable
fun FolderDestinationDialog(folders: List<LibraryFolder>, onSelect:(String?)->Unit, onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("移动到文件夹")},text={LazyColumn(Modifier.heightIn(max=360.dp)) {
        item { TextButton(onClick={onSelect(null)},modifier=Modifier.fillMaxWidth()) { Text("书架（不放入文件夹）") } }
        items(folders,key={it.id}) { folder -> TextButton(onClick={onSelect(folder.id)},modifier=Modifier.fillMaxWidth()) { Text(folder.name) } }
        if(folders.isEmpty()) item { Text("可先在书架点击右上角 ＋，选择新建文件夹。") }
    }},confirmButton={},dismissButton={TextButton(onClick=onDismiss) { Text("取消") }})
}

@Composable
fun FolderBooksDialog(folder: LibraryFolder, books: List<LibraryBook>, folders: List<LibraryFolder>, onSave:(Set<String>)->Unit, onDismiss:()->Unit) {
    var selected by remember(folder.id) { mutableStateOf(books.filter { it.folderId==folder.id }.map { it.id }.toSet()) }
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text("整理：${folder.name}")},text={Column {
        Text("勾选的书归入此文件夹；取消勾选的原有书籍回到书架。")
        OutlinedTextField(query,{query=it},singleLine=true,label={Text("搜索书名")},modifier=Modifier.fillMaxWidth())
        LazyColumn(Modifier.heightIn(max=360.dp)) {
            items(books.filter { it.title.contains(query,true) },key={it.id}) { book ->
                val toggle={selected=if(book.id in selected) selected-book.id else selected+book.id}
                Row(Modifier.fillMaxWidth().clickable(onClick=toggle).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(book.id in selected,{toggle()},modifier=Modifier.semantics { contentDescription="选择书籍：${book.title}" })
                    Column(Modifier.weight(1f)) {
                        Text(book.title,maxLines=2)
                        Text(folders.firstOrNull { it.id==book.folderId }?.name ?: "书架",style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }},confirmButton={TextButton(onClick={onSave(selected)}) { Text("保存（${selected.size}）") }},
        dismissButton={TextButton(onClick=onDismiss) { Text("取消") }})
}

@Composable
fun FolderCoverDialog(folder: LibraryFolder,onChanged:()->Unit,onDismiss:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            busy=true;error=null
            try { source=withContext(Dispatchers.IO) { CoverEditor.source(context,uri) };zoom=1f;x=0f;y=0f }
            catch(e:Exception) { if(e is CancellationException) throw e;error=e.message ?: "图片读取失败" }
            finally { busy=false }
        }
    }
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text("文件夹封面")},text={Column {
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        source?.let { bitmap ->
            Text("拖动调整位置，双指或滑条缩放")
            Canvas(Modifier.height(230.dp).aspectRatio(.72f).pointerInput(bitmap) {
                detectTransformGestures { _,pan,scale,_->zoom=(zoom*scale).coerceIn(1f,4f);x=(x-pan.x/size.width*2).coerceIn(-1f,1f);y=(y-pan.y/size.height*2).coerceIn(-1f,1f) }
            }) {
                drawRect(androidx.compose.ui.graphics.Color.White)
                drawContext.canvas.nativeCanvas.drawBitmap(bitmap,CoverEditor.cropRect(bitmap.width,bitmap.height,zoom,x,y),
                    RectF(0f,0f,size.width,size.height),Paint(Paint.FILTER_BITMAP_FLAG))
            }
            Text("缩放");Slider(zoom,{zoom=it},valueRange=1f..4f)
            Text("水平位置");Slider(x,{x=it},valueRange=-1f..1f)
            Text("垂直位置");Slider(y,{y=it},valueRange=-1f..1f)
        }
        TextButton(onClick={picker.launch(arrayOf("image/*"))},enabled=!busy) { Text("选择封面图片") }
        TextButton(onClick={LibraryRepository(context).setFolderCover(folder.id,null);onChanged();onDismiss()},enabled=!busy && folder.coverPath!=null) { Text("恢复默认拼图") }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={TextButton(enabled=source!=null && !busy,onClick={source?.let { bitmap -> scope.launch {
        busy=true
        try {
            val path=withContext(Dispatchers.IO) { CoverEditor.save(context,"folder-${folder.id}",bitmap,zoom,x,y) }
            LibraryRepository(context).setFolderCover(folder.id,path);onChanged();onDismiss()
        } catch(e:Exception) { if(e is CancellationException) throw e;error=e.message ?: "封面保存失败" }
        finally { busy=false }
    } }}) { Text("保存封面") }},dismissButton={TextButton(onClick=onDismiss,enabled=!busy) { Text("取消") }})
}
