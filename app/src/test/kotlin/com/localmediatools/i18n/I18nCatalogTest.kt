package com.localmediatools.i18n

import com.localmediatools.gallery.core.MediaFilter
import com.localmediatools.gallery.core.SearchParser
import com.localmediatools.gallery.core.SearchTerm
import com.localmediatools.gallery.core.SearchVocabulary
import com.localmediatools.gallery.core.Taxonomy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The translation catalogs (assets/i18n) against the code's English text, with the same rules as
 * buildtools/i18n/catalog.py, plus the parts of the languages that are code: plural rules, number
 * placeholders, date patterns and search.
 */
class I18nCatalogTest {
    @After fun backToEnglish() = I18n.use(Language.ENGLISH, Catalog.EMPTY)

    private val main = File("app/src/main")
    private val translated = Language.entries.filter { it != Language.ENGLISH }
    private fun catalogText(l: Language) = File(main, "assets/" + I18n.path(l)).readText()
    private fun use(l: Language) = I18n.use(l, Catalog.parse(catalogText(l)))
    private val categories by lazy { Taxonomy.parse(File(main, "assets/gallery/categories.tsv").readText()) }

    // ------------------------------------------------------------------ the code's English text

    /** (note, English forms) of every tr/trc/trn/N_ call with literal text, and of the category table. */
    private val codeKeys: Map<Pair<String?, List<String>>, String> by lazy {
        val found = LinkedHashMap<Pair<String?, List<String>>, String>()
        val bad = ArrayList<String>()
        File(main, "kotlin").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && !it.path.endsWith("i18n/I18n.kt") }.sortedBy { it.path }.forEach { f ->
            val src = Source.stripComments(f.readText())
            for (m in Source.CALL.findAll(src)) {
                val fn = m.groupValues[1]
                var i = m.range.last + 1
                if (fn == "trn") i = Source.skipExpr(src, i) + 1
                val want = if (fn == "tr" || fn == "N_") 1 else 2
                val args = ArrayList<String>()
                var ok = true
                for (k in 0 until want) {
                    i = Source.skipWs(src, i)
                    val lit = Source.literalAt(src, i)
                    if (lit == null) { ok = false; break }
                    args.add(lit.first); i = Source.skipWs(src, lit.second)
                    if (k < want - 1) { if (i >= src.length || src[i] != ',') { ok = false; break }; i++ }
                }
                val where = "${f.path}:${src.substring(0, m.range.first).count { it == '\n' } + 1}"
                if (!ok) {
                    // Text passed on from an N_-marked value is fine ("tr(labelEn)"); anything else must be literal.
                    val arg = src.substring(m.range.last + 1).trimStart().takeWhile { it != ')' }
                    if (!Source.dynamicAllowed(fn, arg)) bad.add("$where: $fn(${arg.take(60)}")
                    continue
                }
                val key: Pair<String?, List<String>> = if (fn == "trc") args[0] to listOf(args[1]) else null to args
                found.putIfAbsent(key, where)
            }
        }
        assertTrue("text that can't be translated (not a literal):\n" + bad.joinToString("\n"), bad.isEmpty())
        for (c in categories) {
            found.putIfAbsent(null to listOf(c.englishName), "categories.tsv")
            found.putIfAbsent(null to listOf(groupOf(c)), "categories.tsv")
            found.putIfAbsent("search words for “${c.englishName}”, separated by commas" to listOf(c.words.joinToString(",")), "categories.tsv")
        }
        found
    }

    private val groups by lazy {
        File(main, "assets/gallery/categories.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { it.split('\t').let { c -> c[0] to c[2] } }
    }
    private fun groupOf(c: com.localmediatools.gallery.core.Category) = groups.getValue(c.key)

    private val placeholder = Regex("(?<!\\{)\\{(\\d+)\\}(?!\\})")
    private fun placeholders(s: String) = placeholder.findAll(s).map { it.groupValues[1] }.toSet()

    @Test fun everyLanguageTranslatesEverythingConsistently() {
        val problems = ArrayList<String>()
        for (l in translated) {
            val entries = Catalog.entries(catalogText(l))
            val have = HashSet<Pair<String?, List<String>>>()
            for (e in entries) {
                val key = e.context to e.english
                val where = "${l.tag}:${e.line} ${e.english[0].take(50)}"
                if (!have.add(key)) problems.add("$where: twice")
                if (key !in codeKeys) problems.add("$where: not in the code")
                if (e.english.size == 1) {
                    if (e.translation.size != 1) { problems.add("$where: ${e.translation.size} translations"); continue }
                    val t = e.translation[0]; val en = e.english[0]
                    if (placeholders(t) != placeholders(en)) problems.add("$where: placeholders ${placeholders(t)} instead of ${placeholders(en)}")
                    val between = e.context?.startsWith("between") == true
                    if (!between && (t.take(1).isBlank() != en.take(1).isBlank() || t.takeLast(1).isBlank() != en.takeLast(1).isBlank()))
                        problems.add("$where: spaces around the text differ")
                    if (t.isEmpty()) problems.add("$where: empty")
                } else {
                    if (e.translation.size != l.pluralForms) { problems.add("$where: ${e.translation.size} plural forms instead of ${l.pluralForms}"); continue }
                    val union = placeholders(e.english[0]) + placeholders(e.english[1])
                    val need = placeholders(e.english[1]) - "0"
                    for (t in e.translation) {
                        if (!union.containsAll(placeholders(t))) problems.add("$where: unknown placeholder in \"$t\"")
                        if (!placeholders(t).containsAll(need)) problems.add("$where: \"$t\" lacks ${need - placeholders(t)}")
                    }
                    // Where the first form also covers numbers above 1 (Russian 21, 31…) it must show the number.
                    if (l.pluralIndex(21) == 0)
                        if ("0" in placeholders(e.english[1]) && "0" !in placeholders(e.translation[0])) problems.add("$where: the first form must show {0}")
                }
                if (e.context?.startsWith("search") == true && e.translation.single().split(',').any { it.isBlank() }) problems.add("$where: empty search word")
            }
            for ((k, w) in codeKeys) if (k !in have) problems.add("${l.tag}: missing \"${k.second[0].take(60)}\" ($w)")
        }
        assertTrue(problems.take(60).joinToString("\n") + if (problems.size > 60) "\n… ${problems.size} problems" else "", problems.isEmpty())
    }

    @Test fun datePatternsWorkAndShowTheSameFields() {
        // Letters both java.text and Android's ICU-based SimpleDateFormat read the same way.
        val portable = "GyYMLwWDdFEuaHkKhmsSzZX".toSet()
        fun letters(p: String): String {
            val b = StringBuilder(); var quoted = false
            for (c in p) { if (c == '\'') quoted = !quoted else if (!quoted && (c in 'a'..'z' || c in 'A'..'Z')) b.append(c) }
            return b.toString()
        }
        fun fields(p: String) = letters(p).mapNotNull {
            when (it) { 'y', 'Y', 'u' -> "year"; 'M', 'L' -> "month"; 'd' -> "day"; 'E' -> "weekday"; 'h', 'H', 'k', 'K' -> "hour"; 'm' -> "minute"; else -> null }
        }.toSet()
        val sample = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { set(2024, Calendar.JUNE, 9, 18, 5, 0) }.timeInMillis
        val problems = ArrayList<String>()
        for (l in translated) {
            val ranges = HashMap<String, Pair<MutableSet<String>, MutableSet<String>>>()
            for (e in Catalog.entries(catalogText(l))) {
                if (e.context?.let { it.startsWith("date pattern") || it.startsWith("time pattern") } != true) continue
                val p = e.translation.single()
                val where = "${l.tag}: ${e.context} \"$p\""
                val odd = letters(p).toSet() - portable
                if (odd.isNotEmpty()) { problems.add("$where: letters $odd (put words in 'quotes')"); continue }
                // A range ("5–9 June 2024") may spread its fields differently over its two ends ("2024年6月5日–9日"): compared together.
                val range = Regex("^date pattern: the (first|last) day of a range in (.*)$").find(e.context!!)
                if (range != null) {
                    val r = ranges.getOrPut(range.groupValues[2]) { mutableSetOf<String>() to mutableSetOf() }
                    r.first.addAll(fields(p)); r.second.addAll(fields(e.english[0]))
                } else if (fields(p) != fields(e.english[0])) problems.add("$where: shows ${fields(p)} instead of ${fields(e.english[0])}")
                try { java.text.SimpleDateFormat(p, l.locale).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(sample)) }
                catch (x: IllegalArgumentException) { problems.add("$where: ${x.message}") }
            }
            for ((k, r) in ranges) if (r.first != r.second) problems.add("${l.tag}: a range in $k shows ${r.first} instead of ${r.second}")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun searchWordsAreNotLeftOut() {
        // A word a language leaves out of searches ("de", "的") must not also be what finds something.
        val problems = ArrayList<String>()
        for (l in translated) {
            use(l)
            val stop = SearchParser.list(trc("search: words to leave out of a search, separated by commas",
                "a,an,the,of,with,and,&,+,at,in,on,by,from,near,to,for,my,me,our,show,find,all,some,where,is,are,was,were,together,or,both,having,who,that,taken")).toSet()
            val words = HashMap<String, String>()
            for (c in categories) for (w in c.searchWords + c.name) SearchParser.normalize(w).takeIf { it.isNotEmpty() }?.let { words.putIfAbsent(it, c.englishName) }
            for (k in MediaFilter.entries) for (w in k.words) words.putIfAbsent(w, k.name)
            for (w in stop) words[w]?.let { problems.add("${l.tag}: \"$w\" is left out of searches but finds $it") }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // ------------------------------------------------------------------ the languages as code

    @Test fun pluralRules() {
        fun forms(l: Language, vararg n: Long) = n.map { l.pluralIndex(it) }
        assertEquals(listOf(1, 0, 1, 1), forms(Language.ENGLISH, 0, 1, 2, 21))
        // Brazilian Portuguese: "0 fotos" (CLDR would say "0 foto").
        assertEquals(listOf(1, 0, 1), forms(Language.PORTUGUESE_BR, 0, 1, 2))
        assertEquals(listOf(0, 0, 1, 1), forms(Language.FRENCH, 0, 1, 2, 100))
        assertEquals(listOf(0, 1, 2, 2, 2, 0, 1, 2, 2, 0, 2), forms(Language.RUSSIAN, 1, 2, 5, 11, 12, 21, 22, 25, 111, 101, 0))
        assertEquals(listOf(0, 0, 0), forms(Language.JAPANESE, 0, 1, 2))
        for (l in Language.entries) for (n in 0L..200L) assertTrue(l.pluralIndex(n) in 0 until l.pluralForms)
    }

    @Test fun formatsAndCatalogs() {
        assertEquals("3 of 7", I18n.format("{0} of {1}", arrayOf(3, 7)))
        assertEquals("{x} {0} 1 {5}", I18n.format("{x} {{0}} {0} {5}", arrayOf(1)))
        val c = Catalog.parse("""
            # comment
            @ empty the list
            < Clear
            > Limpar

            < {0} photo
            < {0} photos
            > {0} фотография
            > {0} фотографии
            > {0} фотографий

            < Line\nbreak \\ here
            > Linha\nquebrada \\ aqui
        """.trimIndent())
        I18n.use(Language.RUSSIAN, c)
        assertEquals("Limpar", trc("empty the list", "Clear"))
        assertEquals("Clear", tr("Clear"))
        assertEquals(listOf("1 фотография", "3 фотографии", "11 фотографий", "21 фотография"), listOf(1, 3, 11, 21).map { trn(it, "{0} photo", "{0} photos") })
        assertEquals("Linha\nquebrada \\ aqui", tr("Line\nbreak \\ here"))
        // Text a catalog lacks stays English, with its own singular and plural.
        assertEquals(listOf("1 video", "2 videos"), listOf(1, 2).map { trn(it, "{0} video", "{0} videos") })
        assertEquals("A, B and C", I18n.andList(listOf("A", "B", "C")))
        // Shortening never leaves half of an emoji (an invalid file name).
        assertEquals(listOf("ab", "ab", "ab😀", "ab😀c"), listOf(2, 3, 4, 9).map { "ab😀c".takeWhole(it) })
    }

    @Test fun pickedFromThePhonesLanguages() {
        fun pick(vararg tags: String) = Language.forLocales(tags.map { Locale.forLanguageTag(it) })
        assertEquals(Language.PORTUGUESE_BR, pick("pt-BR"))
        assertEquals(Language.PORTUGUESE_BR, pick("pt-PT"))
        assertEquals(Language.CHINESE_SIMPLIFIED, pick("zh-CN"))
        assertEquals(Language.CHINESE_SIMPLIFIED, pick("zh-Hans-HK"))
        // Traditional Chinese has no translation: the next language on the phone's list is used.
        assertEquals(Language.JAPANESE, pick("zh-TW", "ja-JP"))
        assertEquals(Language.ENGLISH, pick("zh-Hant"))
        assertEquals(Language.FRENCH, pick("fr-CA"))
        assertEquals(Language.ENGLISH, pick("nl-NL", "en-GB", "de"))
        assertEquals(Language.GERMAN, pick("nl-NL", "de-AT"))
        assertEquals(Language.ENGLISH, pick())
    }

    // ------------------------------------------------------------------ search in other languages

    private val utc = TimeZone.getTimeZone("UTC")
    private val now = Calendar.getInstance(utc).apply { set(2026, Calendar.OCTOBER, 8, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun at(y: Int, m: Int, d: Int) = Calendar.getInstance(utc).apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun vocab() = SearchVocabulary(listOf(1L to "João"), categories.map { Triple(it.key, it.name, it.searchWords + it.englishName) }, emptyList())
    private fun keys(q: String) = SearchParser.parse(q, vocab(), now, utc).terms.map { if (it is SearchTerm.Time) "t:${it.fromMs}-${it.toMs}" else SearchParser.termKey(it) }.toSet()
    private fun month(y: Int, m: Int) = "t:${at(y, m, 1)}-${if (m == 11) at(y + 1, 0, 1) else at(y, m + 1, 1)}"

    @Test fun searchesInPortuguese() {
        use(Language.PORTUGUESE_BR)
        assertEquals(setOf("k:PHOTOS", "c:dog", "c:beach", month(2023, Calendar.JUNE)), keys("fotos de cachorros na praia em junho de 2023"))
        // Accents are optional, and English still works.
        assertEquals(setOf("k:VIDEOS", "c:waterfall"), keys("videos da cachoeira"))
        assertEquals(setOf("c:dog", "c:beach"), keys("dogs beach"))
        // "mar" is the sea, not March ("março" is).
        assertEquals(setOf("c:sea"), keys("mar"))
        assertEquals(setOf(month(2026, Calendar.MARCH)), keys("março"))
        assertEquals(setOf("p:1", "t:${at(2026, Calendar.OCTOBER, 7)}-${at(2026, Calendar.OCTOBER, 8)}"), keys("joão ontem"))
    }

    /** A catalog with search words for a few categories, in the catalog format. */
    private fun searchCatalog(stop: String, words: Map<String, String>) = Catalog.parse(buildString {
        append("@ search: words to leave out of a search, separated by commas\n< a,an,the,of,with,and,&,+,at,in,on,by,from,near,to,for,my,me,our,show,find,all,some,where,is,are,was,were,together,or,both,having,who,that,taken\n> $stop\n\n")
        for ((key, w) in words) {
            val c = categories.first { it.key == key }
            append("@ search words for “${c.englishName}”, separated by commas\n< ${c.words.joinToString(",")}\n> $w\n\n")
        }
    })

    @Test fun searchesInChineseJapaneseAndKorean() {
        // Chinese and Japanese are written without spaces: the words are found inside what was typed.
        I18n.use(Language.CHINESE_SIMPLIFIED, searchCatalog("的,和,在,我的", mapOf("dog" to "狗,小狗", "beach" to "海边,海滩,沙滩")))
        assertEquals(setOf("c:dog", "c:beach"), keys("海边的狗"))
        assertEquals(setOf("c:dog", month(2023, Calendar.JUNE)), keys("2023年6月的狗"))
        I18n.use(Language.JAPANESE, searchCatalog("の,と,で,に", mapOf("cat" to "猫,ねこ,ネコ", "sea" to "海")))
        assertEquals(setOf("c:cat", "c:sea"), keys("海の猫"))
        assertEquals(setOf("c:cat", month(2026, Calendar.JUNE)), keys("6月のネコ"))
        // Korean has spaces, but particles stick to the word ("해변에서": at the beach).
        I18n.use(Language.KOREAN, searchCatalog("나의", mapOf("dog" to "개,강아지", "beach" to "해변,바닷가")))
        assertEquals(setOf("c:dog", "c:beach"), keys("해변에서 강아지"))
        assertEquals(setOf("c:dog", month(2023, Calendar.JUNE)), keys("2023년 6월 강아지"))
    }

    /** A video's text in another language than the app's: only the thread making it sees that language. */
    @Test fun anotherLanguageForOneThreadOnly() {
        I18n.reader = { path -> File("app/src/main/assets/$path").takeIf { it.exists() }?.readText() }
        use(Language.GERMAN)
        val inside = I18n.inLanguage(Language.PORTUGUESE_BR) {
            // Meanwhile, another thread still writes German.
            var other = ""
            val t = Thread { other = tr("Cancel") + "|" + I18n.language.tag }
            t.start(); t.join()
            Triple(tr("Cancel"), I18n.date(0, TimeZone.getTimeZone("UTC"), "MMMM"), other)
        }
        assertEquals("Cancelar", inside.first)
        assertEquals("janeiro", inside.second.lowercase(Locale.ROOT))
        assertEquals("Abbrechen|de", inside.third)
        // Back to German afterwards; null or the same language changes nothing.
        assertEquals(Language.GERMAN, I18n.language)
        assertEquals("Abbrechen", tr("Cancel"))
        assertEquals("Abbrechen", I18n.inLanguage(null) { tr("Cancel") })
    }
}

/** Reading Kotlin source text as buildtools/i18n/catalog.py does. */
private object Source {
    val CALL = Regex("(?<![\\w.])(trn|trc|tr|N_)\\(")

    /** Calls whose text comes from a value marked with N_ elsewhere ("tr(labelEn)", "tr(it)"), or from the category table. */
    fun dynamicAllowed(fn: String, arg: String): Boolean =
        (fn == "tr" && Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(arg.trim())) ||
            (fn == "trc" && arg.startsWith("\"search words for “\$englishName”, separated by commas\""))

    fun stripComments(src: String): String {
        val out = StringBuilder(src.length); var i = 0
        while (i < src.length) {
            if (src.startsWith("//", i)) { val j = src.indexOf('\n', i); i = if (j < 0) src.length else j; continue }
            if (src.startsWith("/*", i)) { val j = src.indexOf("*/", i + 2); i = if (j < 0) src.length else j + 2; continue }
            val c = src[i]
            if (c == '"') {
                var j: Int
                if (src.startsWith("\"\"\"", i)) {
                    // A raw string ends at the last quote of its closing run ("…x""""" ends after all of them).
                    j = src.indexOf("\"\"\"", i + 3)
                    if (j < 0) j = src.length else { j += 3; while (j < src.length && src[j] == '"') j++ }
                } else { j = i + 1; while (j < src.length && src[j] != '"') j += if (src[j] == '\\') 2 else 1; j++ }
                out.append(src, i, minOf(j, src.length)); i = j; continue
            }
            if (c == '\'') {
                var j = i + 1; while (j < src.length && src[j] != '\'') j += if (src[j] == '\\') 2 else 1
                out.append(src, i, minOf(j + 1, src.length)); i = j + 1; continue
            }
            out.append(c); i++
        }
        return out.toString()
    }

    fun skipWs(src: String, from: Int): Int { var i = from; while (i < src.length && src[i] in " \t\r\n") i++; return i }

    /** Index of the comma or bracket that ends the argument starting at [from]. */
    fun skipExpr(src: String, from: Int): Int {
        var depth = 0; var i = from
        while (i < src.length) {
            val c = src[i]
            if (c == '"') { var j = i + 1; while (j < src.length && src[j] != '"') j += if (src[j] == '\\') 2 else 1; i = j + 1; continue }
            if (c in "([{") depth++
            else if (c in ")]}") { if (depth == 0) return i; depth-- }
            else if (c == ',' && depth == 0) return i
            i++
        }
        return i
    }

    /** The text of the string literal at [i] and the index after it; null when it isn't a plain literal. */
    fun literalAt(src: String, i: Int): Pair<String, Int>? {
        if (i >= src.length || src[i] != '"' || src.startsWith("\"\"\"", i)) return null
        val b = StringBuilder(); var j = i + 1
        while (j < src.length) {
            val c = src[j]
            if (c == '\\') {
                when (val n = src[j + 1]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                    '"', '\'', '\\', '$' -> b.append(n)
                    'u' -> { b.append(src.substring(j + 2, j + 6).toInt(16).toChar()); j += 4 }
                    else -> throw IllegalArgumentException("escape \\$n")
                }
                j += 2; continue
            }
            if (c == '$' && j + 1 < src.length && (src[j + 1] == '{' || src[j + 1].isLetter())) return null
            if (c == '"') return b.toString() to j + 1
            b.append(c); j++
        }
        return null
    }
}
