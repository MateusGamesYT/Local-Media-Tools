package com.localmediatools.docs.core

/**
 * Excel 97–2003 formulas (BIFF8 "parsed expressions") turned back into the text Excel shows:
 * cell and range references (relative or $-fixed, also on other sheets), numbers, strings, errors,
 * operators, parentheses and the built-in functions. Anything this can't spell exactly (external
 * workbooks, add-in functions, inline arrays) gives null, so the cell shows its value alone rather
 * than a wrong formula.
 */
internal class XlsFormulas(
    private val data: ByteArray,
    /** A sheet reference ("Sheet2", or "'My sheet'"), from the EXTERNSHEET index; null for other workbooks. */
    private val sheetName: (Int) -> String?,
    /** A defined name by its 1-based index. */
    private val definedName: (Int) -> String?,
) {
    companion object {
        private val FUNCS: Map<Int, Pair<String, Int>> by lazy {
            TABLE.trim().split(' ').associate { e -> val p = e.split(':'); p[0].toInt() to (p[1] to p[2].toInt()) }
        }
        private val ERRORS = mapOf(0x00 to "#NULL!", 0x07 to "#DIV/0!", 0x0F to "#VALUE!", 0x17 to "#REF!", 0x1D to "#NAME?", 0x24 to "#NUM!", 0x2A to "#N/A")
        private val BINARY = mapOf(0x03 to "+", 0x04 to "-", 0x05 to "*", 0x06 to "/", 0x07 to "^", 0x08 to "&", 0x09 to "<", 0x0A to "<=", 0x0B to "=", 0x0C to ">=", 0x0D to ">", 0x0E to "<>", 0x0F to " ", 0x10 to ",", 0x11 to ":")

        // Excel's built-in function numbers: index:NAME:fixed argument count (-1: variable).
        private const val TABLE =
            "0:COUNT:-1 1:IF:-1 2:ISNA:1 3:ISERROR:1 4:SUM:-1 5:AVERAGE:-1 6:MIN:-1 7:MAX:-1 8:ROW:-1 9:COLUMN:-1 10:NA:0 " +
            "11:NPV:-1 12:STDEV:-1 13:DOLLAR:-1 14:FIXED:-1 15:SIN:1 16:COS:1 17:TAN:1 18:ATAN:1 19:PI:0 20:SQRT:1 " +
            "21:EXP:1 22:LN:1 23:LOG10:1 24:ABS:1 25:INT:1 26:SIGN:1 27:ROUND:2 28:LOOKUP:-1 29:INDEX:-1 30:REPT:2 " +
            "31:MID:3 32:LEN:1 33:VALUE:1 34:TRUE:0 35:FALSE:0 36:AND:-1 37:OR:-1 38:NOT:1 39:MOD:2 40:DCOUNT:3 41:DSUM:3 " +
            "42:DAVERAGE:3 43:DMIN:3 44:DMAX:3 45:DSTDEV:3 46:VAR:-1 47:DVAR:3 48:TEXT:2 49:LINEST:-1 50:TREND:-1 " +
            "51:LOGEST:-1 52:GROWTH:-1 56:PV:-1 57:FV:-1 58:NPER:-1 59:PMT:-1 60:RATE:-1 61:MIRR:3 62:IRR:-1 63:RAND:0 " +
            "64:MATCH:-1 65:DATE:3 66:TIME:3 67:DAY:1 68:MONTH:1 69:YEAR:1 70:WEEKDAY:-1 71:HOUR:1 72:MINUTE:1 " +
            "73:SECOND:1 74:NOW:0 75:AREAS:1 76:ROWS:1 77:COLUMNS:1 78:OFFSET:-1 82:SEARCH:-1 83:TRANSPOSE:1 86:TYPE:1 " +
            "92:SERIESSUM:4 97:ATAN2:2 98:ASIN:1 99:ACOS:1 100:CHOOSE:-1 101:HLOOKUP:-1 102:VLOOKUP:-1 105:ISREF:1 " +
            "109:LOG:-1 111:CHAR:1 112:LOWER:1 113:UPPER:1 114:PROPER:1 115:LEFT:-1 116:RIGHT:-1 117:EXACT:2 118:TRIM:1 " +
            "119:REPLACE:4 120:SUBSTITUTE:-1 121:CODE:1 124:FIND:-1 125:CELL:-1 126:ISERR:1 127:ISTEXT:1 128:ISNUMBER:1 " +
            "129:ISBLANK:1 130:T:1 131:N:1 140:DATEVALUE:1 141:TIMEVALUE:1 142:SLN:3 143:SYD:4 144:DDB:-1 148:INDIRECT:-1 " +
            "162:CLEAN:1 163:MDETERM:1 164:MINVERSE:1 165:MMULT:2 167:IPMT:-1 168:PPMT:-1 169:COUNTA:-1 183:PRODUCT:-1 " +
            "184:FACT:1 189:DPRODUCT:3 190:ISNONTEXT:1 193:STDEVP:-1 194:VARP:-1 195:DSTDEVP:3 196:DVARP:3 197:TRUNC:-1 " +
            "198:ISLOGICAL:1 199:DCOUNTA:3 204:USDOLLAR:-1 205:FINDB:-1 206:SEARCHB:-1 207:REPLACEB:4 208:LEFTB:-1 " +
            "209:RIGHTB:-1 210:MIDB:3 211:LENB:1 212:ROUNDUP:2 213:ROUNDDOWN:2 214:ASC:1 215:DBCS:1 216:RANK:-1 " +
            "219:ADDRESS:-1 220:DAYS360:-1 221:TODAY:0 222:VDB:-1 227:MEDIAN:-1 228:SUMPRODUCT:-1 229:SINH:1 230:COSH:1 " +
            "231:TANH:1 232:ASINH:1 233:ACOSH:1 234:ATANH:1 235:DGET:3 244:INFO:1 247:DB:-1 252:FREQUENCY:2 " +
            "261:ERROR.TYPE:1 269:AVEDEV:-1 270:BETADIST:-1 271:GAMMALN:1 272:BETAINV:-1 273:BINOMDIST:4 274:CHIDIST:2 " +
            "275:CHIINV:2 276:COMBIN:2 277:CONFIDENCE:3 278:CRITBINOM:3 279:EVEN:1 280:EXPONDIST:3 281:FDIST:3 282:FINV:3 " +
            "283:FISHER:1 284:FISHERINV:1 285:FLOOR:2 286:GAMMADIST:4 287:GAMMAINV:3 288:CEILING:2 289:HYPGEOMDIST:4 " +
            "290:LOGNORMDIST:3 291:LOGINV:3 292:NEGBINOMDIST:3 293:NORMDIST:4 294:NORMSDIST:1 295:NORMINV:3 " +
            "296:NORMSINV:1 297:STANDARDIZE:3 298:ODD:1 299:PERMUT:2 300:POISSON:3 301:TDIST:3 302:WEIBULL:4 " +
            "303:SUMXMY2:2 304:SUMX2MY2:2 305:SUMX2PY2:2 306:CHITEST:2 307:CORREL:2 308:COVAR:2 309:FORECAST:3 " +
            "310:FTEST:2 311:INTERCEPT:2 312:PEARSON:2 313:RSQ:2 314:STEYX:2 315:SLOPE:2 316:TTEST:4 317:PROB:-1 " +
            "318:DEVSQ:-1 319:GEOMEAN:-1 320:HARMEAN:-1 321:SUMSQ:-1 322:KURT:-1 323:SKEW:-1 324:ZTEST:-1 325:LARGE:2 " +
            "326:SMALL:2 327:QUARTILE:2 328:PERCENTILE:2 329:PERCENTRANK:-1 330:MODE:-1 331:TRIMMEAN:2 332:TINV:2 " +
            "336:CONCATENATE:-1 337:POWER:2 342:RADIANS:1 343:DEGREES:1 344:SUBTOTAL:-1 345:SUMIF:-1 346:COUNTIF:2 " +
            "347:COUNTBLANK:1 350:ISPMT:4 351:DATEDIF:3 352:DATESTRING:1 353:NUMBERSTRING:2 354:ROMAN:-1 " +
            "358:GETPIVOTDATA:2 359:HYPERLINK:-1 360:PHONETIC:1 361:AVERAGEA:-1 362:MAXA:-1 363:MINA:-1 364:STDEVPA:-1 " +
            "365:VARPA:-1 366:STDEVA:-1 367:VARA:-1 368:BAHTTEXT:1 369:THAIDAYOFWEEK:1 370:THAIDIGIT:1 " +
            "371:THAIMONTHOFYEAR:1 372:THAINUMSOUND:1 373:THAINUMSTRING:1 374:THAISTRINGLENGTH:1 375:ISTHAIDIGIT:1 " +
            "376:ROUNDBAHTDOWN:1 377:ROUNDBAHTUP:1 378:THAIYEAR:1 379:RTD:-1 "
    }

    private class Unsupported : Exception()

    /** Formula text (without "=") of [len] bytes of tokens at [off], for the cell at [row], [col]; null if it can't be spelled. */
    fun decode(off: Int, len: Int, row: Int, col: Int, shared: Boolean = false): String? = try {
        val end = off + len
        if (len <= 0 || end > data.size) null else run(off, end, row, col, shared)
    } catch (_: Unsupported) { null } catch (_: RuntimeException) { null }

    private fun u8(o: Int) = data[o].toInt() and 0xFF
    private fun u16(o: Int) = le16(data, o)

    private fun colName(c: Int) = XlsxReader.columnName(c)

    private fun ref(rw: Int, colField: Int, row: Int, col: Int, relativeToCell: Boolean): String {
        val rowRel = colField and 0x8000 != 0
        val colRel = colField and 0x4000 != 0
        var r = rw; var c = colField and 0x3FFF
        if (relativeToCell) {
            // Shared formulas store offsets from the cell for relative parts.
            if (rowRel) r = (row + rw.toShort()) and 0xFFFF
            if (colRel) c = (col + (colField and 0xFF).toByte()) and 0xFF
        }
        return (if (colRel) "" else "$") + colName(c) + (if (rowRel) "" else "$") + (r + 1)
    }

    private fun run(start: Int, end: Int, row: Int, col: Int, shared: Boolean): String {
        val st = ArrayList<String>()
        fun pop(): String = if (st.isEmpty()) throw Unsupported() else st.removeAt(st.size - 1)
        var o = start
        while (o < end) {
            val t = u8(o); o++
            val base = if (t >= 0x20) (t and 0x1F) or 0x20 else t
            when {
                t == 0x01 || t == 0x02 -> throw Unsupported() // shared / table formula pointer, resolved by the caller
                t in BINARY -> { val b = pop(); val a = pop(); st.add(a + BINARY.getValue(t) + b) }
                t == 0x12 -> st.add("+" + pop())
                t == 0x13 -> st.add("-" + pop())
                t == 0x14 -> st.add(pop() + "%")
                t == 0x15 -> st.add("(" + pop() + ")")
                t == 0x16 -> st.add("")
                t == 0x17 -> {
                    val cch = u8(o); val wide = u8(o + 1) and 1 != 0
                    val s = if (wide) String(data, o + 2, cch * 2, Charsets.UTF_16LE) else String(data, o + 2, cch, Charsets.ISO_8859_1)
                    st.add("\"" + s.replace("\"", "\"\"") + "\""); o += 2 + if (wide) cch * 2 else cch
                }
                t == 0x18 -> throw Unsupported()
                t == 0x19 -> {
                    val kind = u8(o)
                    when {
                        kind and 0x04 != 0 -> o += 3 + 2 * (u16(o + 1) + 1) // CHOOSE jump table
                        kind and 0x10 != 0 -> { st.add("SUM(" + pop() + ")"); o += 3 } // SUM with one argument
                        else -> o += 3 // spaces, IF/GOTO jumps, volatile marks
                    }
                }
                t == 0x1C -> { st.add(ERRORS[u8(o)] ?: "#N/A"); o += 1 }
                t == 0x1D -> { st.add(if (u8(o) != 0) "TRUE" else "FALSE"); o += 1 }
                t == 0x1E -> { st.add(u16(o).toString()); o += 2 }
                t == 0x1F -> { st.add(number(le64d(data, o))); o += 8 }
                base == 0x20 -> throw Unsupported() // inline array
                base == 0x21 -> {
                    val (name, argc) = FUNCS[u16(o)] ?: throw Unsupported()
                    if (argc < 0) throw Unsupported()
                    o += 2
                    val args = (0 until argc).map { pop() }.reversed()
                    st.add(name + "(" + args.joinToString(",") + ")")
                }
                base == 0x22 -> {
                    val argc = u8(o) and 0x7F; val idx = u16(o + 1) and 0x7FFF
                    o += 3
                    val name = FUNCS[idx]?.first ?: throw Unsupported()
                    val args = (0 until argc).map { pop() }.reversed()
                    st.add(name + "(" + args.joinToString(",") + ")")
                }
                base == 0x23 -> { st.add(definedName(u16(o)) ?: throw Unsupported()); o += 4 }
                base == 0x24 -> { st.add(ref(u16(o), u16(o + 2), row, col, false)); o += 4 }
                base == 0x25 -> { st.add(ref(u16(o), u16(o + 4), row, col, false) + ":" + ref(u16(o + 2), u16(o + 6), row, col, false)); o += 8 }
                base == 0x26 || base == 0x27 || base == 0x28 -> o += 6 // a precomputed reference; the tokens after it spell it
                base == 0x29 || base == 0x2E || base == 0x2F -> o += 2
                base == 0x2A -> { st.add("#REF!"); o += 4 }
                base == 0x2B -> { st.add("#REF!"); o += 8 }
                base == 0x2C -> { st.add(ref(u16(o), u16(o + 2), row, col, shared)); o += 4 }
                base == 0x2D -> { st.add(ref(u16(o), u16(o + 4), row, col, shared) + ":" + ref(u16(o + 2), u16(o + 6), row, col, shared)); o += 8 }
                base == 0x39 -> throw Unsupported() // names in other workbooks and add-ins
                base == 0x3A -> { st.add(sheet(u16(o)) + ref(u16(o + 2), u16(o + 4), row, col, false)); o += 6 }
                base == 0x3B -> { st.add(sheet(u16(o)) + ref(u16(o + 2), u16(o + 6), row, col, false) + ":" + ref(u16(o + 4), u16(o + 8), row, col, false)); o += 10 }
                base == 0x3C -> { st.add(sheet(u16(o)) + "#REF!"); o += 6 }
                base == 0x3D -> { st.add(sheet(u16(o)) + "#REF!"); o += 10 }
                else -> throw Unsupported()
            }
        }
        if (st.size != 1) throw Unsupported()
        return st[0]
    }

    private fun sheet(ixti: Int): String = (sheetName(ixti) ?: throw Unsupported()) + "!"

    private fun number(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else java.math.BigDecimal(v).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()
}
