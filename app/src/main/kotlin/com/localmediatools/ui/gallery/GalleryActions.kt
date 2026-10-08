package com.localmediatools.ui.gallery

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.gallery.GMedia
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.ToolSection
import com.localmediatools.ui.PickKind
import com.localmediatools.ui.Selection
import com.localmediatools.ui.StackScreen
import com.localmediatools.ui.ToolLauncher
import com.localmediatools.ui.ToolRules
import com.localmediatools.ui.editor.EditorMode
import com.localmediatools.ui.tools.ToolScreens

/** Share, delete and "open in a tool" for gallery items. */
object GalleryActions {
    /**
     * Android hands the whole list over in one message with a size limit (about 1 MB), so bigger
     * selections would fail without a word; above this the user is asked to do it in parts.
     */
    const val MAX_AT_ONCE = 500

    private fun tooMany(activity: MainActivity, items: List<GMedia>, what: String): Boolean {
        if (items.size <= MAX_AT_ONCE) return false
        Toast.makeText(activity, "You can $what up to $MAX_AT_ONCE items at a time (${items.size} selected).", Toast.LENGTH_LONG).show()
        return true
    }

    fun share(activity: MainActivity, items: List<GMedia>) {
        if (items.isEmpty() || tooMany(activity, items, "share")) return
        val uris = items.map { it.uri }
        val mime = items.map { it.mime }.distinct().singleOrNull()
            ?: items.map { it.mime.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        val clip = ClipData.newRawUri("", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        i.clipData = clip
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { activity.startActivity(Intent.createChooser(i, "Share")) } catch (_: Exception) { }
    }

    /** Moves items to the system trash (Android asks the user to confirm). */
    fun trash(activity: MainActivity, items: List<GMedia>, done: () -> Unit) {
        if (items.isEmpty() || tooMany(activity, items, "delete")) return
        if (Build.VERSION.SDK_INT < 30) { deleteOnAndroid10(activity, items, done); return }
        val pi = try { MediaStore.createTrashRequest(activity.contentResolver, items.map { it.uri }, true) } catch (e: Exception) {
            Toast.makeText(activity, "These items can't be moved to the trash: ${e.message}", Toast.LENGTH_LONG).show(); return
        }
        activity.launchIntentSender(pi.intentSender) { rc ->
            if (rc == android.app.Activity.RESULT_OK) {
                val n = items.size
                Toast.makeText(activity, "Moved $n ${if (n == 1) "item" else "items"} to the trash. You can restore ${if (n == 1) "it" else "them"} for 30 days.", Toast.LENGTH_LONG).show()
                GalleryIndex.start(activity)
                done()
            }
        }
    }

    /**
     * Android 10 has no trash: items are deleted for good, one at a time, each confirmed by Android
     * itself (the app may only delete other apps' photos after that confirmation).
     */
    private fun deleteOnAndroid10(activity: MainActivity, items: List<GMedia>, done: () -> Unit) {
        if (items.size > 1) {
            Toast.makeText(activity, "On Android 10, photos can be deleted one at a time (there is no trash).", Toast.LENGTH_LONG).show()
            return
        }
        val m = items[0]
        AlertDialog.Builder(activity).setTitle("Delete for good?")
            .setMessage("Android 10 has no trash, so ${m.name} can't be restored afterwards.")
            .setPositiveButton("Delete") { _, _ -> deleteNow(activity, m, done, askedOnce = false) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deleteNow(activity: MainActivity, m: GMedia, done: () -> Unit, askedOnce: Boolean) {
        try {
            activity.contentResolver.delete(m.uri, null, null)
            Toast.makeText(activity, "Deleted.", Toast.LENGTH_SHORT).show()
            GalleryIndex.start(activity)
            done()
        } catch (e: android.app.RecoverableSecurityException) {
            if (askedOnce) { Toast.makeText(activity, "This item can't be deleted.", Toast.LENGTH_LONG).show(); return }
            activity.launchIntentSender(e.userAction.actionIntent.intentSender) { rc ->
                if (rc == android.app.Activity.RESULT_OK) deleteNow(activity, m, done, askedOnce = true)
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "This item can't be deleted: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Opens the system viewer (e.g. to play a format this app can't). */
    fun openExternally(activity: MainActivity, m: GMedia) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, m.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { activity.startActivity(i) } catch (_: Exception) { Toast.makeText(activity, "No app can open this file.", Toast.LENGTH_LONG).show() }
    }

    /** Tools that can work on these items. */
    fun toolsFor(items: List<GMedia>): List<ToolId> {
        val anyVideo = items.any { it.video }
        val anyPhoto = items.any { !it.video && !it.gif }
        val anyGif = items.any { it.gif }
        return ToolId.entries.filter { t ->
            if (t == ToolId.DUPLICATES || t == ToolId.PDF_SCANNER) return@filter false
            val editor = t == ToolId.PHOTO_EDITOR || t == ToolId.MAGIC_ERASER || t == ToolId.BLUR_REDACT
            if (editor) return@filter items.size == 1 && anyPhoto
            if (ToolRules.single(t) && items.size != 1) return@filter false
            if (items.size < ToolRules.minItems(t)) return@filter false
            when (ToolRules.pickKind(t)) {
                PickKind.IMAGES -> !anyVideo && (anyPhoto || anyGif)
                PickKind.VIDEOS -> anyVideo && !anyPhoto && !anyGif
                PickKind.GIFS -> anyGif && !anyPhoto && !anyVideo
                PickKind.MEDIA -> !(t == ToolId.FACE_BLUR && anyGif)
                PickKind.ANY -> true
                PickKind.PDFS -> false
            }
        }.sortedBy { if (it.section == ToolSection.EDIT) 0 else 1 }
    }

    fun chooseTool(activity: MainActivity, items: List<GMedia>) {
        val tools = toolsFor(items)
        if (tools.isEmpty()) { Toast.makeText(activity, "No tool works on this selection.", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(activity)
            .setTitle(if (items.size == 1) "Open in a tool" else "Use ${items.size} items in a tool")
            .setItems(tools.map { it.title }.toTypedArray()) { _, which -> openIn(activity, tools[which], items) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun openIn(activity: MainActivity, t: ToolId, items: List<GMedia>) {
        val uris = items.map { it.uri }
        when (t) {
            ToolId.PHOTO_EDITOR -> ToolLauncher.openEditor(activity, uris[0], t, EditorMode.ADJUST)
            ToolId.MAGIC_ERASER -> ToolLauncher.openEditor(activity, uris[0], t, EditorMode.ERASE)
            ToolId.BLUR_REDACT -> ToolLauncher.openEditor(activity, uris[0], t, EditorMode.BLUR)
            ToolId.TOOL_STACK -> {
                val sel = Selection.of(t); sel.clear(); sel.addUris(activity, uris)
                activity.navigator.push(StackScreen(activity))
            }
            else -> {
                val sel = Selection.of(t); sel.clear(); sel.addUris(activity, uris)
                activity.navigator.push(ToolScreens.create(activity, t))
            }
        }
    }
}
