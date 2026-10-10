package com.localmediatools.gallery.core

import kotlin.math.max
import kotlin.math.sqrt

/** One face as the people grouping sees it. */
class FaceRec(
    val id: Long,
    /** L2-normalised identity embedding (SFace, flip-averaged). */
    val emb: FloatArray,
    /** Higher is better: detector score × eye distance × frontalness. */
    val quality: Float,
    /** Sharp, large and frontal enough to start or shape a group. */
    val good: Boolean,
    /** Set when the user confirmed who this is (naming a group, "this is …"). Such faces never move. */
    val person: Long? = null,
    /** People the user said this face is not. */
    val notPeople: LongArray = LongArray(0),
    /** Clear enough to join a group at all; faces that aren't stay on their own. */
    val usable: Boolean = true,
)

/** A group of faces believed to be one person. [person] is set when the group holds faces confirmed as that person. */
class FaceGroup(val faces: LongArray, val person: Long?)

/** Which descriptor a face was described with; faces are only compared within one kind. */
enum class FaceKind(val code: Int) {
    /** SFace identity embedding (on-device AI). */
    SFACE(0),
    /** Classical LBP texture descriptor (fallback when the AI can't run). */
    LBP(1);

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: SFACE }
}

/**
 * Thresholds for grouping one kind of face descriptor. With [centroid] a face's similarity to a
 * group is its cosine to the group's mean direction (normalised sum of member embeddings), and two
 * groups compare by the cosine of their sums; otherwise both are plain averages of pairwise cosines.
 */
class ClusterParams(
    val join: Float, val merge: Float, val assign: Float, val low: Float, val margin: Float,
    val goodScore: Float, val goodYaw: Float, val goodEye: Float,
    val centroid: Boolean = false,
    /** Faces below this detector score never join a group (blurry or half-hidden bystanders). */
    val lowScore: Float = 0f,
    /** A face this similar to a named person is offered as "is this …?" (one-tap naming). */
    val suggestFace: Float = 0.30f,
    /** Groups this similar (but below [merge]) are offered as "same person?" suggestions. */
    val suggestGroup: Float = 0.30f,
) {
    fun isGood(score: Float, yaw: Float, eyePx: Float) = score >= goodScore && yaw <= goodYaw && eyePx >= goodEye

    fun isUsable(score: Float) = score >= lowScore

    fun quality(score: Float, yaw: Float, eyePx: Float) = score * minOf(eyePx, 60f) * (1f - minOf(yaw, 1f))

    companion object {
        /**
         * SFace (flip-averaged, int8), tuned on 802 hand-verified faces of 78 real people in 1,233
         * Creative Commons photos (Open Images; hats, sunglasses, stage make-up, expressions, profiles,
         * small faces) plus the 3,776 other faces in those photos as strangers; tuned on half of the
         * people and checked on the other half (buildtools/gallery/README.md). Against the averaged
         * rules of 1.4.0–1.4.1 (join 0.42 on the average pairwise cosine), a person's faces found in
         * their main group rose from 75 % to 87 % (B-cubed recall 0.65 → 0.81, all 78 people at once)
         * while faces put with the wrong person stayed under 1 %.
         *  - centroid: a face is compared with a group's mean face, so people photographed in many
         *    different conditions (whose faces agree less with each other) still form one group;
         *  - merge 0.65: the most alike two different people's mean faces came to 0.56;
         *  - lowScore 0.85: blurry, tiny or half-hidden faces below it stay on their own.
         *  - suggestions ("is this …?"), 1.7.0: a wrong suggestion costs more than a missing one. On
         *    each half of the people (groups of 2–8 of a person's faces, the rest as single faces),
         *    no group of someone else reached 0.62 (the most alike: 0.45 and 0.56) while 93–95 % of
         *    the person's own groups did; no single face of someone else reached 0.55 (0.47, 0.54)
         *    while 70–74 % of their own did. 1.6.0's 0.45 / 0.40 offered 37 wrong groups among
         *    15,741 pairs and 39 wrong faces.
         */
        val SFACE = ClusterParams(join = 0.46f, merge = 0.65f, assign = 0.42f, low = 0.46f, margin = 0.06f,
            goodScore = 0.80f, goodYaw = 1.0f, goodEye = 24f, centroid = true, lowScore = 0.85f,
            suggestFace = 0.55f, suggestGroup = 0.62f)

        /**
         * LBP fallback. Different people overlap heavily with same-person pairs (different-person
         * pairs reach 0.71), so only near-identical faces (bursts, copies) are grouped.
         */
        val BASIC = ClusterParams(join = 0.80f, merge = 0.80f, assign = 0.80f, low = 0.86f, margin = 0.05f,
            goodScore = 0.45f, goodYaw = 1f, goodEye = 20f, suggestFace = 0.70f, suggestGroup = 0.70f)

        fun of(kind: FaceKind) = if (kind == FaceKind.LBP) BASIC else SFACE
    }
}

/**
 * Groups faces into people by cosine similarity to each group's mean face (or, for the basic
 * descriptor, the average over its faces), tuned for very few wrong merges (thresholds in [ClusterParams]):
 *  1. groups for named people start from their confirmed faces;
 *  2. good faces (sharp, not in profile, eyes far enough apart) are added best-first to the group
 *     they are most similar to (≥ join) or start a new one;
 *  3. groups whose similarity is ≥ merge are merged (never two named people, never into a
 *     person one of the faces was rejected from);
 *  4. every good face is re-assigned to the group it fits best (≥ assign), twice;
 *  5. small, blurry or turned faces join a group of at least two faces only when they match it
 *     clearly (≥ low, and margin above the next best group) and the detector is sure enough it is
 *     a face (≥ lowScore); otherwise they stay on their own.
 */
object FaceClustering {
    private class Work(val dim: Int, val centroid: Boolean) {
        var sums = FloatArray(0)
        var counts = IntArray(0)
        /** Length of each group's sum. */
        var norms = FloatArray(0)
        val members = ArrayList<MutableList<Int>>()
        val person = ArrayList<Long?>()
        val rejects = ArrayList<HashSet<Long>>()
        val alive = ArrayList<Boolean>()
        val size get() = members.size

        fun add(person: Long?): Int {
            val c = members.size
            if ((c + 1) * dim > sums.size) {
                sums = sums.copyOf(max(64, (c + 1) * 2) * dim)
                counts = counts.copyOf(max(64, (c + 1) * 2))
                norms = norms.copyOf(max(64, (c + 1) * 2))
            }
            members.add(ArrayList()); this.person.add(person); rejects.add(HashSet()); alive.add(true)
            return c
        }

        fun put(c: Int, i: Int, f: FaceRec) {
            members[c].add(i); counts[c]++
            val o = c * dim
            for (k in 0 until dim) sums[o + k] += f.emb[k]
            for (p in f.notPeople) rejects[c].add(p)
            renorm(c)
        }

        fun renorm(c: Int) {
            var s = 0f; val o = c * dim
            for (k in 0 until dim) s += sums[o + k] * sums[o + k]
            norms[c] = sqrt(s)
        }

        /** Similarity of a face to group c. */
        fun sim(e: FloatArray, c: Int): Float {
            val n = counts[c]
            if (n == 0) return -1f
            var s = 0f; val o = c * dim
            for (k in 0 until dim) s += e[k] * sums[o + k]
            return if (centroid) s / max(norms[c], 1e-6f) else s / n
        }

        /** Similarity between groups a and b. */
        fun linkage(a: Int, b: Int): Float {
            var s = 0f; val oa = a * dim; val ob = b * dim
            for (k in 0 until dim) s += sums[oa + k] * sums[ob + k]
            return if (centroid) s / max(norms[a] * norms[b], 1e-6f) else s / (counts[a].toFloat() * counts[b])
        }

        fun canJoin(f: FaceRec, c: Int): Boolean {
            val p = person[c] ?: return true
            return f.notPeople.none { it == p }
        }

        fun canMerge(a: Int, b: Int): Boolean {
            val pa = person[a]; val pb = person[b]
            if (pa != null && pb != null) return false
            if (pa != null && pa in rejects[b]) return false
            if (pb != null && pb in rejects[a]) return false
            return true
        }

        fun merge(a: Int, b: Int) {
            for (i in members[b]) members[a].add(i)
            counts[a] += counts[b]
            val oa = a * dim; val ob = b * dim
            for (k in 0 until dim) sums[oa + k] += sums[ob + k]
            renorm(a)
            if (person[a] == null) person[a] = person[b]
            rejects[a].addAll(rejects[b])
            members[b].clear(); counts[b] = 0; alive[b] = false; norms[b] = 0f
            for (k in 0 until dim) sums[ob + k] = 0f
        }
    }

    fun cluster(faces: List<FaceRec>, p: ClusterParams = ClusterParams.SFACE, cancelled: () -> Boolean = { false }): List<FaceGroup> {
        if (faces.isEmpty()) return emptyList()
        val dim = faces[0].emb.size
        val w = Work(dim, p.centroid)
        // 1. Named people.
        val byPerson = HashMap<Long, Int>()
        for ((i, f) in faces.withIndex()) {
            val p = f.person ?: continue
            val c = byPerson.getOrPut(p) { w.add(p) }
            w.put(c, i, f)
        }
        val fixed = BooleanArray(faces.size) { faces[it].person != null }
        // 2. Leader pass over good faces, best first. Faces the user rejected from someone go last, so
        //    they can't start a group that pulls that person's other faces away.
        val order = faces.indices.filter { !fixed[it] && faces[it].good }
            .sortedWith(compareBy<Int> { faces[it].notPeople.isNotEmpty() }.thenByDescending { faces[it].quality })
        for ((k, i) in order.withIndex()) {
            if (k % 256 == 0 && cancelled()) return emptyList()
            val f = faces[i]
            var best = -1; var bs = -9f
            for (c in 0 until w.size) {
                if (!w.alive[c] || !w.canJoin(f, c)) continue
                val s = w.sim(f.emb, c)
                if (s > bs) { bs = s; best = c }
            }
            if (best >= 0 && bs >= p.join) w.put(best, i, f) else w.put(w.add(null), i, f)
        }
        // 3. Average-linkage merging, tracking each group's best partner.
        mergeGroups(w, p.merge, cancelled)
        if (cancelled()) return emptyList()
        // 4. Re-assign good faces twice to the group that fits best.
        repeat(2) {
            if (cancelled()) return emptyList()
            val n = w.size
            val home = IntArray(faces.size) { -1 }
            for (c in 0 until n) if (w.alive[c]) for (i in w.members[c]) home[i] = c
            val snapSums = w.sums.copyOf(); val snapCounts = w.counts.copyOf(); val snapNorms = w.norms.copyOf()
            val target = IntArray(faces.size) { -1 }
            for (i in order) {
                val f = faces[i]; val own = home[i]
                var best = -1; var bs = -9f; var ownS = -9f
                // A face in a group of two or more doesn't leave it for a single face (too noisy a match).
                val inGroup = own >= 0 && snapCounts[own] > 1
                for (c in 0 until n) {
                    if (!w.alive[c] || !w.canJoin(f, c)) continue
                    val cnt = snapCounts[c]
                    if (inGroup && c != own && cnt < 2) continue
                    var dot = 0f; val o = c * dim
                    for (d in 0 until dim) dot += f.emb[d] * snapSums[o + d]
                    // The face's own group is judged without the face itself.
                    val s = when {
                        c == own && cnt < 2 -> -9f
                        c == own && p.centroid -> (dot - 1f) / sqrt(max(snapNorms[c] * snapNorms[c] - 2f * dot + 1f, 1e-6f))
                        c == own -> (dot - 1f) / (cnt - 1)
                        p.centroid -> dot / max(snapNorms[c], 1e-6f)
                        else -> dot / cnt
                    }
                    if (c == own) ownS = s
                    if (s > bs) { bs = s; best = c }
                }
                // Faces in a named person's group only leave it for a clearly better match.
                if (own >= 0 && w.person[own] != null && best != own && ownS >= p.assign && bs - ownS < p.margin) { best = own; bs = ownS }
                target[i] = if (best >= 0 && bs >= p.assign) best else -1
            }
            // Rebuild: named groups keep their confirmed faces, everything else moves to its target.
            val keep = Work(dim, p.centroid)
            val map = HashMap<Int, Int>()
            for (c in 0 until n) {
                if (!w.alive[c]) continue
                val anchored = w.person[c] != null
                val hasFixed = w.members[c].any { fixed[it] }
                if (anchored && hasFixed) {
                    val nc = keep.add(w.person[c]); map[c] = nc
                    for (i in w.members[c]) if (fixed[i]) keep.put(nc, i, faces[i])
                }
            }
            for (i in order) {
                val t = target[i]
                val nc = if (t >= 0) map.getOrPut(t) { keep.add(w.person[t]) } else keep.add(null)
                keep.put(nc, i, faces[i])
            }
            // Low-quality faces placed earlier keep their group until step 5.
            w.sums = keep.sums; w.counts = keep.counts; w.norms = keep.norms
            w.members.clear(); w.members.addAll(keep.members)
            w.person.clear(); w.person.addAll(keep.person)
            w.rejects.clear(); w.rejects.addAll(keep.rejects)
            w.alive.clear(); w.alive.addAll(keep.alive)
        }
        // 5. Small, blurry or turned faces: only clear matches to a group of two or more.
        val groupsBefore = w.size
        for (i in faces.indices) {
            if (fixed[i] || faces[i].good) continue
            if (i % 256 == 0 && cancelled()) return emptyList()
            val f = faces[i]
            if (!f.usable) { w.put(w.add(null), i, f); continue }
            var b1 = -1; var s1 = -9f; var s2 = -9f
            for (c in 0 until groupsBefore) {
                if (!w.alive[c] || w.counts[c] < 2 || !w.canJoin(f, c)) continue
                val s = w.sim(f.emb, c)
                if (s > s1) { s2 = s1; s1 = s; b1 = c } else if (s > s2) s2 = s
            }
            // The margin is measured against every other group, allowed or not, so a lookalike
            // the face was rejected from still counts as competition.
            for (c in 0 until groupsBefore) {
                if (!w.alive[c] || w.counts[c] < 2 || c == b1 || w.canJoin(f, c)) continue
                val s = w.sim(f.emb, c)
                if (s > s2) s2 = s
            }
            if (b1 >= 0 && s1 >= p.low && s1 - s2 >= p.margin) {
                // Don't let low-quality faces shift the group's centre for later ones.
                w.members[b1].add(i)
            } else w.put(w.add(null), i, f)
        }
        val out = ArrayList<FaceGroup>()
        for (c in 0 until w.size) {
            if (!w.alive[c] || w.members[c].isEmpty()) continue
            out.add(FaceGroup(LongArray(w.members[c].size) { faces[w.members[c][it]].id }, w.person[c]))
        }
        return out
    }

    private fun mergeGroups(w: Work, threshold: Float, cancelled: () -> Boolean) {
        val n = w.size
        val bestJ = IntArray(n) { -1 }
        val bestS = FloatArray(n) { -9f }
        fun refresh(a: Int) {
            bestJ[a] = -1; bestS[a] = -9f
            if (!w.alive[a]) return
            for (b in 0 until n) {
                if (b == a || !w.alive[b] || !w.canMerge(a, b)) continue
                val s = w.linkage(a, b)
                if (s > bestS[a]) { bestS[a] = s; bestJ[a] = b }
            }
        }
        for (a in 0 until n) { if (a % 128 == 0 && cancelled()) return; refresh(a) }
        while (true) {
            var a = -1; var s = -9f
            for (i in 0 until n) if (w.alive[i] && bestS[i] > s) { s = bestS[i]; a = i }
            if (a < 0 || s < threshold) return
            val b = bestJ[a]
            // Keep the named group (if any) as the survivor.
            val (keep, gone) = if (w.person[b] != null && w.person[a] == null) b to a else a to b
            w.merge(keep, gone)
            refresh(keep)
            for (i in 0 until n) {
                if (!w.alive[i] || i == keep) continue
                if (bestJ[i] == keep || bestJ[i] == gone) refresh(i)
                else if (w.canMerge(i, keep)) {
                    val t = w.linkage(i, keep)
                    if (t > bestS[i]) { bestS[i] = t; bestJ[i] = keep }
                } else if (bestJ[i] == keep) refresh(i)
            }
            if (cancelled()) return
        }
    }

    /**
     * Places one new face into existing groups between full re-groupings: the group it fits best
     * if that is ≥ join (good faces) or a clear low-quality match (others), else null.
     * [groups] are (sum of member embeddings, member count, person).
     */
    fun place(f: FaceRec, groups: List<Triple<FloatArray, Int, Long?>>, p: ClusterParams = ClusterParams.SFACE): Int? {
        if (!f.good && !f.usable) return null
        var b1 = -1; var s1 = -9f; var s2 = -9f
        for ((c, g) in groups.withIndex()) {
            val (sum, n, person) = g
            if (n <= 0) continue
            if (person != null && f.notPeople.any { it == person }) continue
            if (!f.good && n < 2) continue
            val s = similarity(f.emb, sum, n, p)
            if (s > s1) { s2 = s1; s1 = s; b1 = c } else if (s > s2) s2 = s
        }
        if (b1 < 0) return null
        return if (f.good) b1.takeIf { s1 >= p.join } else b1.takeIf { s1 >= p.low && s1 - s2 >= p.margin }
    }

    /** Similarity of a face to a group given the sum of its members' embeddings and their number, measured as the grouping does. */
    fun similarity(e: FloatArray, sum: FloatArray, n: Int, p: ClusterParams = ClusterParams.SFACE): Float {
        var s = 0f
        for (k in sum.indices) s += e[k] * sum[k]
        return if (p.centroid) s / max(length(sum), 1e-6f) else s / max(n, 1)
    }

    /** Similarity between two groups given their embedding sums and sizes, measured as the grouping does. */
    fun linkage(sumA: FloatArray, nA: Int, sumB: FloatArray, nB: Int, p: ClusterParams = ClusterParams.SFACE): Float {
        var s = 0f
        for (k in sumA.indices) s += sumA[k] * sumB[k]
        return if (p.centroid) s / max(length(sumA) * length(sumB), 1e-6f) else s / (nA.toFloat() * nB)
    }

    private fun length(v: FloatArray): Float { var s = 0f; for (x in v) s += x * x; return sqrt(s) }
}
