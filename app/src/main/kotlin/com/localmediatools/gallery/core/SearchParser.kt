package com.localmediatools.gallery.core

import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import java.time.format.TextStyle

/** One condition of a gallery search. All terms of a query must hold (AND). */
sealed class SearchTerm(val label: String) {
    class Person(val id: Long, val name: String) : SearchTerm(name)
    class Category(val key: String, val name: String) : SearchTerm(name)
    class Album(val id: String, val name: String) : SearchTerm(name)
    class Kind(val kind: MediaFilter, name: String) : SearchTerm(name)
    /** Taken between [fromMs] (inclusive) and [toMs] (exclusive), local time. */
    class Time(val fromMs: Long, val toMs: Long, name: String) : SearchTerm(name)
    /** Words that matched nothing else: looked for in file and album names. */
    class Text(val text: String) : SearchTerm("\"$text\"")

    override fun toString() = label
}

enum class MediaFilter {
    VIDEOS, PHOTOS, SCREENSHOTS, GIFS, FAVOURITES;

    /** The words that find these, in English and in the app's language (lower case). */
    val words: List<String> get() {
        val english = when (this) {
            VIDEOS -> "video,videos,clip,clips,movie,movies"
            PHOTOS -> "photo,photos,picture,pictures,pic,pics,image,images"
            SCREENSHOTS -> "screenshot,screenshots,screen shot,screen shots,screengrab"
            GIFS -> "gif,gifs,animation,animations"
            FAVOURITES -> "favourite,favourites,favorite,favorites,starred"
        }
        val local = when (this) {
            VIDEOS -> trc("search words for videos, separated by commas", "video,videos,clip,clips,movie,movies")
            PHOTOS -> trc("search words for photos, separated by commas", "photo,photos,picture,pictures,pic,pics,image,images")
            SCREENSHOTS -> trc("search words for screenshots, separated by commas", "screenshot,screenshots,screen shot,screen shots,screengrab")
            GIFS -> trc("search words for GIFs, separated by commas", "gif,gifs,animation,animations")
            FAVOURITES -> trc("search words for favourites, separated by commas", "favourite,favourites,favorite,favorites,starred")
        }
        return SearchParser.list("$english,$local,$label")
    }

    /** As the search shows it. */
    val label: String get() = when (this) {
        VIDEOS -> tr("Videos"); PHOTOS -> tr("Photos"); SCREENSHOTS -> tr("Screenshots"); GIFS -> tr("GIFs"); FAVOURITES -> tr("Favourites")
    }
}

class SearchVocabulary(
    val people: List<Pair<Long, String>>,
    /** Category key, display name and the words that find it. */
    val categories: List<Triple<String, String, List<String>>>,
    val albums: List<Pair<String, String>>,
)

/** A parsed query: the terms, plus "did you mean" fixes that were applied to misspelled words. */
class ParsedQuery(val terms: List<SearchTerm>, val corrections: List<Pair<String, String>>) {
    val isEmpty get() = terms.isEmpty()
}

/**
 * Turns what people type ("John and Sophie at the beach", "dogs 2023", "videos of cats") into
 * search terms. Longest phrases win; names and categories tolerate one or two typos ("Jonh").
 *
 * Words are understood in English and in the app's language ("cachorros na praia", "junho de
 * 2023"). Chinese and Japanese are written without spaces, so their characters are looked up as
 * one-character words ("海边的狗" finds the beach and dogs); Korean particles are taken off a word
 * that matches nothing ("해변에서" → "해변").
 */
object SearchParser {
    /** Words that don't narrow a search (also the English the catalogs translate, below). */
    private const val ENGLISH_STOP = "a,an,the,of,with,and,&,+,at,in,on,by,from,near,to,for,my,me,our,show,find,all,some,where,is,are,was,were,together,or,both,having,who,that,taken"
    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val monthShort = months.map { it.take(3) }

    /** Characters of scripts written without spaces: each is a word of its own. */
    private val unspaced = Regex("([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\u30FC\\u3005])")
    private val betweenUnspaced = Regex("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\u30FC\\u3005]) (?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\u30FC\\u3005])")
    private val hangul = Regex("\\p{IsHangul}")
    private val koreanParticles = listOf("에서는", "에서도", "에서", "으로", "에게", "까지", "부터", "하고", "이랑", "랑", "과", "와", "의", "에", "을", "를", "은", "는", "이", "가", "도", "로", "만")

    private val latinMarks = Regex("(?<=\\p{IsLatin})\\p{Mn}+")

    /**
     * Lower case, accents on Latin letters dropped ("Cão" and "cao" match; Japanese and Korean
     * marks are kept), punctuation gone, characters of Chinese and Japanese spaced out.
     */
    fun normalize(s: String): String = java.text.Normalizer.normalize(
        latinMarks.replace(java.text.Normalizer.normalize(s.lowercase(I18n.locale), java.text.Normalizer.Form.NFD), ""), java.text.Normalizer.Form.NFC)
        .replace('ё', 'е')
        .replace('’', '\'').replace("'s ", " ").replace(Regex("'s$"), "")
        .replace(Regex("[^\\p{L}\\p{N}&+' ]"), " ")
        .replace(unspaced, " $1 ")
        .replace(Regex("\\s+"), " ").trim()

    /** A comma-separated list of words (as the catalogs give them), normalised, without duplicates. */
    fun list(s: String): List<String> = s.split(',').map { normalize(it) }.filter { it.isNotEmpty() }.distinct()

    private enum class Span { TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR }

    /** The phrases for "today", "last month"… in English and the app's language. */
    private fun spans(): Map<String, Span> {
        val out = LinkedHashMap<String, Span>()
        fun add(span: Span, english: String, local: String, label: String) { for (w in list("$english,$local,$label")) out.putIfAbsent(w, span) }
        add(Span.TODAY, "today", trc("search words for today, separated by commas", "today"), label(Span.TODAY))
        add(Span.YESTERDAY, "yesterday", trc("search words for yesterday, separated by commas", "yesterday"), label(Span.YESTERDAY))
        add(Span.THIS_WEEK, "this week", trc("search words for this week, separated by commas", "this week"), label(Span.THIS_WEEK))
        add(Span.LAST_WEEK, "last week", trc("search words for last week, separated by commas", "last week"), label(Span.LAST_WEEK))
        add(Span.THIS_MONTH, "this month", trc("search words for this month, separated by commas", "this month"), label(Span.THIS_MONTH))
        add(Span.LAST_MONTH, "last month", trc("search words for last month, separated by commas", "last month"), label(Span.LAST_MONTH))
        add(Span.THIS_YEAR, "this year", trc("search words for this year, separated by commas", "this year"), label(Span.THIS_YEAR))
        add(Span.LAST_YEAR, "last year", trc("search words for last year, separated by commas", "last year"), label(Span.LAST_YEAR))
        return out
    }

    private fun label(s: Span) = when (s) {
        Span.TODAY -> tr("Today"); Span.YESTERDAY -> tr("Yesterday")
        Span.THIS_WEEK -> tr("This week"); Span.LAST_WEEK -> tr("Last week")
        Span.THIS_MONTH -> tr("This month"); Span.LAST_MONTH -> tr("Last month")
        Span.THIS_YEAR -> tr("This year"); Span.LAST_YEAR -> tr("Last year")
    }

    /**
     * Month names (full and short) in English and the app's language → month (0–11). Short names
     * that are also words of the search ([taken]: "mar" is the sea in Spanish and Portuguese) are
     * left out.
     */
    private fun monthNames(taken: Set<String>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for ((k, m) in months.withIndex()) { out[m] = k; if (monthShort[k] !in taken) out.putIfAbsent(monthShort[k], k) }
        for (m in java.time.Month.entries) for (style in listOf(TextStyle.FULL, TextStyle.FULL_STANDALONE, TextStyle.SHORT, TextStyle.SHORT_STANDALONE)) {
            val n = normalize(m.getDisplayName(style, I18n.locale))
            val short = style == TextStyle.SHORT || style == TextStyle.SHORT_STANDALONE
            if (n.isNotEmpty() && n.any { it.isLetter() } && !(short && n in taken)) out.putIfAbsent(n, m.value - 1)
        }
        return out
    }

    fun parse(query: String, vocab: SearchVocabulary, nowMs: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): ParsedQuery {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return ParsedQuery(emptyList(), emptyList())
        val stop = list(ENGLISH_STOP + "," + trc("search: words to leave out of a search, separated by commas",
            "a,an,the,of,with,and,&,+,at,in,on,by,from,near,to,for,my,me,our,show,find,all,some,where,is,are,was,were,together,or,both,having,who,that,taken")).toHashSet()
        val spans = spans()
        // Phrase dictionary: normalised phrase → term factory.
        val dict = HashMap<String, () -> SearchTerm>()
        for ((key, name, ws) in vocab.categories) {
            val make = { SearchTerm.Category(key, name) }
            for (w in ws + name) {
                val n = normalize(w)
                if (n.isEmpty()) continue
                dict.putIfAbsent(n, make)
                plural(n)?.let { dict.putIfAbsent(it, make) }
            }
        }
        for (k in MediaFilter.entries) for (w in k.words) dict[w] = { SearchTerm.Kind(k, k.label) }
        for ((id, name) in vocab.albums) { val n = normalize(name); if (n.isNotEmpty()) dict.putIfAbsent(n) { SearchTerm.Album(id, name) } }
        // People last so a person called like a category ("Rose") wins: people are what users name.
        for ((id, name) in vocab.people) {
            val n = normalize(name)
            if (n.isEmpty()) continue
            dict[n] = { SearchTerm.Person(id, name) }
            // A first name alone finds someone when no one else has it (not for names written without spaces).
            val first = n.substringBefore(' ')
            if (first != n && !unspaced.containsMatchIn(n) && vocab.people.count { normalize(it.second).substringBefore(' ') == first } == 1) dict.putIfAbsent(first) { SearchTerm.Person(id, name) }
        }
        val monthNames = monthNames(dict.keys + stop)
        val maxLen = dict.keys.maxOfOrNull { it.count { c -> c == ' ' } + 1 } ?: 1
        val terms = ArrayList<SearchTerm>()
        val corrections = ArrayList<Pair<String, String>>()
        val leftovers = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            // Dates first: "june 2023", "2023", "last year", "today".
            val t = time(words, i, nowMs, zone, spans, monthNames, stop)
            if (t != null) { terms.add(t.first); i += t.second; continue }
            var matched = false
            for (len in minOf(maxLen, words.size - i) downTo 1) {
                val phrase = words.subList(i, i + len).joinToString(" ")
                val make = dict[phrase] ?: continue
                terms.add(make()); i += len; matched = true; break
            }
            if (matched) continue
            val w = words[i]
            if (w in stop) { i++; continue }
            // Korean: a word with a particle on it ("해변에서", at the beach).
            if (hangul.containsMatchIn(w)) {
                val stem = koreanParticles.firstOrNull { w.length > it.length && w.endsWith(it) && dict.containsKey(w.removeSuffix(it)) }
                if (stem != null) { terms.add(dict[w.removeSuffix(stem)]!!()); i++; continue }
            }
            // Typos: closest single-word entry within an edit budget that grows with length.
            val budget = when { w.length >= 7 -> 2; w.length >= 4 -> 1; else -> 0 }
            if (budget > 0) {
                var best: String? = null; var bd = budget + 1
                for (k in dict.keys) {
                    if (k.contains(' ') || kotlin.math.abs(k.length - w.length) > budget) continue
                    val d = editDistance(w, k)
                    if (d < bd || (d == bd && best != null && k < best)) { bd = d; best = k }
                }
                if (best != null && bd <= budget) {
                    terms.add(dict[best]!!()); corrections.add(w to best); i++; continue
                }
            }
            leftovers.add(w); i++
        }
        if (leftovers.isNotEmpty()) terms.add(SearchTerm.Text(leftovers.joinToString(" ").replace(betweenUnspaced, "")))
        // The same condition twice ("dog dogs") counts once.
        val unique = terms.distinctBy { termKey(it) }
        return ParsedQuery(unique, corrections)
    }

    fun termKey(t: SearchTerm): String = when (t) {
        is SearchTerm.Person -> "p:${t.id}"
        is SearchTerm.Category -> "c:${t.key}"
        is SearchTerm.Album -> "a:${t.id}"
        is SearchTerm.Kind -> "k:${t.kind}"
        is SearchTerm.Time -> "t:${t.fromMs}-${t.toMs}"
        is SearchTerm.Text -> "x:${t.text}"
    }

    private fun plural(w: String): String? = when {
        w.endsWith("s") || w.contains(' ') -> null
        w.endsWith("y") && w.length > 2 && w[w.length - 2] !in "aeiou" -> w.dropLast(1) + "ies"
        w.endsWith("ch") || w.endsWith("sh") || w.endsWith("x") -> w + "es"
        else -> w + "s"
    }

    /** Optimal string alignment distance (adjacent swaps count once: "jonh" → "john" is 1). */
    fun editDistance(a: String, b: String): Int {
        val n = a.length; val m = b.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[n][m]
    }

    private fun cal(zone: java.util.TimeZone, ms: Long) = java.util.Calendar.getInstance(zone).apply { timeInMillis = ms }

    private fun startOfDay(c: java.util.Calendar) = c.apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }

    /** A date phrase starting at [i]: the term and how many words it used. */
    private fun time(w: List<String>, i: Int, now: Long, zone: java.util.TimeZone, spans: Map<String, Span>, monthNames: Map<String, Int>, stop: Set<String>): Pair<SearchTerm, Int>? {
        val day = 86_400_000L
        for (len in minOf(4, w.size - i) downTo 1) {
            val span = spans[w.subList(i, i + len).joinToString(" ")] ?: continue
            val c = startOfDay(cal(zone, now))
            val (from, to) = when (span) {
                Span.TODAY -> c.timeInMillis.let { it to it + day }
                Span.YESTERDAY -> (c.timeInMillis - day).let { it to it + day }
                Span.THIS_WEEK, Span.LAST_WEEK -> {
                    c.set(java.util.Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
                    val s = c.timeInMillis
                    if (span == Span.THIS_WEEK) s to s + 7 * day else s - 7 * day to s
                }
                Span.THIS_MONTH, Span.LAST_MONTH -> {
                    c.set(java.util.Calendar.DAY_OF_MONTH, 1)
                    val s = c.timeInMillis
                    if (span == Span.THIS_MONTH) { c.add(java.util.Calendar.MONTH, 1); s to c.timeInMillis } else { c.add(java.util.Calendar.MONTH, -1); c.timeInMillis to s }
                }
                Span.THIS_YEAR, Span.LAST_YEAR -> {
                    c.set(java.util.Calendar.DAY_OF_YEAR, 1)
                    val s = c.timeInMillis
                    if (span == Span.THIS_YEAR) { c.add(java.util.Calendar.YEAR, 1); s to c.timeInMillis } else { c.add(java.util.Calendar.YEAR, -1); c.timeInMillis to s }
                }
            }
            return SearchTerm.Time(from, to, label(span)) to len
        }
        fun year(s: String) = s.toIntOrNull()?.takeIf { s.length == 4 && it in 1900..2100 }
            ?: Regex("^(\\d{4})년$").find(s)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1900..2100 }
        /** A year at [k] and how many words it takes ("2023", "2023 年", "2023년"). */
        fun yearAt(k: Int): Pair<Int, Int>? {
            val y = w.getOrNull(k)?.let { year(it) } ?: return null
            return y to if (w.getOrNull(k + 1) == "年") 2 else 1
        }
        /** A month at [k] and how many words it takes ("june", "jun", "junho", "6 月", "6월", "一 月"). */
        fun monthAt(k: Int): Pair<Int, Int>? {
            val word = w.getOrNull(k) ?: return null
            word.toIntOrNull()?.takeIf { it in 1..12 && w.getOrNull(k + 1) == "月" }?.let { return it - 1 to 2 }
            Regex("^(\\d{1,2})월$").find(word)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..12 }?.let { return it - 1 to 1 }
            for (len in minOf(3, w.size - k) downTo 1) {
                val m = monthNames[w.subList(k, k + len).joinToString(" ")] ?: continue
                return m to len
            }
            return null
        }
        fun monthTerm(m: Int, y: Int?): SearchTerm {
            val c = startOfDay(cal(zone, now))
            val yy = y ?: run {
                // A month alone means its latest occurrence.
                val cy = c.get(java.util.Calendar.YEAR)
                if (m > c.get(java.util.Calendar.MONTH)) cy - 1 else cy
            }
            c.set(yy, m, 1)
            val s = c.timeInMillis
            c.add(java.util.Calendar.MONTH, 1)
            val name = I18n.capitalize(I18n.date(s, zone, trc("date pattern: a month and its year", "MMMM yyyy")))
            return SearchTerm.Time(s, c.timeInMillis, name)
        }
        // "2023年6月", "2023년 6월": a year written before its month.
        yearAt(i)?.let { (y, yl) ->
            val next = w.getOrNull(i + yl)
            if (next != null && (next.toIntOrNull() != null || hangul.containsMatchIn(next) || unspaced.containsMatchIn(next)))
                monthAt(i + yl)?.let { (m, ml) -> return monthTerm(m, y) to yl + ml }
        }
        val mm = monthAt(i)
        // "may" alone is a word ("may 2023" is a month).
        if (mm != null && !(w[i] == "may" && mm.second == 1 && w.getOrNull(i + 1)?.let { year(it) } == null)) {
            val (m, ml) = mm
            // Its year may follow, after a word such as "de" ("junho de 2023").
            val y = yearAt(i + ml) ?: w.getOrNull(i + ml)?.takeIf { it in stop }?.let { yearAt(i + ml + 1)?.let { (yy, l) -> yy to l + 1 } }
            return monthTerm(m, y?.first) to ml + (y?.second ?: 0)
        }
        val y = yearAt(i)
        if (y != null) {
            val c = startOfDay(cal(zone, now)); c.set(y.first, 0, 1)
            val s = c.timeInMillis; c.add(java.util.Calendar.YEAR, 1)
            return SearchTerm.Time(s, c.timeInMillis, "${y.first}") to y.second
        }
        return null
    }
}
