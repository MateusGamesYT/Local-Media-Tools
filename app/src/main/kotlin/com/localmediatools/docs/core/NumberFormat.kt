package com.localmediatools.docs.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DateFormatSymbols
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Excel's number formats ("#,##0.00", "dd/mm/yyyy", "[Red](0.0%)", "# ?/?"…), as Excel shows them: the
 * section for positive, negative, zero or text values, colours and conditions, digit placeholders,
 * thousands separators and scaling, percentages, scientific notation, fractions, dates and times
 * (also elapsed [h]:mm), and Excel's own General format. Decimal and thousands separators, month and
 * day names follow [locale], as Excel itself does; the built-in date and currency formats too.
 */
class NumberFormatter(private val locale: Locale = Locale.US, private val date1904: Boolean = false) {
    private val symbols = DecimalFormatSymbols.getInstance(locale)
    private val dec = symbols.decimalSeparator
    private val group = symbols.groupingSeparator.let { if (it == ' ' || it == ' ') ' ' else it }
    private val dateSymbols = DateFormatSymbols.getInstance(locale)
    private val parsed = HashMap<String, List<Section>>()

    /** The value as shown, and the colour the format gives it (0: the cell's own). */
    class Shown(val text: String, val color: Int = 0)

    fun format(value: Double, code: String?): Shown {
        if (value.isNaN() || value.isInfinite()) return Shown("#NUM!")
        val c = code?.takeIf { it.isNotBlank() } ?: "General"
        if (c.equals("General", true)) return Shown(general(value))
        val sections = parsed.getOrPut(c) { parse(c) }
        val (sec, v) = pick(sections, value) ?: return Shown(general(value))
        val text = try { render(sec, v, value) } catch (_: Exception) { general(value) }
        return Shown(text, sec.color)
    }

    /** Text in a cell whose format has a text section ("@" or a 4th section). */
    fun formatText(text: String, code: String?): Shown {
        if (code == null || code.equals("General", true) || code == "@") return Shown(text)
        val sections = parsed.getOrPut(code) { parse(code) }
        val sec = sections.getOrNull(3) ?: sections.firstOrNull { it.isText } ?: return Shown(text)
        val sb = StringBuilder()
        for (t in sec.tokens) when (t) {
            is Tok.Lit -> sb.append(t.s)
            is Tok.At -> sb.append(text)
            else -> Unit
        }
        return Shown(sb.toString(), sec.color)
    }

    // ------------------------------------------------------------------ General
    /** Excel's General: up to 11 characters, switching to scientific notation for very large or small numbers. */
    fun general(v: Double): String {
        if (v == 0.0) return "0"
        val a = abs(v)
        if (a >= 1e11 || a < 1e-9) {
            val bd = BigDecimal(v).round(MathContext(6, RoundingMode.HALF_UP))
            var exp = bd.precision() - bd.scale() - 1
            var mant = bd.movePointLeft(exp).stripTrailingZeros()
            if (mant.abs() >= BigDecimal.TEN) { mant = mant.movePointLeft(1); exp++ }
            val m = mant.toPlainString().replace('.', dec)
            return m + "E" + (if (exp < 0) "-" else "+") + abs(exp).toString().padStart(2, '0')
        }
        // As many significant digits as fit in 11 characters.
        var digits = 10
        while (digits > 0) {
            val bd = BigDecimal(v).round(MathContext(digits, RoundingMode.HALF_UP)).stripTrailingZeros()
            val s = (if (bd.scale() < 0) bd.setScale(0) else bd).toPlainString()
            if (s.length <= 11 || digits == 1) return s.replace('.', dec)
            digits--
        }
        return v.toString()
    }

    // ------------------------------------------------------------------ parsing
    private sealed class Tok {
        class Lit(val s: String) : Tok()
        object At : Tok()
        class Digit(val ch: Char) : Tok() // '0', '#', '?'
        object Point : Tok()
        object Comma : Tok()
        object Percent : Tok()
        class Exp(val plus: Boolean) : Tok()
        object Slash : Tok()
        class Date(val s: String) : Tok() // y…, m…, d…, h…, s…, AM/PM, A/P, .0 (sub-second)
        class Elapsed(val unit: Char, val width: Int) : Tok()
    }

    private class Section(val tokens: List<Tok>, val color: Int, val cond: Pair<String, Double>?) {
        val isDate = tokens.any { it is Tok.Date || it is Tok.Elapsed }
        val isText = tokens.any { it is Tok.At } && tokens.none { it is Tok.Digit }
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        var q = false; var br = false; var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\\' && i + 1 < code.length -> { sb.append(c).append(code[i + 1]); i += 2; continue }
                c == '"' -> q = !q
                !q && c == '[' -> br = true
                !q && c == ']' -> br = false
                !q && !br && c == ';' -> { out.add(sb.toString()); sb.setLength(0); i++; continue }
            }
            sb.append(c); i++
        }
        out.add(sb.toString())
        return out
    }

    private fun parse(code: String): List<Section> = splitSections(code).map { parseSection(it) }

    private fun parseSection(s: String): Section {
        val toks = ArrayList<Tok>()
        var color = 0
        var cond: Pair<String, Double>? = null
        var i = 0
        fun lit(x: String) { val last = toks.lastOrNull(); if (last is Tok.Lit) toks[toks.size - 1] = Tok.Lit(last.s + x) else toks.add(Tok.Lit(x)) }
        val lower = s.lowercase(Locale.ROOT)
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> { val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }; lit(s.substring(i + 1, end)); i = end + 1 }
                c == '\\' -> { if (i + 1 < s.length) lit(s[i + 1].toString()); i += 2 }
                c == '_' -> { lit(" "); i += 2 }
                c == '*' -> i += 2
                c == '[' -> {
                    val end = s.indexOf(']', i).let { if (it < 0) s.length else it }
                    val inner = s.substring(i + 1, end)
                    val li = inner.lowercase(Locale.ROOT)
                    when {
                        Regex("^(h+|m+|s+)$").matches(li) -> toks.add(Tok.Elapsed(li[0], li.length))
                        li.startsWith("$") -> {
                            val sym = inner.substring(1).substringBefore('-')
                            val lcid = inner.substringAfter('-', "").uppercase(Locale.ROOT)
                            if (sym.isNotEmpty()) lit(sym)
                            if (lcid.endsWith("F800")) toks.addAll(parseSection(longDatePattern()).tokens)
                            if (lcid.endsWith("F400")) toks.addAll(parseSection("h:mm:ss AM/PM").tokens)
                        }
                        li.startsWith("color") -> color = PALETTE.getOrElse((li.removePrefix("color").toIntOrNull() ?: 1) - 1) { 0 }.let { (0xFF000000L or it.toLong()).toInt() }
                        li in COLORS -> color = COLORS.getValue(li)
                        Regex("^[<>=]").containsMatchIn(li) -> {
                            val m = Regex("""^(<=|>=|<>|<|>|=)\s*(-?[0-9.]+)""").find(li)
                            if (m != null) cond = m.groupValues[1] to (m.groupValues[2].toDoubleOrNull() ?: 0.0)
                        }
                        else -> Unit // [DBNum1], locale codes…
                    }
                    i = end + 1
                }
                c == '0' || c == '#' || c == '?' -> { toks.add(Tok.Digit(c)); i++ }
                c == '.' -> {
                    // ".0" right after seconds means fractions of a second.
                    if (i + 1 < s.length && s[i + 1] == '0' && toks.lastOrNull().let { it is Tok.Date && it.s.startsWith("s") || it is Tok.Elapsed && it.unit == 's' }) {
                        var k = i + 1; while (k < s.length && s[k] == '0') k++
                        toks.add(Tok.Date("." + s.substring(i + 1, k))); i = k
                    } else { toks.add(Tok.Point); i++ }
                }
                c == ',' -> { toks.add(Tok.Comma); i++ }
                c == '%' -> { toks.add(Tok.Percent); i++ }
                (c == 'E' || c == 'e') && i + 1 < s.length && (s[i + 1] == '+' || s[i + 1] == '-') -> { toks.add(Tok.Exp(s[i + 1] == '+')); i += 2 }
                c == '/' -> { if (toks.any { it is Tok.Digit }) toks.add(Tok.Slash) else lit("/"); i++ }
                c == '@' -> { toks.add(Tok.At); i++ }
                lower.startsWith("am/pm", i) -> { toks.add(Tok.Date("AM/PM")); i += 5 }
                lower.startsWith("a/p", i) -> { toks.add(Tok.Date(if (c == 'a') "a/p" else "A/P")); i += 3 }
                c.lowercaseChar() in "ymdhse" || (c.lowercaseChar() == 'g' && i + 1 < s.length && s[i + 1].lowercaseChar() == 'g') -> {
                    val ch = c.lowercaseChar()
                    var k = i; while (k < s.length && s[k].lowercaseChar() == ch) k++
                    toks.add(Tok.Date(lower.substring(i, k).replace('e', 'y')))
                    i = k
                }
                c == 'b' && i + 1 < s.length && s[i + 1] in "12" -> i += 2
                else -> { lit(c.toString()); i++ }
            }
        }
        // m after h or before s is minutes.
        val dates = toks.withIndex().filter { it.value is Tok.Date || it.value is Tok.Elapsed }
        for ((pos, iv) in dates.withIndex()) {
            val t = iv.value as? Tok.Date ?: continue
            if (!t.s.startsWith("m") || t.s.length > 2) continue
            val prev = dates.getOrNull(pos - 1)?.value
            val next = dates.getOrNull(pos + 1)?.value
            val afterHour = prev is Tok.Date && prev.s.startsWith("h") || prev is Tok.Elapsed && prev.unit == 'h'
            val beforeSecond = next is Tok.Date && next.s.startsWith("s") || next is Tok.Elapsed && next.unit == 's'
            if (afterHour || beforeSecond) toks[iv.index] = Tok.Date(if (t.s.length == 1) "n" else "nn")
        }
        return Section(toks, color, cond)
    }

    /** The section for [v] and the value to show in it (negative sections show the magnitude). */
    private fun pick(sections: List<Section>, v: Double): Pair<Section, Double>? {
        val numeric = sections.filter { !it.isText || it.tokens.none { t -> t is Tok.At } }.take(3)
        if (numeric.isEmpty()) return null
        if (numeric.any { it.cond != null }) {
            for (s in numeric) {
                val c = s.cond ?: continue
                val ok = when (c.first) { "<" -> v < c.second; "<=" -> v <= c.second; ">" -> v > c.second; ">=" -> v >= c.second; "=" -> v == c.second; "<>" -> v != c.second; else -> false }
                if (ok) return s to (if (v < 0 && c.second <= 0 && (c.first == "<" || c.first == "<=")) abs(v) else v)
            }
            val rest = numeric.filter { it.cond == null }
            val s = rest.firstOrNull() ?: numeric.last()
            return s to (if (v < 0 && numeric.size > 1) abs(v) else v)
        }
        return when {
            numeric.size == 1 -> numeric[0] to v
            v < 0 -> numeric[1] to abs(v)
            v == 0.0 && numeric.size >= 3 -> numeric[2] to v
            else -> numeric[0] to v
        }
    }

    // ------------------------------------------------------------------ rendering
    private fun render(sec: Section, v: Double, original: Double): String {
        if (sec.isDate) return renderDate(sec, v)
        val t = sec.tokens
        if (t.none { it is Tok.Digit }) {
            // Only literals (e.g. "-" for zero): the minus sign when a single section shows a negative.
            val sb = StringBuilder()
            for (x in t) if (x is Tok.Lit) sb.append(x.s) else if (x is Tok.At) sb.append(general(v))
            return sb.toString()
        }
        val slash = t.indexOfFirst { it is Tok.Slash }
        if (slash >= 0) return renderFraction(sec, v)
        val exp = t.indexOfFirst { it is Tok.Exp }
        var value = v
        val percents = t.count { it is Tok.Percent }
        repeat(percents) { value *= 100 }
        val point = t.indexOfFirst { it is Tok.Point }
        val mantEnd = if (exp >= 0) exp else t.size
        val intEnd = if (point in 0 until mantEnd) point else mantEnd
        // Commas: between integer digits = grouping; right after the last digit before the point = ÷1000 each.
        val lastIntDigit = (0 until intEnd).lastOrNull { t[it] is Tok.Digit } ?: -1
        val firstIntDigit = (0 until intEnd).firstOrNull { t[it] is Tok.Digit } ?: -1
        var grouping = false
        var scale = 0
        for (k in 0 until intEnd) if (t[k] is Tok.Comma) {
            if (k in firstIntDigit..lastIntDigit && firstIntDigit >= 0) grouping = true
            else if (k > lastIntDigit && lastIntDigit >= 0) scale++
        }
        if (point < 0 && exp < 0) { var k = t.size - 1; while (k > lastIntDigit && k >= 0) { if (t[k] is Tok.Comma) scale++ else if (t[k] !is Tok.Lit && t[k] !is Tok.Percent) break; k-- } }
        repeat(scale) { value /= 1000 }
        val fracDigits = if (point in 0 until mantEnd) (point + 1 until mantEnd).count { t[it] is Tok.Digit } else 0
        var a = abs(value)
        var exponent = 0
        if (exp >= 0) {
            val intDigits = (0 until intEnd).count { t[it] is Tok.Digit }.coerceAtLeast(1)
            if (a != 0.0) {
                exponent = floor(Math.log10(a)).toInt()
                // Engineering-style formats (##0.0E+0) step the exponent by the number of integer digits.
                val step = if ((0 until intEnd).any { (t[it] as? Tok.Digit)?.ch == '#' }) intDigits else 1
                exponent = if (step > 1) Math.floorDiv(exponent, step) * step else exponent - (intDigits - 1)
                a /= Math.pow(10.0, exponent.toDouble())
                val r = BigDecimal(a).setScale(fracDigits, RoundingMode.HALF_UP)
                if (r >= BigDecimal.TEN.pow(if (step > 1) step else intDigits)) { a /= 10; exponent += 1 }
            }
        }
        val rounded = BigDecimal(a).setScale(fracDigits, RoundingMode.HALF_UP)
        val plain = rounded.toPlainString()
        var intStr = plain.substringBefore('.')
        val fracStr = plain.substringAfter('.', "")
        if (intStr == "0") intStr = ""
        val sb = StringBuilder()
        // Integer part: digits right to left into the placeholders; extra digits go to the leftmost one.
        val intToks = t.subList(0, intEnd)
        val placeholders = intToks.withIndex().filter { it.value is Tok.Digit }.map { it.index }
        val digitFor = HashMap<Int, String>()
        var di = intStr.length - 1
        for ((n, idx) in placeholders.withIndex().reversed()) {
            val ch = (intToks[idx] as Tok.Digit).ch
            val d = if (di >= 0) intStr[di--].toString() else when (ch) { '0' -> "0"; '?' -> " "; else -> "" }
            digitFor[idx] = if (n == 0 && di >= 0) intStr.substring(0, di + 1) + d else d
        }
        val literalInside = placeholders.isNotEmpty() && (placeholders.first()..placeholders.last()).any { intToks[it] is Tok.Lit }
        if (grouping && !literalInside && placeholders.isNotEmpty()) {
            for (k in 0 until placeholders.first()) emitInt(intToks[k], sb, v)
            sb.append(groupDigits(placeholders.joinToString("") { digitFor[it] ?: "" }))
            for (k in placeholders.last() + 1 until intToks.size) emitInt(intToks[k], sb, v)
        } else {
            if (placeholders.isEmpty() && intStr.isNotEmpty()) sb.append(intStr)
            for ((k, tok) in intToks.withIndex()) if (tok is Tok.Digit) sb.append(digitFor[k] ?: "") else emitInt(tok, sb, v)
        }
        // Fraction part.
        if (point in 0 until mantEnd) {
            val fracToks = t.subList(point + 1, mantEnd)
            val digits = fracToks.filterIsInstance<Tok.Digit>()
            // Trailing optional placeholders drop their zeros.
            var keep = fracStr.length
            for (k in digits.indices.reversed()) {
                if (k >= fracStr.length) continue
                if (digits[k].ch != '0' && fracStr[k] == '0' && k == keep - 1) keep-- else break
            }
            var fi = 0
            val fracOut = StringBuilder()
            for (tok in fracToks) when (tok) {
                is Tok.Digit -> {
                    val idx = fi++
                    fracOut.append(when {
                        idx < keep -> fracStr[idx]
                        tok.ch == '0' -> '0'
                        tok.ch == '?' -> ' '
                        else -> ""
                    })
                }
                is Tok.Lit -> fracOut.append(tok.s)
                is Tok.Percent -> fracOut.append('%')
                else -> Unit
            }
            val anyDigit = fracOut.any { it.isDigit() }
            if (anyDigit || digits.any { it.ch != '#' }) sb.append(dec)
            sb.append(fracOut)
        }
        // Exponent.
        if (exp >= 0) {
            val e = t[exp] as Tok.Exp
            val expToks = t.subList(exp + 1, t.size)
            val width = expToks.count { it is Tok.Digit }.coerceAtLeast(1)
            sb.append('E').append(if (exponent < 0) "-" else if (e.plus) "+" else "")
            sb.append(abs(exponent).toString().padStart(width, '0'))
            for (tok in expToks) if (tok is Tok.Lit) sb.append(tok.s) else if (tok is Tok.Percent) sb.append('%')
        }
        var out = sb.toString()
        // A single (or conditional) section shows the minus itself; zero after rounding shows none.
        val zero = rounded.signum() == 0 && exponent == 0
        if (original < 0 && v < 0 && !zero) out = "-$out"
        return out
    }

    private fun emitInt(tok: Tok, sb: StringBuilder, v: Double) {
        when (tok) {
            is Tok.Lit -> sb.append(tok.s)
            is Tok.Percent -> sb.append('%')
            is Tok.At -> sb.append(general(v))
            else -> Unit
        }
    }

    /** "1234567" → "1,234,567" with the locale's separator; leading spaces (from ?) are kept. */
    private fun groupDigits(s: String): String {
        val lead = s.takeWhile { !it.isDigit() }
        val d = s.substring(lead.length)
        if (d.length <= 3) return s
        val sb = StringBuilder(lead)
        for ((i, ch) in d.withIndex()) {
            if (i > 0 && (d.length - i) % 3 == 0) sb.append(group)
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun renderFraction(sec: Section, v: Double): String {
        val t = sec.tokens
        val slash = t.indexOfFirst { it is Tok.Slash }
        // Denominator: digits (or a fixed number written as digits) after the slash.
        val after = t.subList(slash + 1, t.size)
        val denDigits = after.takeWhile { it is Tok.Digit || (it is Tok.Lit && it.s.all { c -> c.isDigit() }) }
        val fixed = denDigits.filterIsInstance<Tok.Lit>().joinToString("") { it.s }.toIntOrNull()
        val maxDen = if (fixed != null) fixed else Math.pow(10.0, denDigits.count { it is Tok.Digit }.toDouble()).toInt() - 1
        // Is there a whole-number part? Digits, then a literal space, then numerator digits.
        val before = t.subList(0, slash)
        val numStart = before.indexOfLast { it !is Tok.Digit } + 1
        val wholeToks = before.subList(0, numStart).takeWhile { it !is Tok.Lit || it.s.isBlank().not() }
        val hasWhole = before.subList(0, numStart).any { it is Tok.Digit }
        val a = abs(v)
        val whole = if (hasWhole) floor(a).toLong() else 0L
        val fracPart = a - whole
        var num: Long; var den: Long
        if (fixed != null) { den = fixed.toLong(); num = (fracPart * den).roundToLong() }
        else { val (n, d) = bestFraction(fracPart, maxDen.coerceAtLeast(1).toLong()); num = n; den = d }
        var w = whole
        if (num == den && hasWhole) { w += 1; num = 0 }
        val sb = StringBuilder()
        if (v < 0) sb.append('-')
        if (hasWhole) {
            if (w != 0L || num == 0L) sb.append(w)
            if (num != 0L) sb.append(if (w != 0L) " " else "").append(num).append('/').append(den)
            else if (w == 0L) Unit
        } else {
            val n = if (!hasWhole) num + whole * den else num
            sb.append(n).append('/').append(den)
        }
        for (tok in t) if (tok is Tok.Lit && tok.s.isNotBlank() && !tok.s.all { it.isDigit() }) sb.append(tok.s)
        return sb.toString().ifEmpty { "0" }
    }

    /** The closest fraction to [x] (0 ≤ x < 1) with a denominator up to [maxDen], by continued fractions. */
    private fun bestFraction(x: Double, maxDen: Long): Pair<Long, Long> {
        var p0 = 0L; var q0 = 1L; var p1 = 1L; var q1 = 0L
        var v = x
        for (step in 0 until 64) {
            val a = floor(v).toLong()
            val q2 = q0 + a * q1
            if (q2 > maxDen) {
                // The best semiconvergent within the limit, or the last convergent.
                if (q1 == 0L) return 0L to 1L
                val k = (maxDen - q0) / q1
                val ps = p0 + k * p1; val qs = q0 + k * q1
                return if (qs > 0 && abs(x - ps.toDouble() / qs) < abs(x - p1.toDouble() / q1)) ps to qs else p1 to q1
            }
            val p2 = p0 + a * p1
            p0 = p1; q0 = q1; p1 = p2; q1 = q2
            val frac = v - a
            if (frac < 1e-12 || abs(x - p1.toDouble() / q1) < 1e-15) break
            v = 1 / frac
        }
        return if (q1 == 0L) 0L to 1L else p1 to q1
    }

    // ------------------------------------------------------------------ dates and times
    /** Serial day number → (year, month, day, day of week 0=Sunday) in the workbook's date system. */
    fun dateOf(serialDay: Long): IntArray {
        if (!date1904 && serialDay == 60L) return intArrayOf(1900, 2, 29, 3)
        val epoch = if (date1904) java.time.LocalDate.of(1904, 1, 1) else if (serialDay < 60) java.time.LocalDate.of(1899, 12, 31) else java.time.LocalDate.of(1899, 12, 30)
        val d = epoch.plusDays(serialDay)
        return intArrayOf(d.year, d.monthValue, d.dayOfMonth, d.dayOfWeek.value % 7)
    }

    private fun renderDate(sec: Section, v: Double): String {
        if (v < 0) return "#".repeat(8)
        val sub = sec.tokens.filterIsInstance<Tok.Date>().firstOrNull { it.s.startsWith(".") }?.s?.length?.minus(1) ?: 0
        val unit = Math.pow(10.0, sub.toDouble())
        // Time rounded to what the format shows (whole seconds unless it shows fractions).
        var ticks = Math.round(v * 86400.0 * unit)
        val day = Math.floorDiv(ticks, (86400 * unit).toLong())
        val inDay = ticks - day * (86400 * unit).toLong()
        val secs = (inDay / unit).toLong()
        val frac = inDay - secs * unit.toLong()
        val h = secs / 3600; val mi = secs % 3600 / 60; val s = secs % 60
        val ymd = dateOf(day)
        val ampm = sec.tokens.any { it is Tok.Date && (it.s == "AM/PM" || it.s.equals("a/p", true)) }
        val sb = StringBuilder()
        for (tok in sec.tokens) when (tok) {
            is Tok.Lit -> sb.append(tok.s)
            is Tok.Digit -> sb.append(tok.ch)
            is Tok.Point -> sb.append(dec)
            is Tok.Comma -> sb.append(',')
            is Tok.Percent -> sb.append('%')
            is Tok.Elapsed -> {
                val total = when (tok.unit) { 'h' -> (ticks / unit / 3600).toLong(); 'm' -> (ticks / unit / 60).toLong(); else -> (ticks / unit).toLong() }
                sb.append(total.toString().padStart(tok.width, '0'))
            }
            is Tok.Date -> sb.append(when (tok.s) {
                "yy", "y" -> (ymd[0] % 100).toString().padStart(2, '0')
                "yyy", "yyyy" -> ymd[0].toString()
                "m" -> ymd[1].toString()
                "mm" -> ymd[1].toString().padStart(2, '0')
                "mmm" -> shortMonth(ymd[1])
                "mmmmm" -> longMonth(ymd[1]).take(1).uppercase(locale)
                "d" -> ymd[2].toString()
                "dd" -> ymd[2].toString().padStart(2, '0')
                "ddd" -> shortDay(ymd[3])
                "h", "hh" -> (if (ampm) ((h + 11) % 12 + 1) else h).toString().let { if (tok.s == "hh") it.padStart(2, '0') else it }
                "n" -> mi.toString()
                "nn" -> mi.toString().padStart(2, '0')
                "s" -> s.toString()
                "ss" -> s.toString().padStart(2, '0')
                "AM/PM" -> if (h < 12) "AM" else "PM"
                "A/P" -> if (h < 12) "A" else "P"
                "a/p" -> if (h < 12) "a" else "p"
                "gg", "ggg" -> ""
                else -> when {
                    tok.s.startsWith("mmmm") -> longMonth(ymd[1])
                    tok.s.startsWith("dddd") -> longDay(ymd[3])
                    tok.s.startsWith("yyyy") -> ymd[0].toString()
                    tok.s.startsWith(".") -> dec + frac.toString().padStart(tok.s.length - 1, '0')
                    tok.s.startsWith("h") -> (if (ampm) ((h + 11) % 12 + 1) else h).toString().padStart(2, '0')
                    tok.s.startsWith("s") -> s.toString().padStart(2, '0')
                    tok.s.startsWith("d") -> ymd[2].toString().padStart(2, '0')
                    else -> ""
                }
            })
            else -> Unit
        }
        return sb.toString()
    }

    private fun shortMonth(m: Int) = dateSymbols.shortMonths[m - 1].trimEnd('.')
    private fun longMonth(m: Int) = dateSymbols.months[m - 1]
    private fun shortDay(d: Int) = dateSymbols.shortWeekdays[d + 1].trimEnd('.')
    private fun longDay(d: Int) = dateSymbols.weekdays[d + 1]

    private fun longDatePattern(): String = if (locale.language == "en") "dddd, mmmm d, yyyy" else "dddd, d \"de\" mmmm \"de\" yyyy".takeIf { locale.language == "pt" || locale.language == "es" } ?: "dddd, d. mmmm yyyy"

    // ------------------------------------------------------------------ built-in formats
    /** The format code of a built-in number format id, as Excel shows it in this locale. */
    fun builtin(id: Int): String? = when (id) {
        0 -> "General"; 1 -> "0"; 2 -> "0.00"; 3 -> "#,##0"; 4 -> "#,##0.00"
        5 -> "\"$cur\"#,##0_);(\"$cur\"#,##0)"; 6 -> "\"$cur\"#,##0_);[Red](\"$cur\"#,##0)"
        7 -> "\"$cur\"#,##0.00_);(\"$cur\"#,##0.00)"; 8 -> "\"$cur\"#,##0.00_);[Red](\"$cur\"#,##0.00)"
        9 -> "0%"; 10 -> "0.00%"; 11 -> "0.00E+00"; 12 -> "# ?/?"; 13 -> "# ??/??"
        14 -> shortDate; 15 -> "d-mmm-yy"; 16 -> "d-mmm"; 17 -> "mmm-yy"
        18 -> "h:mm AM/PM"; 19 -> "h:mm:ss AM/PM"; 20 -> "h:mm"; 21 -> "h:mm:ss"; 22 -> "$shortDate h:mm"
        27, 28, 29, 30, 31, 34, 35, 36, 50, 51, 52, 53, 54, 57, 58 -> shortDate
        32, 33, 55, 56 -> "h:mm:ss"
        37 -> "#,##0 ;(#,##0)"; 38 -> "#,##0 ;[Red](#,##0)"; 39 -> "#,##0.00;(#,##0.00)"; 40 -> "#,##0.00;[Red](#,##0.00)"
        41 -> "_(* #,##0_);_(* (#,##0);_(* \"-\"_);_(@_)"
        42 -> "_(\"$cur\"* #,##0_);_(\"$cur\"* (#,##0);_(\"$cur\"* \"-\"_);_(@_)"
        43 -> "_(* #,##0.00_);_(* (#,##0.00);_(* \"-\"??_);_(@_)"
        44 -> "_(\"$cur\"* #,##0.00_);_(\"$cur\"* (#,##0.00);_(\"$cur\"* \"-\"??_);_(@_)"
        45 -> "mm:ss"; 46 -> "[h]:mm:ss"; 47 -> "mmss.0"; 48 -> "##0.0E+0"; 49 -> "@"
        else -> null
    }

    private val cur: String = try { java.util.Currency.getInstance(locale).getSymbol(locale) } catch (_: Exception) { "$" }

    /** Built-in format 14, the short date, in this locale (Excel follows the system's). */
    private val shortDate: String = run {
        if (locale.language == "en" && (locale.country == "US" || locale.country.isEmpty())) return@run "m/d/yyyy"
        val p = (java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, locale) as? java.text.SimpleDateFormat)?.toPattern() ?: return@run "m/d/yyyy"
        p.replace(Regex("y+"), "yyyy").replace("M", "m").replace(Regex("E+"), "ddd").replace("'", "\"")
    }

    /** True when [code] shows dates or times (so the cell's number is a date). */
    fun isDate(code: String?): Boolean {
        if (code == null || code.equals("General", true)) return false
        return parsed.getOrPut(code) { parse(code) }.firstOrNull()?.isDate == true
    }

    companion object {
        private val COLORS = mapOf(
            "black" to 0xFF000000.toInt(), "blue" to 0xFF0000FF.toInt(), "cyan" to 0xFF00FFFF.toInt(), "green" to 0xFF00FF00.toInt(),
            "magenta" to 0xFFFF00FF.toInt(), "red" to 0xFFFF0000.toInt(), "white" to 0xFFFFFFFF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
        )

        /** Excel's default palette (indexed colours 8–63; [ColorN] counts from 1 = index 8). */
        val PALETTE = intArrayOf(
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0x800000, 0x008000, 0x000080, 0x808000,
            0x800080, 0x008080, 0xC0C0C0, 0x808080, 0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
            0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF, 0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99,
            0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99, 0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
            0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
        )
    }
}
