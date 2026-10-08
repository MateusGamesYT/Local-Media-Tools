package com.localmediatools.ui.gallery

import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.ui.Screen
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Reloads a gallery screen when the index changes: right away while the screen is the one in front
 * and the app is visible, otherwise once when it is shown again. Indexing in the background then
 * doesn't keep every open screen (and the battery) busy.
 */
class IndexWatch(
    private val screen: Screen,
    private val debounceMs: Long = 400,
    private val inFront: () -> Boolean = { screen.activity.navigator.top === screen },
    private val load: () -> Unit,
) {
    private var dirty = false
    private var loadedOnce = false

    private fun visible() = screen.activity.started.value && inFront()

    @OptIn(FlowPreview::class)
    fun start() {
        screen.scope.launch {
            val changes = if (debounceMs > 0) GalleryIndex.changes.debounce(debounceMs) else GalleryIndex.changes
            changes.collect {
                if (!loadedOnce || visible()) { loadedOnce = true; dirty = false; load() } else dirty = true
            }
        }
        screen.scope.launch { screen.activity.started.collect { if (it) shown() } }
    }

    /** Call when the screen becomes visible again. */
    fun shown() {
        if (dirty && visible()) { dirty = false; load() }
    }
}
