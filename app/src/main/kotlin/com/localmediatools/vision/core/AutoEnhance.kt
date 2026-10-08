package com.localmediatools.vision.core

import com.localmediatools.codec.edit.AdjustKey
import com.localmediatools.codec.edit.Adjustments
import com.localmediatools.codec.edit.ColorPipeline
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class SceneKind(val label: String) {
    PORTRAIT("Portrait"), LANDSCAPE("Landscape"), FOOD("Food"), ANIMAL("Animal"), PLANT("Plants & flowers"),
    CITY("City & buildings"), DOCUMENT("Document"), NIGHT("Night"), GENERAL("General"),
}

/** Maps image-classifier labels (ImageNet) to the scene kinds auto-enhance cares about. */
object SceneMapper {
    private val landscape = setOf("alp", "cliff", "coral reef", "geyser", "lakeside", "promontory", "sandbar", "seashore", "valley", "volcano",
        "breakwater", "dam", "boathouse", "dock", "canoe", "paddle", "lifeboat", "yawl", "catamaran", "trimaran", "schooner", "speedboat",
        "mountain tent", "ski", "dogsled", "snowmobile", "bubble", "parachute", "balloon")
    private val city = setOf("church", "mosque", "palace", "castle", "monastery", "triumphal arch", "bell cote", "dome", "library", "cinema",
        "steel arch bridge", "suspension bridge", "viaduct", "pier", "planetarium", "water tower", "tile roof", "patio", "barn", "greenhouse",
        "street sign", "traffic light", "trolleybus", "streetcar", "cab", "limousine", "obelisk", "stupa", "fountain", "picket fence", "prison",
        "restaurant", "grocery store", "bakery", "shoe shop", "toyshop", "bookshop", "tobacco shop", "barbershop", "butcher shop", "confectionery")
    private val food = setOf("guacamole", "consomme", "hot pot", "trifle", "ice cream", "ice lolly", "French loaf", "bagel", "pretzel",
        "cheeseburger", "hotdog", "mashed potato", "head cabbage", "broccoli", "cauliflower", "zucchini", "spaghetti squash", "acorn squash",
        "butternut squash", "cucumber", "artichoke", "bell pepper", "cardoon", "mushroom", "Granny Smith", "strawberry", "orange", "lemon",
        "fig", "pineapple", "banana", "jackfruit", "custard apple", "pomegranate", "carbonara", "chocolate sauce", "dough", "meat loaf",
        "pizza", "potpie", "burrito", "red wine", "espresso", "cup", "eggnog", "plate", "wok", "frying pan", "Dutch oven", "soup bowl",
        "coffee mug", "beer glass", "wine bottle", "beer bottle", "goblet", "cocktail shaker")
    private val plant = setOf("daisy", "yellow lady's slipper", "corn", "acorn", "hip", "buckeye", "coral fungus", "agaric", "gyromitra",
        "stinkhorn", "earthstar", "hen-of-the-woods", "bolete", "ear", "pot", "vase", "rapeseed")
    private val document = setOf("web site", "menu", "book jacket", "comic book", "crossword puzzle", "envelope", "packet", "binder",
        "notebook", "monitor", "screen", "television", "scoreboard", "slide rule", "rule", "paper towel", "toilet tissue", "letter opener",
        "fountain pen", "ballpoint", "pencil box", "carton", "jigsaw puzzle")

    /** [top] is (label index, label, probability), best first. ImageNet indices 0–397 are animals. */
    fun scene(top: List<Triple<Int, String, Float>>): Pair<SceneKind, Float> {
        val score = HashMap<SceneKind, Float>()
        for ((idx, label, p) in top) {
            val k = when {
                idx in 0..397 -> SceneKind.ANIMAL
                label in landscape -> SceneKind.LANDSCAPE
                label in city -> SceneKind.CITY
                label in food -> SceneKind.FOOD
                label in plant -> SceneKind.PLANT
                label in document -> SceneKind.DOCUMENT
                else -> null
            } ?: continue
            score[k] = (score[k] ?: 0f) + p
        }
        val best = score.maxByOrNull { it.value } ?: return SceneKind.GENERAL to 0f
        return if (best.value >= 0.25f) best.key to best.value else SceneKind.GENERAL to best.value
    }
}

/** A face box as fractions (0..1) of the image. */
data class FaceBox(val x: Float, val y: Float, val w: Float, val h: Float)

class EnhanceResult(val adjust: Adjustments, val scene: SceneKind, val notes: List<String>)

/**
 * One-tap enhancement: measures exposure, contrast, clipping, colour cast and saturation, and uses
 * the faces and scene found by the on-device models to choose sensible editor settings (which stay
 * editable). [argb] is a small copy of the photo (e.g. 384 px on the long side).
 */
object AutoEnhance {
    private fun luma(c: Int): Float = (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f
    private fun lin(v: Float) = ColorPipeline.srgbToLinear(v.toDouble())
    private fun log2(x: Double) = ln(x) / ln(2.0)

    fun analyze(argb: IntArray, w: Int, h: Int, faces: List<FaceBox>, sceneHint: SceneKind, strength: Float = 1f): EnhanceResult {
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
        val p1 = pct(0.01f); val p5 = pct(0.05f); val p50 = pct(0.5f); val p95 = pct(0.95f); val p99 = pct(0.99f)
        val clipHi = (250..255).sumOf { hist[it] }.toFloat() / total
        val dark = (0..30).sumOf { hist[it] }.toFloat() / total
        val meanSat = if (satCnt > 0) (satSum / satCnt).toFloat() else 0f

        // Scene: faces and darkness override the classifier.
        val faceArea = faces.sumOf { (it.w * it.h).toDouble() }.toFloat()
        val scene = when {
            faces.isNotEmpty() && (faceArea >= 0.012f || faces.size >= 2) -> SceneKind.PORTRAIT
            p50 < 0.16f && p95 < 0.62f -> SceneKind.NIGHT
            else -> sceneHint
        }
        val notes = ArrayList<String>()
        var a = Adjustments()

        // Exposure toward a scene-dependent mid-tone, steered by the faces when there are any.
        val target = when (scene) { SceneKind.NIGHT -> 0.30f; SceneKind.DOCUMENT -> 0.62f; SceneKind.PORTRAIT -> 0.48f; else -> 0.46f }
        var median = p50
        var faceLuma = -1f
        if (faces.isNotEmpty()) {
            val fl = ArrayList<Float>()
            for (f in faces) {
                val x0 = (f.x * w).toInt().coerceIn(0, w - 1); val x1 = ((f.x + f.w) * w).toInt().coerceIn(x0 + 1, w)
                val y0 = (f.y * h).toInt().coerceIn(0, h - 1); val y1 = ((f.y + f.h) * h).toInt().coerceIn(y0 + 1, h)
                for (y in y0 until y1) for (x in x0 until x1) fl.add(luma(argb[y * w + x]))
            }
            if (fl.isNotEmpty()) { fl.sort(); faceLuma = fl[fl.size / 2] }
        }
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
            notes.add(if (faceLuma >= 0 && ev > 0) "brightened the faces" else if (ev > 0) "brightened" else "toned down the brightness")
        }

        // Highlights and shadows.
        if (p99 > 0.95f && clipHi > 0.006f) {
            a = a.with(AdjustKey.HIGHLIGHTS, -min(0.6f, clipHi * 10f + (p99 - 0.93f) * 3f))
            notes.add("recovered highlights")
        }
        if (p5 < 0.07f && dark > 0.12f) {
            val s = min(if (scene == SceneKind.NIGHT) 0.2f else 0.5f, dark * 1.3f)
            a = a.with(AdjustKey.SHADOWS, s); notes.add("lifted shadows")
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
        if (kotlin.math.abs(contrast) > 0.05f) { a = a.with(AdjustKey.CONTRAST, contrast); if (contrast > 0) notes.add("added contrast") }
        // Still too dark after the (limited) exposure change: lift the mid-tones.
        if (scene != SceneKind.NIGHT && midAfter < target - 0.08f) {
            a = a.with(AdjustKey.BRIGHTNESS, ((target - midAfter) * 1.1f).coerceAtMost(0.3f))
            if ("brightened" !in notes && "brightened the faces" !in notes) notes.add("brightened")
        }

        // Colour cast (grey world on neutral mid-tones), gentler for warm light that is part of the scene.
        if (cnt > n / 50) {
            val r = sr / cnt; val g = sg / cnt; val b = sb / cnt
            val m = max(r, max(g, b)).coerceAtLeast(1.0)
            val warmCast = ((r - b) / m).toFloat()
            val greenCast = ((g - (r + b) / 2) / m).toFloat()
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
                notes.add(if (warmCast > 0.04f) "cooled a yellow cast" else if (warmCast < -0.04f) "warmed a blue cast" else "balanced colours")
            }
            if (kotlin.math.abs(tint) > 0.05f) { a = a.with(AdjustKey.TINT, tint); notes.add(if (greenCast > 0) "removed a green tint" else "removed a magenta tint") }
        }

        // Colour and detail by scene.
        var vib = ((0.30f - meanSat) * 1.4f).coerceIn(-0.1f, 0.35f)
        vib += when (scene) { SceneKind.LANDSCAPE, SceneKind.PLANT -> 0.15f; SceneKind.FOOD -> 0.12f; SceneKind.ANIMAL, SceneKind.CITY -> 0.05f; else -> 0f }
        if (scene == SceneKind.PORTRAIT) vib *= 0.5f
        if (scene == SceneKind.DOCUMENT) vib = 0f
        if (vib > 0.05f) { a = a.with(AdjustKey.VIBRANCE, vib.coerceAtMost(0.45f)); notes.add("richer colours") }
        val sharp = when (scene) { SceneKind.LANDSCAPE, SceneKind.CITY -> 0.3f; SceneKind.DOCUMENT -> 0.35f; SceneKind.FOOD, SceneKind.PLANT, SceneKind.ANIMAL -> 0.22f; SceneKind.PORTRAIT -> 0.08f; SceneKind.NIGHT -> 0f; else -> 0.15f }
        if (sharp > 0f) a = a.with(AdjustKey.SHARPNESS, sharp)

        if (strength != 1f) {
            var scaled = Adjustments()
            for ((k, v) in a.values) scaled = scaled.with(k, v * strength)
            a = scaled
        }
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
