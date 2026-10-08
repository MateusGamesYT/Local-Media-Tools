package com.localmediatools.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import com.localmediatools.core.CaptureProvider
import com.localmediatools.ui.MainShell
import com.localmediatools.ui.Navigator
import com.localmediatools.ui.Palette
import com.localmediatools.ui.PickKind
import com.localmediatools.ui.ResultsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : Activity() {
    lateinit var navigator: Navigator
        private set
    val lifecycleScope = MainScope()
    private val pending = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextRequest = 100
    private var permissionThen: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        navigator = Navigator(root)
        navigator.root(MainShell(this))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val id = i?.getLongExtra(EXTRA_SHOW_JOB, -1L) ?: -1L
        if (id > 0) {
            i?.removeExtra(EXTRA_SHOW_JOB)
            navigator.push(ResultsScreen(this, id))
        }
    }

    @Deprecated("Framework back handling")
    override fun onBackPressed() {
        if (!navigator.back()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        lifecycleScope.cancel()
        navigator.all().forEach { it.onDestroy() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ activity results
    private fun launch(intent: Intent, cb: (Int, Intent?) -> Unit) {
        val code = nextRequest++
        pending[code] = cb
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, code)
        } catch (e: ActivityNotFoundException) {
            pending.remove(code)
            Toast.makeText(this, "No app on this phone can handle this action.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Framework result handling")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        pending.remove(requestCode)?.invoke(resultCode, data)
        navigator.dispatchResult(requestCode, resultCode, data)
    }

    private fun urisFrom(data: Intent?): List<Uri> {
        data ?: return emptyList()
        val out = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
        if (out.isEmpty()) data.data?.let { out.add(it) }
        return out
    }

    private fun photoPickerAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 33 || (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2)

    /** System photo picker (images/videos), falling back to the documents UI. */
    fun pickMedia(kind: PickKind, multiple: Boolean = true, cb: (List<Uri>) -> Unit) {
        val type = kind.galleryType ?: return pickDocuments(kind, multiple, cb)
        val both = type == "*/*"
        val intent = if (photoPickerAvailable()) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                // No type = photos and videos.
                if (!both) this.type = type
                if (multiple) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                this.type = type
                if (both) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }
        }
        launch(intent) { code, data -> if (code == RESULT_OK) cb(urisFrom(data)) }
    }

    /** Files app picker: any provider (Downloads, Drive, SD card…), unlimited multi-select. */
    fun pickDocuments(kind: PickKind, multiple: Boolean = true, cb: (List<Uri>) -> Unit) {
        val mimes = if (kind == PickKind.IMAGES) kind.mimes + "application/octet-stream" else kind.mimes
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimes.size == 1) mimes[0] else "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        launch(intent) { code, data -> if (code == RESULT_OK) cb(urisFrom(data)) }
    }

    /** Adds every matching file of a chosen folder (not recursive), sorted by name. */
    fun pickFolder(kind: PickKind, cb: (List<Uri>) -> Unit) {
        launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) { code, data ->
            val tree = data?.data
            if (code != RESULT_OK || tree == null) return@launch
            lifecycleScope.launch {
                val uris = withContext(Dispatchers.IO) { listFolder(tree, kind) }
                if (uris.isEmpty()) Toast.makeText(this@MainActivity, "No ${kind.noun} found in that folder.", Toast.LENGTH_LONG).show()
                cb(uris)
            }
        }
    }

    private fun listFolder(tree: Uri, kind: PickKind): List<Uri> {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val found = ArrayList<Pair<String, Uri>>()
        try {
            contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext() && found.size < 5000) {
                    val id = c.getString(0); val name = c.getString(1) ?: ""; val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val match = kind.mimes.any { m -> if (m.endsWith("/*")) mime.startsWith(m.dropLast(1)) else mime == m } ||
                        (kind == PickKind.IMAGES && name.substringAfterLast('.', "").lowercase() in setOf("qoi", "pbm", "pgm", "ppm", "pam", "pnm", "tga", "psd", "tif", "tiff", "dng"))
                    if (match) found.add(name to DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
        } catch (_: Exception) {
        }
        return found.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first }).map { it.second }
    }

    // ------------------------------------------------------------------ camera
    /** (available, explanation when not). */
    fun cameraAvailability(): Pair<Boolean, String> {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return false to "This device has no camera. Add pages from the gallery instead."
        val resolves = Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(packageManager) != null
        if (!resolves) return false to "No camera app is available on this phone. Add pages from the gallery instead."
        return true to ""
    }

    fun capturePhoto(cb: (Uri) -> Unit) {
        val (ok, why) = cameraAvailability()
        if (!ok) { Toast.makeText(this, why, Toast.LENGTH_LONG).show(); return }
        val (uri, file) = CaptureProvider.newCaptureUri(this)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("page", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(intent) { code, _ ->
            if (code == RESULT_OK && file.exists() && file.length() > 0) cb(CaptureProvider.uriFor(file))
            else {
                file.delete()
                if (code == RESULT_OK) Toast.makeText(this, "The camera app didn't return a photo.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ notifications permission
    fun ensureNotificationPermission(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { then(); return }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) { then(); return }
        prefs.edit().putBoolean("asked_notifications", true).apply()
        permissionThen = then
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9) {
            permissionThen?.invoke()
            permissionThen = null
        }
    }

    companion object {
        const val EXTRA_SHOW_JOB = "show_job"
        @Suppress("unused") private fun cacheFile(a: Activity, n: String) = File(a.cacheDir, n)
    }
}
