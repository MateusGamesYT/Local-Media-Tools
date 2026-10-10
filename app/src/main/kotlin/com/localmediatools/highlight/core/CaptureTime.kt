package com.localmediatools.highlight.core

import java.util.Calendar
import java.util.TimeZone

/**
 * When a photo or video was taken, as UTC milliseconds, from what the files say. Photos record
 * local wall-clock time (EXIF DateTimeOriginal), with the zone only when OffsetTimeOriginal is
 * there; videos record UTC (MP4 creation time, as Android's metadata reader reports it). Mixing
 * the two without care puts every video hours away from the photos taken with it.
 */
object CaptureTime {
    /**
     * EXIF "YYYY:MM:DD HH:MM:SS" with optional sub-seconds ("123") and offset ("+02:00"); without an
     * offset the phone's zone at that date is used (what the camera's clock was set to, normally).
     */
    fun exif(dateTime: String?, subSec: String? = null, offset: String? = null, zone: TimeZone = TimeZone.getDefault()): Long? {
        val s = dateTime?.trim() ?: return null
        val m = Regex("""^(\d{4})[:\-](\d{2})[:\-](\d{2})[ T](\d{2}):(\d{2}):(\d{2})""").find(s) ?: return null
        val (y, mo, d, h, mi, se) = m.destructured
        if (y.toInt() < 1971 || mo.toInt() !in 1..12 || d.toInt() !in 1..31) return null  // "0000:00:00 00:00:00" and other placeholders
        val ms = subSec?.trim()?.takeWhile { it.isDigit() }?.take(3)?.padEnd(3, '0')?.toIntOrNull() ?: 0
        val tz = offset?.let { parseOffset(it) }?.let { java.util.SimpleTimeZone(it, "offset") } ?: zone
        return utc(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt(), ms, tz)
    }

    /**
     * MediaMetadataRetriever's METADATA_KEY_DATE: "20240714T101530.000Z", "20240714T101530Z",
     * sometimes with an offset ("20240714T121530.000+0200"). 1904-01-01 (an unset MP4 time) is null.
     */
    fun video(date: String?): Long? {
        val s = date?.trim() ?: return null
        val m = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(?:\.(\d+))?(Z|[+\-]\d{2}:?\d{2})?$""").find(s) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        if (year < 1971) return null
        val ms = g[7].take(3).padEnd(3, '0').toIntOrNull() ?: 0
        val off = g[8].takeIf { it.isNotEmpty() && it != "Z" }?.let { parseOffset(it) } ?: 0
        return utc(year, g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), ms, java.util.SimpleTimeZone(off, "offset"))
    }

    /** "+02:00", "-0530", "+02" → milliseconds east of UTC. */
    fun parseOffset(s: String): Int? {
        val m = Regex("""^([+\-])(\d{2}):?(\d{2})?$""").find(s.trim()) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        return sign * (m.groupValues[2].toInt() * 3_600_000 + (m.groupValues[3].toIntOrNull() ?: 0) * 60_000)
    }

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int, tz: TimeZone): Long {
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(y, mo - 1, d, h, mi, s)
        c.set(Calendar.MILLISECOND, ms)
        return c.timeInMillis
    }

    /**
     * The best time we have: the file's own capture time, else the library's (MediaStore's
     * DATE_TAKEN), else when the file was last changed.
     */
    fun best(fromFile: Long?, fromLibrary: Long?, modifiedMs: Long): Long = fromFile ?: fromLibrary?.takeIf { it > 0 } ?: modifiedMs
}
