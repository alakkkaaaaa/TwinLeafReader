package com.localreader.twinleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.localreader.twinleaf.data.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BookOutlineDialog(bookKey:String,currentPage:Int,pageCount:Int,chapter:Int,chapterCount:Int,night:Boolean,
    builtinOutline:List<OutlineEntry> = emptyList(),
    currentBuiltinId:String?=null,
    onJump:(OutlineEntry)->Unit,onDismiss:()->Unit) {
    val context=LocalContext.current
    val repository=remember(context) { BookOutlineRepository(context) }
    var entries by remember(bookKey) { mutableStateOf(repository.load(bookKey)) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf("1") }
    var section by rememberSaveable { mutableStateOf("1") }
    var level by rememberSaveable { mutableStateOf("1") }
    var builtin by rememberSaveable { mutableStateOf(repository.builtinMode(bookKey,builtinOutline.isNotEmpty()) && builtinOutline.isNotEmpty()) }
    LaunchedEffect(builtin,bookKey) { repository.setBuiltinMode(bookKey,builtin) }
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by remember(bookKey) { mutableStateOf(repository.collapsed(bookKey)) }
    var imported by remember { mutableStateOf<List<OutlineEntry>?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
        if(uri!=null) scope.launch {
            importing=true;importError=null
            try {
                imported=withContext(Dispatchers.IO) {
                    val bytes=context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(1_000_000) } ?: error("无法读取目录文件")
                    val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    OutlineImport.parse(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString(),pageCount)
                }
            } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;importError=e.message ?: "导入失败，请使用 UTF-8 文件" }
            finally { importing=false }
        }
    }
    var deleting by remember { mutableStateOf<OutlineEntry?>(null) }
    val ink=if(night) Color(0xFFDCE7FF) else Color(0xFF364E76)
    val paper=if(night) Color(0xFF242B38) else Color(0xFFF7FAFF)
    val sameChapter=chapter<0 || section.toIntOrNull()==chapter+1
    val limit=if(sameChapter) pageCount else 999999
    val editIndex=entries.indexOfFirst { it.id==editId }.let { if(it<0) entries.size else it }
    val maxLevel=(entries.getOrNull(editIndex-1)?.level?.plus(1) ?: 1).coerceAtMost(12)
    val candidate=outlineEntry(editId,title,page,section,if(chapter<0) 0 else chapterCount,limit)
        ?.takeIf { level.toIntOrNull() in 1..maxLevel }?.copy(level=level.toInt())
    val displayed=if(builtin) builtinOutline else entries
    val visible=visibleOutline(displayed,collapsed,query)
    val active=if(builtin) currentBuiltinId ?: displayed.firstOrNull { it.chapter==chapter }?.id else
        displayed.lastOrNull { it.chapter<chapter || (it.chapter==chapter && it.page<=currentPage) }?.id
    fun edit(entry:OutlineEntry?) {
        editId=entry?.id ?: "";title=entry?.title ?: ""
        builtin=false;level=(entry?.level ?: 1).toString()
        page=(entry?.page ?: currentPage).toString();section=((entry?.chapter ?: chapter)+1).coerceAtLeast(1).toString()
        editing=true
    }
    Dialog(onDismissRequest={if(editing) editing=false else onDismiss()}) {
        Surface(Modifier.widthIn(max=520.dp).fillMaxWidth(),shape=RoundedCornerShape(24.dp),color=paper,contentColor=ink) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.List,null)
                    Text("章节大纲",Modifier.weight(1f).padding(start=12.dp),style=MaterialTheme.typography.titleMedium)
                    if(!editing) IconButton(onClick={edit(null)}) { Icon(Icons.Rounded.Add,"添加大纲章节") }
                    if(!editing && chapter<0) IconButton(onClick={picker.launch(arrayOf("text/*","application/json","application/octet-stream"))},enabled=!importing) {
                        Icon(Icons.Rounded.FileOpen,"导入外部大纲")
                    }
                    IconButton(onClick={if(editing) editing=false else onDismiss()}) { Icon(Icons.Rounded.Close,"关闭大纲") }
                }
                if(editing) {
                    val colors=OutlinedTextFieldDefaults.colors(focusedTextColor=ink,unfocusedTextColor=ink,
                        focusedLabelColor=ink,unfocusedLabelColor=ink.copy(alpha=.7f))
                    OutlinedTextField(title,{title=it.take(100)},Modifier.fillMaxWidth().semantics { contentDescription="大纲章节名称" },
                        label={Text("章节名称")},singleLine=true,colors=colors)
                    OutlinedTextField(level,{level=it.filter(Char::isDigit).take(2)},Modifier.fillMaxWidth().semantics { contentDescription="大纲层级" },
                        label={Text("层级（1 为章，2 为节）")},supportingText={Text("1 – $maxLevel")},singleLine=true,colors=colors,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    Row(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(chapter>=0) OutlinedTextField(section,{section=it.filter(Char::isDigit).take(6)},
                            Modifier.weight(1f).semantics { contentDescription="大纲章节序号" },
                            label={Text("章节序号")},supportingText={Text("1 – $chapterCount")},singleLine=true,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),colors=colors)
                        OutlinedTextField(page,{page=it.filter(Char::isDigit).take(6)},
                            Modifier.weight(1f).semantics { contentDescription="大纲起始页码" },
                            label={Text(if(chapter<0) "起始页码" else "章节内页码")},singleLine=true,
                            supportingText={Text(if(sameChapter) "1 – $pageCount" else "超出目标章节时定位至末页")},
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),colors=colors)
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        IconButton(onClick={page=currentPage.toString();section=(chapter+1).coerceAtLeast(1).toString()}) {
                            Icon(Icons.Rounded.MyLocation,"使用当前阅读位置")
                        }
                        IconButton(onClick={candidate?.let { entries=repository.put(bookKey,it);editing=false }},enabled=candidate!=null) {
                            Icon(Icons.Rounded.Check,"保存大纲章节")
                        }
                    }
                } else {
                    if(builtinOutline.isNotEmpty()) Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        FilterChip(builtin,{builtin=true},label={Text("原书目录")})
                        FilterChip(!builtin,{builtin=false},label={Text("自定义")})
                    }
                    OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("搜索章节")},
                        leadingIcon={Icon(Icons.Rounded.Search,null)},colors=OutlinedTextFieldDefaults.colors(focusedTextColor=ink,unfocusedTextColor=ink))
                    if(importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(displayed.isEmpty()) Text("点击 ＋，为每章设置名称与起始页码",Modifier.padding(vertical=28.dp),color=ink.copy(alpha=.65f))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max=420.dp)) {
                        items(visible,key={it.id}) { entry ->
                            val index=displayed.indexOf(entry)
                            val hasChildren=(displayed.getOrNull(index+1)?.level ?: 0)>entry.level
                            Row(Modifier.fillMaxWidth().padding(start=((entry.level-1)*14).dp,top=4.dp,bottom=4.dp)
                                .background(ink.copy(alpha=if(entry.id==active) .17f else .06f),RoundedCornerShape(12.dp)),verticalAlignment=Alignment.CenterVertically) {
                                if(hasChildren) IconButton(onClick={collapsed=if(entry.id in collapsed) collapsed-entry.id else collapsed+entry.id;repository.setCollapsed(bookKey,collapsed)}) {
                                    Icon(if(entry.id in collapsed) Icons.Rounded.ChevronRight else Icons.Rounded.ExpandMore,"展开折叠 ${entry.title}")
                                } else Spacer(Modifier.width(48.dp))
                                Column(Modifier.weight(1f).clickable { onJump(entry);onDismiss() }
                                    .semantics { contentDescription="跳转大纲 ${entry.title}" }.padding(14.dp)) {
                                    Text(entry.title,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(if(builtin) "章节 ${entry.chapter+1}" else entry.locationLabel,color=ink.copy(alpha=.65f),style=MaterialTheme.typography.labelMedium)
                                }
                                if(!builtin) {
                                IconButton(onClick={edit(entry)}) { Icon(Icons.Rounded.Edit,"编辑大纲 ${entry.title}",Modifier.size(20.dp)) }
                                IconButton(onClick={deleting=entry}) { Icon(Icons.Rounded.DeleteOutline,"删除大纲 ${entry.title}",Modifier.size(20.dp)) }
                                }
                            }
                        }
                    }
                    if(chapter>=0) Text("页码随字号和横竖屏重排；章首建议设为 1",Modifier.padding(top=10.dp),
                        style=MaterialTheme.typography.labelSmall,color=ink.copy(alpha=.6f))
                }
            }
        }
    }
    importError?.let { message->AlertDialog(onDismissRequest={importError=null},title={Text("目录未导入")},text={Text(message)},
        confirmButton={TextButton(onClick={importError=null}) { Text("知道了") }}) }
    imported?.let { preview->AlertDialog(onDismissRequest={imported=null},title={Text("导入预览 · ${preview.size} 项")},
        text={Column { Text("页码按 PDF 实际页序，不再叠加偏移量。替换仅影响自定义大纲。")
            LazyColumn(Modifier.heightIn(max=320.dp)) { items(preview) { e->Text("${"  ".repeat(e.level-1)}${e.title}  ·  ${e.page}",Modifier.padding(vertical=5.dp)) } } }},
        confirmButton={TextButton(onClick={entries=repository.importEntries(bookKey,preview,false);builtin=false;imported=null}) { Text("合并导入") }},
        dismissButton={Row { TextButton(onClick={entries=repository.importEntries(bookKey,preview,true);collapsed=emptySet();repository.setCollapsed(bookKey,collapsed);builtin=false;imported=null}) { Text("替换大纲") }
            TextButton(onClick={imported=null}) { Text("取消") } }}) }
    deleting?.let { entry->AlertDialog(onDismissRequest={deleting=null},containerColor=paper,titleContentColor=ink,textContentColor=ink,
        title={Text("删除大纲条目？")},text={Text(entry.title)},
        confirmButton={IconButton(onClick={entries=repository.remove(bookKey,entry.id);deleting=null}) { Icon(Icons.Rounded.Check,"确认删除大纲",tint=ink) }},
        dismissButton={IconButton(onClick={deleting=null}) { Icon(Icons.Rounded.Close,"取消删除大纲",tint=ink) }}) }
}

private fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true) { val count=read(buffer);if(count<0) break;require(output.size()+count<=limit) { "目录文件超过 1 MB" };output.write(buffer,0,count) }
    return output.toByteArray()
}
