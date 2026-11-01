package com.localmediatools.gallery

import android.content.Context
import android.graphics.Bitmap
import com.localmediatools.gallery.core.Embedding
import com.localmediatools.gallery.core.MatchScale
import com.localmediatools.gallery.core.PilResample
import com.localmediatools.gallery.core.TextTokenizer
import com.localmediatools.gallery.core.TokenTable
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The Gallery's search model on this phone: SigLIP 2 B/32 (Google, Apache 2.0) in TensorFlow Lite
 * with 8-bit weights, on the CPU. Its image side describes each photo while the library is indexed
 * ([SearchImages]); its text side describes what is typed into search ([SearchTexts]); a photo
 * matches a text as well as the two descriptions agree ([MatchScale]). Each side is loaded when
 * first needed and freed separately (they are about 95 and 120 MB).
 *
 * Each side checks itself once on this phone: it must describe a fixed picture and a fixed text as
 * they were described when the model was converted (references in search/model.json), so a phone
 * whose CPU runs the model wrong searches by categories as before instead of giving wrong matches.
 */
object SearchModels {
    private const val IMAGE = "models/search_image_siglip2_b32.tflite"
    private const val TEXT = "models/search_text_siglip2_b32.tflite"
    private const val TOKENIZER = "search/tokenizer.bin"
    private const val TOKENS = "search/tokens.emb"
    private const val CONFIG = "search/model.json"
    /** The image side's input: the whole photo squashed to a square of this side. */
    const val SIZE = 256
    /** The text side's input: this many tokens (<eos> and padding included). */
    const val LENGTH = 64
    /** Self-test: a description at least this close to the reference passes (8-bit runs give 0.99+). */
    private const val SAME = 0.97f

    /** Stands in for the models where they can't run (JVM and Robolectric tests): text → embedding. */
    @Volatile var textOverride: ((String) -> FloatArray?)? = null
    @Volatile var imageOverride: ((Bitmap) -> FloatArray?)? = null
    @Volatile var scaleOverride: MatchScale? = null

    private class Config(val scale: MatchScale, val imageRef: FloatArray?, val text: String?, val textRef: FloatArray?)

    @Volatile private var config: Config? = null
    @Volatile private var images: SearchImages? = null
    @Volatile private var texts: SearchTexts? = null
    @Volatile private var imageBroken = false
    @Volatile private var textBroken = false

    private fun config(ctx: Context): Config? = config ?: synchronized(this) {
        config ?: try {
            val j = JSONObject(ctx.assets.open(CONFIG).bufferedReader().readText())
            fun floats(key: String) = j.optJSONArray(key)?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            Config(MatchScale(j.getDouble("logit_scale").toFloat(), j.getDouble("logit_bias").toFloat()),
                floats("selftest_image"), j.optString("selftest_text").takeIf { it.isNotEmpty() }, floats("selftest_text_embedding"))
        } catch (e: Exception) { android.util.Log.w("LMT", "search model missing", e); null }.also { config = it }
    }

    /** How sure matches are (null: no search model in this build). */
    fun scale(ctx: Context): MatchScale? = scaleOverride ?: config(ctx)?.scale

    /** The image side, or null where it can't run (it is then not tried again until the app restarts). */
    fun images(ctx: Context): SearchImages? {
        if (imageOverride != null) return SearchImages(null)
        images?.let { return it }
        if (imageBroken) return null
        return synchronized(this) {
            images ?: if (imageBroken) null else try {
                val cfg = config(ctx) ?: throw IllegalStateException("no search model config")
                val s = SearchImages(Interpreter(map(ctx, IMAGE), options()))
                if (cfg.imageRef != null && Embedding.cosine(s.embedPattern(), cfg.imageRef) < SAME) {
                    android.util.Log.w("LMT", "search image self-test failed"); s.close(); imageBroken = true; null
                } else s.also { images = it }
            } catch (t: Throwable) { android.util.Log.w("LMT", "search images unavailable", t); imageBroken = true; null }
        }
    }

    /** The text side, or null where it can't run. */
    fun texts(ctx: Context): SearchTexts? {
        if (textOverride != null) return SearchTexts(null, null, null)
        texts?.let { return it }
        if (textBroken) return null
        return synchronized(this) {
            texts ?: if (textBroken) null else try {
                val cfg = config(ctx) ?: throw IllegalStateException("no search model config")
                val tok = ctx.assets.open(TOKENIZER).use { TextTokenizer.read(it) }
                val s = SearchTexts(Interpreter(map(ctx, TEXT), options()), tok, TokenTable(map(ctx, TOKENS)))
                if (cfg.text != null && cfg.textRef != null && Embedding.cosine(s.embed(cfg.text) ?: FloatArray(Embedding.DIM), cfg.textRef) < SAME) {
                    android.util.Log.w("LMT", "search text self-test failed"); s.close(); textBroken = true; null
                } else s.also { texts = it }
            } catch (t: Throwable) { android.util.Log.w("LMT", "search texts unavailable", t); textBroken = true; null }
        }
    }

    /** Frees the image side (after indexing). */
    fun releaseImages() = synchronized(this) { images?.close(); images = null }

    /** Frees the text side (when search is closed). */
    fun releaseTexts() = synchronized(this) { texts?.close(); texts = null }

    /** Back to a clean state (tests). */
    fun resetForTests() = synchronized(this) {
        releaseImages(); releaseTexts(); imageBroken = false; textBroken = false
        textOverride = null; imageOverride = null; scaleOverride = null
    }

    private fun options() = Interpreter.Options().setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))

    private fun map(ctx: Context, asset: String): MappedByteBuffer {
        val afd = ctx.assets.openFd(asset)
        return afd.use { FileInputStream(it.fileDescriptor).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength) } }
    }

    /** The image side: a photo (any size) → its description (length 1). */
    class SearchImages internal constructor(private val model: Interpreter?) {
        private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        private val output = Array(1) { FloatArray(Embedding.DIM) }

        /** [bmp] squashed to [SIZE] × [SIZE] as the model was trained (Pillow's bilinear), colours to -1…1. */
        fun embed(bmp: Bitmap): FloatArray? {
            imageOverride?.let { return it(bmp) }
            val all = IntArray(bmp.width * bmp.height)
            bmp.getPixels(all, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return run(PilResample.resize(all, bmp.width, bmp.height, SIZE, SIZE))
        }

        /** The self-test picture: red across, green down, blue their mean (also in build_search_model.py). */
        internal fun embedPattern(): FloatArray = run(IntArray(SIZE * SIZE) { i ->
            val x = i % SIZE; val y = i / SIZE
            (0xFF shl 24) or (x shl 16) or (y shl 8) or ((x + y) / 2)
        })!!

        private fun run(px: IntArray): FloatArray? = synchronized(this) {
            val m = model ?: return null
            input.rewind()
            for (c in px) {
                input.putFloat(((c shr 16) and 255) / 127.5f - 1f)
                input.putFloat(((c shr 8) and 255) / 127.5f - 1f)
                input.putFloat((c and 255) / 127.5f - 1f)
            }
            input.rewind()
            m.run(input, output)
            val e = output[0].copyOf()
            if (e.any { it.isNaN() || it.isInfinite() }) return null
            Embedding.normalize(e)
        }

        internal fun close() = synchronized(this) { model?.close() }
    }

    /** The text side: a text → its description (length 1). */
    class SearchTexts internal constructor(private val model: Interpreter?, private val tokenizer: TextTokenizer?, private val table: TokenTable?) {
        private val input = ByteBuffer.allocateDirect(LENGTH * Embedding.DIM * 4).order(ByteOrder.nativeOrder())
        private val output = Array(1) { FloatArray(Embedding.DIM) }
        private val row = FloatArray(Embedding.DIM)
        private val recent = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 32
        }

        fun embed(text: String): FloatArray? {
            textOverride?.let { return it(text) }
            val m = model ?: return null
            synchronized(this) {
                recent[text]?.let { return it }
                val rows = tokenizer!!.encode(text, LENGTH)
                input.rewind()
                for (r in rows) { table!!.row(r, row); for (v in row) input.putFloat(v) }
                input.rewind()
                m.run(input, output)
                val e = output[0].copyOf()
                if (e.any { it.isNaN() || it.isInfinite() }) return null
                return Embedding.normalize(e).also { recent[text] = it }
            }
        }

        internal fun close() = synchronized(this) { model?.close() }
    }
}
