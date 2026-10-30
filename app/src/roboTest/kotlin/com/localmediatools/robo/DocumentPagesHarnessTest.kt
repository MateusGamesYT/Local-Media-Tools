package com.localmediatools.robo

import com.localmediatools.print.DocumentPrint
import com.localmediatools.print.core.MediaSize
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * For measuring document printing (buildtools/docs/pages.py): lays out every file listed in
 * $LMT_PAGES_LIST (one path per line) on Letter paper, the size the government documents were
 * written for, and writes "path<TAB>pages" (or "path<TAB>error<TAB>message") to $LMT_PAGES_OUT.
 * Skipped when those aren't set.
 */
@RunWith(RobolectricTestRunner::class)
class DocumentPagesHarnessTest {
    @Test fun layOutListedFiles() {
        val list = System.getenv("LMT_PAGES_LIST") ?: return
        val out = File(System.getenv("LMT_PAGES_OUT") ?: return)
        out.bufferedWriter().use { w ->
            for (path in File(list).readLines().filter { it.isNotBlank() }) {
                val f = File(path)
                val line = try {
                    val pages = DocumentPrint.open(f, f.name, MediaSize.LETTER)
                    if (pages == null) "$path\terror\tnot a document" else pages.use { p ->
                        // With $LMT_PAGES_SHOTS, every page as a picture too (at 1 pixel to the point, or $LMT_PAGES_SCALE).
                        System.getenv("LMT_PAGES_SHOTS")?.let { d ->
                            val dir = File(d).apply { mkdirs() }
                            val k = System.getenv("LMT_PAGES_SCALE")?.toFloatOrNull() ?: 1f
                            for (i in 0 until p.count) {
                                val bmp = android.graphics.Bitmap.createBitmap((p.widthPt(i) * k).toInt(), (p.heightPt(i) * k).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                p.draw(android.graphics.Canvas(bmp), i, k)
                                File(dir, "${f.nameWithoutExtension}-${f.extension}-${i + 1}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            }
                        }
                        "$path\t${p.count}"
                    }
                } catch (e: Throwable) { "$path\terror\t${e.javaClass.simpleName}: ${e.message?.replace('\n', ' ')?.take(200)}" }
                w.write(line); w.newLine(); w.flush()
            }
        }
    }
}
