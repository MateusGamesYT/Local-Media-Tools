package com.localmediatools.pdf

import android.content.Context
import android.net.Uri
import com.localmediatools.core.UserFacingException
import com.localmediatools.i18n.tr
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File

/**
 * Merges PDFs structurally with PDFBox: pages are appended as PDF objects, so text, vector graphics,
 * images, links/annotations and page sizes are kept exactly (nothing is rasterised).
 */
object PdfMerge {
    @Volatile private var initialised = false

    class Result(val pages: Int, val notes: List<String>)

    fun merge(
        ctx: Context,
        inputs: List<Pair<String, Uri>>,
        output: File,
        scratchDir: File,
        throttle: () -> Unit,
        progress: (Int) -> Unit,
    ): Result {
        if (!initialised) {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            initialised = true
        }
        val notes = ArrayList<String>()
        val opened = ArrayList<PDDocument>()
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(scratchDir)
        val dest = PDDocument(memory)
        try {
            val merger = PDFMergerUtility()
            var total = 0
            for ((i, input) in inputs.withIndex()) {
                throttle()
                val (name, uri) = input
                val stream = ctx.contentResolver.openInputStream(uri) ?: throw UserFacingException(tr("\"{0}\" could not be opened.", name))
                val doc = try {
                    stream.use { PDDocument.load(it, memory) }
                } catch (e: InvalidPasswordException) {
                    throw UserFacingException(tr("\"{0}\" is password-protected. Remove the password before merging.", name))
                } catch (e: OutOfMemoryError) {
                    throw e
                } catch (e: Exception) {
                    throw UserFacingException(tr("\"{0}\" is not a valid PDF or is damaged ({1}).", name, e.message ?: e.javaClass.simpleName), e)
                }
                opened.add(doc)
                if (doc.numberOfPages == 0) throw UserFacingException(tr("\"{0}\" has no pages.", name))
                if (doc.isEncrypted) {
                    doc.isAllSecurityToBeRemoved = true
                    notes.add(tr("\"{0}\" had usage restrictions (no password needed to open); they were not carried over.", name))
                }
                try {
                    merger.appendDocument(dest, doc)
                } catch (e: Exception) {
                    throw UserFacingException(tr("\"{0}\" has a structure that couldn't be merged ({1}).", name, e.message ?: e.javaClass.simpleName), e)
                }
                total += doc.numberOfPages
                progress(i + 1)
            }
            if (dest.numberOfPages != total) throw UserFacingException(tr("Merged page count doesn't match the inputs; nothing was saved."))
            dest.documentInformation.producer = "Local Media Tools"
            dest.save(output)
            return Result(total, notes)
        } finally {
            try { dest.close() } catch (_: Exception) { }
            opened.forEach { try { it.close() } catch (_: Exception) { } }
        }
    }
}
