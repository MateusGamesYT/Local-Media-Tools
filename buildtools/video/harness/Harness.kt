import com.localmediatools.video.core.Box
import com.localmediatools.video.core.Cuts
import com.localmediatools.video.core.Found
import com.localmediatools.video.core.Reframe
import com.localmediatools.video.core.ReframeSample
import com.localmediatools.video.core.SubjectKind
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the app's auto reframe on what reframe_detect.py found in a clip, as the app would:
 *
 *   run.sh <det dir> <clip> <out.tsv> rate=5 aspect=0.5625 zoom=0 speed=0.6 accel=0.4 margin=0.08 cuts=1
 *
 * rate: samples a second (taken from the detections every second frame); cuts=0 ignores cuts.
 * Writes, for every frame time of the clip, "ms x y w h" (the crop, fractions) and on stderr the cuts found.
 */
fun main(args: Array<String>) {
    val dir = File(args[0]); val clip = args[1]; val out = File(args[2])
    val o = args.drop(3).associate { it.substringBefore('=') to it.substringAfter('=') }
    val rate = o["rate"]?.toDouble() ?: 5.0
    val aspect = o["aspect"]?.toFloat() ?: (9f / 16f)
    val zoom = o["zoom"]?.toFloat() ?: 0f
    val d = Reframe.DEFAULT
    val p = Reframe.Params(o["speed"]?.toDouble() ?: d.speed, o["accel"]?.toDouble() ?: d.accel, o["margin"]?.toFloat() ?: d.margin,
        o["hold"]?.toLong() ?: d.holdMs, o["stick"]?.toFloat() ?: d.stick)
    o["cutmin"]?.let { Cuts.MIN_DIFF = it.toFloat() }; o["cutratio"]?.let { Cuts.RATIO = it.toFloat() }
    // Frames and their grey copies.
    val raw = File(dir, "$clip.thumbs").readBytes()
    val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val n = bb.int
    val ms = LongArray(n) { bb.int.toLong() }
    val thumbs = (0 until n).map { ByteArray(Cuts.W * Cuts.H).also { a -> bb.get(a) } }
    val cutFrames = if (o["cuts"] == "0") emptyList() else Cuts.find(thumbs, ms)
    System.err.println("cuts: " + cutFrames.joinToString(" ") { ms[it].toString() })
    // What was found, by time.
    val found = HashMap<Long, MutableList<Found>>()
    File(dir, "$clip.found.tsv").forEachLine { l ->
        val r = l.split('\t')
        found.getOrPut(r[0].toLong()) { ArrayList() }.add(Found(SubjectKind.valueOf(r[1]), Box(r[2].toFloat(), r[3].toFloat(), r[4].toFloat(), r[5].toFloat()), r[6].toFloat()))
    }
    // The app's samples: every 1/rate seconds, the first detected frame at or after; a cut starts a new
    // sample at its first frame (with what the next detected frame found).
    val detTimes = (0 until n step 2).map { ms[it] }
    val samples = ArrayList<ReframeSample>()
    val period = 1000.0 / rate
    var next = 0.0
    var ci = 0
    for (t in detTimes) {
        val cutHere = ci < cutFrames.size && ms[cutFrames[ci]] <= t
        if (t + 1e-6 < next && !cutHere) continue
        val sampleMs = if (cutHere) ms[cutFrames[ci]] else t
        if (cutHere) ci++
        samples.add(ReframeSample(sampleMs, found[t] ?: emptyList(), cut = cutHere))
        next = t + period
    }
    val srcAspect = o["src"]?.toFloat() ?: (16f / 9f)
    val opts = Reframe.Options(aspect, zoom)
    val subjects = Reframe.subjects(samples, srcAspect, aspect, p)
    val keys = Reframe.path(samples, subjects, srcAspect, opts, p)
    out.printWriter().use { w -> for (t in ms) { val c = Reframe.at(keys, t); w.println("$t\t${c.x}\t${c.y}\t${c.w}\t${c.h}") } }
}
