package com.localreader.twinleaf.note

import com.localreader.twinleaf.ink.InkBrush
import com.localreader.twinleaf.ink.InkBeautifyLevel
import com.localreader.twinleaf.ink.InkPoint
import com.localreader.twinleaf.ink.InkStroke
import com.localreader.twinleaf.ink.PressureCurve
import org.json.JSONArray
import org.json.JSONObject

enum class NoteBackground { BLANK, LINED, GRID, DOT_GRID, ENGINEERING, ISOMETRIC }

data class NotePaperSettings(
    val pattern:NoteBackground=NoteBackground.BLANK,
    val spacing:Float=32f,
    val lineOpacity:Float=.18f,
    val backgroundColor:Int=0xFFFFFCF4.toInt(),
    val exportGuides:Boolean=true,
)

fun normalizedNotePaperSettings(value:NotePaperSettings)=value.copy(
    spacing=(value.spacing.takeIf { it.isFinite() } ?: 32f).coerceIn(16f,80f),
    lineOpacity=(value.lineOpacity.takeIf { it.isFinite() } ?: .18f).coerceIn(0f,1f),
)

/** Index stride stays anchored at world coordinate zero while enforcing screen-space spacing. */
fun notePaperLodStride(spacing:Float,scale:Float,minimumScreenPixels:Float=12f):Int {
    if(!spacing.isFinite() || !scale.isFinite() || spacing<=0f || scale<=0f) return 1
    return kotlin.math.ceil((minimumScreenPixels.coerceAtLeast(1f)/(spacing*scale)).toDouble())
        .toInt().coerceAtLeast(1)
}

fun notePaperLodSpacing(spacing:Float,scale:Float,minimumScreenPixels:Float=12f):Float =
    spacing*notePaperLodStride(spacing,scale,minimumScreenPixels)

fun isMajorGridLine(index:Int)=index%5==0

data class NoteViewport(val centerX:Float=0f,val centerY:Float=0f,val scale:Float=1f)

data class NoteSnapshot(
    val strokes:List<InkStroke> = emptyList(),
    val background:NoteBackground = NoteBackground.BLANK,
    val viewport:NoteViewport = NoteViewport(),
    val paperSettings:NotePaperSettings = NotePaperSettings(pattern=background),
)

data class NoteBounds(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    fun intersects(other:NoteBounds)=left<=other.right && right>=other.left && top<=other.bottom && bottom>=other.top
}

fun InkStroke.noteBounds():NoteBounds {
    if(points.isEmpty()) return NoteBounds(0f,0f,0f,0f)
    val pad=width.coerceAtLeast(.5f)*1.5f
    return NoteBounds(points.minOf(InkPoint::x)-pad,points.minOf(InkPoint::y)-pad,
        points.maxOf(InkPoint::x)+pad,points.maxOf(InkPoint::y)+pad)
}

object NoteCodec {
    fun validate(snapshot:NoteSnapshot):NoteSnapshot {
        val paper=normalizedNotePaperSettings(snapshot.paperSettings)
        require(snapshot.viewport.centerX.isFinite() && snapshot.viewport.centerY.isFinite() && snapshot.viewport.scale in .2f..8f)
        snapshot.strokes.forEach { stroke ->
            require(stroke.width in .05f..500f && stroke.opacity in 0f..1f)
            fun validatePoints(points:List<InkPoint>)=points.forEach { point ->
                require(point.x.isFinite() && point.y.isFinite() && kotlin.math.abs(point.x)<=10_000_000f && kotlin.math.abs(point.y)<=10_000_000f)
                require(point.pressure in 0f..10f && point.rawPressure in 0f..10f && point.tilt.isFinite() && point.orientation.isFinite())
            }
            validatePoints(stroke.points);stroke.rawPoints?.let { validatePoints(it) }
        }
        return snapshot.copy(background=paper.pattern,paperSettings=paper)
    }

    fun encode(value:NoteSnapshot):String {
        val snapshot=validate(value)
        return JSONObject().put("version",3)
        .put("background",snapshot.background.name)
        .put("paperSettings",JSONObject().put("pattern",snapshot.paperSettings.pattern.name)
            .put("spacing",snapshot.paperSettings.spacing.toDouble())
            .put("lineOpacity",snapshot.paperSettings.lineOpacity.toDouble())
            .put("backgroundColor",snapshot.paperSettings.backgroundColor)
            .put("exportGuides",snapshot.paperSettings.exportGuides))
        .put("viewport",JSONObject().put("x",snapshot.viewport.centerX.toDouble())
            .put("y",snapshot.viewport.centerY.toDouble()).put("scale",snapshot.viewport.scale.toDouble()))
        .put("strokes",JSONArray().apply { snapshot.strokes.forEach { stroke ->
            put(JSONObject().put("color",stroke.color).put("width",stroke.width.toDouble())
                .put("highlight",stroke.highlight).put("brush",stroke.brush.name)
                .put("opacity",stroke.opacity.toDouble()).put("curve",stroke.curve.name)
                .put("beautify",stroke.beautifyLevel.name).put("autoStraightened",stroke.autoStraightened)
                .put("points",JSONArray().apply { stroke.points.forEach { point ->
                    put(JSONArray(listOf(point.x.toDouble(),point.y.toDouble(),point.pressure.toDouble(),
                        point.tilt.toDouble(),point.orientation.toDouble(),point.timeMillis,point.rawPressure.toDouble())))
                }}).apply { stroke.rawPoints?.let { raw -> put("rawPoints",JSONArray().apply { raw.forEach { point ->
                    put(JSONArray(listOf(point.x.toDouble(),point.y.toDouble(),point.pressure.toDouble(),
                        point.tilt.toDouble(),point.orientation.toDouble(),point.timeMillis,point.rawPressure.toDouble())))
                }}) } })
        }}).toString()
    }

    fun decode(text:String):NoteSnapshot {
        val root=JSONObject(text);val version=root.getInt("version");require(version in 1..3)
        val viewport=root.optJSONObject("viewport") ?: JSONObject()
        val legacyBackground=runCatching { NoteBackground.valueOf(root.optString("background","BLANK")) }
            .getOrDefault(NoteBackground.BLANK)
        val paper=root.optJSONObject("paperSettings")
        val paperSettings=if(paper==null) NotePaperSettings(pattern=legacyBackground,
            spacing=if(version==1 && legacyBackground!=NoteBackground.BLANK) 48f else 32f)
        else NotePaperSettings(
            pattern=runCatching { NoteBackground.valueOf(paper.optString("pattern",legacyBackground.name)) }
                .getOrDefault(legacyBackground),
            spacing=paper.optDouble("spacing",32.0).toFloat(),
            lineOpacity=paper.optDouble("lineOpacity",.18).toFloat(),
            backgroundColor=paper.optInt("backgroundColor",0xFFFFFCF4.toInt()),
            exportGuides=paper.optBoolean("exportGuides",true))
        val array=root.optJSONArray("strokes") ?: JSONArray()
        val strokes=List(array.length()) { index ->
            val source=array.getJSONObject(index);val points=source.getJSONArray("points")
            fun decodePoints(values:JSONArray)=List(values.length()) { pointIndex ->
                val point=values.getJSONArray(pointIndex)
                InkPoint(point.getDouble(0).toFloat(),point.getDouble(1).toFloat(),
                    point.optDouble(2,1.0).toFloat().coerceIn(0f,10f),
                    point.optDouble(3,0.0).toFloat(),point.optDouble(4,0.0).toFloat(),
                    point.optLong(5,0L),point.optDouble(6,point.optDouble(2,1.0)).toFloat().coerceIn(0f,10f)).also {
                    require(it.x.isFinite() && it.y.isFinite() && kotlin.math.abs(it.x)<=10_000_000f && kotlin.math.abs(it.y)<=10_000_000f)
                }
            }
            InkStroke(decodePoints(points),source.getInt("color"),source.getDouble("width").toFloat().also { require(it in .05f..500f) },
                source.optBoolean("highlight"),
                runCatching { InkBrush.valueOf(source.optString("brush","BALLPOINT")) }.getOrDefault(InkBrush.BALLPOINT),
                source.optDouble("opacity",1.0).toFloat().coerceIn(0f,1f),
                runCatching { PressureCurve.valueOf(source.optString("curve","LINEAR")) }.getOrDefault(PressureCurve.LINEAR),
                source.optJSONArray("rawPoints")?.let { decodePoints(it) },
                runCatching { InkBeautifyLevel.valueOf(source.optString("beautify","OFF")) }.getOrDefault(InkBeautifyLevel.OFF),
                source.optBoolean("autoStraightened",false))
        }
        return validate(NoteSnapshot(strokes,
            paperSettings.pattern,
            NoteViewport(viewport.optDouble("x",0.0).toFloat(),viewport.optDouble("y",0.0).toFloat(),
                viewport.optDouble("scale",1.0).toFloat().coerceIn(.2f,8f)),paperSettings))
    }
}
