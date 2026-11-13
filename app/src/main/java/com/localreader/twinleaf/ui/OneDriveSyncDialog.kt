package com.localreader.twinleaf.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localreader.twinleaf.data.LibraryBook
import com.localreader.twinleaf.sync.BookSyncMode
import com.localreader.twinleaf.sync.CloudBook
import com.localreader.twinleaf.sync.DeviceAuthorization
import com.localreader.twinleaf.sync.OneDriveSyncManager
import com.localreader.twinleaf.sync.SyncConflictInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OneDriveSyncDialog(books:List<LibraryBook>,onDismiss:()->Unit,onChanged:()->Unit) {
    val context=LocalContext.current
    val manager=remember(context) { OneDriveSyncManager(context) }
    val scope=rememberCoroutineScope()
    val currentOnChanged by rememberUpdatedState(onChanged)
    var clientId by remember { mutableStateOf(manager.auth.clientId) }
    var signedIn by remember { mutableStateOf(manager.auth.isSignedIn) }
    var authorization by remember { mutableStateOf<DeviceAuthorization?>(null) }
    var enabled by remember { mutableStateOf(manager.preferences.enabled) }
    var progress by remember { mutableStateOf(manager.preferences.syncProgress) }
    var ink by remember { mutableStateOf(manager.preferences.syncInk) }
    var notes by remember { mutableStateOf(manager.preferences.syncNotes) }
    var wifiOnly by remember { mutableStateOf(manager.preferences.wifiOnly) }
    var modes by remember(books) { mutableStateOf(books.associate { it.id to manager.preferences.bookMode(it.id) }) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf(manager.preferences.lastStatus) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastConfirmed by remember { mutableStateOf(manager.preferences.lastSyncAt) }
    var cloudBooks by remember { mutableStateOf<List<CloudBook>>(emptyList()) }
    var cloudLoaded by remember { mutableStateOf(false) }
    var conflicts by remember { mutableStateOf<List<SyncConflictInfo>>(emptyList()) }
    var resolution by remember { mutableStateOf<Pair<SyncConflictInfo,Boolean>?>(null) }
    val openBrowser:(String)->Unit={ url -> runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { error="无法打开浏览器：${it.message}" } }
    val close:()->Unit={ operation?.cancel();onDismiss() }

    fun saveOptions() {
        manager.preferences.enabled=enabled;manager.preferences.syncProgress=progress
        manager.preferences.syncInk=ink;manager.preferences.syncNotes=notes
        manager.preferences.wifiOnly=wifiOnly
    }
    fun runOperation(initialStatus:String,action:suspend ()->Unit) {
        if(busy) return
        busy=true;error=null;status=initialStatus
        operation=scope.launch {
            try { action() }
            catch(cancelled:CancellationException) {
                status="操作已取消；已完成的变更会保留"
                throw cancelled
            }
            catch(t:Exception) { error=t.message ?: "操作失败";status="本次操作未完成，请查看错误后重试" }
            finally {
                authorization=null
                signedIn=manager.auth.isSignedIn
                enabled=manager.preferences.enabled
                lastConfirmed=manager.preferences.lastSyncAt
                modes=books.associate { it.id to manager.preferences.bookMode(it.id) }
                busy=false;operation=null
                currentOnChanged()
            }
        }
    }
    fun beginLogin() {
        if(busy) return
        manager.auth.clientId=clientId
        runOperation("正在获取登录代码…") {
            val code=withContext(Dispatchers.IO) { manager.auth.beginSignIn() }
            authorization=code;status="等待你在 Microsoft 页面完成授权…";openBrowser(code.verificationUri)
            withContext(Dispatchers.IO) { manager.auth.awaitSignIn(code) }
            signedIn=true;authorization=null;enabled=true;saveOptions();status="OneDrive 已连接"
            conflicts=manager.listConflicts()
        }
    }
    fun synchronize() {
        saveOptions()
        runOperation("准备同步…") {
            val report=manager.synchronize { status=it }
            status=when {
                report.errors.isNotEmpty() -> "同步部分完成：更新 ${report.changed} 项，${report.errors.size} 项失败，${report.conflicts} 项冲突"
                report.conflicts>0 -> "同步完成，${report.conflicts} 项冲突等待处理；更新 ${report.changed} 项"
                else -> "同步完成：更新 ${report.changed} 项，读取 ${report.downloaded} 个设备快照"
            }
            error=report.errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
            conflicts=manager.listConflicts()
        }
    }
    fun refreshCloud() {
        runOperation("正在读取云端书库…") {
            cloudBooks=manager.listCloudBooks();cloudLoaded=true
            conflicts=manager.listConflicts()
            status="云端书库已刷新，共 ${cloudBooks.size} 项"
        }
    }
    LaunchedEffect(manager,signedIn) {
        if(signedIn) {
            try { conflicts=manager.listConflicts() }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(t:Exception) { error="无法读取冲突：${t.message}" }
        }
    }

    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=900.dp).fillMaxWidth(.94f).fillMaxHeight(.94f),shape=RoundedCornerShape(28.dp),tonalElevation=8.dp) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(if(signedIn) Icons.Rounded.CloudSync else Icons.Rounded.CloudOff,null,
                        tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(28.dp))
                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                        Text("OneDrive 同步",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                        Text("按书选择同步范围；原书上传需单独开启",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
                    }
                    IconButton(onClick=close) { Icon(Icons.Rounded.Close,"关闭并取消当前操作") }
                }
                HorizontalDivider(Modifier.padding(vertical=12.dp))
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(!signedIn) {
                        item {
                            Text("连接设置",fontWeight=FontWeight.SemiBold)
                            Text("在 Microsoft Entra 注册“移动和桌面应用”，打开“允许公共客户端流”，并授予 Files.ReadWrite.AppFolder 委托权限。然后粘贴应用 Client ID。",
                                color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp,lineHeight=20.sp)
                        }
                        item { OutlinedTextField(clientId,{clientId=it.trim().take(80)},Modifier.fillMaxWidth(),enabled=!busy,
                            label={Text("Microsoft Entra Client ID")},singleLine=true,
                            supportingText={Text("Client ID 不是密码，可以随开源项目公开；应用不会保存客户端密钥。")}) }
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick={openBrowser("https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade")},enabled=!busy) {
                                    Icon(Icons.Rounded.Launch,null);Spacer(Modifier.width(6.dp));Text("打开 Entra")
                                }
                                Button(onClick={beginLogin()},enabled=!busy && clientId.isNotBlank()) { Text("获取登录代码") }
                            }
                        }
                        authorization?.let { code -> item {
                            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                Text("Microsoft 登录代码",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(code.userCode,fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=8.dp))
                                // Keep authorization helpers usable while the login poll is pending.
                                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick={
                                        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Microsoft 登录代码",code.userCode))
                                    }) { Icon(Icons.Rounded.ContentCopy,null);Spacer(Modifier.width(6.dp));Text("复制") }
                                    OutlinedButton(onClick={openBrowser(code.verificationUri)}) {
                                        Icon(Icons.Rounded.Launch,null);Spacer(Modifier.width(6.dp));Text("打开登录页")
                                    }
                                }
                            } }
                        } }
                    } else {
                        item {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("启用云同步",fontWeight=FontWeight.Medium)
                                    Text("关闭后不会进行任何网络同步",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked=enabled,onCheckedChange={enabled=it;saveOptions()},enabled=!busy)
                            }
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("仅通过 Wi-Fi 传输")
                                    Text("默认开启，原书可能占用较多流量",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked=wifiOnly,onCheckedChange={wifiOnly=it;saveOptions()},enabled=enabled && !busy)
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected=progress,onClick={progress=!progress;saveOptions()},label={Text("阅读进度")},enabled=enabled && !busy)
                                FilterChip(selected=ink,onClick={ink=!ink;saveOptions()},label={Text("PDF 批注")},enabled=enabled && !busy)
                                FilterChip(selected=notes,onClick={notes=!notes;saveOptions()},label={Text("无限笔记")},enabled=enabled && !busy)
                            }
                        }
                        item {
                            Text("本机内容的同步范围",fontWeight=FontWeight.SemiBold)
                            Text("“含原书”会上传 PDF / EPUB 文件，供其他设备下载。改为“仅本地”只停止后续同步，不会删除云端已有内容。",
                                fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(books.isEmpty()) Text("本机尚无书籍，可以从下方云端书库下载。",fontSize=13.sp,modifier=Modifier.padding(top=8.dp))
                        }
                        items(books,key={"local-${it.id}"}) { book ->
                            val isNote=book.format.equals("NOTE",true)
                            val mode=modes[book.id] ?: BookSyncMode.LOCAL_ONLY
                            Column(Modifier.fillMaxWidth().padding(vertical=4.dp)) {
                                Text(displayTitle(book),maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(if(isNote) "无限笔记" else book.format,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    val choices=if(isNote) listOf(BookSyncMode.LOCAL_ONLY,BookSyncMode.METADATA) else BookSyncMode.entries
                                    choices.forEach { choice ->
                                        FilterChip(selected=mode==choice,onClick={
                                            manager.preferences.setBookMode(book.id,choice)
                                            modes=modes+(book.id to choice)
                                        },enabled=enabled && !busy,label={Text(when(choice) {
                                            BookSyncMode.LOCAL_ONLY -> "仅本地"
                                            BookSyncMode.METADATA -> if(isNote) "同步笔记" else "进度与批注"
                                            BookSyncMode.WITH_SOURCE -> "含原书"
                                        })})
                                    }
                                }
                            }
                        }
                        item {
                            HorizontalDivider(Modifier.padding(vertical=4.dp))
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("云端书库",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                                OutlinedButton(onClick={refreshCloud()},enabled=enabled && !busy) { Text("刷新书库") }
                            }
                            Text("刷新后选择要下载到本机的内容；新设备无需先导入原书。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(cloudLoaded && cloudBooks.isEmpty()) Text("云端暂无可用内容。",fontSize=13.sp)
                        }
                        items(cloudBooks,key={"cloud-${it.key}"}) { book ->
                            val isNote=book.format.equals("NOTE",true)
                            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(book.title,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(listOf(book.format,book.deviceName).filter { it.isNotBlank() }.joinToString(" · "),fontSize=11.sp,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(when {
                                    isNote -> "无限笔记可恢复到本机"
                                    book.hasSource -> "含原书 · ${syncFileSize(book.sourceBytes)}"
                                    else -> "仅进度与批注；请先在本机导入相同原书"
                                },fontSize=12.sp)
                                OutlinedButton(onClick={
                                    runOperation("正在下载 ${book.title}…") {
                                        manager.downloadCloudBook(book.key) { status=it }
                                        status="已下载到本机：${book.title}"
                                        conflicts=manager.listConflicts()
                                    }
                                },enabled=enabled && !busy && (isNote || book.hasSource)) { Text(if(isNote) "恢复笔记" else "下载到本机") }
                            } }
                        }
                        if(conflicts.isNotEmpty()) {
                            item {
                                HorizontalDivider(Modifier.padding(vertical=4.dp))
                                Text("待处理冲突（${conflicts.size}）",fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.error)
                                Text("两台设备都修改了同一内容，请选择要保留的版本。冲突备份会保留。",fontSize=12.sp)
                            }
                            items(conflicts,key={"conflict-${it.id}"}) { conflict ->
                                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text(conflict.title,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text("${syncChannelLabel(conflict.channel)} · ${conflict.deviceName}\n${syncTime(conflict.createdAt)}",fontSize=12.sp,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick={resolution=conflict to false},enabled=!busy) { Text("保留本机") }
                                        TextButton(onClick={resolution=conflict to true},enabled=!busy) { Text("使用云端") }
                                    }
                                } }
                            }
                        }
                    }
                    error?.let { message -> item { Text(message,color=MaterialTheme.colorScheme.error,fontSize=13.sp) } }
                }
                if(status.isNotBlank() || busy) {
                    Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                        Text("本次／最近操作：$status",Modifier.weight(1f).padding(start=8.dp),fontSize=13.sp,
                            color=if(error==null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            maxLines=3,overflow=TextOverflow.Ellipsis)
                        if(busy) TextButton(onClick={operation?.cancel()}) { Text("取消操作") }
                    }
                }
                if(signedIn) {
                    Text(if(lastConfirmed==0L) "云端确认：尚未成功同步" else "云端确认：${syncTime(lastConfirmed)}",
                        Modifier.padding(top=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.End) {
                        TextButton(onClick={
                            manager.auth.signOut();signedIn=false;authorization=null
                            enabled=false;lastConfirmed=0L;modes=books.associate { it.id to BookSyncMode.LOCAL_ONLY }
                            cloudBooks=emptyList();cloudLoaded=false;conflicts=emptyList();status="已断开 OneDrive"
                        },enabled=!busy) { Text("退出登录") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick={synchronize()},enabled=enabled && !busy) {
                            Icon(Icons.Rounded.CloudSync,null);Spacer(Modifier.width(8.dp));Text("立即同步")
                        }
                    }
                }
            }
        }
    }
    resolution?.let { (conflict,useRemote) ->
        AlertDialog(onDismissRequest={resolution=null},title={Text(if(useRemote) "使用云端版本？" else "保留本机版本？")},
            text={Text("${conflict.title} · ${syncChannelLabel(conflict.channel)}\n"+
                if(useRemote) "此操作会用云端版本替换本机当前内容。冲突备份会保留，以便恢复。"
                else "此操作会保留本机当前内容，并在后续同步时上传。冲突备份会保留，以便恢复。")},
            confirmButton={TextButton(onClick={
                resolution=null
                runOperation("正在处理冲突…") {
                    manager.resolveConflict(conflict.id,useRemote)
                    conflicts=manager.listConflicts()
                    status=if(useRemote) "已使用云端版本，冲突备份已保留" else "已保留本机版本，冲突备份已保留"
                }
            },enabled=!busy) { Text("确认") }},
            dismissButton={TextButton(onClick={resolution=null}) { Text("取消") }})
    }
}

private fun syncTime(value:Long):String = DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(value))

private fun syncFileSize(value:Long):String = when {
    value>=1024L*1024*1024 -> String.format(Locale.getDefault(),"%.1f GB",value/(1024.0*1024*1024))
    value>=1024L*1024 -> String.format(Locale.getDefault(),"%.1f MB",value/(1024.0*1024))
    value>=1024L -> String.format(Locale.getDefault(),"%.1f KB",value/1024.0)
    else -> "$value B"
}

private fun syncChannelLabel(channel:String):String = when(channel.lowercase()) {
    "progress", "position" -> "阅读进度"
    "ink" -> "PDF 批注"
    "note", "notes" -> "无限笔记"
    else -> channel
}
