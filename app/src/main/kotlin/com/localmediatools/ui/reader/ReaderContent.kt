package com.localmediatools.ui.reader

import android.view.View

/** Where a search stands: match [current] (from 1) of [total]; [busy] while pages are still being read. */
data class SearchStatus(val current: Int, val total: Int, val busy: Boolean = false, val progress: String? = null)

/** What a reader view (text, sheets, pages) offers the reader screen. */
interface ReaderContent {
    val view: View

    /** Finds [query] (any case) and shows the first match; [update] is told how it goes, on the main thread. */
    fun search(query: String, update: (SearchStatus) -> Unit)
    fun searchStep(forward: Boolean)
    fun clearSearch()

    /** Chapters, headings, sheets or slides to jump to: label and target for [goTo]. */
    fun contents(): List<Pair<String, Int>> = emptyList()
    fun goTo(target: Int) {}
    val contentsTitle: String get() = com.localmediatools.i18n.tr("Contents")

    /** Larger (> 1) or smaller text or pages, from the menu (pinching does the same). */
    fun zoomBy(factor: Float) {}

    /** Extra lines for the file's information ("12 pages"). */
    fun facts(): List<String> = emptyList()

    fun close() {}
}

/** What the views ask of the screen around them. */
interface ReaderHost {
    /** A link in the document was tapped: [target] is shown first, never opened without asking. */
    fun link(target: String)
    fun toast(message: String)
}
