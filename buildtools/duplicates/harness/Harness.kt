// Runs the app's duplicate grouping (vision/core/Duplicates.kt, FeatureCheck.kt) on photos exported by
// export_harness.py, and writes the groups (one line of photo numbers per group).
//   run.sh photos.bin thumbs/ groups.txt
import com.localmediatools.vision.core.Duplicates
import com.localmediatools.vision.core.FeatureCheck
import com.localmediatools.vision.core.PhotoInfo
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.DataInputStream
import java.io.File

fun main(args: Array<String>) {
    nu.pattern.OpenCV.loadLocally()
    val items = ArrayList<PhotoInfo>()
    DataInputStream(File(args[0]).inputStream().buffered(1 shl 20)).use { d ->
        val n = d.readInt()
        repeat(n) {
            val index = d.readInt(); val w = d.readInt(); val h = d.readInt(); val dhash = d.readLong(); val taken = d.readLong()
            val contrast = d.readFloat()
            val grid = FloatArray(256) { d.readFloat() }
            val len = d.readInt(); val scale = d.readFloat()
            val q = ByteArray(len).also { d.readFully(it) }
            // As the app reads its cache: int8 times the scale, then normalised.
            val emb = if (len > 0) FloatArray(len) { q[it] * scale }.also { e ->
                var s = 0f; for (v in e) s += v * v; val nn = Math.sqrt(s.toDouble()).toFloat(); for (k in e.indices) e[k] /= nn
            } else null
            items.add(PhotoInfo(index, w.toLong() * h, w, h, dhash, emb, grid, contrast, 0.0, if (taken == Long.MIN_VALUE) null else taken, null))
        }
    }
    val check = FeatureCheck()
    val feats = HashMap<Int, FeatureCheck.Features?>()
    fun features(i: Int): FeatureCheck.Features? = feats.getOrPut(i) {
        val f = File(args[1], "$i.raw")
        if (!f.exists()) return@getOrPut null
        DataInputStream(f.inputStream().buffered()).use { d ->
            val w = d.readInt(); val h = d.readInt()
            val px = ByteArray(w * h).also { d.readFully(it) }
            val m = Mat(h, w, CvType.CV_8UC1); m.put(0, 0, px)
            check.features(m).also { m.release() }
        }
    }
    var asked = 0
    val t0 = System.nanoTime()
    val groups = Duplicates.group(items, verify = { a, b ->
        asked++
        val fa = features(items[a].index); val fb = features(items[b].index)
        if (fa == null || fb == null) null else check.compare(fa, fb)
    })
    val ms = (System.nanoTime() - t0) / 1e6
    File(args[2]).printWriter().use { out -> for (g in groups) out.println(g.kind.name + " " + g.members.joinToString(" ")) }
    // Which members the app treats as copies of one picture (only one of them is kept by default).
    File(args[2] + ".pictures").printWriter().use { out -> for (g in groups) out.println(g.pictures.joinToString(" / ") { p -> p.joinToString(" ") }) }
    System.err.println("groups ${groups.size}, feature checks $asked, ${"%.0f".format(ms)} ms")
}
