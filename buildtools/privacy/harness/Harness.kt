import com.localmediatools.vision.core.CodeDetector
import com.localmediatools.vision.core.PlateDetector
import com.localmediatools.vision.core.PrivacyFinder
import com.localmediatools.vision.core.PrivacyOptions
import com.localmediatools.vision.core.PrivateText
import com.localmediatools.vision.core.TextDetector
import com.localmediatools.vision.core.TextReader
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs the app's private-details finder on photos, as the app does (each photo at up to 1600 px):
 *
 *     run.sh find <plates,private,text,codes> <list> <out.tsv>
 *     run.sh classify               (lines of text on stdin → verdict per line)
 *
 * <list>: one photo per line, optionally a tab and vehicle boxes "x0,y0,x1,y1;…" in the photo's
 * pixels. Writes per found thing: photo number, kind, details, the eight corner coordinates (photo
 * pixels) and the text read. Models come from the app's assets.
 */
fun main(args: Array<String>) {
    nu.pattern.OpenCV.loadLocally()
    val assets = File("app/src/main/assets")
    fun lines(n: String) = File(assets, "privacy/$n").readLines().filter { it.isNotBlank() }
    val classifier = PrivateText(lines("first_names.txt").toHashSet(), lines("surnames.txt").toHashSet())
    if (args[0] == "classify") {
        generateSequence(::readLine).forEach { l -> val v = classifier.classify(l); println("${v.details.joinToString(",")}\t${v.label}\t${v.labelOnly}\t$l") }
        return
    }
    val want = args[1].split(',').toSet()
    if ("lines" in want) {
        // Every line found, what was read and the verdict (for looking into misses).
        val det = TextDetector(File(assets, "models/text_detection_ppocrv3_en_fp16.onnx").path)
        val reader = TextReader(File(assets, "models/text_recognition_ppocrv5_mobile_fp16.onnx").path, File(assets, "privacy/ppocrv5_dict.txt").readLines())
        val out = File(args[3]).printWriter()
        for ((i, row) in File(args[2]).readLines().filter { it.isNotBlank() }.withIndex()) {
            val img = Imgcodecs.imread(row.split('\t')[0])
            val long = max(img.cols(), img.rows()); val s = if (long > 1280) 1280f / long else 1f
            val small = if (s < 1f) Mat().also { Imgproc.resize(img, it, Size((img.cols() * s).roundToInt().toDouble(), (img.rows() * s).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA) } else img
            for (f in det.detect(small)) {
                val q = f.quad.map(1 / s, 1 / s)
                val t = reader.read(img, q)
                out.println("$i\tTEXT\t${classifier.classify(t).details.joinToString(",")}\t${q.p.joinToString(",") { "%.1f".format(it) }}\t${t.replace('\t', ' ')}")
            }
        }
        out.close(); return
    }
    val opts = PrivacyOptions(plates = "plates" in want, privateText = "private" in want, allText = "text" in want, codes = "codes" in want)
    val finder = PrivacyFinder(
        PlateDetector(File(assets, "models/license_plate_lpd_yunet_2023mar_fp16.onnx").path),
        TextDetector(File(assets, "models/text_detection_ppocrv3_en_fp16.onnx").path),
        TextReader(File(assets, "models/text_recognition_ppocrv5_mobile_fp16.onnx").path, File(assets, "privacy/ppocrv5_dict.txt").readLines()),
        classifier, CodeDetector())
    val out = File(args[3]).printWriter()
    val t0 = System.nanoTime()
    var n = 0
    for ((i, row) in File(args[2]).readLines().filter { it.isNotBlank() }.withIndex()) {
        val parts = row.split('\t')
        val img = Imgcodecs.imread(parts[0])
        if (img.empty()) { System.err.println("can't read ${parts[0]}"); continue }
        val long = max(img.cols(), img.rows())
        val s = if (long > 1600) 1600f / long else 1f
        val work = if (s < 1f) Mat().also { Imgproc.resize(img, it, Size((img.cols() * s).roundToInt().toDouble(), (img.rows() * s).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA) } else img
        val vehicles = parts.getOrNull(1)?.split(';')?.filter { it.isNotBlank() }?.map { v -> v.split(',').map { it.toFloat() * s }.toFloatArray() } ?: emptyList()
        for (f in finder.find(work, opts, vehicles)) {
            val q = f.quad.map(1 / s, 1 / s)
            out.println("$i\t${f.kind}\t${f.details.joinToString(",")}\t${q.p.joinToString(",") { "%.1f".format(it) }}\t${f.text.replace('\t', ' ')}")
        }
        n++
        if (work !== img) work.release()
        img.release()
    }
    out.close()
    System.err.println("$n photos, ${(System.nanoTime() - t0) / 1_000_000 / max(1, n)} ms per photo")
}
