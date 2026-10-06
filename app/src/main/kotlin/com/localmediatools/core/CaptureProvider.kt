package com.localmediatools.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Private provider that lets the camera app write a captured scanner page into this app's cache.
 * Only URIs explicitly granted for a capture are reachable; nothing is exported.
 */
class CaptureProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw IllegalStateException()
        val name = uri.lastPathSegment ?: throw IllegalArgumentException("Bad URI")
        if (!name.matches(Regex("[A-Za-z0-9_.-]+"))) throw SecurityException("Bad file name")
        return File(dir(ctx), name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.parseMode(mode))

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { col -> when (col) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }.toTypedArray<Any?>())
        return c
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.localmediatools.app.capture"
        fun dir(ctx: Context) = File(ctx.filesDir, "scanner").apply { mkdirs() }
        fun newCaptureUri(ctx: Context): Pair<Uri, File> {
            val name = "page_${System.currentTimeMillis()}.jpg"
            val f = File(dir(ctx), name)
            return Uri.parse("content://$AUTHORITY/$name") to f
        }
        fun uriFor(f: File): Uri = Uri.parse("content://$AUTHORITY/${f.name}")
    }
}
