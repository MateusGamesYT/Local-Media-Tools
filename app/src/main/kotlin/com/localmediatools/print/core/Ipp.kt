package com.localmediatools.print.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** IPP delimiter and value tags (RFC 8010). */
object IppTag {
    const val OPERATION = 0x01
    const val JOB = 0x02
    const val END = 0x03
    const val PRINTER = 0x04
    const val UNSUPPORTED_GROUP = 0x05

    const val UNSUPPORTED = 0x10
    const val UNKNOWN = 0x12
    const val NO_VALUE = 0x13
    const val INTEGER = 0x21
    const val BOOLEAN = 0x22
    const val ENUM = 0x23
    const val OCTET_STRING = 0x30
    const val DATE_TIME = 0x31
    const val RESOLUTION = 0x32
    const val RANGE = 0x33
    const val BEGIN_COLLECTION = 0x34
    const val TEXT_WITH_LANGUAGE = 0x35
    const val NAME_WITH_LANGUAGE = 0x36
    const val END_COLLECTION = 0x37
    const val TEXT = 0x41
    const val NAME = 0x42
    const val KEYWORD = 0x44
    const val URI = 0x45
    const val URI_SCHEME = 0x46
    const val CHARSET = 0x47
    const val LANGUAGE = 0x48
    const val MIME_TYPE = 0x49
    const val MEMBER_NAME = 0x4A

    fun isGroup(t: Int) = t in 0x00..0x0F
    fun isOutOfBand(t: Int) = t in 0x10..0x1F
}

/** IPP operation codes used here. */
object IppOp {
    const val PRINT_JOB = 0x0002
    const val VALIDATE_JOB = 0x0004
    const val CANCEL_JOB = 0x0008
    const val GET_JOB_ATTRIBUTES = 0x0009
    const val GET_PRINTER_ATTRIBUTES = 0x000B
}

/** IPP status codes worth naming. */
object IppStatus {
    const val OK = 0x0000
    const val OK_IGNORED_OR_SUBSTITUTED = 0x0001
    const val OK_CONFLICTING = 0x0002
    const val CLIENT_ERROR_BAD_REQUEST = 0x0400
    const val CLIENT_ERROR_NOT_POSSIBLE = 0x0404
    const val CLIENT_ERROR_DOCUMENT_FORMAT_NOT_SUPPORTED = 0x040A
    const val CLIENT_ERROR_ATTRIBUTES_OR_VALUES = 0x040B
    const val SERVER_ERROR_BUSY = 0x0507
    const val SERVER_ERROR_NOT_ACCEPTING_JOBS = 0x0506

    fun successful(code: Int) = code in 0x0000..0x00FF

    fun describe(code: Int): String = when (code) {
        CLIENT_ERROR_BAD_REQUEST -> "the printer didn't understand the request"
        0x0401 -> "the printer refused the request"
        0x0402 -> "the printer asks for a password"
        0x0403 -> "the printer refused access"
        CLIENT_ERROR_NOT_POSSIBLE -> "the printer can't do that"
        0x0406 -> "the printer has no such job"
        CLIENT_ERROR_DOCUMENT_FORMAT_NOT_SUPPORTED -> "the printer doesn't accept this kind of file"
        CLIENT_ERROR_ATTRIBUTES_OR_VALUES -> "the printer doesn't support some of these settings"
        SERVER_ERROR_NOT_ACCEPTING_JOBS -> "the printer isn't accepting jobs right now"
        SERVER_ERROR_BUSY -> "the printer is busy"
        else -> if (code >= 0x0500) "the printer had an internal problem (code 0x%04x)".format(code) else "the printer answered 0x%04x".format(code)
    }
}

/** A resolution value; [units] 3 = dots per inch, 4 = dots per centimetre. */
data class IppResolution(val x: Int, val y: Int, val units: Int = 3) {
    val dpiX: Int get() = if (units == 4) Math.round(x * 2.54f) else x
    val dpiY: Int get() = if (units == 4) Math.round(y * 2.54f) else y
}

/** An "out of band" value (unsupported, unknown, no-value…), kept by its tag. */
data class IppOutOfBand(val tag: Int)

/** A collection value: its member attributes. */
class IppCollection(val members: List<IppAttribute>) {
    operator fun get(name: String): IppAttribute? = members.firstOrNull { it.name == name }
    fun int(name: String): Int? = get(name)?.int
    fun string(name: String): String? = get(name)?.string
    fun collection(name: String): IppCollection? = get(name)?.values?.firstOrNull() as? IppCollection
    override fun toString() = members.joinToString(" ", "{", "}")
}

/**
 * One attribute: a name, the value tag of its first value and its values. Values are Int
 * (integer, enum), Boolean, String (text, name, keyword, uri, charset, language, mime type),
 * ByteArray (octetString, dateTime), [IppResolution], [IntRange], [IppCollection] or [IppOutOfBand].
 */
class IppAttribute(val name: String, val tag: Int, val values: List<Any>, val tags: List<Int> = List(values.size) { tag }) {
    val int: Int? get() = values.firstOrNull() as? Int
    val ints: List<Int> get() = values.filterIsInstance<Int>()
    val string: String? get() = values.firstOrNull()?.let { asString(it) }
    val strings: List<String> get() = values.mapNotNull { asString(it) }
    val bool: Boolean? get() = values.firstOrNull() as? Boolean
    val collections: List<IppCollection> get() = values.filterIsInstance<IppCollection>()

    private fun asString(v: Any): String? = when (v) {
        is String -> v
        is ByteArray -> String(v, Charsets.UTF_8)
        else -> null
    }

    override fun toString() = "$name=" + values.joinToString(",") { if (it is ByteArray) String(it, Charsets.UTF_8) else it.toString() }
}

/** An attribute group (operation, job, printer, unsupported). */
class IppGroup(val tag: Int, val attributes: MutableList<IppAttribute> = ArrayList()) {
    operator fun get(name: String): IppAttribute? = attributes.firstOrNull { it.name == name }

    fun add(name: String, tag: Int, vararg values: Any): IppGroup { attributes.add(IppAttribute(name, tag, values.toList())); return this }
    fun charset(name: String, v: String) = add(name, IppTag.CHARSET, v)
    fun language(name: String, v: String) = add(name, IppTag.LANGUAGE, v)
    fun uri(name: String, v: String) = add(name, IppTag.URI, v)
    fun name(name: String, v: String) = add(name, IppTag.NAME, v)
    fun text(name: String, v: String) = add(name, IppTag.TEXT, v)
    fun keyword(name: String, vararg v: String) = add(name, IppTag.KEYWORD, *v)
    fun mime(name: String, v: String) = add(name, IppTag.MIME_TYPE, v)
    fun integer(name: String, vararg v: Int) = add(name, IppTag.INTEGER, *v.toTypedArray())
    fun enum(name: String, vararg v: Int) = add(name, IppTag.ENUM, *v.toTypedArray())
    fun bool(name: String, v: Boolean) = add(name, IppTag.BOOLEAN, v)
    fun collection(name: String, vararg v: IppCollection) = add(name, IppTag.BEGIN_COLLECTION, *v)
}

/** Builds a collection: `collection { integer("x-dimension", 21000) }`. */
fun collection(build: IppGroup.() -> Unit): IppCollection = IppCollection(IppGroup(0).apply(build).attributes)

/** An IPP request or response. [code] is the operation for requests and the status for responses. */
class IppMessage(val code: Int, val requestId: Int, val groups: List<IppGroup>, val major: Int = 2, val minor: Int = 0) {
    fun group(tag: Int): IppGroup? = groups.firstOrNull { it.tag == tag }
    fun groups(tag: Int): List<IppGroup> = groups.filter { it.tag == tag }

    /** The first attribute with this name in any group. */
    operator fun get(name: String): IppAttribute? = groups.firstNotNullOfOrNull { it[name] }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeByte(major); out.writeByte(minor); out.writeShort(code); out.writeInt(requestId)
        for (g in groups) {
            out.writeByte(g.tag)
            for (a in g.attributes) writeAttribute(out, a.name, a)
        }
        out.writeByte(IppTag.END)
        return bytes.toByteArray()
    }

    override fun toString() = "IPP %d.%d 0x%04x #%d ".format(major, minor, code, requestId) + groups.joinToString(" ") { g -> "[${g.tag}] " + g.attributes.joinToString(" ") }

    companion object {
        /** A request with the operation attributes every request starts with. */
        fun request(op: Int, requestId: Int, printerUri: String, user: String = "LocalMediaTools", major: Int = 2, minor: Int = 0, more: IppGroup.() -> Unit = {}): IppMessage {
            val g = IppGroup(IppTag.OPERATION)
                .charset("attributes-charset", "utf-8")
                .language("attributes-natural-language", "en")
                .uri("printer-uri", printerUri)
                .name("requesting-user-name", user)
            g.more()
            return IppMessage(op, requestId, listOf(g), major, minor)
        }

        private fun writeAttribute(out: DataOutputStream, name: String, a: IppAttribute) {
            for ((i, v) in a.values.withIndex()) {
                val tag = a.tags.getOrElse(i) { a.tag }
                writeValue(out, if (i == 0) name else "", tag, v)
            }
        }

        private fun writeName(out: DataOutputStream, name: String) {
            val b = name.toByteArray(Charsets.UTF_8)
            out.writeShort(b.size); out.write(b)
        }

        private fun writeValue(out: DataOutputStream, name: String, tag: Int, v: Any) {
            if (v is IppCollection) {
                out.writeByte(IppTag.BEGIN_COLLECTION); writeName(out, name); out.writeShort(0)
                for (m in v.members) {
                    out.writeByte(IppTag.MEMBER_NAME); out.writeShort(0)
                    val mb = m.name.toByteArray(Charsets.UTF_8); out.writeShort(mb.size); out.write(mb)
                    for ((i, mv) in m.values.withIndex()) writeValue(out, "", m.tags.getOrElse(i) { m.tag }, mv)
                }
                out.writeByte(IppTag.END_COLLECTION); out.writeShort(0); out.writeShort(0)
                return
            }
            out.writeByte(tag); writeName(out, name)
            when (v) {
                is IppOutOfBand -> out.writeShort(0)
                is Int -> { out.writeShort(4); out.writeInt(v) }
                is Boolean -> { out.writeShort(1); out.writeByte(if (v) 1 else 0) }
                is IppResolution -> { out.writeShort(9); out.writeInt(v.x); out.writeInt(v.y); out.writeByte(v.units) }
                is IntRange -> { out.writeShort(8); out.writeInt(v.first); out.writeInt(v.last) }
                is ByteArray -> { out.writeShort(v.size); out.write(v) }
                is String -> {
                    val b = v.toByteArray(Charsets.UTF_8)
                    require(b.size <= 0x7FFF) { "IPP value too long" }
                    out.writeShort(b.size); out.write(b)
                }
                else -> throw IllegalArgumentException("Unsupported IPP value ${v::class.java.simpleName}")
            }
        }

        /** Parses a message; [bytes] may continue with document data, which is ignored (see [decodedLength]). */
        fun decode(bytes: ByteArray): IppMessage = Reader(ByteBuffer.wrap(bytes)).message()

        /** Where the IPP part of [bytes] ends (document data, if any, follows). */
        fun decodedLength(bytes: ByteArray): Int { val r = Reader(ByteBuffer.wrap(bytes)); r.message(); return r.position }
    }

    private class Reader(val b: ByteBuffer) {
        val position get() = b.position()

        private fun u8() = b.get().toInt() and 0xFF
        private fun u16() = b.short.toInt() and 0xFFFF
        private fun bytes(n: Int): ByteArray { if (n > b.remaining()) throw IllegalArgumentException("Truncated IPP message"); return ByteArray(n).also { b.get(it) } }

        fun message(): IppMessage {
            if (b.remaining() < 9) throw IllegalArgumentException("Truncated IPP message")
            val major = u8(); val minor = u8(); val code = u16(); val id = b.int
            val groups = ArrayList<IppGroup>()
            var group: IppGroup? = null
            var last: MutableAttr? = null
            while (true) {
                if (!b.hasRemaining()) throw IllegalArgumentException("Truncated IPP message")
                val tag = u8()
                if (tag == IppTag.END) break
                if (IppTag.isGroup(tag)) {
                    last?.let { group?.attributes?.add(it.build()) }; last = null
                    group = IppGroup(tag).also { groups.add(it) }
                    continue
                }
                val g = group ?: throw IllegalArgumentException("IPP attribute outside a group")
                val name = String(bytes(u16()), Charsets.UTF_8)
                val value = value(tag)
                if (name.isEmpty()) {
                    (last ?: throw IllegalArgumentException("IPP value without attribute")).add(tag, value)
                } else {
                    last?.let { g.attributes.add(it.build()) }
                    last = MutableAttr(name).also { it.add(tag, value) }
                }
            }
            last?.let { group?.attributes?.add(it.build()) }
            return IppMessage(code, id, groups, major, minor)
        }

        /** Reads a value whose tag and name were read; collections are read to their end. */
        private fun value(tag: Int): Any {
            val len = u16()
            if (tag == IppTag.BEGIN_COLLECTION) { bytes(len); return collectionBody() }
            val v = bytes(len)
            return when {
                IppTag.isOutOfBand(tag) -> IppOutOfBand(tag)
                tag == IppTag.INTEGER || tag == IppTag.ENUM -> if (len == 4) ByteBuffer.wrap(v).int else IppOutOfBand(tag)
                tag == IppTag.BOOLEAN -> len == 1 && v[0].toInt() != 0
                tag == IppTag.RESOLUTION -> if (len == 9) ByteBuffer.wrap(v).let { IppResolution(it.int, it.int, it.get().toInt()) } else IppOutOfBand(tag)
                tag == IppTag.RANGE -> if (len == 8) ByteBuffer.wrap(v).let { it.int..it.int } else IppOutOfBand(tag)
                tag == IppTag.TEXT_WITH_LANGUAGE || tag == IppTag.NAME_WITH_LANGUAGE -> {
                    val bb = ByteBuffer.wrap(v)
                    val ll = bb.short.toInt() and 0xFFFF; bb.position(bb.position() + ll)
                    val tl = bb.short.toInt() and 0xFFFF
                    String(v, bb.position(), tl.coerceAtMost(bb.remaining()), Charsets.UTF_8)
                }
                tag == IppTag.OCTET_STRING || tag == IppTag.DATE_TIME -> v
                else -> String(v, Charsets.UTF_8)
            }
        }

        private fun collectionBody(): IppCollection {
            val members = ArrayList<IppAttribute>()
            var cur: MutableAttr? = null
            while (true) {
                val tag = u8()
                val nameLen = u16(); bytes(nameLen)
                when (tag) {
                    IppTag.END_COLLECTION -> { bytes(u16()); cur?.let { members.add(it.build()) }; return IppCollection(members) }
                    IppTag.MEMBER_NAME -> {
                        cur?.let { members.add(it.build()) }
                        cur = MutableAttr(String(bytes(u16()), Charsets.UTF_8))
                    }
                    else -> (cur ?: throw IllegalArgumentException("IPP collection value without a member name")).add(tag, value(tag))
                }
            }
        }
    }

    private class MutableAttr(val name: String) {
        val values = ArrayList<Any>(); val tags = ArrayList<Int>()
        fun add(tag: Int, v: Any) { values.add(v); tags.add(tag) }
        fun build() = IppAttribute(name, tags.firstOrNull() ?: IppTag.NO_VALUE, values, tags)
    }
}
