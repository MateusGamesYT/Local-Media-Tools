package com.localmediatools.meta

import com.localmediatools.meta.core.MetaField
import com.localmediatools.meta.core.MetaReader
import com.localmediatools.meta.core.MetaReport
import com.localmediatools.meta.core.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * The metadata readers on files made for these tests (test resources meta/, see CREDITS.md: test
 * patterns tagged by ExifTool and ffmpeg, PDFs protected by LibreOffice and by make_pdf.py) and on the
 * file reader's real documents (docs/). The measurements against ExifTool on about 2,700 files are in
 * buildtools/meta (compare.py).
 */
class MetaCoreTest {
    private fun res(path: String) = File(MetaCoreTest::class.java.getResource("/$path")!!.toURI())
    private fun read(path: String) = MetaReader.read(res(path))
    private fun MetaReport.f(name: String, group: String? = null): MetaField =
        fields.firstOrNull { it.name == name && (group == null || it.group == group) } ?: throw AssertionError("$name not found in ${fields.map { "${it.group}:${it.name}" }}")

    @Test
    fun aPhotoSaysWhereWhenAndWhoTookIt() {
        val r = read("meta/photo.jpg")
        assertEquals("JPEG", r.format)
        val (lat, lon) = r.location!!
        assertEquals(-22.9519, lat, 1e-6); assertEquals(-43.2105, lon, 1e-6)
        assertEquals(Privacy.LOCATION, r.f("GPSLatitude", "Composite").privacy)
        assertEquals("Ana Souza", r.f("Artist").value); assertEquals(Privacy.PERSON, r.f("Artist").privacy)
        assertEquals("SN-123456", r.f("SerialNumber").value); assertEquals(Privacy.DEVICE, r.f("SerialNumber").privacy)
        assertEquals("2023:07:14 18:30:05", r.f("DateTimeOriginal").raw); assertEquals(Privacy.TIME, r.f("DateTimeOriginal").privacy)
        assertEquals("trip, family", r.f("Keywords", "IPTC").value)
        assertEquals("Rio de Janeiro", r.f("City", "IPTC").value)
        assertEquals("Ana Souza", r.f("Creator", "XMP-dc").value)
        assertEquals("Test Editor 2.1", r.f("Software").value); assertEquals(Privacy.SOFTWARE, r.f("Software").privacy)
        assertEquals("320 240", r.f("ImageSize").raw)
        assertEquals("photo.jpg", r.f("FileName").value)
    }

    @Test
    fun pngTextAndXmp() {
        val r = read("meta/photo.png")
        assertEquals("PNG", r.format)
        assertEquals("made for the tests", r.f("Comment").value)
        assertEquals("Ana Souza", r.f("Creator", "XMP-dc").value)
        assertNull(r.location)
    }

    @Test
    fun videosSayWhereTheyWereFilmed() {
        val mp4 = read("meta/clip.mp4")
        assertEquals("MP4", mp4.format)
        // The place is written twice by ffmpeg (a QuickTime key and a 3GPP location box); both are read.
        assertEquals(-22.9519, mp4.location!!.first, 1e-4); assertEquals(-43.2105, mp4.location!!.second, 1e-4)
        assertTrue(mp4.fields.any { it.name == "LocationInformation" && it.privacy == Privacy.LOCATION })
        assertEquals("Holiday", mp4.f("Title").value)
        assertEquals("10", mp4.f("VideoFrameRate").raw)
        assertEquals("64", mp4.f("ImageWidth").value)
        assertEquals(1.0, mp4.f("Duration", "QuickTime").raw.toDouble(), 0.01)
        val mkv = read("meta/clip.mkv")
        assertEquals("MKV", mkv.format)
        assertEquals(-22.9519, mkv.location!!.first, 1e-6)
        assertEquals("Holiday", mkv.f("Title").value)
        assertEquals("V_MPEG4/ISO/AVC", mkv.f("VideoCodecID").value)
        assertEquals(1.06, mkv.f("Duration", "Matroska").raw.toDouble(), 0.01)
    }

    @Test
    fun songsTagsAndCoverPictures() {
        val mp3 = read("meta/song.mp3")
        assertEquals("MP3", mp3.format)
        assertEquals("Song", mp3.f("Title", "ID3v2_3").value)
        assertEquals("Band", mp3.f("Artist", "ID3v2_3").value)
        assertEquals("2/10", mp3.f("Track", "ID3v2_3").value)
        assertEquals("Song", mp3.f("Title", "ID3v1").value)
        assertEquals(Privacy.HIDDEN_IMAGE, mp3.f("Picture").privacy)
        assertEquals("22050", mp3.f("SampleRate").value)
        assertEquals(1.0, mp3.f("Duration").raw.toDouble(), 0.1)
        val flac = read("meta/song.flac")
        assertEquals("Song", flac.f("Title", "Vorbis").value)
        assertEquals("image/png", flac.f("PictureMIMEType").value)
        assertEquals("32", flac.f("PictureWidth").value)
        assertEquals(1.0, flac.f("Duration").raw.toDouble(), 0.001)
        val opus = read("meta/song.opus")
        assertEquals("OPUS", opus.format)
        assertEquals("Band", opus.f("Artist", "Vorbis").value)
        assertEquals(1.0, opus.f("Duration").raw.toDouble(), 0.05)
        val wav = read("meta/song.wav")
        assertEquals("WAV", wav.format)
        assertEquals("Song", wav.f("Title", "RIFF").value)
        assertEquals("8000", wav.f("SampleRate").value)
        assertEquals(1.0, wav.f("Duration").raw.toDouble(), 0.001)
    }

    @Test
    fun pdfsProtectedOnlyAgainstChangesAreRead() {
        // RC4, as LibreOffice protects them.
        val lo = read("meta/restricted.pdf")
        assertEquals("Standard V2.3 (128-bit)", lo.f("Encryption").value)
        assertEquals("BOREAS HYD-04 Areal Snow Course Data", lo.f("Title").value)
        assertEquals("LibreOffice 24.2", lo.f("Producer").value)
        assertEquals("14", lo.f("PageCount").value)
        // AES-256, as PDF 2.0 and Acrobat protect them.
        val aes = read("meta/aes256.pdf")
        assertEquals("Standard V5.6 (256-bit)", aes.f("Encryption").value)
        assertEquals("Quarterly report", aes.f("Title").value)
        assertEquals("Ana Souza", aes.f("Author").value); assertEquals(Privacy.PERSON, aes.f("Author").privacy)
        assertEquals(Privacy.SOFTWARE, aes.f("Creator", "PDF").privacy)
        assertEquals("2024:01:02 03:04:05+01:00", aes.f("CreateDate").raw)
        assertEquals("Print, Extract, Print high-res", aes.f("UserAccess").value)
        // One that needs a password to open: nothing inside is shown, and the app says why.
        val locked = read("meta/password.pdf")
        assertTrue(locked.fields.none { it.name == "Title" || it.name == "Author" })
        assertTrue(locked.notes.any { it.contains("password") })
    }

    @Test
    fun officeFilesTellWhoWroteAndSavedThem() {
        val docx = read("meta/props.docx")
        assertEquals("DOCX", docx.format)
        assertEquals("Ana Souza", docx.f("Creator").value)
        assertEquals("Bruno Lima", docx.f("LastModifiedBy").value); assertEquals(Privacy.PERSON, docx.f("LastModifiedBy").privacy)
        assertEquals("Example Ltda", docx.f("Company").value)
        assertEquals("95", docx.f("TotalEditTime").raw)
        // Office's hidden custom properties (written when a document is e-mailed).
        assertEquals("ana@example.com", docx.f("AuthorEmail").value); assertEquals(Privacy.PERSON, docx.f("AuthorEmail").privacy)
        assertEquals("X-17", docx.f("ProjectCode").value)
        assertEquals("2024:03:01 09:00:00Z", docx.f("CreateDate").raw)
        // A real Word 97 file: its summary, and the history of who saved it where.
        val doc = read("docs/boreas.doc")
        assertEquals("DOC", doc.format)
        assertEquals("Teresa G. Yow", doc.f("LastModifiedBy").value)
        assertEquals("Lockheed Martin Energy Systems", doc.f("Company").value)
        val saves = doc.fields.filter { it.name == "LastSavedBy" }
        assertEquals(5, saves.size)
        assertTrue(saves.all { it.privacy == Privacy.PERSON })
        assertTrue(saves.last().value.startsWith("Teresa G. Yow (\\\\Daacs\\GUIDEdocs\\BOREAS\\HYD\\"))
        assertEquals("3294", doc.f("Words", "MS-DOC").value)
        // PowerPoint keeps whoever last opened it.
        val ppt = read("docs/SampleShow.ppt")
        assertEquals("Nick Burch", ppt.f("Author").value)
        assertEquals("kiwiwings", ppt.f("CurrentUser").value)
        assertEquals("2", ppt.f("Slides").value)
        assertNull(ppt.f("Notes").privacy)
        // OpenDocument, EPUB and RTF made from the same Word file keep its people and dates.
        val odt = read("docs/boreas.odt")
        assertEquals("33m", odt.f("InitialCreator").value)
        assertEquals("Teresa G. Yow", odt.f("Creator", "XMP-dc").value)
        assertEquals("14", odt.f("DocumentStatisticPageCount").value)
        assertEquals("33m", read("docs/boreas.epub").f("Creator", "EPUB").value)
        assertEquals("1998:07:09 04:11:00", read("docs/boreas.rtf").f("CreateDate").raw)
        assertEquals("Acrobat PDFWriter 4.0 for Windows", read("docs/glnpo.pdf").f("Producer").value)
    }

    @Test
    fun aRenamedFileIsReadByWhatItIs() {
        val copy = File.createTempFile("renamed", ".txt")
        try {
            res("meta/song.flac").copyTo(copy, overwrite = true)
            assertEquals("FLAC", MetaReader.read(copy).format)
        } finally { copy.delete() }
    }

    /** Cut-off and damaged copies of every test file: the reader must finish quickly, never throw, and still say what it can. */
    @Test
    fun damagedFilesNeverStopTheReader() {
        val names = listOf("meta/photo.jpg", "meta/photo.png", "meta/clip.mp4", "meta/clip.mkv", "meta/song.mp3", "meta/song.flac", "meta/song.opus",
            "meta/song.wav", "meta/restricted.pdf", "meta/aes256.pdf", "meta/props.docx", "docs/boreas.doc", "docs/SampleShow.ppt", "docs/boreas.odt",
            "docs/boreas.epub", "docs/boreas.rtf")
        val rnd = Random(7)
        val tmp = File.createTempFile("damaged", ".bin")
        try {
            for (name in names) {
                val good = res(name).readBytes()
                repeat(12) { k ->
                    val b = if (k < 6) good.copyOf(maxOf(1, good.size * (k + 1) / 7)) else good.copyOf().also { c -> repeat(20) { c[rnd.nextInt(c.size)] = rnd.nextInt(256).toByte() } }
                    tmp.writeBytes(b)
                    val start = System.nanoTime()
                    val r = MetaReader.read(tmp, name.substringAfterLast('/'), 0)
                    val ms = (System.nanoTime() - start) / 1_000_000
                    assertNotNull(r.fields.firstOrNull { it.name == "FileType" })
                    assertTrue("$name copy $k took $ms ms", ms < 5000)
                }
            }
        } finally { tmp.delete() }
    }
}
