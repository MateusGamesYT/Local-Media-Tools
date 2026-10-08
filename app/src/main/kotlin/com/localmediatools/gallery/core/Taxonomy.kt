package com.localmediatools.gallery.core

/**
 * A searchable category ("Dogs", "Beach", "Pizza"…) and how it is recognised: by the object
 * detector (COCO classes), by the scene classifier (summed probabilities of ImageNet-21k classes
 * under WordNet synsets) and/or by a linear probe on the classifier's features. Each source has its
 * own threshold, calibrated on labelled photos for high precision.
 */
class Category(
    val key: String,
    val name: String,
    val group: String,
    /** Words that find this category in search (lower case). */
    val words: List<String>,
    val det: IntArray,
    val detThreshold: Float,
    /** Smallest box (fraction of the photo) that counts. */
    val detMinArea: Float,
    /** Rows of the scene head whose probabilities add up to this category. */
    val scene: IntArray,
    val sceneThreshold: Float,
    /** Index of a linear probe, or -1. */
    val probe: Int,
    val probeThreshold: Float,
)

object Taxonomy {
    /** A photo is tagged with a category when its fused score reaches this. */
    const val TAGGED = 0.5f
    /** Scores below this aren't stored. */
    const val KEEP = 0.25f

    /** Parses the tab-separated category table (see the asset's header line). */
    fun parse(tsv: String): List<Category> = tsv.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val c = line.split('\t')
            fun ints(s: String) = s.split(' ', ',').filter { it.isNotBlank() }.map { it.trim().toInt() }.toIntArray()
            Category(
                key = c[0], name = c[1], group = c[2],
                words = c[3].split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                det = ints(c[4]), detThreshold = c[5].toFloat(), detMinArea = c[6].toFloat(),
                scene = ints(c[7]), sceneThreshold = c[8].toFloat(),
                probe = c[9].toInt(), probeThreshold = c[10].toFloat(),
            )
        }.toList()

    /**
     * Maps a raw score onto 0..1 so that the source's threshold lands on [TAGGED]: scores at the
     * threshold give 0.5, a perfect score gives 1.
     */
    fun calibrate(s: Float, t: Float): Float = when {
        t <= 0f || t >= 1f -> 0f
        s >= t -> TAGGED + (1f - TAGGED) * ((s - t) / (1f - t)).coerceIn(0f, 1f)
        else -> TAGGED * (s / t).coerceAtLeast(0f)
    }

    /**
     * Fused score of [c] for one picture: the strongest of its calibrated sources.
     * [scene] holds the scene head's probabilities (by row), [probes] the probes' outputs.
     */
    fun score(c: Category, dets: List<Detection>, scene: FloatArray?, probes: FloatArray?): Float {
        var best = 0f
        if (c.det.isNotEmpty() && c.detThreshold > 0f) {
            var s = 0f
            for (d in dets) if (d.score > s && d.area >= c.detMinArea && c.det.contains(d.cls)) s = d.score
            best = maxOf(best, calibrate(s, c.detThreshold))
        }
        if (scene != null && c.scene.isNotEmpty() && c.sceneThreshold > 0f) {
            var s = 0f
            for (r in c.scene) s += scene[r]
            best = maxOf(best, calibrate(s, c.sceneThreshold))
        }
        if (probes != null && c.probe >= 0 && c.probeThreshold > 0f) best = maxOf(best, calibrate(probes[c.probe], c.probeThreshold))
        return best
    }

    /** All categories' scores worth keeping for one picture. */
    fun scores(cats: List<Category>, dets: List<Detection>, scene: FloatArray?, probes: FloatArray?): Map<String, Float> {
        val out = HashMap<String, Float>()
        for (c in cats) {
            val s = score(c, dets, scene, probes)
            if (s >= KEEP) out[c.key] = s
        }
        return out
    }
}
