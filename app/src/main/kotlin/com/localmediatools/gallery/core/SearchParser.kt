package com.localmediatools.gallery.core

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

enum class MediaFilter(val words: List<String>) {
    VIDEOS(listOf("video", "videos", "clip", "clips", "movie", "movies")),
    PHOTOS(listOf("photo", "photos", "picture", "pictures", "pic", "pics", "image", "images")),
    SCREENSHOTS(listOf("screenshot", "screenshots", "screen shot", "screen shots", "screengrab")),
    GIFS(listOf("gif", "gifs", "animation", "animations")),
    FAVOURITES(listOf("favourite", "favourites", "favorite", "favorites", "starred")),
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
 */
object SearchParser {
    private val stop = setOf(
        "a", "an", "the", "of", "with", "and", "&", "+", "at", "in", "on", "by", "from", "near", "to", "for", "my", "me", "our",
        "show", "find", "all", "some", "where", "is", "are", "was", "were", "together", "or", "both", "having", "who", "that", "taken",
    )
    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val monthShort = months.map { it.take(3) }

    fun normalize(s: String): String = s.lowercase()
        .replace('’', '\'').replace("'s ", " ").replace(Regex("'s$"), "")
        .replace(Regex("[^\\p{L}\\p{N}&+' ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun parse(query: String, vocab: SearchVocabulary, nowMs: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): ParsedQuery {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return ParsedQuery(emptyList(), emptyList())
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
        for (k in MediaFilter.entries) for (w in k.words) dict[w] = { SearchTerm.Kind(k, k.name.lowercase().replaceFirstChar { it.uppercase() }) }
        for ((id, name) in vocab.albums) { val n = normalize(name); if (n.isNotEmpty()) dict.putIfAbsent(n) { SearchTerm.Album(id, name) } }
        // People last so a person called like a category ("Rose") wins: people are what users name.
        for ((id, name) in vocab.people) {
            val n = normalize(name)
            if (n.isEmpty()) continue
            dict[n] = { SearchTerm.Person(id, name) }
            val first = n.substringBefore(' ')
            if (first != n && vocab.people.count { normalize(it.second).substringBefore(' ') == first } == 1) dict.putIfAbsent(first) { SearchTerm.Person(id, name) }
        }
        val maxLen = dict.keys.maxOfOrNull { it.count { c -> c == ' ' } + 1 } ?: 1
        val terms = ArrayList<SearchTerm>()
        val corrections = ArrayList<Pair<String, String>>()
        val leftovers = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            // Dates first: "june 2023", "2023", "last year", "today".
            val t = time(words, i, nowMs, zone)
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
        if (leftovers.isNotEmpty()) terms.add(SearchTerm.Text(leftovers.joinToString(" ")))
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
    private fun time(w: List<String>, i: Int, now: Long, zone: java.util.TimeZone): Pair<SearchTerm, Int>? {
        val word = w[i]
        fun year(s: String) = s.toIntOrNull()?.takeIf { s.length == 4 && it in 1900..2100 }
        fun monthOf(s: String): Int { val k = months.indexOf(s); return if (k >= 0) k else monthShort.indexOf(s).takeIf { s.length == 3 } ?: -1 }
        val day = 86_400_000L
        when (word) {
            "today" -> { val s = startOfDay(cal(zone, now)).timeInMillis; return SearchTerm.Time(s, s + day, "Today") to 1 }
            "yesterday" -> { val s = startOfDay(cal(zone, now)).timeInMillis - day; return SearchTerm.Time(s, s + day, "Yesterday") to 1 }
        }
        if ((word == "last" || word == "this") && i + 1 < w.size) {
            val c = startOfDay(cal(zone, now))
            when (w[i + 1]) {
                "week" -> {
                    c.set(java.util.Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
                    val s = c.timeInMillis
                    return if (word == "this") SearchTerm.Time(s, s + 7 * day, "This week") to 2 else SearchTerm.Time(s - 7 * day, s, "Last week") to 2
                }
                "month" -> {
                    c.set(java.util.Calendar.DAY_OF_MONTH, 1)
                    val s = c.timeInMillis
                    if (word == "this") { c.add(java.util.Calendar.MONTH, 1); return SearchTerm.Time(s, c.timeInMillis, "This month") to 2 }
                    c.add(java.util.Calendar.MONTH, -1); return SearchTerm.Time(c.timeInMillis, s, "Last month") to 2
                }
                "year" -> {
                    c.set(java.util.Calendar.DAY_OF_YEAR, 1)
                    val s = c.timeInMillis
                    if (word == "this") { c.add(java.util.Calendar.YEAR, 1); return SearchTerm.Time(s, c.timeInMillis, "This year") to 2 }
                    c.add(java.util.Calendar.YEAR, -1); return SearchTerm.Time(c.timeInMillis, s, "Last year") to 2
                }
            }
        }
        val m = monthOf(word)
        if (m >= 0 && word != "may" || (word == "may" && i + 1 < w.size && year(w[i + 1]) != null)) {
            val y = if (i + 1 < w.size) year(w[i + 1]) else null
            val c = startOfDay(cal(zone, now))
            val yy = y ?: run {
                // A month alone means its latest occurrence.
                val cy = c.get(java.util.Calendar.YEAR)
                if (m > c.get(java.util.Calendar.MONTH)) cy - 1 else cy
            }
            c.set(yy, m, 1)
            val s = c.timeInMillis
            c.add(java.util.Calendar.MONTH, 1)
            val name = months[m].replaceFirstChar { it.uppercase() } + " $yy"
            return SearchTerm.Time(s, c.timeInMillis, name) to (if (y != null) 2 else 1)
        }
        val y = year(word)
        if (y != null) {
            val c = startOfDay(cal(zone, now)); c.set(y, 0, 1)
            val s = c.timeInMillis; c.add(java.util.Calendar.YEAR, 1)
            return SearchTerm.Time(s, c.timeInMillis, "$y") to 1
        }
        return null
    }
}
