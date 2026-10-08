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
        // Someone who has used the gallery gets new photos organised without opening it first.
        if (hasMediaAccess() && getDatabasePath("gallery.db").exists()) com.localmediatools.gallery.GalleryIndex.start(this)
    }

    /** Whether the app is on screen (gallery screens only refresh then). */
    val started = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun onStart() {
        super.onStart()
        started.value = true
    }

    override fun onStop() {
        started.value = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND || i?.action == Intent.ACTION_SEND_MULTIPLE) { receivePrint(i); return }
        val id = i?.getLongExtra(EXTRA_SHOW_JOB, -1L) ?: -1L
        if (id > 0) {
            i?.removeExtra(EXTRA_SHOW_JOB)
            navigator.push(ResultsScreen(this, id))
        }
        if (i?.getBooleanExtra(EXTRA_OPEN_GALLERY, false) == true) {
            i.removeExtra(EXTRA_OPEN_GALLERY)
            while (navigator.depth > 1) navigator.pop()
            (navigator.top as? MainShell)?.show(MainShell.TAB_GALLERY)
        }
    }

    /**
     * Photos or PDFs shared from another app to "Print": copied into the app's cache first (another
     * app's permission to read them ends with this screen, while printing runs in the background).
     */
    private fun receivePrint(i: Intent) {
        @Suppress("DEPRECATION")
        val uris: List<Uri> = if (i.action == Intent.ACTION_SEND) listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else (i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf())
        i.action = null
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val copies = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
                uris.mapIndexedNotNull { n, u ->
                    try {
                        val name = com.localmediatools.core.MediaProbe.describe(this@MainActivity, u).name.replace('/', '_')
                        val f = File(dir, "${System.currentTimeMillis()}-$n-$name")
                        contentResolver.openInputStream(u)?.use { input -> f.outputStream().use { input.copyTo(it, 1 shl 16) } } ?: return@mapIndexedNotNull null
                        Uri.fromFile(f)
                    } catch (_: Exception) { null }
                }
            }
            if (copies.isEmpty()) { Toast.makeText(this@MainActivity, "Couldn't read the shared files.", Toast.LENGTH_LONG).show(); return@launch }
            val sel = com.localmediatools.ui.Selection.of(com.localmediatools.tools.ToolId.PRINT)
            sel.clear(); sel.addUris(this@MainActivity, copies)
            while (navigator.depth > 1) navigator.pop()
            navigator.push(com.localmediatools.ui.print.PrintScreen(this@MainActivity))
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

    /** Starts a system confirmation (e.g. moving photos to the trash); [cb] gets the result code. */
    fun launchIntentSender(sender: android.content.IntentSender, cb: (Int) -> Unit) {
        val code = nextRequest++
        pending[code] = { rc, _ -> cb(rc) }
        try {
            @Suppress("DEPRECATION")
            startIntentSenderForResult(sender, code, null, 0, 0, 0)
        } catch (e: Exception) {
            pending.remove(code)
            Toast.makeText(this, "This action isn't available on this phone.", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ photo library permission
    private var photoAccessThen: ((Boolean) -> Unit)? = null

    fun hasPhotoAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 -> checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= 33 -> checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** Asks for read access to the photo library (the duplicate finder needs it); [then] gets whether access was given. */
    fun ensurePhotoAccess(then: (Boolean) -> Unit) {
        if (hasPhotoAccess() && Build.VERSION.SDK_INT < 34) { then(true); return }
        photoAccessThen = then
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms, 10)
    }

    /** Photos and videos (the gallery shows both). With "selected photos" access only those are visible. */
    fun hasMediaAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 -> checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= 33 -> checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** True when the user allowed only some photos (Android 14+). */
    fun hasPartialMediaAccess(): Boolean = Build.VERSION.SDK_INT >= 34 &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

    /** Asks for read access to photos and videos for the gallery; [then] gets whether any access was given. */
    fun ensureMediaAccess(then: (Boolean) -> Unit) {
        photoAccessThen = { then(hasMediaAccess()) }
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms, 10)
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
        if (requestCode == 10) {
            photoAccessThen?.invoke(hasPhotoAccess())
            photoAccessThen = null
        }
    }

    companion object {
        const val EXTRA_SHOW_JOB = "show_job"
        const val EXTRA_OPEN_GALLERY = "open_gallery"
        @Suppress("unused") private fun cacheFile(a: Activity, n: String) = File(a.cacheDir, n)
    }
}
