package com.localmediatools.vision.core

import com.localmediatools.codec.edit.AdjustKey
import com.localmediatools.codec.edit.Adjustments
import com.localmediatools.codec.edit.ColorPipeline
import com.localmediatools.i18n.N_
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class SceneKind {
    PORTRAIT, LANDSCAPE, FOOD, ANIMAL, PLANT, CITY, DOCUMENT, NIGHT, GENERAL;

    /** The kind of photo ("Portrait" a photo of a person, not the page orientation). */
    val label: String get() = when (this) {
        PORTRAIT -> trc("kind of photo", "Portrait"); LANDSCAPE -> trc("kind of photo", "Landscape"); FOOD -> tr("Food"); ANIMAL -> tr("Animal")
        PLANT -> tr("Plants & flowers"); CITY -> tr("City & buildings"); DOCUMENT -> tr("Document"); NIGHT -> tr("Night"); GENERAL -> tr("General")
    }
}

/** Maps the gallery's recognised categories to the scene kinds auto-enhance cares about. */
object SceneMapper {
    private val kinds: Map<SceneKind, Set<String>> = mapOf(
        SceneKind.LANDSCAPE to setOf("beach", "sea", "mountain", "lake", "river", "waterfall", "forest", "desert", "snow", "sky", "sunset", "grass", "rock"),
        SceneKind.FOOD to setOf("food", "pizza", "cake", "burger", "sushi", "fruit", "coffee", "drink", "wine", "beer", "cocktail"),
        SceneKind.ANIMAL to setOf("animal", "dog", "cat", "bird", "horse", "fish", "butterfly", "cow", "sheep", "goat", "pig", "elephant",
            "zebra", "giraffe", "monkey", "lion", "tiger", "rabbit", "reptile"),
        SceneKind.PLANT to setOf("flower"),
        SceneKind.CITY to setOf("city", "street", "bridge", "castle"),
        SceneKind.DOCUMENT to setOf("document"),
        SceneKind.NIGHT to setOf("night"),
    )

    /** [scores] are category → fused score (0.5 = recognised). The strongest recognised kind wins. */
    fun fromCategories(scores: Map<String, Float>): Pair<SceneKind, Float> {
        var best = SceneKind.GENERAL; var bs = 0f
        for ((kind, keys) in kinds) {
            val s = keys.maxOfOrNull { scores[it] ?: 0f } ?: 0f
            // Night beats landscape when both hold (a city or beach at night is enhanced as night).
            val boost = if (kind == SceneKind.NIGHT) 0.05f else 0f
            if (s + boost > bs) { bs = s + boost; best = kind }
        }
        return if (bs >= 0.5f) best to minOf(1f, bs) else SceneKind.GENERAL to bs
    }
}

/** A face box as fractions (0..1) of the image. */
data class FaceBox(val x: Float, val y: Float, val w: Float, val h: Float)

/** [notes] are in English ("lifted shadows"): shown with [shownNotes]. */
class EnhanceResult(val adjust: Adjustments, val scene: SceneKind, val notes: List<String>) {
    /** What was changed, in the app's language. */
    val shownNotes: String get() = notes.joinToString(trc("between the items of a list", ", ")) { tr(it) }
}

/**
 * One-tap enhancement: measures exposure, contrast, clipping, colour cast and saturation, and uses
 * the faces and scene found by the on-device models to choose sensible editor settings (which stay
 * editable). [argb] is a small copy of the photo (e.g. 384 px on the long side).
 */
object AutoEnhance {
    private fun luma(c: Int): Float = (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f
    private fun lin(v: Float) = ColorPipeline.srgbToLinear(v.toDouble())
    private fun log2(x: Double) = ln(x) / ln(2.0)

    /** What [analyze] looks at in a photo: its tones, colour, colour of the light and faces. */
    class Stats(
        /** Luma percentiles (0..1). */
        val p1: Float, val p5: Float, val p50: Float, val p95: Float, val p99: Float,
        /** Share of pixels at the very top (250–255) and in the deep shadows (0–30). */
        val clipHi: Float, val dark: Float,
        /** Mean saturation of the mid-tones. */
        val meanSat: Float,
        /** Colour of the light from near-neutral mid-tones: red minus blue, and green minus magenta (relative); null when too few such pixels. */
        val warmCast: Float?, val greenCast: Float?,
        /** Median luma of the faces, or −1 without faces; their total area (fraction of the photo) and number. */
        val faceLuma: Float, val faceArea: Float, val faceCount: Int,
        /** Skin colour: median red minus blue (relative) in the middle of the faces; 0 without faces. Skin is warm, so a low value means a cold cast. */
        val faceWarm: Float,
    ) {
        fun csv() = listOf(p1, p5, p50, p95, p99, clipHi, dark, meanSat, warmCast ?: 0f, greenCast ?: 0f, if (warmCast == null) 0f else 1f, faceLuma, faceArea, faceCount.toFloat(), faceWarm)
            .joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) }

        companion object { const val HEADER = "p1,p5,p50,p95,p99,clip_hi,dark,mean_sat,warm_cast,green_cast,has_cast,face_luma,face_area,face_count,face_warm" }
    }

    fun measure(argb: IntArray, w: Int, h: Int, faces: List<FaceBox>): Stats {
        val n = argb.size
        val hist = IntArray(256)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var cnt = 0
        var satSum = 0.0; var satCnt = 0
        for (c in argb) {
            if ((c ushr 24) < 128) continue
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val l = luma(c)
            hist[(l * 255).toInt().coerceIn(0, 255)]++
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            if (l in 0.12f..0.88f) {
                satSum += sat; satCnt++
                // Only near-neutral pixels tell the colour of the light (not blue sky or green grass).
                if (sat < 0.35f) { sr += r; sg += g; sb += b; cnt++ }
            }
        }
        val total = hist.sum().coerceAtLeast(1)
        fun pct(p: Float): Float { var acc = 0; for (i in 0 until 256) { acc += hist[i]; if (acc >= p * total) return i / 255f }; return 1f }
        var warmCast: Float? = null; var greenCast: Float? = null
        if (cnt > n / 50) {
            val r = sr / cnt; val g = sg / cnt; val b = sb / cnt
            val m = max(r, max(g, b)).coerceAtLeast(1.0)
            warmCast = ((r - b) / m).toFloat()
            greenCast = ((g - (r + b) / 2) / m).toFloat()
        }
        var faceLuma = -1f
        var faceWarm = 0f
        if (faces.isNotEmpty()) {
            val fl = ArrayList<Float>(); val fw = ArrayList<Float>()
            for (f in faces) {
                val x0 = (f.x * w).toInt().coerceIn(0, w - 1); val x1 = ((f.x + f.w) * w).toInt().coerceIn(x0 + 1, w)
                val y0 = (f.y * h).toInt().coerceIn(0, h - 1); val y1 = ((f.y + f.h) * h).toInt().coerceIn(y0 + 1, h)
                for (y in y0 until y1) for (x in x0 until x1) fl.add(luma(argb[y * w + x]))
                // The middle of the face (cheeks, nose), clear of hair and background.
                val ix0 = x0 + (x1 - x0) / 4; val ix1 = maxOf(ix0 + 1, x1 - (x1 - x0) / 4)
                val iy0 = y0 + (y1 - y0) / 4; val iy1 = maxOf(iy0 + 1, y1 - (y1 - y0) / 4)
                for (y in iy0 until iy1) for (x in ix0 until ix1) {
                    val c = argb[y * w + x]
                    val r = (c shr 16) and 255; val b = c and 255; val m = max(r, max((c shr 8) and 255, b))
                    if (m >= 24) fw.add((r - b).toFloat() / m)
                }
            }
            if (fl.isNotEmpty()) { fl.sort(); faceLuma = fl[fl.size / 2] }
            if (fw.isNotEmpty()) { fw.sort(); faceWarm = fw[fw.size / 2] }
        }
        return Stats(pct(0.01f), pct(0.05f), pct(0.5f), pct(0.95f), pct(0.99f),
            (250..255).sumOf { hist[it] }.toFloat() / total, (0..30).sumOf { hist[it] }.toFloat() / total,
            if (satCnt > 0) (satSum / satCnt).toFloat() else 0f, warmCast, greenCast,
            faceLuma, faces.sumOf { (it.w * it.h).toDouble() }.toFloat(), faces.size, faceWarm)
    }

    /** False: the hand-written rules of 1.8 for every photo (for comparisons). */
    @Volatile var learned = true

    fun analyze(argb: IntArray, w: Int, h: Int, faces: List<FaceBox>, sceneHint: SceneKind, strength: Float = 1f): EnhanceResult {
        val st = measure(argb, w, h, faces)
        // Scene: faces and darkness override the classifier.
        val scene = when {
            faces.isNotEmpty() && (st.faceArea >= 0.012f || faces.size >= 2) -> SceneKind.PORTRAIT
            st.p50 < 0.16f && st.p95 < 0.62f -> SceneKind.NIGHT
            else -> sceneHint
        }
        // Documents aren't among the photos the model learned from: they keep the rules.
        val r = if (learned && scene != SceneKind.DOCUMENT) byModel(st, scene) else byRules(st, scene)
        if (strength == 1f) return r
        var scaled = Adjustments()
        for ((k, v) in r.adjust.values) scaled = scaled.with(k, v * strength)
        return EnhanceResult(scaled, r.scene, r.notes)
    }

    /** The features [MODEL] reads (buildtools/enhance/fit_model.py builds the same). */
    fun features(st: Stats, scene: SceneKind): FloatArray {
        val hasFace = st.faceLuma >= 0
        val evMid = log2(lin(0.46f) / lin(max(st.p50, 0.02f)).coerceAtLeast(1e-4)).toFloat()
        val evFace = if (hasFace) log2(lin(0.56f) / lin(max(st.faceLuma, 0.02f)).coerceAtLeast(1e-4)).toFloat() else 0f
        val cast = if (st.warmCast != null) 1f else 0f
        // Plants are scored as landscapes, food and anything else as a general photo.
        val s = when (scene) { SceneKind.PLANT -> SceneKind.LANDSCAPE; else -> scene }
        return floatArrayOf(evMid, evFace, if (hasFace) 1f else 0f, min(st.faceArea, 0.5f),
            st.p1, st.p5, st.p50, st.p95, st.p99, st.p95 - st.p5, min(st.clipHi * 10, 1f), min(st.dark * 2, 1f), st.meanSat,
            (st.warmCast ?: 0f) * cast, (st.greenCast ?: 0f) * cast, cast,
            if (s == SceneKind.PORTRAIT) 1f else 0f, if (s == SceneKind.LANDSCAPE) 1f else 0f, if (s == SceneKind.ANIMAL) 1f else 0f,
            if (s == SceneKind.CITY) 1f else 0f, if (s == SceneKind.NIGHT) 1f else 0f, if (hasFace) st.faceWarm else 0f)
    }

    private val MODEL_KEYS = listOf(AdjustKey.EXPOSURE, AdjustKey.BRIGHTNESS, AdjustKey.CONTRAST, AdjustKey.HIGHLIGHTS, AdjustKey.SHADOWS,
        AdjustKey.WARMTH, AdjustKey.TINT, AdjustKey.VIBRANCE, AdjustKey.SATURATION)

    // Fitted by buildtools/enhance/fit_model.py (ridge, lambda 0.1) on 320 MIT-Adobe FiveK photos (expert C), and their 320 finished versions (no change, weight 0.5; 0.2 of it for exposure and brightness).
    // Features: ev_mid, ev_face, has_face, face_area, p1, p5, p50, p95, p99, spread, clip, dark, sat, warm, green, has_cast, scene_portrait, scene_landscape, scene_animal, scene_city, scene_night, face_warm
    private val MODEL = arrayOf(
        floatArrayOf(0.28782f, 0.02887f, 0.04849f, -0.05376f, 0.03538f, 0.01754f, -0.28035f, -0.44898f, -0.16295f, 0.21086f, -0.05549f, -0.02376f, 0.03430f, 0.11332f, 0.19533f, 0.02496f, -0.06814f, -0.00270f, 0.05363f, -0.08324f, 0.01600f, -0.06844f, 0.03212f), // exposure
        floatArrayOf(0.30750f, 0.00546f, 0.05283f, -0.01387f, 0.05504f, -0.24132f, -0.48873f, 0.01379f, -0.04683f, -0.06695f, 0.13163f, -0.00900f, 0.10340f, -0.02114f, -0.07224f, 0.02969f, -0.09651f, -0.01454f, -0.04833f, 0.00833f, -0.01581f, -0.17548f, -0.11309f), // brightness
        floatArrayOf(0.18748f, 0.01889f, 0.03327f, -0.03930f, 0.20623f, 2.09038f, 0.07693f, -0.23264f, 0.05796f, 0.15274f, 0.02610f, -0.07573f, 0.00237f, -0.11334f, 0.36501f, -0.23930f, -0.19076f, 0.01483f, 0.00419f, -0.10965f, -0.03181f, 0.00083f, -0.04387f), // contrast
        floatArrayOf(0.02260f, -0.01693f, -0.03068f, 0.01488f, 0.09140f, -1.35629f, 0.50357f, 0.50173f, 0.16732f, -0.53654f, -0.02150f, 0.30067f, 0.12851f, 0.22689f, -0.27584f, -0.23492f, -0.12824f, -0.00891f, -0.06279f, -0.02029f, -0.06080f, -0.00416f, -0.13064f), // highlights
        floatArrayOf(-0.07251f, 0.00766f, -0.04714f, 0.01873f, -0.07875f, -1.90553f, -0.35768f, -0.15927f, -0.01353f, -0.25122f, 0.11079f, 0.06209f, -0.04917f, 0.14907f, -0.08011f, 0.31614f, 0.18315f, -0.00366f, -0.08164f, 0.05575f, -0.02115f, 0.03249f, 0.07584f), // shadows
        floatArrayOf(-0.27660f, 0.00000f, 0.00000f, 0.16577f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, -0.60051f, 0.86209f, 0.16818f, 0.08671f, 0.07712f, 0.05762f, -0.04981f, 0.05221f, -0.62604f), // warmth
        floatArrayOf(-0.29108f, 0.00000f, 0.00000f, -0.07063f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.00000f, 0.37546f, 1.71260f, 0.26739f, 0.02304f, -0.13111f, 0.01943f, -0.00112f, -0.00969f, 0.31898f), // tint
        floatArrayOf(-0.27557f, 0.01224f, -0.00047f, 0.07186f, -0.46683f, 0.06898f, 0.04647f, -0.13882f, -0.05057f, 0.37412f, -0.06185f, 0.09717f, -0.09309f, -0.36370f, -0.28082f, 0.10186f, 0.31326f, 0.00119f, 0.02604f, -0.02261f, 0.04229f, -0.12753f, -0.31755f), // vibrance
        floatArrayOf(-0.18574f, 0.00818f, 0.00286f, 0.04079f, -0.10630f, 0.29133f, 0.06564f, -0.06029f, -0.09207f, 0.29376f, -0.10609f, -0.01137f, -0.03074f, -0.22499f, -0.09338f, 0.03410f, 0.18660f, 0.03465f, 0.06020f, -0.02994f, 0.01238f, -0.12329f, -0.27625f), // saturation
    )

    /**
     * The sliders an expert would most likely set (learned from MIT-Adobe FiveK's expert C, see
     * buildtools/enhance/README.md), kept within the range the expert used, plus scene-based detail.
     */
    private fun byModel(st: Stats, scene: SceneKind): EnhanceResult {
        val f = features(st, scene)
        var a = Adjustments()
        for ((j, k) in MODEL_KEYS.withIndex()) {
            val row = MODEL[j]
            var v = row[0]
            for (i in f.indices) v += row[i + 1] * f[i]
            a = a.with(k, v.coerceIn(-LIMIT, LIMIT))
        }
        a = a.with(AdjustKey.SHARPNESS, sharpness(scene))
        return EnhanceResult(a, scene, notesFor(a, st))
    }

    private const val LIMIT = 0.8f

    private fun sharpness(scene: SceneKind) = when (scene) {
        SceneKind.LANDSCAPE, SceneKind.CITY -> 0.3f; SceneKind.DOCUMENT -> 0.35f; SceneKind.FOOD, SceneKind.PLANT, SceneKind.ANIMAL -> 0.22f
        SceneKind.PORTRAIT -> 0.08f; SceneKind.NIGHT -> 0f; else -> 0.15f
    }

    /** What the settings do, in a few words each (English, shown with [EnhanceResult.shownNotes]). */
    private fun notesFor(a: Adjustments, st: Stats): List<String> {
        val notes = ArrayList<String>()
        val lighter = a[AdjustKey.EXPOSURE] + a[AdjustKey.BRIGHTNESS] * 0.5f
        if (lighter > 0.06f) notes.add(if (st.faceLuma >= 0) N_("brightened the faces") else N_("brightened"))
        else if (lighter < -0.06f) notes.add(N_("toned down the brightness"))
        if (a[AdjustKey.HIGHLIGHTS] < -0.1f && st.clipHi > 0.002f) notes.add(N_("recovered highlights"))
        if (a[AdjustKey.SHADOWS] > 0.1f) notes.add(N_("lifted shadows"))
        if (a[AdjustKey.CONTRAST] > 0.1f) notes.add(N_("added contrast"))
        val warmth = a[AdjustKey.WARMTH]; val warm = st.warmCast ?: 0f
        if (warmth < -0.08f && warm > 0.04f) notes.add(N_("cooled a yellow cast"))
        else if (warmth > 0.08f && warm < -0.04f) notes.add(N_("warmed a blue cast"))
        else if (kotlin.math.abs(warmth) > 0.08f) notes.add(N_("balanced colours"))
        val tint = a[AdjustKey.TINT]
        if (kotlin.math.abs(tint) > 0.08f) notes.add(if (tint < 0) N_("removed a green tint") else N_("removed a magenta tint"))
        if (a[AdjustKey.VIBRANCE] + a[AdjustKey.SATURATION] > 0.1f) notes.add(N_("richer colours"))
        return notes
    }

    /** The hand-written rules (1.2–1.8): still used for documents. */
    private fun byRules(st: Stats, scene: SceneKind): EnhanceResult {
        val p5 = st.p5; val p50 = st.p50; val p95 = st.p95; val p99 = st.p99
        val clipHi = st.clipHi; val dark = st.dark; val meanSat = st.meanSat
        val notes = ArrayList<String>()
        var a = Adjustments()

        // Exposure toward a scene-dependent mid-tone, steered by the faces when there are any.
        val target = when (scene) { SceneKind.NIGHT -> 0.30f; SceneKind.DOCUMENT -> 0.62f; SceneKind.PORTRAIT -> 0.48f; else -> 0.46f }
        val median = p50
        val faceLuma = st.faceLuma
        var ev = log2(lin(target) / lin(median.coerceAtLeast(0.02f)).coerceAtLeast(1e-4))
        if (faceLuma >= 0) {
            val faceEv = log2(lin(0.56f) / lin(faceLuma.coerceAtLeast(0.02f)).coerceAtLeast(1e-4))
            ev = 0.35 * ev + 0.65 * faceEv
        }
        ev = ev.coerceIn(-0.7, 1.5) * 0.8
        if (ev > 0 && clipHi > 0.02f) ev *= (0.02 / clipHi).coerceIn(0.3, 1.0)
        if (scene == SceneKind.NIGHT) ev = ev.coerceAtMost(0.35)
        if (kotlin.math.abs(ev) > 0.08) {
            a = a.with(AdjustKey.EXPOSURE, (ev / 2).toFloat())
            notes.add(if (faceLuma >= 0 && ev > 0) N_("brightened the faces") else if (ev > 0) N_("brightened") else N_("toned down the brightness"))
        }

        // Highlights and shadows.
        if (p99 > 0.95f && clipHi > 0.006f) {
            a = a.with(AdjustKey.HIGHLIGHTS, -min(0.6f, clipHi * 10f + (p99 - 0.93f) * 3f))
            notes.add(N_("recovered highlights"))
        }
        if (p5 < 0.07f && dark > 0.12f) {
            val s = min(if (scene == SceneKind.NIGHT) 0.2f else 0.5f, dark * 1.3f)
            a = a.with(AdjustKey.SHADOWS, s); notes.add(N_("lifted shadows"))
        }

        // Where the mid-tones land after the exposure change.
        val evApplied = if (kotlin.math.abs(ev) > 0.08) ev else 0.0
        val midAfter = ColorPipeline.linearToSrgb((lin(median.coerceAtLeast(0.02f)) * Math.pow(2.0, evApplied)).coerceAtMost(1.0)).toFloat()

        // Contrast from the tonal spread. The contrast curve pivots at mid-grey, so for a picture
        // that is still dark it would darken it again: use less.
        val spread = p95 - p5
        val want = when (scene) { SceneKind.LANDSCAPE, SceneKind.CITY -> 0.80f; SceneKind.PORTRAIT -> 0.66f; SceneKind.DOCUMENT -> 0.9f; SceneKind.NIGHT -> 0.6f; else -> 0.74f }
        var contrast = ((want - spread) * 1.3f + if (scene == SceneKind.DOCUMENT) 0.12f else 0f).coerceIn(-0.2f, 0.42f)
        if (contrast > 0 && midAfter < 0.42f) contrast *= ((midAfter / 0.42f) * (midAfter / 0.42f)).coerceIn(0.15f, 1f)
        if (kotlin.math.abs(contrast) > 0.05f) { a = a.with(AdjustKey.CONTRAST, contrast); if (contrast > 0) notes.add(N_("added contrast")) }
        // Still too dark after the (limited) exposure change: lift the mid-tones.
        if (scene != SceneKind.NIGHT && midAfter < target - 0.08f) {
            a = a.with(AdjustKey.BRIGHTNESS, ((target - midAfter) * 1.1f).coerceAtMost(0.3f))
            if ("brightened" !in notes && "brightened the faces" !in notes) notes.add(N_("brightened"))
        }

        // Colour cast (grey world on neutral mid-tones), gentler for warm light that is part of the scene.
        if (st.warmCast != null && st.greenCast != null) {
            val warmCast: Float = st.warmCast
            val greenCast: Float = st.greenCast
            var warmth = -warmCast * 1.3f
            if (warmCast > 0 && scene in setOf(SceneKind.LANDSCAPE, SceneKind.NIGHT, SceneKind.FOOD)) warmth *= 0.35f
            // Outdoors, cool tones are mostly sky and water rather than a cast.
            if (warmCast < 0 && scene in setOf(SceneKind.LANDSCAPE, SceneKind.PLANT, SceneKind.NIGHT)) warmth *= 0.5f
            if (scene == SceneKind.FOOD) warmth += 0.06f
            warmth = warmth.coerceIn(-0.35f, 0.35f)
            // Greenery is green: only a light touch on tint for nature scenes.
            val tint = (greenCast * 1.6f * if (scene == SceneKind.LANDSCAPE || scene == SceneKind.PLANT) 0.3f else 1f).coerceIn(-0.3f, 0.3f)
            if (kotlin.math.abs(warmth) > 0.05f) {
                a = a.with(AdjustKey.WARMTH, warmth)
                notes.add(if (warmCast > 0.04f) N_("cooled a yellow cast") else if (warmCast < -0.04f) N_("warmed a blue cast") else N_("balanced colours"))
            }
            if (kotlin.math.abs(tint) > 0.05f) { a = a.with(AdjustKey.TINT, tint); notes.add(if (greenCast > 0) N_("removed a green tint") else N_("removed a magenta tint")) }
        }

        // Colour and detail by scene.
        var vib = ((0.30f - meanSat) * 1.4f).coerceIn(-0.1f, 0.35f)
        vib += when (scene) { SceneKind.LANDSCAPE, SceneKind.PLANT -> 0.15f; SceneKind.FOOD -> 0.12f; SceneKind.ANIMAL, SceneKind.CITY -> 0.05f; else -> 0f }
        if (scene == SceneKind.PORTRAIT) vib *= 0.5f
        if (scene == SceneKind.DOCUMENT) vib = 0f
        if (vib > 0.05f) { a = a.with(AdjustKey.VIBRANCE, vib.coerceAtMost(0.45f)); notes.add(N_("richer colours")) }
        val sharp = sharpness(scene)
        if (sharp > 0f) a = a.with(AdjustKey.SHARPNESS, sharp)
        return EnhanceResult(a, scene, notes)
    }

    /** Variance of the Laplacian of luma: higher = sharper (used to pick the best of similar shots). */
    fun sharpness(argb: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var s = 0.0; var s2 = 0.0; var k = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val c = luma(argb[y * w + x]) * 4 - luma(argb[y * w + x - 1]) - luma(argb[y * w + x + 1]) - luma(argb[(y - 1) * w + x]) - luma(argb[(y + 1) * w + x])
            s += c; s2 += c * c; k++
        }
        val m = s / k
        return sqrt((s2 / k - m * m).coerceAtLeast(0.0)) * 1000
    }
}
