import com.localmediatools.codec.raw.FileSource
import com.localmediatools.codec.raw.Mosaic
import com.localmediatools.codec.raw.RawFiles
import com.localmediatools.denoise.core.NoiseEstimator
import com.localmediatools.denoise.core.PhotoDenoiser
import com.localmediatools.denoise.core.RawDenoiser
import com.localmediatools.denoise.core.VideoDenoise
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Checks of the app's RAW code against LibRaw (see check_raw.py, which drives it):
 *   info <raw>...                 what the app finds in each file
 *   dump <raw> <out.u16>          the sensor data as decoded (little-endian, row by row)
 *   write <raw> <in.u16> <out>    the file with its sensor data replaced by in.u16
 *   noise <raw>...                the noise the app measures in each file (per colour-filter site)
 *   crop <in.u16> <w> <h> <max> <h> <template> <search> <out.u16>   RawDenoiser on a crop (no masked area)
 *   denoise <raw> <out> [strength]  the whole file through RawDenoiser, written back in its format
 *   video <dir> <w> <h> <frames> <out dir> <still,lo,hi,range,spatialStill,spatialMoving>...
 *                                 VideoDenoise over f000.rgb… (8-bit RGB) for each parameter set; noise
 *                                 measured from the first 3 frames as the app does
 */
fun main(args: Array<String>) {
    nu.pattern.OpenCV.loadLocally()
    when (args[0]) {
        "video" -> {
            val dir = File(args[1]); val w = args[2].toInt(); val h = args[3].toInt(); val n = args[4].toInt(); val outDir = File(args[5])
            val frames = (0 until n).map { k -> File(dir, "f%03d.rgb".format(k)).readBytes() }
            // Noise as the app measures it: three frames measured together.
            val meter = PhotoDenoiser.Meter()
            for (k in listOf(0, n / 2, n - 1).distinct()) {
                val f = frames[k]
                meter.add(IntArray(w * h) { (0xFF shl 24) or ((f[3 * it].toInt() and 255) shl 16) or ((f[3 * it + 1].toInt() and 255) shl 8) or (f[3 * it + 2].toInt() and 255) }, w, h)
            }
            val sig = VideoDenoise.sigma(meter.result())
            for ((pi, spec) in args.drop(6).withIndex()) {
                val v = spec.split(',').map { it.toFloat() }
                val p = VideoDenoise.Params(v[0], v[1], v[2], v[3], v[4], v[5])
                val od = File(outDir, "p$pi").apply { mkdirs() }
                var prev: FloatArray? = null
                val t = System.nanoTime()
                for (k in 0 until n) {
                    val cur = FloatArray(w * h * 3) { (frames[k][it].toInt() and 255) / 255f }
                    val out = FloatArray(w * h * 3)
                    VideoDenoise.step(cur, prev, w, h, sig, p, out)
                    // The GPU writes 8-bit frames: so does the reference, and the next frame starts from them.
                    val b = ByteArray(out.size) { Math.round(out[it] * 255f).coerceIn(0, 255).toByte() }
                    File(od, "f%03d.rgb".format(k)).writeBytes(b)
                    prev = FloatArray(out.size) { (b[it].toInt() and 255) / 255f }
                }
                println("p$pi\tsigma ${"%.5f".format(sig)}\t${(System.nanoTime() - t) / 1_000_000 / n} ms/frame")
            }
        }
        "noise" -> for (p in args.drop(1)) RandomAccessFile(p, "r").use { f ->
            val c = try { RawFiles.open(FileSource(f)) } catch (e: Exception) { println("${File(p).name}\tUNSUPPORTED\t${e.message}"); return@use }
            val i = c.info
            val m = c.decode()
            val r = RawDenoiser.measure(m.data, i.width, i.active, i.maxValue)
            println("${File(p).name}\t${i.maxValue}\t" + r.models.joinToString("\t") { it?.let { "%.4f %.2f".format(it.slope, it.offset) } ?: "-" })
        }
        "crop" -> {
            val w = args[2].toInt(); val h = args[3].toInt(); val max = args[4].toInt()
            val bytes = File(args[1]).readBytes()
            val data = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
            val t = System.nanoTime()
            val r = RawDenoiser.denoise(data, w, h, intArrayOf(0, 0, h, w), max, 1f, RawDenoiser.Settings(args[5].toFloat(), args[6].toInt(), args[7].toInt()))
            val ms = (System.nanoTime() - t) / 1_000_000
            val bb = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN); bb.asShortBuffer().put(data)
            File(args[8]).writeBytes(bb.array())
            println("$ms\t" + r.models.joinToString("\t") { it?.let { "%.5f %.3f".format(it.slope, it.offset) } ?: "-" })
        }
        "denoise" -> RandomAccessFile(args[1], "r").use { f ->
            val src = FileSource(f)
            val c = RawFiles.open(src); val i = c.info
            var t = System.nanoTime()
            val m = c.decode()
            val dec = (System.nanoTime() - t) / 1_000_000; t = System.nanoTime()
            val r = RawDenoiser.denoise(m.data, i.width, i.height, i.active, i.maxValue, args.getOrNull(3)?.toFloat() ?: 1f)
            val den = (System.nanoTime() - t) / 1_000_000; t = System.nanoTime()
            val patches = c.encode(m)
            File(args[2]).outputStream().buffered().use { RawFiles.write(src, patches, it) }
            val enc = (System.nanoTime() - t) / 1_000_000
            println("${File(args[1]).name}: decode $dec ms, denoise $den ms, encode+write $enc ms; noise ${"%.4f".format(r.noiseShare)}; size ${src.size} -> ${File(args[2]).length()}")
        }
        "info" -> for (p in args.drop(1)) {
            val name = File(p).name
            try {
                RandomAccessFile(p, "r").use { f ->
                    val c = RawFiles.open(FileSource(f))
                    val i = c.info
                    println("$name\t${i.format}\t${i.variant}\t${i.width}x${i.height}\tmax ${i.maxValue}\tactive ${i.active.toList()}")
                }
            } catch (e: Exception) { println("$name\tUNSUPPORTED\t${e.javaClass.simpleName}: ${e.message}") }
        }
        "dump" -> RandomAccessFile(args[1], "r").use { f ->
            val t = System.nanoTime()
            val m = RawFiles.open(FileSource(f)).decode()
            val ms = (System.nanoTime() - t) / 1_000_000
            val bb = ByteBuffer.allocate(m.data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(m.data)
            File(args[2]).writeBytes(bb.array())
            println("${m.width}x${m.height} decoded in $ms ms")
        }
        "write" -> RandomAccessFile(args[1], "r").use { f ->
            val src = FileSource(f)
            val c = RawFiles.open(src)
            val bytes = File(args[2]).readBytes()
            val data = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
            val t = System.nanoTime()
            val patches = c.encode(Mosaic(c.info.width, c.info.height, data))
            val ms = (System.nanoTime() - t) / 1_000_000
            File(args[3]).outputStream().buffered().use { RawFiles.write(src, patches, it) }
            println("encoded in $ms ms, ${patches.size} patches, ${File(args[3]).length() - src.size} bytes added")
        }
        else -> error("unknown command ${args[0]}")
    }
}
