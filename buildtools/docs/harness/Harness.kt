import com.localmediatools.docs.core.DocFormats
import com.localmediatools.docs.core.Deck
import com.localmediatools.docs.core.FlowDocument
import com.localmediatools.docs.core.Workbook
import com.localmediatools.docs.core.plainText
import java.io.File
import java.util.Locale

/**
 * Runs the app's readers on files: for each, one line "file<TAB>format<TAB>ok|error<TAB>ms<TAB>detail"
 * and the plain text into <out>/<n>.txt (for comparing with other readers).
 *   run.sh <out dir> <file>…   (or a list of files on stdin with "-")
 */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val files = if (args.getOrNull(1) == "-") generateSequence(::readLine).filter { it.isNotBlank() }.toList() else args.drop(1)
    for ((n, path) in files.withIndex()) {
        val f = File(path)
        val t0 = System.nanoTime()
        var format = "?"
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val task = pool.submit<String> { work(f, n, out) { format = it } }
        val result = try { task.get(60, java.util.concurrent.TimeUnit.SECONDS) } catch (e: java.util.concurrent.TimeoutException) { "error\tTIMEOUT after 60 s" } catch (e: Exception) { "error\t${e.cause ?: e}" }
        pool.shutdownNow()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("$path\t$format\t${result.substringBefore('\t')}\t$ms\t${result.substringAfter('\t')}")
    }
    System.exit(0)
}

private fun work(f: File, n: Int, out: File, setFormat: (String) -> Unit): String {
        return try {
            val kind = DocFormats.detect(f, f.name)
            setFormat(kind?.name ?: "none")
            if (kind == null || kind == com.localmediatools.docs.core.DocFormat.PDF) "skip\t-"
            else {
                val doc = DocFormats.open(f, kind, Locale.US)
                File(out, "$n.txt").writeText(doc.plainText())
                if (doc is Workbook) File(out, "$n.cells").bufferedWriter().use { w ->
                    for ((si, sh) in doc.sheets.withIndex()) for (r in sh.cells.rowIndices()) for ((c, cell) in sh.cells.row(r)!!) {
                        if (cell.text.isNotEmpty()) w.write("$si\t${sh.name}\t$r\t$c\t${cell.text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')}\n")
                    }
                }
                val detail = when (doc) {
                    is FlowDocument -> "blocks=${doc.blocks.size}"
                    is Workbook -> "sheets=${doc.sheets.size} cells=${doc.sheets.sumOf { it.cells.count }}"
                    is Deck -> "slides=${doc.slides.size}"
                }
                doc.close()
                "ok\t$detail ${doc.notes.joinToString(" | ")}"
            }
        } catch (e: Throwable) {
            "error\t${e.javaClass.simpleName}: ${e.message?.replace('\n', ' ')?.take(200)} @ ${e.stackTrace.firstOrNull { it.className.contains("docs") }}"
        }
}
