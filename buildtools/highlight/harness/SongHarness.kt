import com.localmediatools.highlight.core.Song
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The app's song code on decoded songs (see song_eval.py): for each <song.f32> (48 kHz stereo
 * float32, little-endian) prints its beat (bpm or "none"), confidence, the start and, for a
 * <video seconds> long video, the loop and level.
 */
fun main(args: Array<String>) {
    val seconds = args[0].toDouble()
    System.getenv("MIN_CONFIDENCE")?.let { Song.MIN_CONFIDENCE = it.toDouble() }
    for (p in args.drop(1)) {
        val b = ByteBuffer.wrap(File(p).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val pcm = FloatArray(b.remaining()).also { b.get(it) }
        val t = System.nanoTime()
        val fit = Song.fit(pcm, 48_000, (seconds * 48_000).toInt())
        val ms = (System.nanoTime() - t) / 1_000_000
        val bpm = fit.beat?.let { "%.2f".format(it.bpm) } ?: "none"
        val conf = "%.3f".format(fit.beat?.confidence ?: Song.lastConfidence)
        println("${File(p).name}\t$bpm\t$conf\t${"%.3f".format(fit.startFrame / 48_000.0)}\t${fit.loopFrames}\t${"%.2f".format(fit.gain)}\t$ms")
    }
}
