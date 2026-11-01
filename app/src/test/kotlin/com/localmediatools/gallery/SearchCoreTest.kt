package com.localmediatools.gallery

import com.localmediatools.gallery.core.Embedding
import com.localmediatools.gallery.core.MatchScale
import com.localmediatools.gallery.core.SearchParser
import com.localmediatools.gallery.core.SearchVocabulary
import com.localmediatools.gallery.core.SemanticIndex
import com.localmediatools.gallery.core.TextTokenizer
import com.localmediatools.gallery.core.TokenTable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/** The search model's pieces that run without Android: tokenizer, token table, stored descriptions, scores, query text. */
class SearchCoreTest {
    private fun unit(rnd: Random) = Embedding.normalize(FloatArray(Embedding.DIM) { rnd.nextGaussian().toFloat() })

    @Test fun packedDescriptionsKeepTheirCosines() {
        val rnd = Random(1)
        repeat(50) {
            val a = unit(rnd); val b = unit(rnd)
            val back = Embedding.unpack(Embedding.pack(a))
            // The direction is kept (the length moves by about 0.0003 either way).
            assertTrue(Embedding.cosine(a, Embedding.normalize(back.copyOf())) > 0.9999f)
            assertEquals(1f, Embedding.cosine(a, back), 0.0015f)
            assertEquals(Embedding.cosine(a, b), Embedding.cosine(back, Embedding.unpack(Embedding.pack(b))), 0.002f)
        }
    }

    @Test fun matchScalePutsTheThresholdAtHalf() {
        // SigLIP 2 B/32's own scale and bias.
        val s = MatchScale(111.83f, -16.77f)
        assertEquals(0.5f, s.calibrated(s.threshold), 0.001f)
        assertEquals(MatchScale.THRESHOLD, s.probability(s.threshold), 1e-6)
        var prev = -1f
        for (k in -20..40) {
            val c = s.calibrated(k / 100f)
            assertTrue(c >= prev); assertTrue(c in 0f..1f); prev = c
        }
        assertTrue(s.calibrated(s.threshold - 0.01f) < 0.5f)
        assertTrue(s.calibrated(s.threshold + 0.01f) > 0.5f)
    }

    @Test fun semanticIndexScoresBestFrameAndReplacesItems() {
        val rnd = Random(2)
        val q = unit(rnd)
        val near = Embedding.normalize(FloatArray(Embedding.DIM) { q[it] + 0.3f * rnd.nextGaussian().toFloat() / 27f })
        val far = unit(rnd)
        val idx = SemanticIndex()
        idx.put(1, listOf(Embedding.pack(far)))
        idx.put(2, listOf(Embedding.pack(far), Embedding.pack(near)))
        val best = idx.best(q)
        assertEquals(Embedding.cosine(q, near), best[2]!!, 0.003f)
        assertTrue(best[2]!! > best[1]!!)
        // Only the items asked about.
        assertEquals(setOf(1L), idx.best(q, listOf(1L, 99L)).keys)
        // An item described again replaces its earlier rows; empty forgets it.
        idx.put(2, listOf(Embedding.pack(far)))
        assertEquals(best[1]!!, idx.best(q)[2]!!, 0.003f)
        idx.put(1, emptyList())
        assertFalse(idx.has(1)); assertEquals(1, idx.size)
        // Many replacements: compacted, still right.
        repeat(6000) { idx.put(3, listOf(Embedding.pack(if (it % 2 == 1) far else near))) }
        assertEquals(Embedding.cosine(q, far), idx.best(q)[3]!!, 0.003f)
        assertEquals(setOf(2L, 3L), idx.mediaIds().toSet())
    }

    @Test fun tokenTableReadsEightAndFourBitRows() {
        val rows = arrayOf(floatArrayOf(0.5f, -1f, 0.25f, 0f), floatArrayOf(-0.1f, 0.2f, 0.3f, -0.4f))
        for (bits in intArrayOf(8, 4)) {
            val q = (1 shl (bits - 1)) - 1
            val scales = rows.map { r -> r.maxOf { kotlin.math.abs(it) } / q }
            val b = ByteBuffer.allocate(20 + 8 + 8 * bits / 8).order(ByteOrder.BIG_ENDIAN)
            b.putInt(0x4C4D5445).putInt(1).putInt(2).putInt(4).putInt(bits)
            scales.forEach { b.putFloat(it) }
            val vals = rows.mapIndexed { i, r -> r.map { Math.round(it / scales[i]).coerceIn(-q - 1, q) } }.flatten()
            if (bits == 8) vals.forEach { b.put(it.toByte()) }
            else vals.chunked(2).forEach { (lo, hi) -> b.put(((lo and 15) or ((hi and 15) shl 4)).toByte()) }
            b.flip()
            val t = TokenTable(b)
            val out = FloatArray(8)
            t.row(1, out, 4); t.row(0, out, 0)
            for (i in 0 until 4) {
                assertEquals(rows[0][i], out[i], scales[0] / 2 + 1e-6f)
                assertEquals(rows[1][i], out[4 + i], scales[1] / 2 + 1e-6f)
            }
        }
    }

    /** The app's tokenizer against the original one (Hugging Face), on texts in the app's ten languages. */
    @Test fun tokenizerGivesTheOriginalIds() {
        val file = File("app/src/main/assets/search/tokenizer.bin")
        val tok = file.inputStream().use { TextTokenizer.read(it) }
        val lines = File("app/src/test/resources/search/tokens.tsv").readLines(Charsets.UTF_8).filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(lines.size >= 20)
        for (line in lines) {
            val (text, ids, rows) = line.split('\t')
            val want = ids.split(' ').map { it.toInt() }
            val got = tok.tokens(text).toList().take(63) + tok.eos
            assertEquals(text, want, (got + List(64 - got.size) { tok.pad }))
            assertArrayEquals(text, rows.split(' ').map { it.toInt() }.toIntArray(), tok.encode(text))
        }
        // Every row the tokenizer gives exists in the table the app ships.
        val table = TokenTable(ByteBuffer.wrap(File("app/src/main/assets/search/tokens.emb").readBytes()))
        assertTrue(tok.rows.all { it < table.rows })
        assertEquals(Embedding.DIM, table.dim)
    }

    @Test fun queriesGiveTheWordsThatDescribeThePhoto() {
        val vocab = SearchVocabulary(
            people = listOf(1L to "John", 2L to "Sophie"),
            categories = listOf(Triple("dog", "Dogs", listOf("dog", "dogs")), Triple("beach", "Beach", listOf("beach", "praia"))),
            albums = listOf("b1" to "WhatsApp Images"),
        )
        fun content(q: String) = SearchParser.parse(q, vocab).content
        assertEquals("dog on the beach", content("photos of Sophie with her dog on the beach in 2023").removePrefix("her "))
        assertEquals("red car at night", content("Red car at night"))
        assertEquals("", content("John and Sophie"))
        assertEquals("", content("videos from last year"))
        // As typed: accents stay (the model reads "cão", not "cao"); the category word stays in.
        assertEquals("cão na praia", content("Cão na praia"))
        // A corrected misspelling gives the corrected word.
        assertEquals("dogs", content("dgos"))
        // Chinese and Japanese are written without spaces.
        assertEquals("海边的狗", content("海边的狗"))
        // Album words are a filter, not a description.
        assertEquals("sunset", content("sunset whatsapp images"))
    }
}
