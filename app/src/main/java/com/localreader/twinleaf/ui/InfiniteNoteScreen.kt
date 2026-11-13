package com.localreader.twinleaf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.localreader.twinleaf.R
import com.localreader.twinleaf.data.LibraryBook
import com.localreader.twinleaf.ink.InkBeautifyLevel
import com.localreader.twinleaf.ink.StraightenSensitivity
import com.localreader.twinleaf.ink.InkBrush
import com.localreader.twinleaf.ink.InkTool
import com.localreader.twinleaf.note.InfiniteNoteView
import com.localreader.twinleaf.note.NoteBackground
import com.localreader.twinleaf.note.NotePaperSettings
import com.localreader.twinleaf.note.NoteStore
import com.localreader.twinleaf.ui.components.ReaderChromePalette
import com.localreader.twinleaf.ui.components.ReaderImmersiveBars
import com.localreader.twinleaf.ui.components.readerChromePalette

@Composable
fun InfiniteNoteScreen(record:LibraryBook,onClose:()->Unit) {
    ReaderImmersiveBars()
    val context=LocalContext.current
    val store=remember(record.id) { NoteStore.get(context,record.id) }
    var collapsed by rememberSaveable(record.id) { mutableStateOf(false) }
    BackHandler { if(!store.drawing) onClose() }
    Box(Modifier.fillMaxSize().background(Color(0xFFFFFCF4))) {
        AndroidView(factory={InfiniteNoteView(it)},modifier=Modifier.fillMaxSize(),update={view ->
            view.store=store
            @Suppress("UNUSED_VARIABLE") val changed=listOf(store.revision,store.paperSettings,store.viewport,store.tool,store.ready)
            view.invalidate()
        })
        if(collapsed) NoteCollapsedHandle(store.drawing,{collapsed=false},Modifier.align(Alignment.TopCenter))
        else NoteToolbar(record.title,store,onClose,{collapsed=true},Modifier.align(Alignment.TopCenter))
        if(!store.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
        store.failure?.let { failure -> Surface(Modifier.align(Alignment.BottomCenter).padding(18.dp),shape=RoundedCornerShape(12.dp),
            color=MaterialTheme.colorScheme.errorContainer) { Text(failure,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.onErrorContainer) } }
    }
}

@Composable
private fun NoteToolbar(title:String,store:NoteStore,onClose:()->Unit,onCollapse:()->Unit,modifier:Modifier) {
    val chrome=readerChromePalette()
    var settings by remember { mutableStateOf(false) }
    var paperOpen by rememberSaveable { mutableStateOf(false) }
    var calibrationOpen by rememberSaveable { mutableStateOf(false) }
    Box(modifier.padding(start=8.dp,end=8.dp,top=3.dp).widthIn(max=1120.dp).fillMaxWidth().height(107.dp)) {
        Surface(Modifier.fillMaxWidth().height(64.dp).align(Alignment.BottomCenter),shape=RoundedCornerShape(32.dp),
            color=chrome.background,contentColor=chrome.content,shadowElevation=8.dp,border=BorderStroke(1.dp,chrome.border)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(chrome.gradientTop,chrome.gradientBottom))))
        }
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal=6.dp),verticalAlignment=Alignment.Bottom) {
            NoteAction(Icons.AutoMirrored.Rounded.ArrowBack,"返回书架",false,!store.drawing,chrome,onClose)
            NoteAction(Icons.Rounded.PanTool,"浏览与移动",store.tool==InkTool.BROWSE,!store.drawing,chrome) { store.tool=InkTool.BROWSE }
            NoteDivider(chrome)
            NoteInstrument("圆珠笔",InkBrush.BALLPOINT,store.tool==InkTool.PEN && store.brush==InkBrush.BALLPOINT,!store.drawing) { store.brush=InkBrush.BALLPOINT;store.tool=InkTool.PEN }
            NoteInstrument("钢笔",InkBrush.FOUNTAIN,store.tool==InkTool.PEN && store.brush==InkBrush.FOUNTAIN,!store.drawing) { store.brush=InkBrush.FOUNTAIN;store.tool=InkTool.PEN }
            NoteInstrument("铅笔",InkBrush.PENCIL,store.tool==InkTool.PEN && store.brush==InkBrush.PENCIL,!store.drawing) { store.brush=InkBrush.PENCIL;store.tool=InkTool.PEN }
            NoteInstrument("荧光笔",InkBrush.HIGHLIGHTER,store.tool==InkTool.HIGHLIGHT,!store.drawing) { store.brush=InkBrush.HIGHLIGHTER;store.tool=InkTool.HIGHLIGHT }
            NoteInstrument("毛笔",InkBrush.BRUSH,store.tool==InkTool.PEN && store.brush==InkBrush.BRUSH,!store.drawing) { store.brush=InkBrush.BRUSH;store.tool=InkTool.PEN }
            NoteInstrument("局部橡皮",null,store.tool==InkTool.ERASER,!store.drawing) { store.tool=InkTool.ERASER }
            NoteDivider(chrome)
            Box {
                NoteAction(Icons.Rounded.Tune,"笔记工具设置",false,!store.drawing,chrome) { settings=true }
                DropdownMenu(settings,{settings=false}) {
                    Text(title,maxLines=1,modifier=Modifier.widthIn(max=280.dp).padding(horizontal=14.dp,vertical=8.dp))
                    if(store.tool==InkTool.ERASER) {
                        Text("橡皮大小 · ${store.eraserRadiusDp.toInt()} dp",Modifier.padding(horizontal=14.dp))
                        Slider(store.eraserRadiusDp,store::updateEraserRadius,valueRange=4f..64f,modifier=Modifier.width(260.dp).padding(horizontal=14.dp))
                    } else {
                        Row(Modifier.padding(horizontal=10.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            listOf(0xFF243A68,0xFFD33F49,0xFF252525,0xFF2A9560,0xFFFFBF00).forEach { value ->
                                Box(Modifier.size(44.dp).clip(CircleShape).clickable { store.color=value.toInt() },contentAlignment=Alignment.Center) {
                                    Box(Modifier.size(28.dp).background(Color(value),CircleShape)
                                        .border(if(store.color==value.toInt()) 3.dp else 1.dp,chrome.content.copy(alpha=.65f),CircleShape))
                                }
                            }
                        }
                        Text("笔迹粗细",Modifier.padding(horizontal=14.dp))
                        Slider(store.widthDp,{store.widthDp=it},valueRange=1f..14f,modifier=Modifier.width(260.dp).padding(horizontal=14.dp))
                        Text("自动美化 · 仅影响新笔迹",Modifier.padding(horizontal=14.dp,vertical=4.dp))
                        Row(Modifier.padding(horizontal=10.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            InkBeautifyLevel.entries.forEach { level ->
                                val label=when(level) {
                                    InkBeautifyLevel.OFF -> "关"
                                    InkBeautifyLevel.LIGHT -> "轻"
                                    InkBeautifyLevel.STANDARD -> "标准"
                                    InkBeautifyLevel.STRONG -> "强"
                                }
                                FilterChip(store.beautifyLevel==level,{store.updateBeautifyLevel(level)},{Text(label)},enabled=!store.drawing)
                            }
                        }
                        Row(Modifier.width(280.dp).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("自动拉直")
                                Text("识别近似直线，抬笔后拉直",style=MaterialTheme.typography.bodySmall)
                            }
                            Switch(store.autoStraighten,store::updateAutoStraighten,enabled=!store.drawing,
                                modifier=Modifier.semantics { contentDescription="近似直线自动拉直" })
                        }
                        Text("直线识别灵敏度",Modifier.padding(horizontal=14.dp,vertical=4.dp))
                        Row(Modifier.padding(horizontal=10.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            StraightenSensitivity.entries.forEach { sensitivity ->
                                val label=when(sensitivity) {
                                    StraightenSensitivity.STEADY -> "稳健"
                                    StraightenSensitivity.STANDARD -> "标准"
                                    StraightenSensitivity.SENSITIVE -> "灵敏"
                                }
                                FilterChip(store.straightenSensitivity==sensitivity,{store.updateStraightenSensitivity(sensitivity)},
                                    {Text(label)},enabled=store.autoStraighten && !store.drawing,
                                    modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription="直线识别灵敏度：$label" })
                            }
                        }
                    }
                    DropdownMenuItem(text={Text("触笔压力校准")},leadingIcon={Icon(Icons.Rounded.Gesture,"触笔压力校准")},
                        onClick={settings=false;calibrationOpen=true},enabled=!store.drawing)
                }
            }
            NoteAction(Icons.AutoMirrored.Rounded.Undo,"撤销",false,store.canUndo && !store.drawing,chrome,store::undo)
            NoteAction(Icons.AutoMirrored.Rounded.Redo,"重做",false,store.canRedo && !store.drawing,chrome,store::redo)
            NoteAction(Icons.Rounded.GridOn,"纸张设置",paperOpen,!store.drawing && store.ready,chrome) { paperOpen=true }
            NoteAction(if(store.saveStatus=="已保存") Icons.Rounded.CloudDone else Icons.Rounded.CloudUpload,
                "保存状态：${store.saveStatus}",false,true,chrome,store::persist)
            NoteAction(Icons.Rounded.KeyboardArrowUp,"向上收起工具栏",false,!store.drawing,chrome,onCollapse)
        }
    }
    if(paperOpen) NotePaperSheet(store) { paperOpen=false }
    if(calibrationOpen) StylusCalibrationDialog { calibrationOpen=false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotePaperSheet(store:NoteStore,onDismiss:()->Unit) {
    val paper=store.paperSettings
    val enabled=store.ready && !store.drawing
    // Persist sliders when released, keeping expensive thumbnail writes off the drag path.
    var spacing by remember(paper.spacing) { mutableFloatStateOf(paper.spacing) }
    var lineOpacity by remember(paper.lineOpacity) { mutableFloatStateOf(paper.lineOpacity) }
    val preview=paper.copy(spacing=spacing,lineOpacity=lineOpacity)
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState())
            .padding(horizontal=24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("纸张设置",style=MaterialTheme.typography.titleLarge)
            Text("选择纸张，保留现有笔迹与画布位置。",style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            val patterns=listOf(NoteBackground.BLANK,NoteBackground.LINED,NoteBackground.GRID,
                NoteBackground.DOT_GRID,NoteBackground.ENGINEERING,NoteBackground.ISOMETRIC)
            patterns.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach { pattern ->
                        val selected=paper.pattern==pattern
                        val label=notePaperLabel(pattern)
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .border(if(selected) 2.dp else 1.dp,
                                if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp))
                            .clickable(enabled=enabled,role=Role.RadioButton) { store.updatePaperSettings(store.paperSettings.copy(pattern=pattern)) }
                            .semantics { contentDescription="$label 纸张";this.selected=selected }
                            .padding(6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            NotePaperPreview(preview.copy(pattern=pattern),Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(7.dp)))
                            Text(label,Modifier.padding(top=5.dp,bottom=2.dp),fontSize=13.sp,
                                color=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            val guidesEnabled=enabled && paper.pattern!=NoteBackground.BLANK
            Text("辅助线间距 · ${spacing.toInt()}",style=MaterialTheme.typography.labelLarge)
            Slider(value=spacing,onValueChange={spacing=it},
                onValueChangeFinished={store.updatePaperSettings(store.paperSettings.copy(spacing=spacing))},
                valueRange=16f..80f,enabled=guidesEnabled,
                modifier=Modifier.fillMaxWidth().semantics { contentDescription="辅助线间距" })
            Text("线条深浅 · ${(lineOpacity*100).toInt()}%",style=MaterialTheme.typography.labelLarge)
            Slider(value=lineOpacity,onValueChange={lineOpacity=it},
                onValueChangeFinished={store.updatePaperSettings(store.paperSettings.copy(lineOpacity=lineOpacity))},
                valueRange=0f..1f,enabled=guidesEnabled,
                modifier=Modifier.fillMaxWidth().semantics { contentDescription="辅助线深浅" })
            Text("背景色",style=MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                listOf("暖白" to 0xFFFFFCF4.toInt(),"纯白" to 0xFFFFFFFF.toInt(),"护眼黄" to 0xFFFFF2CC.toInt(),
                    "浅灰" to 0xFFF0F1F3.toInt(),"深色" to 0xFF252B35.toInt()).forEach { (label,value) ->
                    val selected=paper.backgroundColor==value
                    Column(Modifier.width(58.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(enabled=enabled,role=Role.RadioButton) { store.updatePaperSettings(store.paperSettings.copy(backgroundColor=value)) }
                        .semantics { contentDescription="$label 背景";this.selected=selected }
                        .padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Box(Modifier.size(36.dp).background(Color(value),CircleShape)
                            .border(if(selected) 3.dp else 1.dp,
                                if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,CircleShape))
                        Text(label,Modifier.padding(top=6.dp),fontSize=12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end=12.dp)) {
                    Text("导出辅助线",style=MaterialTheme.typography.bodyLarge)
                    Text("为后续导出保留此选项；当前版本暂不提供无限笔记导出。",style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked=paper.exportGuides,
                    onCheckedChange={store.updatePaperSettings(store.paperSettings.copy(exportGuides=it))},enabled=enabled,
                    modifier=Modifier.semantics { contentDescription="导出辅助线" })
            }
            TextButton(onClick=onDismiss,modifier=Modifier.align(Alignment.End)) { Text("完成") }
        }
    }
}

private fun notePaperLabel(pattern:NoteBackground)=when(pattern) {
    NoteBackground.BLANK->"空白"
    NoteBackground.LINED->"横线"
    NoteBackground.GRID->"方格"
    NoteBackground.DOT_GRID->"点阵"
    NoteBackground.ENGINEERING->"工程"
    NoteBackground.ISOMETRIC->"等距"
}

@Composable
private fun NotePaperPreview(paper:NotePaperSettings,modifier:Modifier) {
    Canvas(modifier) {
        val background=Color(paper.backgroundColor)
        drawRect(background)
        if(paper.pattern==NoteBackground.BLANK) return@Canvas
        val guide=(if(background.luminance()<.3f) Color(0xFFD9E4F5) else Color(0xFF526B91))
            .copy(alpha=paper.lineOpacity)
        // A constant preview scale lets spacing changes remain visible in every tile.
        val spacing=(paper.spacing*.36f).dp.toPx().coerceAtLeast(3.dp.toPx())
        val lineWidth=.7.dp.toPx()
        fun horizontal(step:Float,color:Color=guide,width:Float=lineWidth) {
            var y=step
            while(y<size.height) { drawLine(color,Offset(0f,y),Offset(size.width,y),width);y+=step }
        }
        fun vertical(step:Float,color:Color=guide,width:Float=lineWidth) {
            var x=step
            while(x<size.width) { drawLine(color,Offset(x,0f),Offset(x,size.height),width);x+=step }
        }
        when(paper.pattern) {
            NoteBackground.BLANK->Unit
            NoteBackground.LINED->horizontal(spacing)
            NoteBackground.GRID->{ horizontal(spacing);vertical(spacing) }
            NoteBackground.DOT_GRID->{
                var x=spacing
                while(x<size.width) {
                    var y=spacing
                    while(y<size.height) { drawCircle(guide,.95.dp.toPx(),Offset(x,y));y+=spacing }
                    x+=spacing
                }
            }
            NoteBackground.ENGINEERING->{
                horizontal(spacing,guide.copy(alpha=paper.lineOpacity*.55f));vertical(spacing,guide.copy(alpha=paper.lineOpacity*.55f))
                val major=guide.copy(alpha=(paper.lineOpacity*1.8f).coerceAtMost(1f))
                horizontal(spacing*5,major,1.dp.toPx());vertical(spacing*5,major,1.dp.toPx())
            }
            NoteBackground.ISOMETRIC->{
                val row=spacing*.8660254f
                horizontal(row)
                val rise=1.7320508f*size.width
                var start=-rise
                while(start<size.height+rise) {
                    drawLine(guide,Offset(0f,start),Offset(size.width,start+rise),lineWidth)
                    drawLine(guide,Offset(0f,start),Offset(size.width,start-rise),lineWidth)
                    start+=row*2
                }
            }
        }
    }
}

@Composable
private fun NoteCollapsedHandle(drawing:Boolean,onExpand:()->Unit,modifier:Modifier) {
    val chrome=readerChromePalette()
    Box(modifier.fillMaxWidth().height(48.dp),contentAlignment=Alignment.TopCenter) {
        IconButton(onClick=onExpand,enabled=!drawing,modifier=Modifier.width(88.dp).height(48.dp)) {
            Surface(Modifier.width(72.dp).height(16.dp),shape=RoundedCornerShape(bottomStart=12.dp,bottomEnd=12.dp),
                color=chrome.background,contentColor=chrome.content,shadowElevation=5.dp,border=BorderStroke(1.dp,chrome.border)) {
                Icon(Icons.Rounded.KeyboardArrowDown,"展开工具栏",Modifier.size(16.dp),tint=chrome.content)
            }
        }
    }
}

@Composable
private fun NoteAction(icon:ImageVector,label:String,selected:Boolean,enabled:Boolean,chrome:ReaderChromePalette,onClick:()->Unit) {
    IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(48.dp).offset(y=(-8).dp)
        .background(if(selected) chrome.selected else Color.Transparent,CircleShape)
        .then(if(selected) Modifier.border(1.dp,chrome.selectedBorder,CircleShape) else Modifier)
        .semantics { this.selected=selected;contentDescription=label }) {
        Icon(icon,null,Modifier.size(22.dp),tint=if(!enabled) chrome.disabled else if(selected) chrome.accent else chrome.content)
    }
}

@Composable
private fun NoteDivider(chrome:ReaderChromePalette) {
    Box(Modifier.padding(horizontal=5.dp).offset(y=(-18).dp).width(1.dp).height(28.dp)
        .background(Brush.verticalGradient(listOf(Color.Transparent,chrome.border,Color.Transparent))))
}

@Composable
private fun NoteInstrument(label:String,brush:InkBrush?,selected:Boolean,enabled:Boolean,onClick:()->Unit) {
    val atlas=ImageBitmap.imageResource(R.drawable.ink_instruments_v1)
    val index=when(brush) { InkBrush.BALLPOINT->0;InkBrush.FOUNTAIN->1;InkBrush.PENCIL->2;InkBrush.HIGHLIGHTER->3;InkBrush.BRUSH->4;else->5 }
    val crop=when(index) {
        0->intArrayOf(221,80,184,580);1->intArrayOf(508,80,177,580);2->intArrayOf(783,80,184,580)
        3->intArrayOf(1052,80,205,580);4->intArrayOf(1356,80,159,580);else->intArrayOf(1603,80,241,580)
    }
    val baseWidth=(crop[2]*82f/crop[3]).dp
    val widthScale by animateFloatAsState(if(selected) 1.18f else 1f,spring(dampingRatio=.72f,stiffness=500f),label="noteToolWidth")
    val heightScale by animateFloatAsState(if(selected) 1.3f else 1f,spring(dampingRatio=.7f,stiffness=460f),label="noteToolHeight")
    Box(Modifier.width(56.dp).height(104.dp).clickable(enabled=enabled,role=Role.Button,onClick=onClick)
        .semantics { contentDescription=label;this.selected=selected;role=Role.Button },contentAlignment=Alignment.BottomCenter) {
        Canvas(Modifier.width(baseWidth).height(82.dp).graphicsLayer {
            transformOrigin=TransformOrigin(.5f,1f);scaleX=widthScale;scaleY=heightScale
        }) {
            drawImage(atlas,srcOffset=IntOffset(crop[0],crop[1]),srcSize=IntSize(crop[2],crop[3]),
                dstOffset=IntOffset.Zero,dstSize=IntSize(size.width.toInt(),size.height.toInt()))
        }
    }
}
