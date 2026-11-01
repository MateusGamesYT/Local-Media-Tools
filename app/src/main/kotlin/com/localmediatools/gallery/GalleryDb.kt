package com.localmediatools.gallery

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A photo or video in the library, as the gallery index knows it. */
class GMedia(
    val id: Long,
    val video: Boolean,
    val mime: String,
    val name: String,
    val bucketId: String,
    val bucket: String,
    val path: String,
    /** When it was taken (ms), falling back to when it was last changed. */
    val taken: Long,
    val modified: Long,
    val size: Long,
    /** Size as displayed (orientation applied). */
    val width: Int,
    val height: Int,
    val orientation: Int,
    val durationMs: Long,
    val favorite: Boolean,
) {
    val uri get() = GalleryLibrary.uriOf(id, video)
    val screenshot get() = GalleryLibrary.isScreenshot(path, bucket, name)

    /** The file itself changed, so what was learned about it is stale. */
    fun contentDiffers(o: GMedia) = modified != o.modified || size != o.size

    /** Something shown or searched changed (name, album, date, favourite…). */
    fun metaDiffers(o: GMedia) = video != o.video || mime != o.mime || name != o.name || bucketId != o.bucketId ||
        bucket != o.bucket || path != o.path || taken != o.taken || width != o.width || height != o.height ||
        orientation != o.orientation || durationMs != o.durationMs || favorite != o.favorite
    val gif get() = mime == "image/gif"
}

/** A face found in a photo (or a video frame). Coordinates are fractions of the displayed picture. */
class GFace(
    val id: Long,
    val mediaId: Long,
    val x: Float, val y: Float, val w: Float, val h: Float,
    val score: Float,
    val eyePx: Float,
    val yaw: Float,
    val good: Boolean,
    val quality: Float,
    val frameMs: Long,
    val kind: FaceKind,
    val emb: FloatArray?,
    val personId: Long?,
    val confirmed: Boolean,
    /** The user said this isn't someone to keep track of (a poster, a statue, a stranger). */
    val ignored: Boolean = false,
)

/** [me]: the person the user said they are; [favorite]: one they starred (both listed first). */
class GPerson(val id: Long, val name: String?, val hidden: Boolean, val coverFace: Long?, val faceCount: Int, val mediaCount: Int,
              val favorite: Boolean = false, val me: Boolean = false) {
    val named get() = !name.isNullOrBlank()
}

/**
 * The gallery's on-device index (SQLite in app storage): what is in each photo, the faces and
 * the people they belong to. Nothing here leaves the phone.
 */
class GalleryDb private constructor(ctx: Context, name: String?) : SQLiteOpenHelper(ctx, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE media(
            id INTEGER PRIMARY KEY, video INTEGER NOT NULL, mime TEXT NOT NULL, name TEXT NOT NULL,
            bucket_id TEXT NOT NULL, bucket TEXT NOT NULL, path TEXT NOT NULL,
            taken INTEGER NOT NULL, modified INTEGER NOT NULL, size INTEGER NOT NULL,
            width INTEGER NOT NULL, height INTEGER NOT NULL, orientation INTEGER NOT NULL, duration INTEGER NOT NULL,
            favorite INTEGER NOT NULL DEFAULT 0, analyzed INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0,
            searched INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX media_taken ON media(taken DESC)")
        db.execSQL("CREATE INDEX media_bucket ON media(bucket_id, taken DESC)")
        db.execSQL("CREATE INDEX media_pending ON media(analyzed, taken DESC)")
        db.execSQL("CREATE TABLE tags(media_id INTEGER NOT NULL, cat TEXT NOT NULL, score REAL NOT NULL, PRIMARY KEY(media_id, cat)) WITHOUT ROWID")
        db.execSQL("CREATE INDEX tags_cat ON tags(cat, score DESC)")
        db.execSQL("""CREATE TABLE faces(
            id INTEGER PRIMARY KEY AUTOINCREMENT, media_id INTEGER NOT NULL,
            x REAL NOT NULL, y REAL NOT NULL, w REAL NOT NULL, h REAL NOT NULL,
            score REAL NOT NULL, eye REAL NOT NULL, yaw REAL NOT NULL, good INTEGER NOT NULL, quality REAL NOT NULL,
            frame_ms INTEGER NOT NULL DEFAULT 0, kind INTEGER NOT NULL DEFAULT 0, emb BLOB, person_id INTEGER,
            confirmed INTEGER NOT NULL DEFAULT 0, ignored INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX faces_media ON faces(media_id)")
        db.execSQL("CREATE INDEX faces_person ON faces(person_id)")
        db.execSQL("CREATE TABLE people(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, hidden INTEGER NOT NULL DEFAULT 0, cover_face INTEGER, created INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE not_person(face_id INTEGER NOT NULL, person_id INTEGER NOT NULL, PRIMARY KEY(face_id, person_id)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
        createSearch(db)
    }

    /** The search model's descriptions of each photo (one per video frame looked at), [Embedding.pack]ed. */
    private fun createSearch(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE search(id INTEGER PRIMARY KEY AUTOINCREMENT, media_id INTEGER NOT NULL, frame_ms INTEGER NOT NULL, emb BLOB NOT NULL)")
        db.execSQL("CREATE INDEX search_media ON search(media_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: favourite people (1.12). Everything already there stays.
        if (oldVersion < 2) db.execSQL("ALTER TABLE people ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        // 3: descriptions for the search model (1.15); every item gets one while the rest stays.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE media ADD COLUMN searched INTEGER NOT NULL DEFAULT 0")
            createSearch(db)
        }
    }

    // ---------------------------------------------------------------- media
    /** Everything indexed, by id (for comparing with the library). */
    fun mediaById(): HashMap<Long, GMedia> {
        val out = HashMap<Long, GMedia>()
        for (m in queryMedia()) out[m.id] = m
        return out
    }

    fun upsert(list: List<GMedia>, changed: Set<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in list) {
                val v = ContentValues().apply {
                    put("id", m.id); put("video", if (m.video) 1 else 0); put("mime", m.mime); put("name", m.name)
                    put("bucket_id", m.bucketId); put("bucket", m.bucket); put("path", m.path); put("taken", m.taken)
                    put("modified", m.modified); put("size", m.size); put("width", m.width); put("height", m.height)
                    put("orientation", m.orientation); put("duration", m.durationMs); put("favorite", if (m.favorite) 1 else 0)
                }
                if (m.id in changed) {
                    // Content changed: what we learned about it is stale.
                    forgetAnalysis(db, m.id)
                    v.put("analyzed", 0); v.put("failures", 0); v.put("searched", 0)
                    db.insertWithOnConflict("media", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                } else {
                    // Metadata only (favourite, album rename): keep the analysis.
                    if (db.update("media", v, "id=?", arrayOf(m.id.toString())) == 0) db.insert("media", null, v)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                forgetAnalysis(db, id)
                db.delete("media", "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun forgetAnalysis(db: SQLiteDatabase, id: Long) {
        val a = arrayOf(id.toString())
        db.delete("tags", "media_id=?", a)
        if (db.delete("search", "media_id=?", a) > 0) searchForgets.incrementAndGet()
        db.execSQL("DELETE FROM not_person WHERE face_id IN (SELECT id FROM faces WHERE media_id=?)", a)
        db.delete("faces", "media_id=?", a)
    }

    private fun media(c: Cursor) = GMedia(
        c.getLong(0), c.getInt(1) == 1, c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6),
        c.getLong(7), c.getLong(8), c.getLong(9), c.getInt(10), c.getInt(11), c.getInt(12), c.getLong(13), c.getInt(14) == 1)

    private val mediaCols = "m.id, m.video, m.mime, m.name, m.bucket_id, m.bucket, m.path, m.taken, m.modified, m.size, m.width, m.height, m.orientation, m.duration, m.favorite"

    fun queryMedia(where: String = "1", args: Array<String> = emptyArray(), joins: String = "", limit: Int? = null): List<GMedia> {
        val out = ArrayList<GMedia>()
        val sql = "SELECT $mediaCols FROM media m $joins WHERE $where ORDER BY m.taken DESC, m.id DESC" + (limit?.let { " LIMIT $it" } ?: "")
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(media(c)) }
        return out
    }

    fun mediaById(id: Long): GMedia? = queryMedia("m.id=?", arrayOf(id.toString())).firstOrNull()

    /** Items to look at: not analysed at [version], or not described for search at [search] (0: no search model). */
    fun pending(version: Int, limit: Int, exclude: Collection<Long> = emptyList(), search: Int = 0): List<GMedia> =
        queryMedia("(m.analyzed < ? OR m.searched < ?) AND m.failures < 2" + (if (exclude.isEmpty()) "" else " AND m.id NOT IN (${exclude.joinToString(",")})"),
            arrayOf(version.toString(), search.toString()), limit = limit)

    /** (done, total): items analysed at [version] and described for search at [search], or given up on. */
    fun counts(version: Int, search: Int = 0): Pair<Int, Int> {
        readableDatabase.rawQuery("SELECT COUNT(*), SUM(CASE WHEN (analyzed >= ? AND searched >= ?) OR failures >= 2 THEN 1 ELSE 0 END) FROM media",
            arrayOf(version.toString(), search.toString())).use { c ->
            c.moveToFirst(); return c.getInt(1) to c.getInt(0)
        }
    }

    /** Counts a try before looking at an item; a finished analysis resets it (see [saveAnalysis]). */
    fun markAttempt(id: Long) {
        writableDatabase.execSQL("UPDATE media SET failures = failures + 1 WHERE id=?", arrayOf(id))
    }

    fun visibleFaceCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM faces WHERE ignored = 0", null).use { c -> c.moveToFirst(); c.getInt(0) }

    fun faceCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM faces WHERE emb IS NOT NULL", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Unnamed people left without faces (their photos were deleted) disappear. */
    fun pruneEmptyPeople() {
        writableDatabase.execSQL("DELETE FROM people WHERE (name IS NULL OR name = '') AND id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id IS NOT NULL)")
    }

    /** Albums: bucket id, name, item count and the newest item. */
    fun albums(): List<Triple<String, String, Pair<Int, Long>>> {
        val out = ArrayList<Triple<String, String, Pair<Int, Long>>>()
        readableDatabase.rawQuery(
            "SELECT bucket_id, bucket, COUNT(*), (SELECT id FROM media m2 WHERE m2.bucket_id = m.bucket_id ORDER BY taken DESC LIMIT 1) " +
                "FROM media m GROUP BY bucket_id ORDER BY MAX(taken) DESC", null).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getString(0), c.getString(1), c.getInt(2) to c.getLong(3)))
        }
        return out
    }

    // ---------------------------------------------------------------- analysis results
    /** [search]: the search model's descriptions (frame ms → [Embedding.pack]ed), stored as [searchVersion] (0: none). */
    fun saveAnalysis(id: Long, version: Int, tags: Map<String, Float>, faces: List<GFace>,
                     search: List<Pair<Long, ByteArray>> = emptyList(), searchVersion: Int = 0) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("tags", "media_id=?", arrayOf(id.toString()))
            for ((k, s) in tags) db.insert("tags", null, ContentValues().apply { put("media_id", id); put("cat", k); put("score", s) })
            replaceFaces(db, id, faces)
            if (searchVersion > 0) writeSearch(db, id, search, searchVersion)
            db.execSQL("UPDATE media SET analyzed=?, failures=0 WHERE id=?", arrayOf<Any>(version, id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (searchVersion > 0) searchSeq.incrementAndGet()
    }

    /** Only the search model's descriptions are new (the rest of the analysis stays). */
    fun saveSearch(id: Long, search: List<Pair<Long, ByteArray>>, searchVersion: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            writeSearch(db, id, search, searchVersion)
            db.execSQL("UPDATE media SET failures=0 WHERE id=?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        searchSeq.incrementAndGet()
    }

    private fun writeSearch(db: SQLiteDatabase, id: Long, search: List<Pair<Long, ByteArray>>, searchVersion: Int) {
        db.delete("search", "media_id=?", arrayOf(id.toString()))
        for ((ms, e) in search) db.insert("search", null, ContentValues().apply { put("media_id", id); put("frame_ms", ms); put("emb", e) })
        db.execSQL("UPDATE media SET searched=? WHERE id=?", arrayOf<Any>(searchVersion, id))
    }

    /** Increases whenever search descriptions are written (the in-memory index reads what is new). */
    val searchSeq = java.util.concurrent.atomic.AtomicLong()
    /** Increases whenever an item's search descriptions are dropped (it changed or was deleted). */
    val searchForgets = java.util.concurrent.atomic.AtomicLong()

    /**
     * Search descriptions stored after row [afterRow] (for reading the new ones only): calls [each]
     * with media id and its packed descriptions; returns the last row read.
     */
    fun searchRows(afterRow: Long, each: (Long, List<ByteArray>) -> Unit): Long {
        var last = afterRow
        val byMedia = LinkedHashMap<Long, ArrayList<ByteArray>>()
        readableDatabase.rawQuery("SELECT s.id, s.media_id, s.emb FROM search s WHERE s.media_id IN " +
            "(SELECT media_id FROM search WHERE id > ?) ORDER BY s.media_id, s.id", arrayOf(afterRow.toString())).use { c ->
            while (c.moveToNext()) {
                last = maxOf(last, c.getLong(0))
                byMedia.getOrPut(c.getLong(1)) { ArrayList() }.add(c.getBlob(2))
            }
        }
        for ((k, v) in byMedia) each(k, v)
        return last
    }

    /** Items without search descriptions anymore (deleted, or changed and waiting to be looked at again). */
    fun searchGone(ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val have = HashSet<Long>()
        for (chunk in ids.chunked(900)) readableDatabase.rawQuery("SELECT DISTINCT media_id FROM search WHERE media_id IN (${chunk.joinToString(",")})", null).use { c ->
            while (c.moveToNext()) have.add(c.getLong(0))
        }
        return ids.filterTo(HashSet()) { it !in have }
    }

    /** How many items have search descriptions. */
    fun searchedCount(version: Int): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM media WHERE searched >= ?", arrayOf(version.toString())).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Like [saveAnalysis] when only the faces were looked at again: the tags stay. */
    fun saveFaces(id: Long, version: Int, faces: List<GFace>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            replaceFaces(db, id, faces)
            db.execSQL("UPDATE media SET analyzed=?, failures=0 WHERE id=?", arrayOf<Any>(version, id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** What an item's analysis was stored as ([GalleryIndex.analyzedValue]); 0 if never analysed. */
    fun analyzedValue(id: Long): Int =
        readableDatabase.rawQuery("SELECT analyzed FROM media WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Items analysed by analyzer version [fromVersion] that need nothing new stay as they are (marked as
     * analysed at [target]): their tags were made by engines at least as capable as now ([target]'s
     * modes), and only faces are looked at again — so items without faces (the detector is the same),
     * or with [keepFaces] every item (the face AI doesn't work on this phone: faces described before
     * stay described as they were, rather than with the basic fallback).
     */
    fun promoteUnchanged(fromVersion: Int, target: Int, keepFaces: Boolean, photosOnly: Boolean = false) {
        val kind = if (photosOnly) " AND video = 0" else ""
        if (keepFaces) writableDatabase.execSQL("UPDATE media SET analyzed=? WHERE analyzed / 10 = ? AND analyzed % 2 >= ?$kind",
            arrayOf<Any>(target, fromVersion, target % 2))
        else writableDatabase.execSQL(
            "UPDATE media SET analyzed=? WHERE analyzed / 10 = ? AND (analyzed % 10) / 2 = ? AND analyzed % 2 >= ? AND " +
                "id NOT IN (SELECT media_id FROM faces)$kind", arrayOf<Any>(target, fromVersion, (target % 10) / 2, target % 2))
    }

    /** Photos analysed by [fromVersion] with engines at least as capable as [target]'s are as [target] would make them. */
    fun promotePhotos(fromVersion: Int, target: Int) {
        writableDatabase.execSQL("UPDATE media SET analyzed=? WHERE analyzed / 10 = ? AND video = 0 AND (analyzed % 10) / 2 >= ? AND analyzed % 2 >= ?",
            arrayOf<Any>(target, fromVersion, (target % 10) / 2, target % 2))
    }

    /** The moments (ms) of a video where faces were found. */
    fun faceFrames(mediaId: Long): List<Long> =
        readableDatabase.rawQuery("SELECT DISTINCT frame_ms FROM faces WHERE media_id=?", arrayOf(mediaId.toString())).use { c ->
            val out = ArrayList<Long>(); while (c.moveToNext()) out.add(c.getLong(0)); out
        }

    /**
     * Writes an item's newly found faces over the ones found before, keeping what the user said about
     * each face (who it is, whether confirmed or hidden, who it is not): a new face takes over the row
     * of the old face its box overlaps most (the same video frame, at least half of their area in
     * common), so its id and everything attached to it stay. Old faces no new face takes over go, with
     * what was said about them.
     */
    private fun replaceFaces(db: SQLiteDatabase, mediaId: Long, faces: List<GFace>) {
        class Old(val id: Long, val x: Float, val y: Float, val w: Float, val h: Float, val frameMs: Long)
        val old = ArrayList<Old>()
        db.rawQuery("SELECT id, x, y, w, h, frame_ms FROM faces WHERE media_id=?", arrayOf(mediaId.toString())).use { c ->
            while (c.moveToNext()) old.add(Old(c.getLong(0), c.getFloat(1), c.getFloat(2), c.getFloat(3), c.getFloat(4), c.getLong(5)))
        }
        fun overlap(o: Old, f: GFace): Float {
            if (o.frameMs != f.frameMs) return 0f
            val iw = minOf(o.x + o.w, f.x + f.w) - maxOf(o.x, f.x); val ih = minOf(o.y + o.h, f.y + f.h) - maxOf(o.y, f.y)
            if (iw <= 0f || ih <= 0f) return 0f
            val i = iw * ih
            return i / (o.w * o.h + f.w * f.h - i)
        }
        val pairs = ArrayList<Triple<Int, Int, Float>>()
        for ((ni, f) in faces.withIndex()) for ((oi, o) in old.withIndex()) { val v = overlap(o, f); if (v >= 0.5f) pairs.add(Triple(ni, oi, v)) }
        pairs.sortByDescending { it.third }
        val newDone = BooleanArray(faces.size); val oldTaken = BooleanArray(old.size)
        for ((ni, oi, _) in pairs) {
            if (newDone[ni] || oldTaken[oi]) continue
            newDone[ni] = true; oldTaken[oi] = true
            val v = faceValues(faces[ni], mediaId).apply {
                remove("person_id"); remove("confirmed")
                if (faces[ni].emb == null) putNull("emb")
            }
            db.update("faces", v, "id=?", arrayOf(old[oi].id.toString()))
        }
        for ((ni, f) in faces.withIndex()) if (!newDone[ni]) db.insert("faces", null, faceValues(f, mediaId))
        for ((oi, o) in old.withIndex()) if (!oldTaken[oi]) {
            db.execSQL("DELETE FROM not_person WHERE face_id=?", arrayOf(o.id))
            db.delete("faces", "id=?", arrayOf(o.id.toString()))
        }
    }

    private fun faceValues(f: GFace, mediaId: Long) = ContentValues().apply {
        put("media_id", mediaId); put("x", f.x); put("y", f.y); put("w", f.w); put("h", f.h)
        put("score", f.score); put("eye", f.eyePx); put("yaw", f.yaw); put("good", if (f.good) 1 else 0); put("quality", f.quality)
        put("frame_ms", f.frameMs); put("kind", f.kind.code); f.emb?.let { put("emb", pack(it)) }
        f.personId?.let { put("person_id", it) }; put("confirmed", if (f.confirmed) 1 else 0)
    }

    /** For each of [ids], the lowest of its scores for [cats] (only items that have them all). */
    fun minTagScores(ids: Collection<Long>, cats: List<String>): Map<Long, Float> {
        val out = HashMap<Long, Float>()
        if (ids.isEmpty() || cats.isEmpty()) return out
        val q = cats.joinToString(",") { "?" }
        for (chunk in ids.chunked(800)) readableDatabase.rawQuery(
            "SELECT media_id, MIN(score), COUNT(*) FROM tags WHERE cat IN ($q) AND media_id IN (${chunk.joinToString(",")}) GROUP BY media_id",
            cats.toTypedArray()).use { c ->
            while (c.moveToNext()) if (c.getInt(2) == cats.size) out[c.getLong(0)] = c.getFloat(1)
        }
        return out
    }

    fun tagged(cat: String, minScore: Float): List<GMedia> =
        queryMedia("m.id IN (SELECT media_id FROM tags WHERE cat=? AND score>=?)", arrayOf(cat, minScore.toString()))

    /** Category → number of items at or above each category's threshold. */
    fun tagCounts(thresholds: Map<String, Float>): Map<String, Int> {
        val out = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT cat, score FROM tags", null).use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0)
                val t = thresholds[k] ?: continue
                if (c.getFloat(1) >= t) out[k] = (out[k] ?: 0) + 1
            }
        }
        return out
    }

    fun tagsOf(id: Long): Map<String, Float> {
        val out = HashMap<String, Float>()
        readableDatabase.rawQuery("SELECT cat, score FROM tags WHERE media_id=?", arrayOf(id.toString())).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getFloat(1) }
        return out
    }

    // ---------------------------------------------------------------- faces and people
    // "good" is worked out from the current grouping rules rather than read back, so faces found by
    // an earlier version follow them too (column 9 says whether the face has an embedding).
    private fun face(c: Cursor): GFace {
        val kind = FaceKind.of(c.getInt(12))
        val good = c.getInt(9) == 1 && ClusterParams.of(kind).isGood(c.getFloat(6), c.getFloat(8), c.getFloat(7))
        return GFace(
            c.getLong(0), c.getLong(1), c.getFloat(2), c.getFloat(3), c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getFloat(7),
            c.getFloat(8), good, c.getFloat(10), c.getLong(11), kind, c.getBlob(13)?.let { unpack(it) },
            if (c.isNull(14)) null else c.getLong(14), c.getInt(15) == 1, c.getInt(16) == 1)
    }

    private val faceCols = "f.id, f.media_id, f.x, f.y, f.w, f.h, f.score, f.eye, f.yaw, f.emb IS NOT NULL, f.quality, f.frame_ms, f.kind, f.emb, f.person_id, f.confirmed, f.ignored"

    fun faces(where: String = "1", args: Array<String> = emptyArray(), withEmb: Boolean = false, order: String = "m.taken DESC, f.id", limit: Int? = null): List<GFace> {
        val cols = if (withEmb) faceCols else faceCols.replace(", f.emb, ", ", NULL, ")
        val out = ArrayList<GFace>()
        readableDatabase.rawQuery("SELECT $cols FROM faces f JOIN media m ON m.id = f.media_id WHERE $where ORDER BY $order" + (limit?.let { " LIMIT $it" } ?: ""), args).use { c ->
            while (c.moveToNext()) out.add(face(c))
        }
        return out
    }

    fun faceById(id: Long, withEmb: Boolean = false) = faces("f.id=?", arrayOf(id.toString()), withEmb).firstOrNull()

    fun facesOf(mediaId: Long) = faces("f.media_id=?", arrayOf(mediaId.toString()), order = "f.x")

    fun notPeople(): Map<Long, LongArray> {
        val m = HashMap<Long, MutableList<Long>>()
        readableDatabase.rawQuery("SELECT face_id, person_id FROM not_person", null).use { c -> while (c.moveToNext()) m.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getLong(1)) }
        return m.mapValues { it.value.toLongArray() }
    }

    /** People: the user first, then favourites, then named ones, then the rest, each by how many photos they are in. */
    fun people(includeHidden: Boolean = false): List<GPerson> {
        val out = ArrayList<GPerson>()
        val me = meta(ME_KEY)?.toLongOrNull()
        readableDatabase.rawQuery(
            "SELECT p.id, p.name, p.hidden, p.cover_face, COUNT(f.id), COUNT(DISTINCT f.media_id), p.favorite FROM people p " +
                "LEFT JOIN faces f ON f.person_id = p.id GROUP BY p.id " +
                (if (includeHidden) "" else "HAVING p.hidden = 0 ") +
                "ORDER BY (p.name IS NULL OR p.name = ''), COUNT(DISTINCT f.media_id) DESC, p.id", null).use { c ->
            while (c.moveToNext()) out.add(GPerson(c.getLong(0), c.getString(1), c.getInt(2) == 1, if (c.isNull(3)) null else c.getLong(3), c.getInt(4), c.getInt(5),
                favorite = c.getInt(6) == 1, me = c.getLong(0) == me))
        }
        return out.sortedWith(compareByDescending<GPerson> { it.me }.thenByDescending { it.favorite })
    }

    /** The person the user said they are, if they still exist. */
    fun me(): Long? = meta(ME_KEY)?.toLongOrNull()?.takeIf { id ->
        readableDatabase.rawQuery("SELECT 1 FROM people WHERE id=?", arrayOf(id.toString())).use { it.moveToFirst() }
    }

    /**
     * [id] is the user ([null]: no one is). Their faces are confirmed, so regrouping keeps them
     * together under this person even before they have a name.
     */
    fun setMe(id: Long?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (id == null) db.delete("meta", "k=?", arrayOf(ME_KEY)) else {
                db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", ME_KEY); put("v", id.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL("UPDATE faces SET confirmed=1 WHERE person_id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Stars [id] (or takes the star away); a starred person's faces are confirmed like a named person's. */
    fun setFavorite(id: Long, favorite: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("people", ContentValues().apply { put("favorite", if (favorite) 1 else 0) }, "id=?", arrayOf(id.toString()))
            if (favorite) db.execSQL("UPDATE faces SET confirmed=1 WHERE person_id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Forgets "me" when it pointed at [id] (inside the caller's transaction). */
    private fun dropMe(db: SQLiteDatabase, id: Long) = db.execSQL("DELETE FROM meta WHERE k=? AND v=?", arrayOf(ME_KEY, id.toString()))

    fun person(id: Long) = people(includeHidden = true).firstOrNull { it.id == id }

    fun mediaOfPeople(ids: Collection<Long>): List<GMedia> {
        if (ids.isEmpty()) return emptyList()
        val w = ids.joinToString(" AND ") { "m.id IN (SELECT media_id FROM faces WHERE person_id = $it)" }
        return queryMedia(w)
    }

    fun newPerson(name: String?): Long = writableDatabase.insert("people", null, ContentValues().apply {
        if (name != null) put("name", name); put("created", System.currentTimeMillis())
    })

    fun renamePerson(id: Long, name: String?) {
        writableDatabase.update("people", ContentValues().apply { if (name == null) putNull("name") else put("name", name) }, "id=?", arrayOf(id.toString()))
    }

    fun setHidden(id: Long, hidden: Boolean) {
        writableDatabase.update("people", ContentValues().apply { put("hidden", if (hidden) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    fun setCover(id: Long, faceId: Long) {
        writableDatabase.update("people", ContentValues().apply { put("cover_face", faceId) }, "id=?", arrayOf(id.toString()))
    }

    /** Confirms that [faceIds] show [personId] (they stop moving between groups). */
    fun confirm(faceIds: Collection<Long>, personId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in faceIds) {
                db.execSQL("UPDATE faces SET person_id=?, confirmed=1 WHERE id=?", arrayOf(personId, f))
                db.execSQL("DELETE FROM not_person WHERE face_id=? AND person_id=?", arrayOf(f, personId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** "This isn't [personId]": the face leaves the person and never joins it again. */
    fun reject(faceId: Long, personId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO not_person(face_id, person_id) VALUES(?, ?)", arrayOf(faceId, personId))
            db.execSQL("UPDATE faces SET person_id=NULL, confirmed=0 WHERE id=? AND person_id=?", arrayOf(faceId, personId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Hides a face from people (or brings it back). */
    fun setIgnored(faceId: Long, ignored: Boolean) {
        if (ignored) writableDatabase.execSQL("UPDATE faces SET ignored=1, person_id=NULL, confirmed=0 WHERE id=?", arrayOf(faceId))
        else writableDatabase.execSQL("UPDATE faces SET ignored=0 WHERE id=?", arrayOf(faceId))
    }

    /** Names a group in one step: renames it and confirms all its faces (they now anchor the person). */
    fun nameGroup(personId: Long, name: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("people", ContentValues().apply { put("name", name) }, "id=?", arrayOf(personId.toString()))
            db.execSQL("UPDATE faces SET confirmed=1 WHERE person_id=?", arrayOf(personId))
            db.execSQL("DELETE FROM not_person WHERE person_id=? AND face_id IN (SELECT id FROM faces WHERE person_id=?)", arrayOf(personId, personId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Stops tracking a whole group (strangers, a poster): its faces are ignored and the group goes. */
    fun ignoreGroup(personId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE faces SET ignored=1, person_id=NULL, confirmed=0 WHERE person_id=?", arrayOf(personId))
            db.execSQL("DELETE FROM not_person WHERE person_id=?", arrayOf(personId))
            db.delete("people", "id=? AND (name IS NULL OR name = '')", arrayOf(personId.toString()))
            dropMe(db, personId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** "None of these faces is [personId]" for a whole group (they stay together). */
    fun rejectGroup(groupId: Long, personId: Long) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO not_person(face_id, person_id) SELECT id, ? FROM faces WHERE person_id = ?", arrayOf(personId, groupId))
    }

    /** Moves everyone in [from] into [into] (the user merged them); [from] is removed. */
    fun mergePeople(into: Long, from: Long) {
        if (into == from) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE faces SET person_id=?, confirmed=1 WHERE person_id=?", arrayOf(into, from))
            db.execSQL("UPDATE OR IGNORE not_person SET person_id=? WHERE person_id=?", arrayOf(into, from))
            db.execSQL("DELETE FROM not_person WHERE person_id=?", arrayOf(from))
            // A face rejected from one of the two can't stay rejected from the merged person if it is in it.
            db.execSQL("DELETE FROM not_person WHERE person_id=? AND face_id IN (SELECT id FROM faces WHERE person_id=?)", arrayOf(into, into))
            // The star and "me" go with the person kept.
            db.execSQL("UPDATE people SET favorite = MAX(favorite, (SELECT favorite FROM people WHERE id=?)) WHERE id=?", arrayOf(from, into))
            db.execSQL("UPDATE meta SET v=? WHERE k=? AND v=?", arrayOf(into.toString(), ME_KEY, from.toString()))
            db.delete("people", "id=?", arrayOf(from.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Forgets a person: their faces become unnamed again (confirmations and rejections are dropped). */
    fun deletePerson(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE faces SET person_id=NULL, confirmed=0 WHERE person_id=?", arrayOf(id))
            db.execSQL("DELETE FROM not_person WHERE person_id=?", arrayOf(id))
            db.delete("people", "id=?", arrayOf(id.toString()))
            dropMe(db, id)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Writes a new grouping: [assign] maps face → person id (null = on its own). Unnamed people
     * left without faces are removed.
     */
    fun applyGrouping(assign: Map<Long, Long?>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((f, p) in assign) {
                if (p == null) db.execSQL("UPDATE faces SET person_id=NULL WHERE id=? AND confirmed=0", arrayOf(f))
                else db.execSQL("UPDATE faces SET person_id=? WHERE id=? AND confirmed=0", arrayOf(p, f))
            }
            db.execSQL("DELETE FROM people WHERE (name IS NULL OR name = '') AND id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id IS NOT NULL)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun meta(k: String): String? = readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setMeta(k: String, v: String?) {
        if (v == null) writableDatabase.delete("meta", "k=?", arrayOf(k))
        else writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Clears everything learned (people, names and tags); the library itself is re-read on the next sync. */
    fun reset() {
        val db = writableDatabase
        for (t in listOf("tags", "faces", "people", "not_person", "media", "meta", "search")) db.delete(t, null, null)
        searchSeq.incrementAndGet(); searchForgets.incrementAndGet()
    }

    companion object {
        const val VERSION = 3
        private const val ME_KEY = "me_person"
        @Volatile private var instance: GalleryDb? = null

        fun get(ctx: Context): GalleryDb = instance ?: synchronized(this) {
            instance ?: GalleryDb(ctx.applicationContext, "gallery.db").also { instance = it }
        }

        /** Forgets the open index (tests start each case with a fresh app). */
        fun closeForTests() = synchronized(this) { instance?.close(); instance = null }

        fun pack(f: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in f) b.putFloat(v)
            return b.array()
        }

        fun unpack(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { bb.getFloat() }
        }
    }
}
