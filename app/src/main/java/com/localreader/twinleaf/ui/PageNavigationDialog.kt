package com.localreader.twinleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.localreader.twinleaf.data.PageShortcut
import com.localreader.twinleaf.data.PageShortcutRepository
import com.localreader.twinleaf.data.validPage

@Composable
fun PageNavigationDialog(bookKey:String,currentPage:Int,pageCount:Int,chapter:Int=-1,night:Boolean=false,
    initialOutline:Boolean=false, chapterCount:Int=1,
    builtinOutline:List<com.localreader.twinleaf.data.OutlineEntry> = emptyList(),
    currentBuiltinId:String?=null,
    targetForPage:(Int)->PageShortcut, onJump:(PageShortcut)->Unit, onDismiss:()->Unit) {
    if(initialOutline) {
        BookOutlineDialog(bookKey,currentPage,pageCount,chapter,chapterCount,night,
            builtinOutline=builtinOutline,
            currentBuiltinId=currentBuiltinId,
            onJump={entry->onJump(targetForPage(entry.page).copy(chapter=entry.chapter,anchor=entry.anchor))},onDismiss=onDismiss)
        return
    }
    val context=LocalContext.current
    val repository=remember(context) { PageShortcutRepository(context) }
    var shortcuts by remember(bookKey) { mutableStateOf(repository.load(bookKey)) }
    var input by remember { mutableStateOf(currentPage.coerceIn(1,pageCount.coerceAtLeast(1)).toString()) }
    val target=validPage(input,pageCount)
    val color=if(night) Color(0xFFDCE7FF) else Color(0xFF364E76)
    fun jump(item:PageShortcut) { onJump(item); onDismiss() }
    Dialog(onDismissRequest=onDismiss) {
        Surface(shape=RoundedCornerShape(24.dp),color=if(night) Color(0xFF242B38) else Color(0xFFF7FAFF),
            contentColor=color,modifier=Modifier.fillMaxWidth().widthIn(max=440.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Numbers,"页码跳转")
                    Text(if(chapter<0) "$currentPage / $pageCount" else "${chapter+1} · $currentPage / $pageCount",
                        Modifier.weight(1f).padding(start=12.dp))
                    IconButton(onClick=onDismiss) { Icon(Icons.Rounded.Close,"关闭页码跳转") }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(value=input,onValueChange={input=it.filter { ch->ch in '0'..'9' }.take(9)},
                        modifier=Modifier.weight(1f).semantics { contentDescription=if(chapter<0) "输入页码" else "输入当前章节内页码" },
                        singleLine=true,isError=input.isNotEmpty() && target==null,
                        placeholder={Text("1 – $pageCount")},supportingText={Text("1 – $pageCount")},
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Go),
                        keyboardActions=KeyboardActions(onGo={target?.let { jump(targetForPage(it)) }}),
                        colors=OutlinedTextFieldDefaults.colors(focusedTextColor=color,unfocusedTextColor=color))
                    IconButton(onClick={target?.let { jump(targetForPage(it)) }},enabled=target!=null) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward,"跳到输入页码")
                    }
                    IconButton(onClick={target?.let { shortcuts=repository.add(bookKey,targetForPage(it)) }},enabled=target!=null) {
                        Icon(Icons.Rounded.BookmarkAdd,"保存输入页码为快捷页")
                    }
                }
                HorizontalDivider(Modifier.padding(vertical=10.dp),color=color.copy(alpha=.12f))
                Icon(Icons.Rounded.Bookmarks,"已保存的快捷页",tint=color.copy(alpha=.65f))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=280.dp).padding(top=8.dp)) {
                    items(shortcuts,key={it.id}) { shortcut ->
                        Row(Modifier.fillMaxWidth().padding(vertical=3.dp)
                            .background(color.copy(alpha=.06f),RoundedCornerShape(12.dp)),verticalAlignment=Alignment.CenterVertically) {
                            Text(shortcut.label,Modifier.weight(1f).clickable { jump(shortcut) }
                                .semantics { contentDescription="跳转快捷页 ${shortcut.label}" }.padding(16.dp))
                            IconButton(onClick={shortcuts=repository.remove(bookKey,shortcut.id)}) {
                                Icon(Icons.Rounded.DeleteOutline,"移除快捷页 ${shortcut.label}",Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
