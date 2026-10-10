// Runs 1.6.0's duplicate grouping (Duplicates.kt from release 1.6.0, see build.sh) on the same export.
//   run_old.sh photos.bin groups.txt
import com.localmediatools.vision.core.Duplicates
import com.localmediatools.vision.core.PhotoInfo
import java.io.DataInputStream
import java.io.File

fun main(args: Array<String>) {
    val items = ArrayList<PhotoInfo>()
    DataInputStream(File(args[0]).inputStream().buffered(1 shl 20)).use { d ->
        val n = d.readInt()
        repeat(n) {
            val index = d.readInt(); val w = d.readInt(); val h = d.readInt(); val dhash = d.readLong(); val taken = d.readLong()
            d.readFloat()
            repeat(256) { d.readFloat() }
            val len = d.readInt(); val scale = d.readFloat()
            val q = ByteArray(len).also { d.readFully(it) }
            val emb = if (len > 0) FloatArray(len) { q[it] * scale }.also { e ->
                var s = 0f; for (v in e) s += v * v; val nn = Math.sqrt(s.toDouble()).toFloat(); for (k in e.indices) e[k] /= nn
            } else null
            items.add(PhotoInfo(index, w.toLong() * h, w, h, dhash, emb, 0.0, if (taken == Long.MIN_VALUE) null else taken, null))
        }
    }
    val t0 = System.nanoTime()
    val groups = Duplicates.group(items)
    File(args[1]).printWriter().use { out -> for (g in groups) out.println(g.kind.name + " " + g.members.joinToString(" ")) }
    System.err.println("groups ${groups.size}, ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms")
}
