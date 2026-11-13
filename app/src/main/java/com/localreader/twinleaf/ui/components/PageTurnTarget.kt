package com.localreader.twinleaf.ui.components

/** Keyboard and scroll input must use the same animation/commit path as a screen tap. */
interface PageTurnTarget {
    fun turnPage(direction: Int): Boolean
}
