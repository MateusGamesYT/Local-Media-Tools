import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceClustering
import com.localmediatools.gallery.core.FaceRec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A simulated user naming people in the app's own grouping (FaceClustering), round by round, on the
 * labelled real-people faces (learn_export.py writes learn_in.bin and the halves of the people):
 *  - each round, for every person of the half being evaluated, the user names that person's biggest
 *    group still without a name, and takes out the faces in it that aren't them ("Some of these
 *    aren't the same person…"); naming a second group with the same name adds it to the person;
 *  - the library is then grouped again with those confirmations and corrections.
 * After every round it prints how many of each person's faces sit under their name, how many wrong
 * faces (other labelled people, and strangers) do, and how many of their groups still wait to be named.
 *
 *   build_learn.sh; run_learn.sh learn_in.bin learn_halfA.txt [variants…]
 * A variant is name:key=value,… overriding ClusterParams.SFACE (join, merge, assign, low, margin, …).
 * LEARN_DIAG=1 also says why the faces still missing at the end are missing.
 */
fun main(args: Array<String>) {
    val b = ByteBuffer.wrap(File(args[0]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val n = b.int; val dim = b.int; val nPeople = b.int
    val score = FloatArray(n); val eye = FloatArray(n); val yaw = FloatArray(n); val label = IntArray(n); val image = IntArray(n)
    val emb = Array(n) { FloatArray(0) }
    for (i in 0 until n) {
        score[i] = b.float; eye[i] = b.float; yaw[i] = b.float; label[i] = b.int; image[i] = b.int
        emb[i] = FloatArray(dim) { b.float }
    }
    val halfA = File(args[1]).readText().trim().split(' ').map { it.toInt() }.toSet()
    val halfB = (0 until nPeople).toSet() - halfA
    val d = ClusterParams.SFACE
    val variants = ArrayList<Pair<String, ClusterParams>>()
    variants.add("shipped" to d)
    for (a in args.drop(2)) {
        val name = a.substringBefore(':')
        val o = a.substringAfter(':').split(',').filter { it.isNotBlank() }.associate { it.substringBefore('=') to it.substringAfter('=') }
        fun f(k: String, v: Float) = o[k]?.toFloat() ?: v
        variants.add(name to ClusterParams(f("join", d.join), f("merge", d.merge), f("assign", d.assign), f("low", d.low), f("margin", d.margin),
            f("goodScore", d.goodScore), f("goodYaw", d.goodYaw), f("goodEye", d.goodEye), centroid = d.centroid, lowScore = f("lowScore", d.lowScore),
            suggestFace = d.suggestFace, suggestGroup = d.suggestGroup))
    }
    val rounds = 3
    for ((vname, p) in variants) for ((hname, half) in listOf("A" to halfA, "B" to halfB)) {
        val confirmed = LongArray(n)
        val notP = Array(n) { HashSet<Long>() }
        var named = 0; var removed = 0
        val t0 = System.nanoTime()
        fun recs() = (0 until n).map { i ->
            FaceRec(i.toLong(), emb[i], p.quality(score[i], yaw[i], eye[i]), p.isGood(score[i], yaw[i], eye[i]),
                person = confirmed[i].takeIf { it != 0L }, notPeople = notP[i].toLongArray(), usable = p.isUsable(score[i]))
        }
        val line = StringBuilder()
        for (round in 0..rounds) {
            val groups = FaceClustering.cluster(recs(), p)
            // Measure.
            val total = IntArray(nPeople); val under = IntArray(nPeople)
            var wrongPeople = 0; var strangers = 0; var waiting = 0
            for (i in 0 until n) if (label[i] in half) total[label[i]]++
            for (g in groups) {
                val person = g.person
                val labs = g.faces.map { label[it.toInt()] }
                if (person != null) {
                    val pp = (person - 1).toInt()
                    if (pp !in half) continue
                    for (l in labs) when { l == pp -> under[pp]++; l >= 0 -> wrongPeople++; else -> strangers++ }
                } else if (g.faces.size >= 2) {
                    val maj = labs.filter { it >= 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }
                    if (maj != null && maj.key in half && maj.value >= 2) waiting++
                }
            }
            val people = half.filter { total[it] > 0 }
            val share = people.map { under[it].toDouble() / total[it] }.average()
            val faceShare = people.sumOf { under[it] }.toDouble() / people.sumOf { total[it] }
            line.append(String.format("  r%d: under name %.3f (faces %.3f) wrong %d strangers %d waiting %d |", round, share, faceShare, wrongPeople, strangers, waiting))
            if (round == rounds) {
                if (System.getenv("LEARN_DIAG") != null) {
                    // Who is still missing, and why: unusable (detector unsure), good, or small/turned.
                    val home = HashMap<Int, Long?>()
                    for (g in groups) for (id in g.faces) home[id.toInt()] = g.person
                    var unusable = 0; var good = 0; var lowq = 0
                    for (i in 0 until n) {
                        val l = label[i]; if (l !in half || home[i] == l + 1L) continue
                        when { !p.isUsable(score[i]) -> unusable++; p.isGood(score[i], yaw[i], eye[i]) -> good++; else -> lowq++ }
                    }
                    line.append(" missing: unusable $unusable good $good small/turned $lowq")
                }
                break
            }
            // The user names each person's biggest unnamed group (where they are the majority).
            val best = HashMap<Int, Pair<Int, Int>>()  // person → (group index, their faces in it)
            for ((gi, g) in groups.withIndex()) {
                if (g.person != null || g.faces.size < 2) continue
                val c = g.faces.map { label[it.toInt()] }.filter { it in half }.groupingBy { it }.eachCount()
                val top = c.maxByOrNull { it.value } ?: continue
                if (top.value < 2) continue
                if ((best[top.key]?.second ?: 0) < top.value) best[top.key] = gi to top.value
            }
            for ((person, pick) in best) {
                named++
                val pid = person + 1L
                for (id in groups[pick.first].faces) {
                    val i = id.toInt()
                    if (label[i] == person) confirmed[i] = pid else { notP[i].add(pid); removed++ }
                }
            }
        }
        println(String.format("%-14s half %s%s  [named %d groups, took out %d faces, %d ms]", vname, hname, line, named, removed, (System.nanoTime() - t0) / 1_000_000))
    }
}
