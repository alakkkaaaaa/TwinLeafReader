package com.localreader.twinleaf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localreader.twinleaf.R
import com.localreader.twinleaf.data.PdfDocument
import com.localreader.twinleaf.ink.*
import com.localreader.twinleaf.pdf.*
import com.localreader.twinleaf.ui.components.ReaderChromePalette
import com.localreader.twinleaf.ui.components.readerChromePalette
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable

class PdfToolsState(val ink:InkStore,val appearance:PdfAppearanceState) {
    var open by mutableStateOf(ink.tool!=InkTool.BROWSE)
    var collapsed by mutableStateOf(false)
    var appearanceOpen by mutableStateOf(false)
    var exportOpen by mutableStateOf(false)
    var calibrationOpen by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
}

@Composable
fun rememberPdfTools(book:PdfDocument):PdfToolsState {
    val context=LocalContext.current.applicationContext
    return remember(book.uri) { PdfToolsState(InkStore.get(context,book.uri.toString()),PdfAppearanceState(context,book.uri.toString())) }
}

@Composable
fun PdfToolActions(tools:PdfToolsState) {
    val chrome=readerChromePalette()
    IconButton(onClick={if(!tools.ink.drawing) {
        tools.open=!tools.open
        tools.collapsed=false
        if(!tools.open) tools.ink.choose(InkTool.BROWSE)
    }}) {
        Icon(Icons.Rounded.Draw,"批注工具",tint=if(tools.open) chrome.accent else chrome.content)
    }
    IconButton(onClick={if(!tools.ink.drawing) tools.appearanceOpen=true}) {
        Icon(Icons.Rounded.Palette,"PDF 颜色设置",tint=if(tools.appearanceOpen) chrome.accent else chrome.content)
    }
}

@Composable
fun PdfInkToolbar(tools:PdfToolsState,onPrevious:()->Unit,onNext:()->Unit,modifier:Modifier=Modifier) {
    val ink=tools.ink
    val chrome=readerChromePalette()
    var palette by remember { mutableStateOf(false) }
    var diagnostic by remember { mutableStateOf(false) }
    if(!tools.open) return
    if(tools.collapsed) {
        CollapsedInkHandle(tools,modifier)
        return
    }
    Box(modifier.padding(start=8.dp,end=8.dp,top=3.dp).widthIn(max=1120.dp).fillMaxWidth().height(107.dp)) {
        Surface(Modifier.fillMaxWidth().height(64.dp).align(Alignment.BottomCenter),shape=RoundedCornerShape(32.dp),
            color=chrome.background,contentColor=chrome.content,shadowElevation=8.dp,
            border=BorderStroke(1.dp,chrome.border)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(chrome.gradientTop,chrome.gradientBottom))))
        }
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal=6.dp),
            verticalAlignment=Alignment.Bottom) {
            fun select(tool:InkTool,brush:InkBrush?=null) {
                if(ink.tool==tool && (brush==null || ink.brush==brush)) palette=true
                else {
                    brush?.let { ink.brush=it };ink.choose(tool)
                    if(tool==InkTool.BROWSE) tools.open=false
                }
            }
            PdfAction(Icons.AutoMirrored.Rounded.ArrowBack,"退出批注",false,!ink.drawing,chrome) {
                tools.open=false;tools.collapsed=false;ink.choose(InkTool.BROWSE)
            }
            PdfAction(Icons.Rounded.PanTool,"浏览模式",ink.tool==InkTool.BROWSE,!ink.drawing,chrome) { select(InkTool.BROWSE) }
            ToolRailDivider(chrome)
            InkInstrument("圆珠笔",InkBrush.BALLPOINT,ink.tool==InkTool.PEN && ink.brush==InkBrush.BALLPOINT,!ink.drawing && ink.ready) { select(InkTool.PEN,InkBrush.BALLPOINT) }
            InkInstrument("钢笔",InkBrush.FOUNTAIN,ink.tool==InkTool.PEN && ink.brush==InkBrush.FOUNTAIN,!ink.drawing && ink.ready) { select(InkTool.PEN,InkBrush.FOUNTAIN) }
            InkInstrument("铅笔",InkBrush.PENCIL,ink.tool==InkTool.PEN && ink.brush==InkBrush.PENCIL,!ink.drawing && ink.ready) { select(InkTool.PEN,InkBrush.PENCIL) }
            InkInstrument("荧光笔",InkBrush.HIGHLIGHTER,ink.tool==InkTool.HIGHLIGHT,!ink.drawing && ink.ready) { select(InkTool.HIGHLIGHT,InkBrush.HIGHLIGHTER) }
            InkInstrument("毛笔",InkBrush.BRUSH,ink.tool==InkTool.PEN && ink.brush==InkBrush.BRUSH,!ink.drawing && ink.ready) { select(InkTool.PEN,InkBrush.BRUSH) }
            InkInstrument("局部橡皮擦",null,ink.tool==InkTool.ERASER,!ink.drawing && ink.ready) { select(InkTool.ERASER) }
            ToolRailDivider(chrome)
            Box {
                PdfAction(if(ink.tool==InkTool.ERASER) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.Palette,
                    if(ink.tool==InkTool.ERASER) "橡皮大小" else "笔迹颜色和粗细",false,!ink.drawing,chrome) { palette=true }
                DropdownMenu(palette,{palette=false}) {
                    if(ink.tool==InkTool.ERASER) Column(Modifier.width(260.dp).padding(12.dp)) {
                        Text("局部橡皮 · ${ink.eraserRadiusDp.toInt()} dp")
                        Slider(ink.eraserRadiusDp,ink::updateEraserRadiusDp,valueRange=3f..48f,
                            modifier=Modifier.fillMaxWidth().semantics { contentDescription="橡皮大小" })
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            listOf(8f to "小",16f to "中",28f to "大").forEach { (radius,label) ->
                                FilterChip(kotlin.math.abs(ink.eraserRadiusDp-radius)<1f,
                                    {ink.updateEraserRadiusDp(radius)},{Text(label)})
                            }
                        }
                        Text("只擦除经过的笔画区段；一次拖动可整体撤销。",
                            style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=8.dp))
                    } else {
                    Row(Modifier.padding(horizontal=8.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                        listOf(0xFF2C65CE,0xFFD33F49,0xFF252525,0xFF2A9560,0xFFFFBF00).forEach { color ->
                            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role=Role.Button) { ink.color=color.toInt() }
                                .semantics { contentDescription="笔色 ${color.toString(16)}";selected=ink.color==color.toInt() },contentAlignment=Alignment.Center) {
                                Box(Modifier.size(30.dp).background(Color(color),CircleShape)
                                    .border(if(ink.color==color.toInt()) 3.dp else 1.dp,MaterialTheme.colorScheme.onSurface.copy(alpha=.7f),CircleShape))
                            }
                        }
                    }
                    Slider(ink.width,{ink.width=it},valueRange=.0015f.. .012f,
                        modifier=Modifier.width(240.dp).padding(horizontal=12.dp).semantics { contentDescription="笔迹粗细" })
                    Text("透明度",modifier=Modifier.padding(horizontal=12.dp))
                    Slider(ink.opacity,{ink.opacity=it},valueRange=.2f..1f,
                        modifier=Modifier.width(240.dp).padding(horizontal=12.dp).semantics { contentDescription="笔迹透明度" })
                    Row(Modifier.padding(8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        PressureCurve.entries.forEach { curve ->
                            FilterChip(ink.pressureCurve==curve,{ink.pressureCurve=curve},{Text(when(curve) {
                                PressureCurve.SOFT -> "柔和";PressureCurve.LINEAR -> "线性";PressureCurve.FIRM -> "硬朗"
                            })})
                        }
                    }
                    Text("自动美化 · 仅影响新笔迹",modifier=Modifier.padding(horizontal=12.dp,vertical=4.dp))
                    Row(Modifier.padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        InkBeautifyLevel.entries.forEach { level ->
                            val label=when(level) {
                                InkBeautifyLevel.OFF -> "关"
                                InkBeautifyLevel.LIGHT -> "轻"
                                InkBeautifyLevel.STANDARD -> "标准"
                                InkBeautifyLevel.STRONG -> "强"
                            }
                            FilterChip(ink.beautifyLevel==level,{ink.updateBeautifyLevel(level)},{Text(label)},enabled=!ink.drawing)
                        }
                    }
                    Row(Modifier.width(280.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动拉直")
                            Text("识别近似直线，抬笔后拉直",style=MaterialTheme.typography.bodySmall)
                        }
                        Switch(ink.autoStraighten,ink::updateAutoStraighten,enabled=!ink.drawing,
                            modifier=Modifier.semantics { contentDescription="近似直线自动拉直" })
                    }
                    Text("直线识别灵敏度",modifier=Modifier.padding(horizontal=12.dp,vertical=4.dp))
                    Row(Modifier.padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        StraightenSensitivity.entries.forEach { sensitivity ->
                            val label=when(sensitivity) {
                                StraightenSensitivity.STEADY -> "稳健"
                                StraightenSensitivity.STANDARD -> "标准"
                                StraightenSensitivity.SENSITIVE -> "灵敏"
                            }
                            FilterChip(ink.straightenSensitivity==sensitivity,{ink.updateStraightenSensitivity(sensitivity)},
                                {Text(label)},enabled=ink.autoStraighten && !ink.drawing,
                                modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription="直线识别灵敏度：$label" })
                        }
                    }
                    Row(Modifier.padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Gesture,"收笔灵敏度",modifier=Modifier.size(20.dp))
                        ReleaseSensitivity.entries.forEach { sensitivity ->
                            val label=when(sensitivity) {
                                ReleaseSensitivity.STEADY -> "稳"
                                ReleaseSensitivity.STANDARD -> "标"
                                ReleaseSensitivity.LIGHT -> "轻"
                            }
                            FilterChip(ink.releaseSensitivity==sensitivity,{ink.updateReleaseSensitivity(sensitivity)},
                                {Text(label)},enabled=!ink.drawing,
                                modifier=Modifier.heightIn(min=48.dp).semantics {
                                    contentDescription="收笔灵敏度："+when(sensitivity) {
                                        ReleaseSensitivity.STEADY -> "稳健，更多过滤抬笔拖尾"
                                        ReleaseSensitivity.STANDARD -> "标准"
                                        ReleaseSensitivity.LIGHT -> "轻触，保留更多细微笔迹"
                                    }
                                })
                            }
                        }
                    }
                    DropdownMenuItem(text={Text("触笔压力校准")},leadingIcon={Icon(Icons.Rounded.Gesture,"触笔压力校准")},
                        onClick={palette=false;tools.calibrationOpen=true},enabled=!ink.drawing)
                    }
                }
            PdfAction(Icons.AutoMirrored.Rounded.Undo,"撤销批注",false,ink.canUndo && !ink.drawing,chrome) { ink.undo() }
            PdfAction(Icons.AutoMirrored.Rounded.Redo,"重做批注",false,ink.canRedo && !ink.drawing,chrome) { ink.redo() }
            PdfAction(if(ink.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                if(ink.visible) "隐藏批注" else "显示批注",ink.visible,!ink.drawing,chrome) { ink.updateVisibility(!ink.visible) }
            PdfAction(Icons.Rounded.FileUpload,"导出 PDF",false,ink.ready && !ink.drawing,chrome) { tools.exportOpen=true }
            PdfAction(Icons.Rounded.ChevronLeft,"批注上一页",false,!ink.drawing,chrome) { tools.zoom=1f;onPrevious() }
            PdfAction(Icons.Rounded.ChevronRight,"批注下一页",false,!ink.drawing,chrome) { tools.zoom=1f;onNext() }
            PdfAction(Icons.Rounded.ZoomIn,"放大页面",tools.zoom>1f,!ink.drawing,chrome) { tools.zoom=(tools.zoom+.5f).coerceAtMost(3f) }
            PdfAction(Icons.Rounded.ZoomOut,"缩小页面",false,!ink.drawing,chrome) { tools.zoom=(tools.zoom-.5f).coerceAtLeast(1f) }
            PdfAction(Icons.Rounded.Info,"触笔诊断和按键设置",false,!ink.drawing,chrome) { diagnostic=true }
            PdfAction(if(ink.failure!=null) Icons.Rounded.ErrorOutline else if(ink.saveStatus=="已保存") Icons.Rounded.CloudDone else Icons.Rounded.CloudUpload,
                "批注保存状态：${ink.saveStatus}",false,true,chrome) { ink.retry() }
            PdfAction(Icons.Rounded.KeyboardArrowUp,"向上收起批注栏",false,!ink.drawing,chrome) { tools.collapsed=true }
        }
    }
    if(diagnostic) AlertDialog(onDismissRequest={diagnostic=false},title={Text("触笔诊断")},text={Column {
        Text(ink.stylusDiagnostic)
        Text("主按键",modifier=Modifier.padding(top=12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            StylusButtonAction.entries.forEach { action -> FilterChip(ink.primaryButtonAction==action,{ink.setButtonAction(true,action)},{Text(action.label())}) }
        }
        Text("副按键",modifier=Modifier.padding(top=12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            StylusButtonAction.entries.forEach { action -> FilterChip(ink.secondaryButtonAction==action,{ink.setButtonAction(false,action)},{Text(action.label())}) }
        }
    }},confirmButton={TextButton(onClick={diagnostic=false}) { Text("完成") }})
}

@Composable
private fun CollapsedInkHandle(tools:PdfToolsState,modifier:Modifier) {
    val chrome=readerChromePalette()
    Box(modifier.fillMaxWidth().height(48.dp),contentAlignment=Alignment.TopCenter) {
        IconButton(onClick={tools.collapsed=false},enabled=!tools.ink.drawing,
            modifier=Modifier.width(88.dp).height(48.dp).semantics { contentDescription="展开批注栏" }) {
            Surface(Modifier.width(72.dp).height(16.dp),shape=RoundedCornerShape(bottomStart=12.dp,bottomEnd=12.dp),
                color=chrome.background,contentColor=chrome.content,shadowElevation=5.dp,
                border=BorderStroke(1.dp,chrome.border)) {
                Icon(Icons.Rounded.KeyboardArrowDown,"展开批注栏",Modifier.size(16.dp),tint=chrome.content)
            }
        }
    }
}

private fun StylusButtonAction.label()=when(this) {
    StylusButtonAction.ERASER -> "橡皮";StylusButtonAction.HIGHLIGHTER -> "荧光"
    StylusButtonAction.PEN -> "圆珠";StylusButtonAction.NONE -> "无"
}

@Composable
private fun PdfAction(icon:ImageVector,label:String,selected:Boolean,enabled:Boolean=true,
    chrome:ReaderChromePalette,onClick:()->Unit) {
    IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(48.dp).offset(y=(-8).dp)
        .background(if(selected) chrome.selected else Color.Transparent,CircleShape)
        .then(if(selected) Modifier.border(1.dp,chrome.selectedBorder,CircleShape) else Modifier)
        .semantics { this.selected=selected }) {
        Icon(icon,label,tint=if(!enabled) chrome.disabled else if(selected) chrome.accent else chrome.content,modifier=Modifier.size(22.dp))
    }
}

@Composable
private fun ToolRailDivider(chrome:ReaderChromePalette) {
    Box(Modifier.padding(horizontal=5.dp).offset(y=(-18).dp).width(1.dp).height(28.dp)
        .background(Brush.verticalGradient(listOf(Color.Transparent,chrome.border,Color.Transparent))))
}

/** Approved six-tool atlas. Selection is expressed only by enlarging and elongating the tool. */
@Composable
private fun InkInstrument(label:String,brush:InkBrush?,selected:Boolean,enabled:Boolean,onClick:()->Unit) {
    val atlas=ImageBitmap.imageResource(R.drawable.ink_instruments_v1)
    val index=when(brush) {
        InkBrush.BALLPOINT -> 0
        InkBrush.FOUNTAIN -> 1
        InkBrush.PENCIL -> 2
        InkBrush.HIGHLIGHTER -> 3
        InkBrush.BRUSH -> 4
        else -> 5
    }
    val crop=when(index) {
        0 -> intArrayOf(221,80,184,580)
        1 -> intArrayOf(508,80,177,580)
        2 -> intArrayOf(783,80,184,580)
        3 -> intArrayOf(1052,80,205,580)
        4 -> intArrayOf(1356,80,159,580)
        else -> intArrayOf(1603,80,241,580)
    }
    val baseWidth=(crop[2]*82f/crop[3]).dp
    val widthScale by animateFloatAsState(if(selected) 1.18f else 1f,
        spring(dampingRatio=.72f,stiffness=500f),label="instrumentWidth")
    val heightScale by animateFloatAsState(if(selected) 1.3f else 1f,
        spring(dampingRatio=.7f,stiffness=460f),label="instrumentHeight")
    Box(Modifier.width(56.dp).height(104.dp)
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick)
        .semantics { contentDescription=label;this.selected=selected },contentAlignment=Alignment.BottomCenter) {
        Canvas(Modifier.width(baseWidth).height(82.dp).graphicsLayer {
            transformOrigin=TransformOrigin(.5f,1f)
            scaleX=widthScale;scaleY=heightScale
        }.alpha(if(enabled) 1f else .38f)) {
            drawImage(atlas,srcOffset=IntOffset(crop[0],crop[1]),
                srcSize=IntSize(crop[2],crop[3]),
                dstOffset=IntOffset.Zero,dstSize=IntSize(size.width.toInt(),size.height.toInt()))
        }
    }
}

@Composable
fun PdfToolDialogs(book:PdfDocument,tools:PdfToolsState) {
    val context=LocalContext.current
    var includeInk by remember { mutableStateOf(true) }
    if(tools.calibrationOpen) StylusCalibrationDialog { tools.calibrationOpen=false }
    if(tools.exportOpen) AlertDialog(onDismissRequest={tools.exportOpen=false},title={Text("导出 PDF")},text={Column {
        Text("保留原始页面颜色。批注显隐不影响此选项；合并后的笔迹作为页面内容导出。")
        Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(includeInk,{includeInk=it});Text("包含手写批注") }
    }},confirmButton={TextButton(onClick={tools.exportOpen=false
        PdfExportJob.request(book.uri,book.title.removeSuffix(".pdf")+(if(includeInk) "-批注.pdf" else "-原文.pdf"),tools.ink.pages,includeInk)}) { Text("另存为") }},
        dismissButton={TextButton(onClick={tools.exportOpen=false}) { Text("取消") }})
    if(tools.appearanceOpen) {
        val appearance=tools.appearance;val value=appearance.value
        AlertDialog(onDismissRequest={tools.appearanceOpen=false},title={Text("PDF 颜色")},text={Column {
            Text("仅改变阅读显示，原文件和导出不受影响。")
            Row(verticalAlignment=Alignment.CenterVertically) { Switch(!value.original,{appearance.update(value.copy(original=!it))});Text("跟随应用深色模式") }
            Row {
                FilterChip(value.style==PdfDarkStyle.GENTLE,{appearance.update(value.copy(style=PdfDarkStyle.GENTLE))},{Text("柔和")})
                Spacer(Modifier.width(8.dp))
                FilterChip(value.style==PdfDarkStyle.INVERT,{appearance.update(value.copy(style=PdfDarkStyle.INVERT))},{Text("反色")})
            }
            Text("转换强度")
            Slider(value.strength,{appearance.update(value.copy(strength=it))},valueRange=.1f..1f)
            Row(verticalAlignment=Alignment.CenterVertically) { Switch(appearance.temporaryOriginal,{appearance.temporaryOriginal=it});Text("临时查看原色") }
            Text("彩色图片也会转换；查看图表时可切换原色。",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={tools.appearanceOpen=false}) { Text("完成") }})
    }
}

/** Keep the document picker registration above the landscape/portrait reader branch. */
@Composable
fun PdfExportHost() {
    val context=LocalContext.current
    var launched by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
        PdfExportJob.selected(context,it)
    }
    val pending=PdfExportJob.pending
    LaunchedEffect(pending?.id) { if(pending!=null && launched!=pending.id) { launched=pending.id;launcher.launch(pending.name) } }
    if(PdfExportJob.busy) AlertDialog(onDismissRequest={},title={Text("正在导出")},text={CircularProgressIndicator()},confirmButton={})
    PdfExportJob.result?.let { message -> AlertDialog(onDismissRequest={PdfExportJob.dismiss()},text={Text(message)},confirmButton={TextButton(onClick={PdfExportJob.dismiss()}) { Text("确定") }}) }
}
