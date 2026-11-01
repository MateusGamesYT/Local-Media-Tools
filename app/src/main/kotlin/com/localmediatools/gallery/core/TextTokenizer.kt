package com.localmediatools.gallery.core

import java.io.DataInputStream
import java.io.InputStream

/**
 * The tokenizer of the search model's text side (SigLIP 2, Gemma's byte-fallback BPE), in token ids:
 * spaces become "▁"; the text is split into characters (a character without a token becomes its UTF-8
 * bytes); then the adjacent pair whose merge comes first is merged, again and again. It gives the same
 * ids as the original tokenizer (checked against it in buildtools/search/bpe.py and the JVM tests).
 *
 * The app keeps embeddings only for the tokens of everyday words in its ten languages ([rows]); a
 * token without one is taken apart along the merge that made it, until every piece has one.
 */
class TextTokenizer(
    /** Code point → token id of the single characters the vocabulary has. */
    private val chars: Map<Int, Int>,
    /** Token ids of the 256 byte tokens <0x00>…<0xFF>. */
    private val bytes: IntArray,
    /** Merges, best first: (left, right) → result. */
    private val left: IntArray, private val right: IntArray, private val result: IntArray,
    /** Token id → its row in the embedding table, or -1. */
    val rows: IntArray,
    val pad: Int, val eos: Int,
) {
    // (left, right) → merge rank, in an open-addressing table of longs.
    private val keys: LongArray
    private val ranks: IntArray
    private val mask: Int
    /** Token id → the merge that first makes it (to take it apart), or -1. */
    private val madeBy = IntArray(rows.size) { -1 }

    init {
        var cap = 1
        while (cap < left.size * 2) cap = cap shl 1
        keys = LongArray(cap) { EMPTY }; ranks = IntArray(cap); mask = cap - 1
        for (r in left.indices) {
            val k = key(left[r], right[r])
            var i = mix(k) and mask
            while (keys[i] != EMPTY && keys[i] != k) i = (i + 1) and mask
            if (keys[i] == EMPTY) { keys[i] = k; ranks[i] = r }
            if (madeBy[result[r]] < 0) madeBy[result[r]] = r
        }
    }

    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
    private fun mix(k: Long): Int { var h = k * -0x61c8864680b583ebL; h = h xor (h ushr 29); return h.toInt() }

    private fun rank(a: Int, b: Int): Int {
        val k = key(a, b)
        var i = mix(k) and mask
        while (true) {
            val v = keys[i]
            if (v == EMPTY) return -1
            if (v == k) return ranks[i]
            i = (i + 1) and mask
        }
    }

    /** The BPE token ids of [text] (lower-cased; as the original tokenizer gives them). */
    fun tokens(text: String): IntArray {
        val s = text.lowercase().replace(' ', '▁')
        val sym = ArrayList<Int>(s.length + 8)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val id = chars[cp]
            if (id != null) sym.add(id)
            else for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) sym.add(bytes[b.toInt() and 0xFF])
            i += Character.charCount(cp)
        }
        while (sym.size > 1) {
            var best = Int.MAX_VALUE; var at = -1
            for (k in 0 until sym.size - 1) {
                val r = rank(sym[k], sym[k + 1])
                if (r in 0 until best) { best = r; at = k }
            }
            if (at < 0) break
            sym[at] = result[best]; sym.removeAt(at + 1)
        }
        return sym.toIntArray()
    }

    /**
     * What the model is given: the tokens of [text] as rows of the embedding table (tokens without a
     * row taken apart), then <eos>, padded with <pad> to [length].
     */
    fun encode(text: String, length: Int = 64): IntArray {
        val out = ArrayList<Int>(length)
        fun add(t: Int) {
            if (rows[t] >= 0 || madeBy[t] < 0) { out.add(if (rows[t] >= 0) rows[t] else rows[pad]); return }
            val m = madeBy[t]; add(left[m]); add(right[m])
        }
        for (t in tokens(text)) add(t)
        val n = minOf(out.size, length - 1)
        return IntArray(length) { k -> if (k < n) out[k] else if (k == n) rows[eos] else rows[pad] }
    }

    companion object {
        private const val EMPTY = Long.MIN_VALUE

        /**
         * Reads the app's tokenizer file (buildtools/search/build_search_model.py): magic "LMTK", version,
         * vocabulary size, pad and eos ids; the single characters (code point, id); the 256 byte tokens;
         * the merges (left, right, result), best first; and each token's embedding row (-1: none).
         */
        fun read(input: InputStream): TextTokenizer {
            val d = DataInputStream(input.buffered(1 shl 16))
            require(d.readInt() == 0x4C4D544B) { "not a tokenizer file" }
            require(d.readInt() == 1) { "unknown tokenizer version" }
            val vocab = d.readInt(); val pad = d.readInt(); val eos = d.readInt()
            val nc = d.readInt()
            val chars = HashMap<Int, Int>(nc * 2)
            repeat(nc) { chars[d.readInt()] = d.readInt() }
            val bytes = IntArray(256) { d.readInt() }
            val nm = d.readInt()
            val l = IntArray(nm); val r = IntArray(nm); val res = IntArray(nm)
            for (k in 0 until nm) { l[k] = d.readInt(); r[k] = d.readInt(); res[k] = d.readInt() }
            val rows = IntArray(vocab) { d.readInt() }
            return TextTokenizer(chars, bytes, l, r, res, rows, pad, eos)
        }
    }
}
