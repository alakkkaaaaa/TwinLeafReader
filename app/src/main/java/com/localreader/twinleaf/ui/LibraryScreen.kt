package com.localreader.twinleaf.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localreader.twinleaf.R
import com.localreader.twinleaf.data.LibraryBook
import com.localreader.twinleaf.data.LibraryRepository
import com.localreader.twinleaf.data.LibraryFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import com.localreader.twinleaf.data.sortedFolderBooks
import com.localreader.twinleaf.data.normalizedShelfOrder
import com.localreader.twinleaf.ui.theme.ThemeCycleButton
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Category
import com.localreader.twinleaf.sync.OneDriveSyncManager
import kotlinx.coroutines.CancellationException

private val ShelfInk:Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val ShelfMuted:Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Ocean:Color @Composable get() = MaterialTheme.colorScheme.primary

@Composable
fun LibraryScreen(books: List<LibraryBook>, onOpen: (LibraryBook) -> Unit, onAdd: (String?) -> Unit,
    onRemove: (LibraryBook) -> Unit,onChanged:()->Unit = {},
    folderId: String? = null, onFolderChange: (String?) -> Unit = {},
    onCreateNote:(String,String?)->Unit = {_,_->}) {
    val context=LocalContext.current
    val repository=remember(context) { LibraryRepository(context) }
    var folders by remember { mutableStateOf(repository.folders()) }
    var categories by remember { mutableStateOf(repository.categories()) }
    var rootOrder by remember { mutableStateOf(repository.rootShelfOrder()) }
    val currentFolder=folders.firstOrNull { it.id==folderId }
    val addBook={onAdd(currentFolder?.id)}
    fun refresh() { folders=repository.folders();categories=repository.categories();rootOrder=repository.rootShelfOrder();onChanged() }
    var creatingFolder by remember { mutableStateOf(false) }
    var creatingNote by remember { mutableStateOf(false) }
    var noteTitle by remember { mutableStateOf("") }
    var sorting by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var oneDriveOpen by remember { mutableStateOf(false) }
    val oneDrive=remember(context) { OneDriveSyncManager(context) }
    LaunchedEffect(Unit) {
        if(oneDrive.preferences.enabled && oneDrive.auth.isSignedIn && oneDrive.preferences.selectedBookIds().isNotEmpty()) {
            try {
                oneDrive.synchronize()
                onChanged()
            } catch(cancelled:CancellationException) {
                throw cancelled
            } catch(_:Exception) {
                // The persistent status in the sync panel explains background failures.
            }
        }
    }
    if(oneDriveOpen) OneDriveSyncDialog(books,onDismiss={oneDriveOpen=false},onChanged=onChanged)
    if(sorting && currentFolder!=null) FolderSortDialog(currentFolder,books.filter { it.folderId==currentFolder.id },
        onSave={sort,descending,order -> repository.updateFolderSort(currentFolder.id,sort,descending,order);refresh();sorting=false},
        onDismiss={sorting=false})
    if(sorting && currentFolder==null) ShelfOrderDialog(repository.rootShelfOrder(), books, folders,
        onSave={repository.reorderRootEntries(it);refresh();sorting=false}, onDismiss={sorting=false})
    var editingFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var managingFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var deletingFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var organizingFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var coverFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var movingBooks by remember { mutableStateOf<Set<String>?>(null) }
    var managing by remember { mutableStateOf<LibraryBook?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryManager by remember { mutableStateOf(false) }
    var formatMenu by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var batchCategory by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<LibraryBook?>(null) }
    fun navigate(id:String?) { selected=emptySet();selecting=false;onFolderChange(id) }
    BackHandler(selecting || currentFolder!=null) { if(selecting) { selecting=false;selected=emptySet() } else navigate(null) }
    if(creatingFolder || editingFolder!=null) FolderNameDialog(editingFolder,onSave={name ->
        val editing=editingFolder
        if(editing==null) repository.createFolder(name) else repository.renameFolder(editing.id,name)
        refresh();creatingFolder=false;editingFolder=null
    },onDismiss={creatingFolder=false;editingFolder=null})
    if(creatingNote) AlertDialog(onDismissRequest={creatingNote=false},title={Text("新建无限笔记")},
        text={OutlinedTextField(noteTitle,{noteTitle=it.take(80)},singleLine=true,label={Text("笔记名称")})},
        confirmButton={TextButton(onClick={val title=noteTitle.trim().ifBlank { "未命名笔记" };creatingNote=false;noteTitle="";onCreateNote(title,currentFolder?.id)}) { Text("创建") }},
        dismissButton={TextButton(onClick={creatingNote=false}) { Text("取消") }})
    movingBooks?.let { ids -> FolderDestinationDialog(folders,onSelect={destination ->
        repository.moveToFolder(ids,destination);refresh();movingBooks=null;selected=emptySet();selecting=false
    },onDismiss={movingBooks=null}) }
    managingFolder?.let { folder -> AlertDialog(onDismissRequest={managingFolder=null},title={Text(folder.name)},
        text={Column {
            TextButton(onClick={managingFolder=null;coverFolder=folder}) { Text("更换文件夹封面") }
            TextButton(onClick={managingFolder=null;editingFolder=folder}) { Text("重命名文件夹") }
            TextButton(onClick={managingFolder=null;deletingFolder=folder}) { Text("删除文件夹") }
        }},confirmButton={TextButton(onClick={managingFolder=null}) { Text("关闭") }}) }
    organizingFolder?.let { folder -> FolderBooksDialog(folder,books,folders,onSave={ids ->
        repository.setFolderBooks(folder.id,ids);refresh();organizingFolder=null
    },onDismiss={organizingFolder=null}) }
    coverFolder?.let { folder -> FolderCoverDialog(folder,onChanged={refresh()},onDismiss={coverFolder=null}) }
    deletingFolder?.let { folder -> AlertDialog(onDismissRequest={deletingFolder=null},title={Text("删除文件夹？")},
        text={Text("“${folder.name}”内的书籍将回到书架。原文件、阅读进度、封面和大纲均保留。")},
        confirmButton={TextButton(onClick={repository.deleteFolder(folder.id);refresh();if(folderId==folder.id) navigate(null);deletingFolder=null}) { Text("删除文件夹") }},
        dismissButton={TextButton(onClick={deletingFolder=null}) { Text("取消") }}) }
    managing?.let { book->BookManagementDialog(books.firstOrNull { it.id==book.id } ?: book,
        onChanged=onChanged,onDismiss={managing=null},onRemove={removing=book},
        onMoveToFolder={managing=null;movingBooks=setOf(book.id)}) }
    if(batchCategory) CategoryDialog(categories,onSelect={repository.categorize(selected,it);refresh();batchCategory=false;selected=emptySet();selecting=false},
        onDismiss={batchCategory=false},title="移动 ${selected.size} 本书到分类")
    if(categoryManager) CategoryManagerDialog(repository,categories,onChanged={category=null;refresh()},onDismiss={categoryManager=false})
    removing?.let { book ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("移出书架？") },
            text = { Text(if(book.format.equals("NOTE",true)) "《${displayTitle(book)}》及其无限画布内容将从本机删除，此操作不可恢复。"
                else "《${displayTitle(book)}》将从书架移除，阅读记录会清除。原始 PDF / EPUB 文件不会删除。") },
            confirmButton = { TextButton(onClick = { onRemove(book); removing = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
    }
    var filter by rememberSaveable { mutableStateOf("全部") }
    if(formatMenu) AlertDialog(onDismissRequest={formatMenu=false}, title={Text("格式筛选")}, text={Column {
        listOf("全部" to "全部","EPUB" to "EPUB","PDF" to "PDF","笔记" to "NOTE").forEach { (label,value) ->
            Row(Modifier.fillMaxWidth().clickable { filter=value;formatMenu=false;selected=emptySet() }.padding(vertical=6.dp),
                verticalAlignment=Alignment.CenterVertically) {
                RadioButton(filter==value,onClick={filter=value;formatMenu=false;selected=emptySet()})
                Text(label)
            }
        }
    }}, confirmButton={TextButton(onClick={formatMenu=false}) { Text("关闭") }})
    val matching = remember(books, filter,category) {
        books.filter { (filter == "全部" || it.format.equals(filter, true)) && (category==null || it.category==category) }.sortedByDescending { it.lastOpenedAt }
    }
    val candidates=matching.filter { if(currentFolder!=null) it.folderId==currentFolder.id else it.folderId==null || folders.none { folder -> folder.id==it.folderId } }
    val filtered=if(currentFolder!=null) sortedFolderBooks(candidates,currentFolder) else candidates
    val visibleFolders=if(currentFolder!=null) emptyList() else folders.filter { folder ->
        (category==null && filter=="全部") || matching.any { it.folderId==folder.id }
    }
    val visibleOrder=if(currentFolder!=null) filtered.map { "book:${it.id}" }
        else normalizedShelfOrder(rootOrder,visibleFolders.map { it.id },filtered.map { it.id })
    val recent = (if(currentFolder==null) matching else filtered).maxByOrNull { it.lastOpenedAt }
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background,MaterialTheme.colorScheme.surface)))
        .padding(horizontal = 28.dp, vertical = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ShelfBrandMark(Modifier.size(54.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("大肥鱼阅读", color = ShelfInk, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines=1)
                Text("把时间，留给喜欢的书。", color = ShelfMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp))
            }
            ThemeCycleButton()
            Box {
                FilledTonalIconButton(onClick={addMenu=true},enabled=!selecting) { Icon(Icons.Rounded.Add,"添加内容") }
                DropdownMenu(expanded=addMenu,onDismissRequest={addMenu=false}) {
                    DropdownMenuItem(text={Text(if(currentFolder==null) "导入书籍" else "导入到此文件夹")},
                        leadingIcon={Icon(Icons.Rounded.AutoStories,null)},onClick={addMenu=false;addBook()})
                    DropdownMenuItem(text={Text("新建无限笔记")},leadingIcon={Icon(Icons.Rounded.EditNote,null)},
                        onClick={addMenu=false;noteTitle="";creatingNote=true})
                    if(currentFolder==null) DropdownMenuItem(text={Text("新建文件夹")},
                        leadingIcon={Icon(Icons.Rounded.CreateNewFolder,null)},onClick={addMenu=false;creatingFolder=true})
                    else DropdownMenuItem(text={Text("从书架移入已有书籍")},
                        leadingIcon={Icon(Icons.AutoMirrored.Rounded.DriveFileMove,null)},onClick={addMenu=false;organizingFolder=currentFolder})
                }
            }
            Box {
                IconButton(onClick={moreMenu=true},enabled=!selecting) { Icon(Icons.Rounded.MoreVert,"书架更多",tint=Ocean) }
                DropdownMenu(expanded=moreMenu,onDismissRequest={moreMenu=false}) {
                    DropdownMenuItem(text={Text("OneDrive 同步")},leadingIcon={Icon(Icons.Rounded.CloudSync,null)},
                        onClick={moreMenu=false;oneDriveOpen=true})
                    HorizontalDivider()
                    DropdownMenuItem(text={Text(if(currentFolder==null) "书架排序" else "文件夹排序")},
                        leadingIcon={Icon(Icons.Rounded.Sort,null)},onClick={moreMenu=false;sorting=true})
                    DropdownMenuItem(text={Text("格式筛选")},leadingIcon={Icon(Icons.Rounded.FilterAlt,null)},onClick={moreMenu=false;formatMenu=true})
                    DropdownMenuItem(text={Text("管理分类")},leadingIcon={Icon(Icons.Rounded.Category,null)},onClick={moreMenu=false;categoryManager=true})
                    DropdownMenuItem(text={Text("批量管理")},leadingIcon={Icon(Icons.Rounded.Checklist,null)},onClick={moreMenu=false;selecting=true;selected=emptySet()})
                    if(currentFolder!=null) {
                        HorizontalDivider()
                        DropdownMenuItem(text={Text("文件夹设置")},leadingIcon={Icon(Icons.Rounded.Folder,null)},onClick={moreMenu=false;managingFolder=currentFolder})
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        if (recent != null && !selecting) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .8f))
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                .clickable { onOpen(recent) }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoStories, null, Modifier.size(25.dp), tint = Ocean)
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(if(recent.format.equals("NOTE",true)) "继续编辑" else "继续阅读", color = ShelfMuted, fontSize = 11.sp)
                    Text(displayTitle(recent), color = ShelfInk, fontWeight = FontWeight.Medium,
                        fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if(recent.format.equals("NOTE",true)) "无限画布" else String.format(Locale.ROOT, "%.1f%%", recent.progress * 100), color = Ocean, fontSize = 14.sp)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(start = 16.dp).size(20.dp), tint = Ocean)
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if(currentFolder!=null) IconButton(onClick={navigate(null)}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回书架根目录",tint=Ocean) }
            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                Text(currentFolder?.name ?: "我的书架", modifier=Modifier.weight(1f,false),color = ShelfInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("  ${if(currentFolder==null) books.size else books.count { it.folderId==currentFolder.id }}", color = ShelfMuted, fontSize = 14.sp)
            }
            if(selecting) TextButton(onClick={batchCategory=true},enabled=selected.isNotEmpty()) { Text("分类（${selected.size}）") }
            if(selecting) IconButton(onClick={movingBooks=selected},enabled=selected.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.DriveFileMove,"批量移入文件夹",tint=Ocean) }
            if(selecting) TextButton(onClick={selecting=false;selected=emptySet()}) { Text("完成") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            FilterChip(selected=category==null,onClick={category=null;selected=emptySet()},label={Text("全部")},
                colors=FilterChipDefaults.filterChipColors(selectedContainerColor=MaterialTheme.colorScheme.surfaceVariant,selectedLabelColor=Ocean))
            categories.forEach { item->FilterChip(selected=category==item.id,onClick={category=item.id;selected=emptySet()},label={Text(item.name)},
                colors=FilterChipDefaults.filterChipColors(selectedContainerColor=MaterialTheme.colorScheme.surfaceVariant,selectedLabelColor=Ocean)) }
        }
        if(filter!="全部") Text("格式：${if(filter=="NOTE") "笔记" else filter}",color=ShelfMuted,fontSize=12.sp)
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty() && visibleFolders.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                ShelfBrandMark(Modifier.size(120.dp))
                Spacer(Modifier.height(20.dp))
                Text(if(currentFolder!=null) "文件夹里还没有符合筛选的书" else if(books.isEmpty()) "第一本好书，从这里开始" else "这个分类还没有书", color = ShelfInk, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                Text("点击右上角 ＋ 添加内容", color = ShelfMuted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            }
        } else {
            ShelfDragGrid(filtered, visibleFolders, visibleOrder, "${currentFolder?.id}:$category:$filter", currentFolder != null, selecting,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onOrder = { order -> if(currentFolder==null) repository.reorderRootEntries(order)
                    else repository.reorderBooks(currentFolder.id, order.map { it.removePrefix("book:") });refresh() },
                onMove = { book, destination -> repository.moveToFolder(setOf(book.id), destination);refresh() },
                folderContent = { folder ->
                    FolderCard(folder,books.filter { it.folderId==folder.id },onOpen={navigate(folder.id)},onManage={managingFolder=folder})
                }, bookContent = { book ->
                    BookCard(book, onClick = { if(selecting) selected=if(book.id in selected) selected-book.id else selected+book.id else onOpen(book) },
                        onManage={managing=book},selecting=selecting,selected=book.id in selected)
                })
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun FolderCard(folder:LibraryFolder,books:List<LibraryBook>,onOpen:()->Unit,onManage:()->Unit) {
    val bitmap by produceState<Bitmap?>(null,folder.coverPath) {
        value=withContext(Dispatchers.IO) { folder.coverPath?.let { BitmapFactory.decodeFile(it) } }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(onClick=onOpen).padding(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.72f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha=.12f))) {
            if(bitmap!=null) Image(bitmap!!.asImageBitmap(),"${folder.name}文件夹封面",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else if(books.isEmpty()) Icon(Icons.Rounded.FolderOpen,null,Modifier.align(Alignment.Center).size(56.dp),tint=Ocean)
            else Column(Modifier.fillMaxSize().padding(7.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                repeat(2) { row -> Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    repeat(2) { col -> Box(Modifier.weight(1f)) { books.getOrNull(row*2+col)?.let { Cover(it) } } }
                } }
            }
            Icon(Icons.Rounded.Folder,null,Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(6.dp)).padding(4.dp).size(20.dp),tint=Ocean)
        }
        Text(folder.name,color=ShelfInk,fontSize=14.sp,fontWeight=FontWeight.SemiBold,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=12.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("${books.size} 本 · 系列",color=ShelfMuted,fontSize=11.sp,modifier=Modifier.weight(1f))
            IconButton(onClick=onManage) { Icon(Icons.Rounded.MoreVert,"管理文件夹：${folder.name}",tint=Ocean) }
        }
    }
}

/** Show the restored full illustration without cropping its transparent corners. */
@Composable
private fun ShelfBrandMark(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(26)).background(colorResource(R.color.launcher_background)).semantics { contentDescription="大肥鱼阅读图标" }) {
        Image(painterResource(R.mipmap.ic_launcher_whale),null,
            Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
    }
}

internal fun displayTitle(book: LibraryBook): String =
    book.title.replace(Regex("\\.(epub|pdf)$", RegexOption.IGNORE_CASE), "")

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun BookCard(book: LibraryBook, onClick: () -> Unit, onManage: () -> Unit,selecting:Boolean,selected:Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(if(selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface.copy(alpha = .9f)).clickable(onClick=onClick).padding(12.dp)) {
        if(selecting) Checkbox(selected,onCheckedChange={onClick()})
        Cover(book)
        Spacer(Modifier.height(12.dp))
        Text(displayTitle(book), color = ShelfInk, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
        Spacer(Modifier.height(10.dp))
        if(!book.format.equals("NOTE",true)) {
            LinearProgressIndicator(progress = { book.progress.coerceIn(0f,1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Ocean, trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp, drawStopIndicator = {})
            Spacer(Modifier.height(7.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if(book.format.equals("NOTE",true)) "无限画布 · 自动保存" else if (book.progress <= .001f) "未读" else "已读", color = ShelfMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            if(!book.format.equals("NOTE",true)) Text(String.format(Locale.ROOT, "%.1f%%", book.progress * 100), color = Ocean, fontSize = 11.sp)
            IconButton(onClick = onManage, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.MoreVert, "管理：${displayTitle(book)}", tint = ShelfMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Cover(book: LibraryBook) {
    val coverPath=book.customCoverPath ?: book.coverPath
    val bitmap by produceState<Bitmap?>(null, coverPath) {
        value = withContext(Dispatchers.IO) {
            coverPath?.let(::File)?.takeIf(File::isFile)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
    }
    val palettes = listOf(0xFF697DAC, 0xFF76958F, 0xFFAD917C, 0xFF8982A5)
    val coverColor = Color(palettes[Math.floorMod(book.id.hashCode(), palettes.size)])
    Box(Modifier.fillMaxWidth().aspectRatio(.72f)
        .shadow(4.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
        .background(Brush.linearGradient(listOf(coverColor, coverColor.copy(alpha = .75f)))),
        contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), "${book.title}封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if(book.format.equals("NOTE",true)) Icons.Rounded.EditNote else Icons.Rounded.AutoStories,
                    null, tint = Color.White.copy(alpha = .7f))
                Spacer(Modifier.height(16.dp))
                Text(displayTitle(book), color = Color.White, fontSize = 16.sp, lineHeight = 23.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }
        }
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(8.dp)
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .16f), Color.Transparent))))
        Text(if(book.format.equals("NOTE",true)) "笔记" else book.format.uppercase(Locale.ROOT), color = Color.White, fontSize = 9.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF26354D).copy(alpha = .65f)).padding(horizontal = 6.dp, vertical = 3.dp))
    }
}
