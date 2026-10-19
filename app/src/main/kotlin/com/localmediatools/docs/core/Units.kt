package com.localmediatools.docs.core

/** Units and values the office formats write. */
object Units {
    /** English Metric Units (DrawingML): 12 700 per point. */
    fun emuToPt(emu: Long) = emu / 12700f
    /** Twentieths of a point (Word's "dxa"). */
    fun twipsToPt(t: Int) = t / 20f

    /** "FF8800" / "#ff8800" / "F80" → opaque colour; null for "auto", empty or unreadable. */
    fun hexColor(s: String?): Int? {
        if (s == null) return null
        val h = s.trim().removePrefix("#")
        if (h.equals("auto", true) || h.isEmpty()) return null
        val v = when (h.length) {
            6 -> h.toLongOrNull(16)
            8 -> h.substring(2).toLongOrNull(16) // ARGB as Excel writes it: alpha ignored
            3 -> h.toLongOrNull(16)?.let { ((it shr 8 and 0xF) * 0x110000) or ((it shr 4 and 0xF) * 0x1100) or ((it and 0xF) * 0x11) }
            else -> null
        } ?: return null
        return (0xFF000000L or v).toInt()
    }

    /** Word's highlight names. */
    fun highlightColor(name: String?): Int? = when (name?.lowercase()) {
        "yellow" -> 0xFFFFFF00.toInt(); "green" -> 0xFF00FF00.toInt(); "cyan" -> 0xFF00FFFF.toInt(); "magenta" -> 0xFFFF00FF.toInt()
        "blue" -> 0xFF0000FF.toInt(); "red" -> 0xFFFF0000.toInt(); "darkblue" -> 0xFF000080.toInt(); "darkcyan" -> 0xFF008080.toInt()
        "darkgreen" -> 0xFF008000.toInt(); "darkmagenta" -> 0xFF800080.toInt(); "darkred" -> 0xFF800000.toInt(); "darkyellow" -> 0xFF808000.toInt()
        "darkgray" -> 0xFF808080.toInt(); "lightgray" -> 0xFFC0C0C0.toInt(); "black" -> 0xFF000000.toInt(); "white" -> 0xFFFFFFFF.toInt()
        else -> null
    }

    /** OOXML on/off values: absent value means on. */
    fun onOff(v: String?): Boolean = v == null || !(v == "0" || v.equals("false", true) || v.equals("off", true) || v.equals("none", true))

    /** "12pt", "1.5cm", "20mm", "0.5in", "100px", "3pc", plain number = points. */
    fun lengthToPt(s: String?): Float? {
        if (s == null) return null
        val m = Regex("""^\s*(-?[0-9]*\.?[0-9]+)\s*([a-z%]*)\s*$""", RegexOption.IGNORE_CASE).find(s) ?: return null
        val v = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "", "pt" -> v
            "cm" -> v * 72f / 2.54f
            "mm" -> v * 72f / 25.4f
            "in" -> v * 72f
            "pc" -> v * 12f
            "px" -> v * 0.75f
            "em" -> v * 12f
            else -> null
        }
    }

    private val MONO = setOf("courier", "courier new", "consolas", "lucida console", "menlo", "monaco", "source code pro", "dejavu sans mono", "liberation mono", "andale mono", "fira code", "fira mono", "roboto mono", "monospace", "ocr a extended")
    fun isMonospace(font: String?) = font != null && font.trim().lowercase() in MONO

    /** Characters of the Symbol and Wingdings fonts Word uses for bullets, as Unicode. */
    fun symbolChar(c: Char, font: String?): String {
        val code = c.code.let { if (it in 0xF000..0xF0FF) it - 0xF000 else it }
        val f = font?.lowercase() ?: ""
        return when {
            f.startsWith("wingdings") -> when (code) { 0xA7 -> "▪"; 0xD8 -> "➢"; 0xFC -> "✓"; 0x76 -> "❖"; 0x6E -> "■"; 0x71 -> "❑"; 0x77 -> "⬥"; 0xA8 -> "◻"; 0x9F -> "•"; 0x6C -> "●"; 0xE0 -> "➔"; 0xF0 -> "⇨"; else -> "•" }
            f.startsWith("symbol") -> when (code) { 0xB7 -> "•"; 0x2D -> "−"; 0xAE -> "→"; 0xB0 -> "°"; 0xD7 -> "×"; 0xB1 -> "±"; 0xA3 -> "≤"; 0xB3 -> "≥"; 0xB9 -> "≠"; 0x6F -> "ο"; 0x61 -> "α"; 0x62 -> "β"; 0x70 -> "π"; else -> if (code in 0x20..0x7E) code.toChar().toString() else "•" }
            c.code in 0xF000..0xF0FF -> "•"
            else -> c.toString()
        }
    }

    fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n; val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }

    /** 1 → a, 26 → z, 27 → aa (Word repeats the letter). */
    fun letters(n: Int): String {
        if (n <= 0) return n.toString()
        val c = ('a' + (n - 1) % 26)
        return c.toString().repeat((n - 1) / 26 + 1)
    }

    /** List numbers in a numbering format name (Word's numFmt; ODF's 1/a/A/i/I). */
    fun formatNumber(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter", "a" -> letters(n)
        "upperLetter", "A" -> letters(n).uppercase()
        "lowerRoman", "i" -> roman(n).lowercase()
        "upperRoman", "I" -> roman(n)
        "decimalZero" -> if (n < 10) "0$n" else n.toString()
        "ordinal" -> n.toString() + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }
        "none", "" -> ""
        else -> n.toString()
    }
}
