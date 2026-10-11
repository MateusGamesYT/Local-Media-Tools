package com.localmediatools.i18n

import java.util.Locale

/**
 * The languages the app speaks. Text is written in English in the code and passed through [tr] (or
 * [trn] when it depends on a number); the English text is the key into the language's catalog
 * (assets/i18n/<tag>.txt, [Catalog]). Anything a catalog lacks stays in English.
 */
enum class Language(val tag: String, val nativeName: String, val locale: Locale, private val rule: PluralRule) {
    ENGLISH("en", "English", Locale.ENGLISH, PluralRule.ONE_OTHER),
    PORTUGUESE_BR("pt-BR", "Português (Brasil)", Locale.forLanguageTag("pt-BR"), PluralRule.ONE_OTHER),
    SPANISH("es", "Español", Locale.forLanguageTag("es"), PluralRule.ONE_OTHER),
    FRENCH("fr", "Français", Locale.FRENCH, PluralRule.FRENCH),
    GERMAN("de", "Deutsch", Locale.GERMAN, PluralRule.ONE_OTHER),
    ITALIAN("it", "Italiano", Locale.ITALIAN, PluralRule.ONE_OTHER),
    RUSSIAN("ru", "Русский", Locale.forLanguageTag("ru"), PluralRule.RUSSIAN),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文", Locale.forLanguageTag("zh-Hans-CN"), PluralRule.NONE),
    JAPANESE("ja", "日本語", Locale.JAPANESE, PluralRule.NONE),
    KOREAN("ko", "한국어", Locale.KOREAN, PluralRule.NONE);

    /** How many forms a count-dependent text has in this language (the catalog gives them in this order). */
    val pluralForms get() = rule.forms

    /** Which of the forms goes with [n]. */
    fun pluralIndex(n: Long): Int = rule.index(n)

    /** Languages without spaces between words (search looks for words inside what was typed). */
    val unspaced get() = this == CHINESE_SIMPLIFIED || this == JAPANESE

    companion object {
        fun of(tag: String?): Language? = entries.firstOrNull { it.tag == tag }

        /**
         * The best language for the phone's languages (most preferred first), as Android picks
         * translations: the same language and, for Chinese, the same script; English otherwise.
         * Portuguese from anywhere gets the Brazilian translation.
         */
        fun forLocales(locales: List<Locale>): Language {
            for (l in locales) {
                val lang = l.language
                val hit = when (lang) {
                    "zh" -> CHINESE_SIMPLIFIED.takeIf { script(l) == "Hans" }
                    "en" -> ENGLISH
                    else -> entries.firstOrNull { it != CHINESE_SIMPLIFIED && it.locale.language == lang }
                }
                if (hit != null) return hit
            }
            return ENGLISH
        }

        /** The script of a Chinese locale: explicit, or implied by the region (Taiwan, Hong Kong and Macau write Traditional). */
        private fun script(l: Locale): String = l.script.ifEmpty { if (l.country in setOf("TW", "HK", "MO")) "Hant" else "Hans" }
    }
}

/**
 * Plural rules for whole numbers (CLDR), except Portuguese, which uses the singular for 1 only
 * ("0 fotos", as Brazilian Portuguese says it; CLDR's rule would give "0 foto").
 */
enum class PluralRule(val forms: Int) {
    /** one: 1; other: everything else. */
    ONE_OTHER(2) { override fun index(n: Long) = if (n == 1L) 0 else 1 },
    /** one: 0 and 1; other: everything else. */
    FRENCH(2) { override fun index(n: Long) = if (n == 0L || n == 1L) 0 else 1 },
    /** one: 1, 21, 31…; few: 2–4, 22–24…; many: 0, 5–20, 25–30… */
    RUSSIAN(3) {
        override fun index(n: Long): Int {
            val a = kotlin.math.abs(n); val d = a % 10; val h = a % 100
            return when { d == 1L && h != 11L -> 0; d in 2L..4L && h !in 12L..14L -> 1; else -> 2 }
        }
    },
    /** One form for every number (Chinese, Japanese, Korean). */
    NONE(1) { override fun index(n: Long) = 0 };

    abstract fun index(n: Long): Int
}

/** The current language and its translations. */
object I18n {
    @Volatile var language: Language = Language.ENGLISH
        private set
    @Volatile var catalog: Catalog = Catalog.EMPTY
        private set

    /** For numbers, dates and letter case in the current language. */
    val locale: Locale get() = language.locale

    fun use(language: Language, catalog: Catalog) {
        this.catalog = catalog
        this.language = language
    }

    /** Switches language, reading its catalog with [read] (asset path → text, or null when missing). */
    fun load(language: Language, read: (String) -> String?) {
        val c = if (language == Language.ENGLISH) Catalog.EMPTY else read(path(language))?.let { Catalog.parse(it) } ?: Catalog.EMPTY
        use(language, c)
    }

    fun path(language: Language) = "i18n/${language.tag}.txt"

    /**
     * [ms] written with a date pattern (java.text.SimpleDateFormat's letters, e.g. "d MMMM yyyy"),
     * which the catalogs translate as they do text: the order, the words and the month names follow
     * the current language.
     */
    fun date(ms: Long, zone: java.util.TimeZone, pattern: String): String =
        java.text.SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(java.util.Date(ms))

    /** [s] with its first letter in upper case, as a title starts ("sábado, 14 de junho" → "Sábado, …"). */
    fun capitalize(s: String): String = s.replaceFirstChar { it.titlecase(locale) }

    /** "A", "A and B", "A, B and C" in the current language. */
    fun andList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> tr("{0} and {1}", items.dropLast(1).joinToString(trc("between the items of a list", ", ")), items.last())
    }

    /** Puts [args] into {0}, {1}…; "{{" and "}}" are literal braces. */
    fun format(text: String, args: Array<out Any?>): String {
        if (args.isEmpty() && text.indexOf('{') < 0 && text.indexOf('}') < 0) return text
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{' && i + 1 < text.length && text[i + 1] == '{') { out.append('{'); i += 2; continue }
            if (c == '}' && i + 1 < text.length && text[i + 1] == '}') { out.append('}'); i += 2; continue }
            if (c == '{') {
                val end = text.indexOf('}', i)
                val k = if (end > i) text.substring(i + 1, end).toIntOrNull() else null
                if (k != null && k in args.indices) { out.append(args[k].toString()); i = end + 1; continue }
            }
            out.append(c); i++
        }
        return out.toString()
    }
}

/** [english] in the current language, with [args] put into its {0}, {1}… */
fun tr(english: String, vararg args: Any?): String = I18n.format(I18n.catalog.text(english), args)

/**
 * Text that depends on a number: [one] and [other] are the English singular and plural, {0} is [n],
 * further [args] go into {1}, {2}… The language picks its form for [n] (some have three, some one).
 */
fun trn(n: Number, one: String, other: String, vararg args: Any?): String {
    val count = n.toLong()
    val forms = I18n.catalog.forms(one, other)
    val text = if (forms != null) forms[I18n.language.pluralIndex(count).coerceAtMost(forms.size - 1)] else if (count == 1L) one else other
    return I18n.format(text, arrayOf(n, *args))
}

/**
 * [english] in the current language, with a note on which meaning is meant where the same English
 * word means different things ("Clear" a list, "Clear" weather); the note is not shown.
 */
fun trc(context: String, english: String, vararg args: Any?): String = I18n.format(I18n.catalog.text(english, context), args)

/** The first [n] UTF-16 units of the text, without cutting a surrogate pair (an emoji) in half. */
fun String.takeWhole(n: Int): String = if (length <= n) this else take(if (n > 0 && Character.isHighSurrogate(this[n - 1])) n - 1 else n)

/**
 * Marks English text that is stored and translated later, where it is shown (with [tr]); it is
 * returned unchanged (as gettext's N_).
 */
@Suppress("FunctionName")
fun N_(english: String): String = english

/**
 * One language's translations: the English text (and, for count-dependent text, its plural; and a
 * note telling apart words with several meanings) → the translation (one per plural form).
 *
 * The file is UTF-8 text; entries are separated by blank lines:
 *
 *     @ verb                    (optional: which meaning, as given to trc)
 *     < {0} photo               (the English text; a second line is its plural)
 *     < {0} photos
 *     > {0} foto                (the translation; one line per plural form of the language)
 *     > {0} fotos
 *
 * Lines starting with "#" are comments. In the text, "\n" is a line break and "\\" a backslash.
 */
class Catalog private constructor(private val single: Map<String, String>, private val plural: Map<String, List<String>>) {
    fun text(english: String, context: String? = null): String = single[key(english, context)] ?: english
    fun forms(one: String, other: String): List<String>? = plural[pluralKey(one, other)]

    val size get() = single.size + plural.size

    /** Every entry (for tests): its English lines, its note and its translations. */
    class Entry(val english: List<String>, val context: String?, val translation: List<String>, val line: Int)

    companion object {
        val EMPTY = Catalog(emptyMap(), emptyMap())

        fun key(english: String, context: String?) = if (context == null) english else "$context\u0004$english"
        fun pluralKey(one: String, other: String) = "$one\u0000$other"

        fun parse(text: String): Catalog {
            val single = HashMap<String, String>(); val plural = HashMap<String, List<String>>()
            for (e in entries(text)) {
                if (e.english.size == 1) single[key(e.english[0], e.context)] = e.translation.firstOrNull() ?: continue
                else if (e.english.size == 2 && e.translation.isNotEmpty()) plural[pluralKey(e.english[0], e.english[1])] = e.translation
            }
            return Catalog(single, plural)
        }

        fun entries(text: String): List<Entry> {
            val out = ArrayList<Entry>()
            var english = ArrayList<String>(); var translation = ArrayList<String>(); var context: String? = null; var start = 0
            fun flush() {
                if (english.isNotEmpty()) out.add(Entry(english, context, translation, start))
                english = ArrayList(); translation = ArrayList(); context = null
            }
            for ((n, raw) in text.lineSequence().withIndex()) {
                val line = raw.removeSuffix("\r")
                when {
                    line.isBlank() -> flush()
                    line.startsWith("#") -> {}
                    line.startsWith("@ ") -> { if (english.isNotEmpty()) flush(); context = line.substring(2) }
                    line.startsWith("< ") || line == "<" -> {
                        if (translation.isNotEmpty()) flush()
                        if (english.isEmpty()) start = n + 1
                        english.add(unescape(line.drop(2)))
                    }
                    line.startsWith("> ") || line == ">" -> translation.add(unescape(line.drop(2)))
                    else -> throw IllegalArgumentException("line ${n + 1}: expected \"< \", \"> \", \"@ \" or \"#\": $line")
                }
            }
            flush()
            return out
        }

        fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")

        fun unescape(s: String): String {
            if (s.indexOf('\\') < 0) return s
            val b = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) { 'n' -> b.append('\n'); '\\' -> b.append('\\'); else -> b.append(c).append(s[i + 1]) }
                    i += 2
                } else { b.append(c); i++ }
            }
            return b.toString()
        }
    }
}
