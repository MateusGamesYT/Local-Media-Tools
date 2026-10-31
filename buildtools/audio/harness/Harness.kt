import com.localmediatools.audio.core.LoudnessMeter
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the app's audio core on raw sound: `loudness <rate> <channels> <file.f32>` reads interleaved
 * 32-bit float samples (as `ffmpeg -f f32le` writes them) and prints the meter's result.
 */
fun main(args: Array<String>) {
    when (args[0]) {
        "resample" -> {
            // resample <inRate> <outRate> <channels> <in.f32> <out.f32>
            val r = com.localmediatools.audio.core.SincResampler(args[1].toInt(), args[2].toInt(), args[3].toInt())
            val inp = readAll(File(args[4]))
            val t = System.nanoTime()
            val out = com.localmediatools.audio.core.SincResampler.concat(r.process(inp), r.flush())
            System.err.println("resampled ${inp.size / args[3].toInt()} → ${out.size / args[3].toInt()} frames in ${(System.nanoTime() - t) / 1_000_000} ms")
            writeAll(File(args[5]), out)
        }
        "flac" -> {
            // flac <rate> <channels> <in.s16le> <out.flac>
            val rate = args[1].toInt(); val ch = args[2].toInt()
            val raw = File(args[3]).readBytes()
            val sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = IntArray(sb.remaining()) { sb.get().toInt() }
            val t = System.nanoTime()
            val f = File(args[4])
            val os = f.outputStream().buffered(1 shl 20)
            val enc = com.localmediatools.audio.core.FlacEncoder(os, rate, ch, 16, comments = listOf("TITLE" to "Test"))
            var i = 0
            while (i < samples.size) { val n = minOf(samples.size - i, 10000 * ch); enc.add(samples.copyOfRange(i, i + n)); i += n }
            val info = enc.finish(); os.close()
            java.io.RandomAccessFile(f, "rw").use { it.seek(enc.streamInfoOffset); it.write(info) }
            System.err.println("encoded ${samples.size / ch} frames in ${(System.nanoTime() - t) / 1_000_000} ms → ${f.length()} bytes")
        }
        "mp3" -> {
            // mp3 <rate> <channels> <kbps> <in.f32> <out.mp3>
            val rate = args[1].toInt(); val ch = args[2].toInt(); val kbps = args[3].toInt()
            val x = readAll(File(args[4]))
            val f = File(args[5])
            val t = System.nanoTime()
            val os = f.outputStream().buffered(1 shl 20)
            val enc = com.localmediatools.audio.core.Mp3Encoder(os, rate, ch, kbps)
            enc.allShort = System.getenv("LMT_MP3_ALLSHORT") == "1"
            var i = 0
            while (i < x.size) { val n = minOf(x.size - i, 4096 * ch); enc.add(x.copyOfRange(i, i + n)); i += n }
            var info: ByteArray? = null
            enc.finish { _, b -> info = b }; os.close()
            java.io.RandomAccessFile(f, "rw").use { it.seek(0); it.write(info!!) }
            System.err.println("mp3: ${x.size / ch} frames in ${(System.nanoTime() - t) / 1_000_000} ms → ${f.length()} bytes, ${enc.shortGranules} short granules, ${enc.stuffedBytes} bytes stuffed")
        }
        "loudness" -> {
            val rate = args[1].toInt(); val ch = args[2].toInt()
            val m = LoudnessMeter(rate, ch)
            File(args[3]).inputStream().buffered(1 shl 20).use { inp ->
                val buf = ByteArray(ch * 4 * 4096)
                while (true) {
                    var n = 0
                    while (n < buf.size) { val r = inp.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    if (n == 0) break
                    val fb = ByteBuffer.wrap(buf, 0, n - n % (4 * ch)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val f = FloatArray(fb.remaining()); fb.get(f)
                    m.add(f)
                    if (n < buf.size) break
                }
            }
            println(m.result())
        }
    }
}

fun readAll(f: File): FloatArray {
    val b = f.readBytes()
    val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(fb.remaining()).also { fb.get(it) }
}

fun writeAll(f: File, x: FloatArray) {
    val bb = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    bb.asFloatBuffer().put(x)
    f.writeBytes(bb.array())
}
