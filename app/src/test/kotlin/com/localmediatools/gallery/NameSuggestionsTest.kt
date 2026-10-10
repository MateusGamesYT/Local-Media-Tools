package com.localmediatools.gallery

import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceClustering
import com.localmediatools.gallery.core.FaceRec
import com.localmediatools.gallery.core.NameSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * "This is …?" suggestions while naming people, on the real photos of RealPeopleTest: the groups
 * are named one after another, biggest first, as in the Name people screen; a group of someone
 * already named should be suggested as them, and a suggestion must never name the wrong person.
 */
class NameSuggestionsTest {
    companion object {
        @BeforeClass @JvmStatic fun faces() { if (RealPeopleTest.seen.isEmpty()) RealPeopleTest.analyse() }
    }

    @Test fun suggestsWhoAGroupIsAndNeverSomeoneElse() {
        val p = ClusterParams.MBF
        val seen = RealPeopleTest.seen
        val recs = seen.mapIndexed { i, s -> FaceRec(i.toLong(), s.emb, p.quality(s.score, s.yaw, s.eye), p.isGood(s.score, s.yaw, s.eye), usable = p.isUsable(s.score)) }
        val groups = FaceClustering.cluster(recs, p).filter { it.faces.size >= 2 }.sortedByDescending { it.faces.size }
        // Who each group is: the label most of its faces have (null: strangers).
        fun who(g: LongArray): String? = g.toList().mapNotNull { seen[it.toInt()].label }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val named = HashMap<String, NameSuggestions.Faces>()
        var right = 0; var wrong = 0; var shouldHave = 0
        for ((k, g) in groups.withIndex()) {
            val faces = NameSuggestions.of(g.faces.map { seen[it.toInt()].emb }, -1L - k)!!
            val truth = who(g.faces)
            val ids = named.keys.toList()
            val sugg = NameSuggestions.forGroup(faces, ids.mapIndexed { i, n -> NameSuggestions.Faces(i.toLong(), named.getValue(n).sum, named.getValue(n).count) }, p)
            val top = sugg.firstOrNull()?.let { ids[it.first.toInt()] }
            if (truth != null && truth in named) shouldHave++
            if (top != null) { if (top == truth) right++ else { wrong++; println("  group of ${g.faces.size} (${truth ?: "strangers"}: ${g.faces.map { seen[it.toInt()].label }}) suggested as $top ${sugg.first().second}") } }
            // The user names the group (or adds it to the person already named).
            if (truth != null) {
                val prev = named[truth]
                named[truth] = if (prev == null) faces else NameSuggestions.Faces(0, FloatArray(faces.sum.size) { faces.sum[it] + prev.sum[it] }, faces.count + prev.count)
            }
        }
        println("naming ${groups.size} groups: $right right suggestions of $shouldHave possible, $wrong wrong")
        assertEquals(0, wrong)
        assertTrue("right $right of $shouldHave", shouldHave == 0 || right * 2 >= shouldHave)
    }
}
