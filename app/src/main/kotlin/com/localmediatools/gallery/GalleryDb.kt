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

class GPerson(val id: Long, val name: String?, val hidden: Boolean, val coverFace: Long?, val faceCount: Int, val mediaCount: Int) {
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
            favorite INTEGER NOT NULL DEFAULT 0, analyzed INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0)""")
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
        db.execSQL("CREATE TABLE people(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, hidden INTEGER NOT NULL DEFAULT 0, cover_face INTEGER, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE not_person(face_id INTEGER NOT NULL, person_id INTEGER NOT NULL, PRIMARY KEY(face_id, person_id)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First version; future upgrades go here.
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
                    v.put("analyzed", 0); v.put("failures", 0)
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

    fun pending(version: Int, limit: Int, exclude: Collection<Long> = emptyList()): List<GMedia> =
        queryMedia("m.analyzed < ? AND m.failures < 2" + (if (exclude.isEmpty()) "" else " AND m.id NOT IN (${exclude.joinToString(",")})"),
            arrayOf(version.toString()), limit = limit)

    fun counts(version: Int): Pair<Int, Int> {
        readableDatabase.rawQuery("SELECT COUNT(*), SUM(CASE WHEN analyzed >= ? OR failures >= 2 THEN 1 ELSE 0 END) FROM media", arrayOf(version.toString())).use { c ->
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
    fun saveAnalysis(id: Long, version: Int, tags: Map<String, Float>, faces: List<GFace>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            forgetAnalysis(db, id)
            for ((k, s) in tags) db.insert("tags", null, ContentValues().apply { put("media_id", id); put("cat", k); put("score", s) })
            for (f in faces) db.insert("faces", null, faceValues(f, id))
            db.execSQL("UPDATE media SET analyzed=?, failures=0 WHERE id=?", arrayOf<Any>(version, id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun faceValues(f: GFace, mediaId: Long) = ContentValues().apply {
        put("media_id", mediaId); put("x", f.x); put("y", f.y); put("w", f.w); put("h", f.h)
        put("score", f.score); put("eye", f.eyePx); put("yaw", f.yaw); put("good", if (f.good) 1 else 0); put("quality", f.quality)
        put("frame_ms", f.frameMs); put("kind", f.kind.code); f.emb?.let { put("emb", pack(it)) }
        f.personId?.let { put("person_id", it) }; put("confirmed", if (f.confirmed) 1 else 0)
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

    fun people(includeHidden: Boolean = false): List<GPerson> {
        val out = ArrayList<GPerson>()
        readableDatabase.rawQuery(
            "SELECT p.id, p.name, p.hidden, p.cover_face, COUNT(f.id), COUNT(DISTINCT f.media_id) FROM people p " +
                "LEFT JOIN faces f ON f.person_id = p.id GROUP BY p.id " +
                (if (includeHidden) "" else "HAVING p.hidden = 0 ") +
                "ORDER BY (p.name IS NULL OR p.name = ''), COUNT(DISTINCT f.media_id) DESC, p.id", null).use { c ->
            while (c.moveToNext()) out.add(GPerson(c.getLong(0), c.getString(1), c.getInt(2) == 1, if (c.isNull(3)) null else c.getLong(3), c.getInt(4), c.getInt(5)))
        }
        return out
    }

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
        for (t in listOf("tags", "faces", "people", "not_person", "media", "meta")) db.delete(t, null, null)
    }

    companion object {
        const val VERSION = 1
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
