package com.localmediatools.gallery

import com.localmediatools.gallery.core.Embedding
import com.localmediatools.gallery.core.MatchScale
import com.localmediatools.gallery.core.SemanticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The search model's real descriptions of real photos (Open Images, CC BY 2.0, credits in
 * resources/search/CREDITS.tsv; buildtools/search/make_fixture.py): 16 kinds of things three times
 * each, and 12 photos of other things. The descriptions come from the app's converted models, the
 * queries' through its tokenizer and reduced vocabulary, in the app's languages; this checks that the
 * app's index and threshold find the right photos with them.
 */
class SearchRealPhotosTest {
    private val dir = File("app/src/test/resources/search")
    private fun unpack(b64: String) = Embedding.unpack(Base64.getDecoder().decode(b64))
    private fun table(name: String) = File(dir, name).readLines(Charsets.UTF_8).filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t') }

    private val photos = table("photos.tsv")
    private val labels = photos.withIndex().associate { (i, r) -> i.toLong() to r[1] }
    private val index = SemanticIndex().apply { for ((i, r) in photos.withIndex()) put(i.toLong(), listOf(Base64.getDecoder().decode(r[2]))) }
    private val scale = table("model.tsv").associate { it[0] to it[1].toFloat() }.let { MatchScale(it.getValue("logit_scale"), it.getValue("logit_bias")) }

    @Test fun queriesInTheAppsLanguagesFindTheirPhotos() {
        assertEquals(60, photos.size)
        val queries = table("queries.tsv")
        assertTrue(queries.map { it[1] }.toSet().size >= 10)
        var found = 0; var right = 0; var wanted = 0; var topRight = 0; var asked = 0
        for ((q, lang, want, emb) in queries) {
            val cos = index.best(unpack(emb))
            val ranked = cos.entries.sortedByDescending { it.value }
            val sure = ranked.filter { scale.calibrated(it.value) >= 0.5f }
            if (want.isEmpty()) {
                // Nothing like it among the photos: nothing (or next to nothing) sure.
                assertTrue("$q: ${sure.size} sure matches", sure.size <= 1)
                continue
            }
            asked++
            // The best match is one of the three.
            assertEquals("$q ($lang): best match", want, labels[ranked.first().key])
            if (labels[ranked.first().key] == want) topRight++
            val hits = sure.count { labels[it.key] == want }
            // At least two of the three are sure matches, and most sure matches are right.
            assertTrue("$q ($lang): $hits of 3 found", hits >= 2)
            assertTrue("$q ($lang): ${sure.size - hits} wrong of ${sure.size}", sure.size - hits <= maxOf(2, hits))
            found += sure.size; right += hits; wanted += 3
        }
        // Over all queries: recall and precision at the app's threshold.
        assertTrue("recall ${right.toFloat() / wanted}", right.toFloat() / wanted >= 0.85f)
        assertTrue("precision ${right.toFloat() / found}", right.toFloat() / found >= 0.8f)
        assertEquals(asked, topRight)
    }
}
