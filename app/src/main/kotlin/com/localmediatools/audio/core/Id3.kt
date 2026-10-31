package com.localmediatools.audio.core

/** A song's tags, carried from the original into the new file. Blank fields are left out. */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val track: String? = null,
    val genre: String? = null,
    val comment: String? = null,
    /** The cover picture (JPEG or PNG). */
    val cover: ByteArray? = null,
) {
    val coverMime: String? get() = cover?.let { if (it.size > 3 && it[0] == 0x89.toByte() && it[1] == 'P'.code.toByte()) "image/png" else "image/jpeg" }

    /** As Vorbis comments (FLAC, Ogg Opus). */
    fun vorbisComments(): List<Pair<String, String>> = listOfNotNull(
        title?.takeIf { it.isNotBlank() }?.let { "TITLE" to it },
        artist?.takeIf { it.isNotBlank() }?.let { "ARTIST" to it },
        album?.takeIf { it.isNotBlank() }?.let { "ALBUM" to it },
        albumArtist?.takeIf { it.isNotBlank() }?.let { "ALBUMARTIST" to it },
        year?.takeIf { it.isNotBlank() }?.let { "DATE" to it },
        track?.takeIf { it.isNotBlank() }?.let { "TRACKNUMBER" to it },
        genre?.takeIf { it.isNotBlank() }?.let { "GENRE" to it },
        comment?.takeIf { it.isNotBlank() }?.let { "COMMENT" to it },
    )

    /** The cover as a FLAC picture block (or METADATA_BLOCK_PICTURE in Ogg), front cover. */
    fun flacPicture(width: Int = 0, height: Int = 0): FlacEncoder.Picture? = cover?.let { FlacEncoder.Picture(3, coverMime!!, "", width, height, it) }

    override fun equals(other: Any?) = other is AudioTags && title == other.title && artist == other.artist && album == other.album &&
        albumArtist == other.albumArtist && year == other.year && track == other.track && genre == other.genre && comment == other.comment &&
        (cover?.contentEquals(other.cover ?: ByteArray(0)) ?: (other.cover == null))
    override fun hashCode() = listOf(title, artist, album, albumArtist, year, track, genre, comment).hashCode() * 31 + (cover?.contentHashCode() ?: 0)
}

/** ID3v2.4 tags (for MP3 files), written as UTF-8. */
object Id3 {
    fun v24(tags: AudioTags): ByteArray {
        val frames = java.io.ByteArrayOutputStream()
        fun frame(id: String, body: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1)); frames.write(syncsafe(body.size)); frames.write(0); frames.write(0); frames.write(body)
        }
        fun text(id: String, v: String?) { if (!v.isNullOrBlank()) frame(id, byteArrayOf(3) + v.toByteArray(Charsets.UTF_8)) }
        text("TIT2", tags.title); text("TPE1", tags.artist); text("TALB", tags.album); text("TPE2", tags.albumArtist)
        text("TDRC", tags.year); text("TRCK", tags.track); text("TCON", tags.genre)
        if (!tags.comment.isNullOrBlank()) frame("COMM", byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + tags.comment.toByteArray(Charsets.UTF_8))
        tags.cover?.let { pic ->
            frame("APIC", byteArrayOf(0) + tags.coverMime!!.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + pic)
        }
        val body = frames.toByteArray()
        if (body.isEmpty()) return ByteArray(0)
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(body.size) + body
    }

    /** The size of an ID3v2 tag at the start of [head] (header, body and footer), 0 when there is none. */
    fun size(head: ByteArray): Int {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        val s = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
        val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + s + footer
    }

    private fun syncsafe(n: Int): ByteArray {
        require(n < (1 shl 28)) { "ID3 tag too large" }
        return byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
    }
}
