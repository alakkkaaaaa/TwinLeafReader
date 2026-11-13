package com.localreader.twinleaf.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localreader.twinleaf.data.*
import kotlinx.coroutines.*

@Composable
fun BookManagementDialog(book:LibraryBook,onChanged:()->Unit,onDismiss:()->Unit,onRemove:()->Unit,onMoveToFolder:()->Unit={}) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val repository=remember(context) { LibraryRepository(context) }
    var category by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var pdfPage by remember { mutableStateOf<String?>(null) }
    var renameNote by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) };var x by remember { mutableFloatStateOf(0f) };var y by remember { mutableFloatStateOf(0f) }
    fun load(block:suspend ()->Bitmap) { scope.launch {
        loading=true;error=null
        try { source=withContext(Dispatchers.IO) { block() };zoom=1f;x=0f;y=0f }
        catch(e:Exception) { if(e is CancellationException) throw e;error=e.message ?: "封面读取失败" }
        finally { loading=false }
    } }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->uri?.let { load { CoverEditor.source(context,it) } } }
    if(source==null) AlertDialog(onDismissRequest={if(!loading) onDismiss()},title={Text(book.title)},text={Column {
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        TextButton(onClick={picker.launch(arrayOf("image/*"))},enabled=!loading) { Text("从图片更换封面") }
        if(book.format.equals("PDF",true)) TextButton(onClick={pdfPage="1"},enabled=!loading) { Text("选择 PDF 页面作封面") }
        if(book.format.equals("NOTE",true)) TextButton(onClick={renameNote=book.title},enabled=!loading) { Text("重命名笔记") }
        TextButton(onClick={repository.setCustomCover(book.id,null);onChanged();onDismiss()},enabled=!loading && book.customCoverPath!=null) { Text("恢复原始封面") }
        TextButton(onClick={category=true},enabled=!loading) { Text("修改分类 · ${repository.categoryName(book.category)}") }
        TextButton(onClick=onMoveToFolder,enabled=!loading) { Text("移动到文件夹") }
        TextButton(onClick={onDismiss();onRemove()},enabled=!loading) { Text(if(book.format.equals("NOTE",true)) "删除笔记" else "移出书架") }
    }},confirmButton={TextButton(onClick=onDismiss,enabled=!loading) { Text("关闭") }})
    if(category) CategoryDialog(repository.categories(),book.category,
        onSelect={repository.categorize(setOf(book.id),it);onChanged();onDismiss()},onDismiss={category=false})
    renameNote?.let { input -> AlertDialog(onDismissRequest={renameNote=null},title={Text("重命名笔记")},
        text={OutlinedTextField(input,{renameNote=it.take(80)},singleLine=true,label={Text("笔记名称")},
            modifier=Modifier.semantics { contentDescription="笔记名称" })},
        confirmButton={TextButton(onClick={repository.renameNote(book.id,input);renameNote=null;onChanged();onDismiss()},
            enabled=input.trim().isNotEmpty()) { Text("保存") }},
        dismissButton={TextButton(onClick={renameNote=null}) { Text("取消") }}) }
    pdfPage?.let { input->AlertDialog(onDismissRequest={pdfPage=null},title={Text("输入 PDF 实际页码")},
        text={OutlinedTextField(input,{pdfPage=it.filter(Char::isDigit).take(6)},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.semantics { contentDescription="封面 PDF 页码" })},
        confirmButton={TextButton(onClick={val page=input.toIntOrNull();if(page!=null && page>0) { pdfPage=null;load {
            BookSessionFactory.open(context,Uri.parse(book.uri)).use { session->
                val pdf=session as? PdfDocument ?: error("不是 PDF")
                require(page<=pdf.pageCount) { "页码必须在 1 – ${pdf.pageCount}" }
                val ratio=pdf.pageAspectRatio(page-1).coerceIn(.1f,10f)
                pdf.renderPage(page-1,1400,(1400/ratio).toInt().coerceAtMost(5000))
            }
        } }},enabled=input.toIntOrNull()?.let { it>0 }==true) { Text("预览") }},
        dismissButton={TextButton(onClick={pdfPage=null}) { Text("取消") }}) }
    source?.let { bitmap->AlertDialog(onDismissRequest={if(!loading) source=null},title={Text("裁剪封面")},
        text={Column {
            Text("拖动移动，双指缩放；也可使用下方滑条。")
            Canvas(Modifier.height(270.dp).aspectRatio(.72f).pointerInput(bitmap) {
                detectTransformGestures { _,pan,scale,_->zoom=(zoom*scale).coerceIn(1f,4f);x=(x-pan.x/size.width*2).coerceIn(-1f,1f);y=(y-pan.y/size.height*2).coerceIn(-1f,1f) }
            }) {
                drawRect(androidx.compose.ui.graphics.Color.White)
                drawContext.canvas.nativeCanvas.drawBitmap(bitmap,CoverEditor.cropRect(bitmap.width,bitmap.height,zoom,x,y),
                    RectF(0f,0f,size.width,size.height),Paint(Paint.FILTER_BITMAP_FLAG))
            }
            Text("缩放");Slider(zoom,{zoom=it},valueRange=1f..4f)
            Text("水平位置");Slider(x,{x=it},valueRange=-1f..1f)
            Text("垂直位置");Slider(y,{y=it},valueRange=-1f..1f)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }},confirmButton={TextButton(onClick={scope.launch {
            loading=true
            try { val path=withContext(Dispatchers.IO) { CoverEditor.save(context,book.id,bitmap,zoom,x,y) }
                repository.setCustomCover(book.id,path);onChanged();onDismiss()
            } catch(e:Exception) { if(e is CancellationException) throw e;error=e.message } finally { loading=false }
        }},enabled=!loading) { Text("保存封面") }},dismissButton={TextButton(onClick={source=null},enabled=!loading) { Text("取消") }}) }
}
