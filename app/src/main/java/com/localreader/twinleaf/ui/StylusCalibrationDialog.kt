package com.localreader.twinleaf.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.localreader.twinleaf.ink.*

private enum class CalibrationStep { HOVER, LIGHT, NORMAL_FAST, TRIAL, RESULT }
private data class CapturedStylusEvent(
    val key: StylusDeviceKey,
    val capabilities: StylusCapabilities,
    val action: Int,
    val pointerId: Int,
    val timestamp: Long,
    val pressure: Float,
    val distance: Float?,
    val x: Float,
    val y: Float,
    val hover: Boolean,
)

private class StylusCaptureView(context: Context) : View(context) {
    var onStylusEvent: ((CapturedStylusEvent) -> Unit)? = null
    private var activePointerId: Int? = null
    private var activeKey: StylusDeviceKey? = null
    private var activeDownTime = Long.MIN_VALUE
    private var lastDispatchKey: StylusDeviceKey? = null
    private var lastTimestamp = Long.MIN_VALUE

    init { isFocusable = true; isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                if (!isStylus(event, index)) { activePointerId = null; activeKey = null; return false }
                activePointerId = event.getPointerId(index)
                activeKey = StylusDeviceKey.from(event.device, event.source)
                activeDownTime = event.downTime
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTimestamp = Long.MIN_VALUE
                dispatchPointer(event, index, hover = false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeKey != StylusDeviceKey.from(event.device, event.source) || activeDownTime != event.downTime) return false
                val id = activePointerId ?: return false
                val index = (0 until event.pointerCount).firstOrNull { event.getPointerId(it) == id } ?: return true
                if (isStylus(event, index)) dispatchHistoryAndCurrent(event, index, hover = false)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (activeKey != StylusDeviceKey.from(event.device, event.source) || activeDownTime != event.downTime) return false
                if (activePointerId == event.getPointerId(event.actionIndex)) {
                    if(isStylus(event,event.actionIndex)) dispatchPointer(event,event.actionIndex,hover=false)
                    finishTouch()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeKey != StylusDeviceKey.from(event.device, event.source) || activeDownTime != event.downTime) return false
                val id=activePointerId
                val index=if(id==null) -1 else (0 until event.pointerCount).firstOrNull { event.getPointerId(it)==id } ?: -1
                if(index>=0 && isStylus(event,index)) dispatchPointer(event,index,hover=false)
                finishTouch()
                return true
            }
        }
        return activePointerId != null
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if ((0 until event.pointerCount).none { isStylus(event, it) }) return false
        if (activePointerId != null) finishTouch()
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
            val index = (0 until event.pointerCount).firstOrNull { isStylus(event, it) } ?: return false
            dispatchHistoryAndCurrent(event, index, hover = true)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            val index = (0 until event.pointerCount).firstOrNull { isStylus(event, it) }
            if (index != null) dispatchPointer(event, index, hover = true)
            return true
        }
        return false
    }

    private fun isStylus(event: MotionEvent, index: Int) =
        event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER

    private fun dispatchHistoryAndCurrent(event: MotionEvent, index: Int, hover: Boolean) {
        val eventKey = StylusDeviceKey.from(event.device, event.source) ?: return
        if (eventKey != lastDispatchKey) { lastDispatchKey = eventKey; lastTimestamp = Long.MIN_VALUE }
        for (historyIndex in 0 until event.historySize) {
            val time = event.getHistoricalEventTime(historyIndex)
            if (time <= lastTimestamp) continue
            val key = StylusDeviceKey.from(event.device, event.source) ?: continue
            if (key != activeKey && !hover) { activeKey = key; lastTimestamp = Long.MIN_VALUE }
            val caps = StylusCapabilities.from(event.device, event.source)
            onStylusEvent?.invoke(CapturedStylusEvent(key,caps,event.actionMasked,event.getPointerId(index),time,
                event.getHistoricalPressure(index,historyIndex),
                if (caps.distance) event.getHistoricalAxisValue(MotionEvent.AXIS_DISTANCE,index,historyIndex).takeIf { it.isFinite() && it >= 0f } else null,
                (event.getHistoricalX(index,historyIndex)/width.coerceAtLeast(1)).coerceIn(0f,1f),
                (event.getHistoricalY(index,historyIndex)/height.coerceAtLeast(1)).coerceIn(0f,1f),hover))
            lastTimestamp = time
        }
        dispatchPointer(event,index,hover)
    }

    private fun dispatchPointer(event: MotionEvent,index: Int,hover: Boolean) {
        val key=StylusDeviceKey.from(event.device,event.source) ?: return
        if(key != lastDispatchKey) { lastDispatchKey=key;lastTimestamp=Long.MIN_VALUE }
        if(!hover && key != activeKey) { activeKey=key;lastTimestamp=Long.MIN_VALUE }
        val boundary = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_UP
        if(event.eventTime<=lastTimestamp && !boundary) return
        val caps=StylusCapabilities.from(event.device,event.source)
        onStylusEvent?.invoke(CapturedStylusEvent(key,caps,event.actionMasked,event.getPointerId(index),event.eventTime,
            event.getPressure(index),
            if(caps.distance) event.getAxisValue(MotionEvent.AXIS_DISTANCE,index).takeIf { it.isFinite() && it>=0f } else null,
            (event.getX(index)/width.coerceAtLeast(1)).coerceIn(0f,1f),
            (event.getY(index)/height.coerceAtLeast(1)).coerceIn(0f,1f),hover))
        lastTimestamp=event.eventTime
    }

    private fun finishTouch() { activePointerId=null; activeKey=null; activeDownTime=Long.MIN_VALUE; lastTimestamp=Long.MIN_VALUE; parent?.requestDisallowInterceptTouchEvent(false) }
}

private class TrialStroke {
    val points = mutableStateListOf<Offset>()
}

@Composable
fun StylusCalibrationDialog(onDismiss: () -> Unit) {
    val context=LocalContext.current
    val repository=remember { StylusCalibrationRepository(context.applicationContext) }
    var deviceKey by remember { mutableStateOf<StylusDeviceKey?>(null) }
    var capabilities by remember { mutableStateOf<StylusCapabilities?>(null) }
    var step by remember { mutableStateOf(CalibrationStep.HOVER) }
    val hoverSamples=remember { mutableStateListOf<StylusCalibrationSample>() }
    val writingSamples=remember { mutableStateListOf<StylusCalibrationSample>() }
    val normalSamples=remember { mutableStateListOf<StylusCalibrationSample>() }
    val liftSamples=remember { mutableStateListOf<StylusCalibrationSample>() }
    val graphSamples=remember { mutableStateListOf<Float>() }
    val trialStrokes=remember { mutableStateListOf<TrialStroke>() }
    var activeTrialStroke by remember { mutableStateOf<TrialStroke?>(null) }
    var trialActive by remember { mutableStateOf(false) }
    var livePressure by remember { mutableFloatStateOf(0f) }
    var liveDistance by remember { mutableStateOf<Float?>(null) }
    var inContact by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("在取样区内移动触笔，依次采集悬停、轻写与抬笔数据。") }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var candidate by remember { mutableStateOf<StylusCalibrationProfile?>(null) }
    var trialGate by remember { mutableStateOf<StylusContactGate?>(null) }
    var trialDecision by remember { mutableStateOf<StylusContactDecision?>(null) }
    var saved by remember { mutableStateOf(false) }
    var lastTimestamp by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var savedProfile by remember(deviceKey) { mutableStateOf(deviceKey?.let(repository::load)) }

    fun resetSamples(message:String) {
        hoverSamples.clear();writingSamples.clear();normalSamples.clear();liftSamples.clear();graphSamples.clear();trialStrokes.clear()
        activeTrialStroke=null;trialActive=false;candidate=null;trialGate=null;trialDecision=null;saved=false
        step=CalibrationStep.HOVER;resultMessage=null;status=message;lastTimestamp=Long.MIN_VALUE
    }
    fun appendBounded(target: androidx.compose.runtime.snapshots.SnapshotStateList<StylusCalibrationSample>, sample: StylusCalibrationSample) {
        if(target.size>=240) target.removeAt(0)
        target.add(sample)
    }
    fun receive(event:CapturedStylusEvent) {
        if(deviceKey!=null && deviceKey!=event.key) {
            deviceKey=event.key;capabilities=event.capabilities
            resetSamples("检测到另一支触笔，已清除旧采样；请重新校准。")
        } else {
            deviceKey=event.key;capabilities=event.capabilities
        }
        val boundary = event.action==MotionEvent.ACTION_UP || event.action==MotionEvent.ACTION_CANCEL || event.action==MotionEvent.ACTION_POINTER_UP
        if(event.timestamp<=lastTimestamp && !boundary && event.action!=MotionEvent.ACTION_DOWN && !event.hover) return
        lastTimestamp=event.timestamp
        livePressure=event.pressure;liveDistance=event.distance
        inContact=!event.hover && (event.action==MotionEvent.ACTION_DOWN || event.action==MotionEvent.ACTION_MOVE)
        if(graphSamples.size>=180) graphSamples.removeAt(0)
        graphSamples.add(event.pressure)

        val sample=StylusCalibrationSample(
            phase=when { event.hover -> if(step==CalibrationStep.HOVER) StylusCalibrationPhase.HOVER else StylusCalibrationPhase.LIFT; else -> StylusCalibrationPhase.LIGHT_WRITING },
            pressure=event.pressure,distance=event.distance)
        when(step) {
            CalibrationStep.HOVER -> if(event.hover && event.action==MotionEvent.ACTION_HOVER_MOVE) appendBounded(hoverSamples,sample)
            CalibrationStep.LIGHT -> when {
                event.hover && event.action==MotionEvent.ACTION_HOVER_MOVE -> appendBounded(liftSamples,sample)
                !event.hover && (event.action==MotionEvent.ACTION_DOWN || event.action==MotionEvent.ACTION_MOVE) -> appendBounded(writingSamples,sample)
            }
            CalibrationStep.NORMAL_FAST -> when {
                event.hover && event.action==MotionEvent.ACTION_HOVER_MOVE -> appendBounded(liftSamples,sample)
                !event.hover && (event.action==MotionEvent.ACTION_DOWN || event.action==MotionEvent.ACTION_MOVE) -> appendBounded(normalSamples,sample)
            }
            CalibrationStep.TRIAL -> {
                val gate=trialGate
                when {
                    event.hover || boundary -> {
                        gate?.end();trialActive=false;inContact=false;activeTrialStroke=null
                        if(event.hover) trialDecision=StylusContactDecision.END
                    }
                    event.action==MotionEvent.ACTION_DOWN -> {
                        gate?.begin();trialActive=true
                        trialDecision=gate?.onSample(event.pressure,event.distance)
                        inContact=trialDecision==StylusContactDecision.ACCEPT
                        val stroke=TrialStroke();if(trialStrokes.size>=32) trialStrokes.removeAt(0);trialStrokes.add(stroke);activeTrialStroke=stroke
                        if(inContact) stroke.points.add(Offset(event.x,event.y))
                    }
                    event.action==MotionEvent.ACTION_MOVE && trialActive -> {
                        trialDecision=gate?.onSample(event.pressure,event.distance)
                        inContact=trialDecision==StylusContactDecision.ACCEPT
                        if(inContact) activeTrialStroke?.points?.let { points -> if(points.size>=512) points.removeAt(0);points.add(Offset(event.x,event.y)) }
                        if(trialDecision==StylusContactDecision.END) { gate?.end();trialActive=false;inContact=false;activeTrialStroke=null }
                    }
                }
            }
            CalibrationStep.RESULT -> Unit
        }
    }

    Dialog(onDismissRequest=onDismiss) {
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=6.dp,
            modifier=Modifier.fillMaxWidth().widthIn(max=600.dp).heightIn(max=760.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("触笔压力校准",style=MaterialTheme.typography.titleLarge)
                Text(when(step) {
                    CalibrationStep.HOVER -> "第 1 步 · 悬停：在取样区上方移动触笔，不要接触屏幕。"
                    CalibrationStep.LIGHT -> "第 2 步 · 轻触：轻轻画几次，每次抬笔后在空中移动一小段。"
                    CalibrationStep.NORMAL_FAST -> "第 3 步 · 常规与快速：使用正常力度书写，再快速书写；每笔抬起后稍作悬停移动。"
                    CalibrationStep.TRIAL -> "试用：轻轻试写并观察落笔判定，满意后保存。试用笔迹显示在取样区内。"
                    CalibrationStep.RESULT -> resultMessage ?: "校准完成。"
                },style=MaterialTheme.typography.bodyMedium)

                Box(Modifier.fillMaxWidth().height(160.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(16.dp))
                    .border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(16.dp))) {
                    AndroidView(factory={ ctx -> StylusCaptureView(ctx).apply { onStylusEvent={ receive(it) } } },
                        modifier=Modifier.fillMaxSize(),update={it.onStylusEvent={ receive(it) }})
                    val trialInk=MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                        trialStrokes.forEach { stroke ->
                            if(stroke.points.size>1) {
                                val path=Path().apply {
                                    moveTo(stroke.points.first().x*size.width,stroke.points.first().y*size.height)
                                    stroke.points.drop(1).forEach { lineTo(it.x*size.width,it.y*size.height) }
                                }
                                drawPath(path,trialInk,style=androidx.compose.ui.graphics.drawscope.Stroke(width=3.dp.toPx()))
                            }
                        }
                    }
                    Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(if(inContact) "接触中" else "悬停 / 未接触",style=MaterialTheme.typography.titleMedium)
                        Text("原始压力 ${"%.3f".format(livePressure)}${liveDistance?.let { "   悬停距离 ${"%.2f".format(it)}" } ?: "   距离不可用"}",fontSize=13.sp)
                        Text("只接收触笔事件 · ${when(step) { CalibrationStep.HOVER -> "悬停 ${hoverSamples.size}";CalibrationStep.LIGHT -> "轻写 ${writingSamples.size} / 抬笔 ${liftSamples.size}";CalibrationStep.NORMAL_FAST -> "书写 ${normalSamples.size} / 抬笔 ${liftSamples.size}";else -> "${graphSamples.size} 个实时样本" }}",
                            style=MaterialTheme.typography.labelSmall)
                    }
                }
                PressureGraph(graphSamples.toList(),Modifier.fillMaxWidth().height(82.dp))
                val caps=capabilities
                Text(if(caps==null) "等待触笔输入。校准仅采用真实悬停事件与触摸事件。"
                    else "压力 ${if(caps.pressure) "可用" else "不可用"} · 悬停距离 ${if(caps.distance) "可用" else "不可用"} · ${deviceKey?.descriptor ?: "设备标识不可用"}",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(step==CalibrationStep.TRIAL) Text(when(trialDecision) {
                    StylusContactDecision.ACCEPT -> "试用判定：识别为接触"
                    StylusContactDecision.HOLD -> "试用判定：等待稳定接触"
                    StylusContactDecision.END -> "试用判定：识别为抬笔"
                    null -> "落笔试写以查看校准判断"
                },style=MaterialTheme.typography.bodySmall)
                if(savedProfile!=null && !saved) Text("此触笔已有校准；保存新校准后将替换原设置。",style=MaterialTheme.typography.bodySmall)
                Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={resetSamples("已清除采样，请从悬停步骤重新开始。")}) { Text("重新校准") }
                    savedProfile?.let { profileToReset -> TextButton(onClick={repository.reset(profileToReset.key);savedProfile=null;resetSamples("已清除该触笔的校准设置。")}) { Text("清除校准") } }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick=onDismiss) { Text("关闭") }
                    Button(onClick={
                        when(step) {
                            CalibrationStep.HOVER -> {
                                if(hoverSamples.size<12) status=if(hoverSamples.isEmpty()) "尚未收到真实悬停事件。请将触笔靠近屏幕并移动；若设备不支持悬停，则无法完成本校准，可关闭并继续使用默认判定。" else "悬停数据不足 ${hoverSamples.size}/12。请继续将触笔悬停移动后再继续。"
                                else { step=CalibrationStep.LIGHT;status="轻触多次，每次抬笔后在空中移动以记录抬笔状态。" }
                            }
                            CalibrationStep.LIGHT -> {
                                if(writingSamples.size<12 || liftSamples.size<12) status="样本不足：轻写 ${writingSamples.size}/12，抬笔 ${liftSamples.size}/12。请继续轻写并反复抬笔。"
                                else { step=CalibrationStep.NORMAL_FAST;status="继续普通与快速笔画，并在每次抬笔后悬停移动。" }
                            }
                            CalibrationStep.NORMAL_FAST -> {
                                if(normalSamples.size<24 || liftSamples.size<24) status="常规与快速笔画样本不足；请继续书写和反复抬笔。"
                                else {
                                    val key=deviceKey;val cap=capabilities
                                    if(key==null || cap==null || !cap.pressure && !cap.distance) status="无法分离触笔信号：压力和悬停距离均不可用，或设备标识不可用；未保存。"
                                    else {
                                        val outcome=StylusCalibration.calibrate(key,cap,hoverSamples.toList()+writingSamples.toList()+normalSamples.toList()+liftSamples.toList())
                                        resultMessage=outcome.message
                                        if(outcome.profile!=null) {
                                            candidate=outcome.profile;trialGate=StylusContactGate(outcome.profile);trialDecision=null
                                            step=CalibrationStep.TRIAL;status=outcome.message
                                        } else { step=CalibrationStep.RESULT;status="无法可靠分离信号，已保留当前设置。${outcome.message}" }
                                    }
                                }
                            }
                            CalibrationStep.TRIAL -> candidate?.let { profile ->
                                repository.save(profile);savedProfile=profile;saved=true
                                resultMessage="校准已保存。轻写范围和抬笔识别已启用。";status=resultMessage!!;step=CalibrationStep.RESULT
                            }
                            CalibrationStep.RESULT -> resetSamples("已重置采样，请重新开始。")
                        }
                    }) { Text(when(step) { CalibrationStep.RESULT -> "再校准一次";CalibrationStep.TRIAL -> "保存校准";CalibrationStep.NORMAL_FAST -> "生成试用";else -> "下一步" }) }
                }
            }
        }
    }
}

@Composable
private fun PressureGraph(samples:List<Float>,modifier:Modifier=Modifier) {
    val line=MaterialTheme.colorScheme.primary
    val grid=MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        repeat(4) { i -> val y=size.height*i/3f;drawLine(grid,Offset(0f,y),Offset(size.width,y),1f) }
        if(samples.size>1) {
            val minValue=minOf(0f,samples.min());val maxValue=maxOf(1f,samples.max())
            val range=(maxValue-minValue).coerceAtLeast(.0001f)
            for(i in 1 until samples.size) {
                val a=((samples[i-1]-minValue)/range).coerceIn(0f,1f);val b=((samples[i]-minValue)/range).coerceIn(0f,1f)
                drawLine(line,Offset((i-1)*size.width/(samples.size-1),size.height*(1-a)),
                    Offset(i*size.width/(samples.size-1),size.height*(1-b)),2.dp.toPx())
            }
        }
    }
}
