import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceClustering
import com.localmediatools.gallery.core.FaceRec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the app's FaceClustering on faces from a file (score, eye px, yaw, embedding);
 * prints "faceIndex groupIndex" lines. Optional third argument "join=0.44,merge=0.6,…" overrides SFACE.
 */
fun main(args: Array<String>) {
    val b = ByteBuffer.wrap(File(args[0]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val n = b.int; val dim = b.int
    val d = ClusterParams.SFACE
    val o = if (args.size > 2) args[2].split(',').associate { it.substringBefore('=') to it.substringAfter('=') } else emptyMap()
    fun f(k: String, v: Float) = o[k]?.toFloat() ?: v
    val p = ClusterParams(f("join", d.join), f("merge", d.merge), f("assign", d.assign), f("low", d.low), f("margin", d.margin),
        f("goodScore", d.goodScore), f("goodYaw", d.goodYaw), f("goodEye", d.goodEye),
        centroid = o["link"]?.let { it == "centroid" } ?: d.centroid, lowScore = f("lowScore", d.lowScore))
    val recs = ArrayList<FaceRec>(n)
    for (i in 0 until n) {
        val score = b.float; val eye = b.float; val yaw = b.float
        val e = FloatArray(dim) { b.float }
        recs.add(FaceRec(i.toLong(), e, p.quality(score, yaw, eye), p.isGood(score, yaw, eye), usable = p.isUsable(score)))
    }
    val t0 = System.nanoTime()
    val groups = FaceClustering.cluster(recs, p)
    System.err.println("clustered $n faces in ${(System.nanoTime() - t0) / 1_000_000} ms")
    val out = StringBuilder()
    for ((g, grp) in groups.withIndex()) for (id in grp.faces) out.append(id).append(' ').append(g).append('\n')
    File(args[1]).writeText(out.toString())
}
