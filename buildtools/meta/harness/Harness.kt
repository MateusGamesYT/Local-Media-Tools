import com.localmediatools.meta.core.MetaReader
import java.io.File

/** Reads each file named on standard input and prints one JSON line per file: its kind, fields and notes. */
fun main() {
    val out = System.out.bufferedWriter()
    for (line in generateSequence(::readLine)) {
        val path = line.trim()
        if (path.isEmpty()) continue
        val f = File(path)
        val start = System.nanoTime()
        val sb = StringBuilder()
        try {
            val r = MetaReader.read(f, f.name, 0)
            sb.append("{\"file\":").append(q(path)).append(",\"format\":").append(q(r.format)).append(",\"ms\":").append((System.nanoTime() - start) / 1_000_000)
            sb.append(",\"fields\":[")
            r.fields.forEachIndexed { i, m ->
                if (i > 0) sb.append(',')
                sb.append("[").append(q(m.group)).append(',').append(q(m.name)).append(',').append(q(m.value)).append(',').append(q(m.raw)).append(',').append(q(m.privacy?.name ?: "")).append("]")
            }
            sb.append("],\"notes\":[").append(r.notes.joinToString(",") { q(it) }).append("]}")
        } catch (e: Throwable) {
            sb.setLength(0)
            sb.append("{\"file\":").append(q(path)).append(",\"error\":").append(q(e.toString() + " @ " + e.stackTrace.take(4).joinToString(" < "))).append("}")
        }
        out.write(sb.toString()); out.newLine(); out.flush()
    }
}

private fun q(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) when {
        c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\"); c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
        c < ' ' -> sb.append("\\u%04x".format(c.code))
        else -> sb.append(c)
    }
    return sb.append('"').toString()
}
