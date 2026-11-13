package com.localreader.twinleaf.ink

/** Adjacent ranges share a vertex, never a segment. A sealed range is append-invariant. */
internal object LiveStrokeChunks {
    const val SEGMENTS = 128
    fun count(points: Int): Int = if (points <= 0) 0 else maxOf(1, (points - 2) / SEGMENTS + 1)
}
