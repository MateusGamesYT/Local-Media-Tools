package com.localmediatools.gallery

import android.content.Context
import com.localmediatools.gallery.core.Category
import com.localmediatools.gallery.core.MediaFilter
import com.localmediatools.gallery.core.ParsedQuery
import com.localmediatools.gallery.core.SearchParser
import com.localmediatools.gallery.core.SearchTerm
import com.localmediatools.gallery.core.SearchVocabulary
import com.localmediatools.gallery.core.Taxonomy

/** Results of a gallery search. */
class SearchResult(val query: ParsedQuery, val items: List<GMedia>)

/** Read side of the gallery: categories, albums, people and search, on top of [GalleryDb]. */
object GalleryRepo {
    @Volatile private var cats: List<Category>? = null

    /** The searchable categories (things, animals, food, places…). */
    fun categories(ctx: Context): List<Category> = cats ?: synchronized(this) {
        cats ?: try {
            Taxonomy.parse(ctx.assets.open("gallery/categories.tsv").bufferedReader().readText())
        } catch (e: Exception) {
            android.util.Log.w("LMT", "categories missing", e); emptyList()
        }.also { cats = it }
    }

    fun category(ctx: Context, key: String) = categories(ctx).firstOrNull { it.key == key }

    fun vocabulary(ctx: Context): SearchVocabulary {
        val db = GalleryDb.get(ctx)
        val people = db.people(includeHidden = true).filter { it.named }.map { it.id to it.name!! }
        val categories = categories(ctx).map { Triple(it.key, it.name, it.words) }
        val albums = db.albums().map { it.first to it.second }
        return SearchVocabulary(people, categories, albums)
    }

    fun search(ctx: Context, text: String): SearchResult {
        val q = SearchParser.parse(text, vocabulary(ctx))
        return SearchResult(q, run(ctx, q.terms))
    }

    /** Items matching every term. */
    fun run(ctx: Context, terms: List<SearchTerm>): List<GMedia> {
        if (terms.isEmpty()) return emptyList()
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        for (t in terms) when (t) {
            is SearchTerm.Person -> { where.add("m.id IN (SELECT media_id FROM faces WHERE person_id = ? AND ignored = 0)"); args.add(t.id.toString()) }
            is SearchTerm.Category -> { where.add("m.id IN (SELECT media_id FROM tags WHERE cat = ? AND score >= ?)"); args.add(t.key); args.add(Taxonomy.TAGGED.toString()) }
            is SearchTerm.Album -> { where.add("m.bucket_id = ?"); args.add(t.id) }
            is SearchTerm.Kind -> where.add(kindWhere(t.kind))
            is SearchTerm.Time -> { where.add("m.taken >= ? AND m.taken < ?"); args.add(t.fromMs.toString()); args.add(t.toMs.toString()) }
            is SearchTerm.Text -> {
                where.add("(m.name LIKE ? OR m.bucket LIKE ? OR m.path LIKE ?)")
                val like = "%" + t.text.replace("%", "").replace("_", "") + "%"
                repeat(3) { args.add(like) }
            }
        }
        return GalleryDb.get(ctx).queryMedia(where.joinToString(" AND "), args.toTypedArray())
    }

    fun kindWhere(k: MediaFilter): String = when (k) {
        MediaFilter.VIDEOS -> "m.video = 1"
        MediaFilter.PHOTOS -> "m.video = 0"
        MediaFilter.GIFS -> "m.mime = 'image/gif'"
        MediaFilter.FAVOURITES -> "m.favorite = 1"
        MediaFilter.SCREENSHOTS -> "(lower(m.path) LIKE '%screenshot%' OR lower(m.bucket) LIKE '%screenshot%' OR lower(m.name) LIKE 'screenshot%' " +
            "OR lower(m.path) LIKE '%screen_recording%' OR lower(m.path) LIKE '%screenrecord%')"
    }

    fun byCategory(ctx: Context, key: String): List<GMedia> = GalleryDb.get(ctx).tagged(key, Taxonomy.TAGGED)

    fun byKind(ctx: Context, k: MediaFilter): List<GMedia> = GalleryDb.get(ctx).queryMedia(kindWhere(k))

    fun all(ctx: Context): List<GMedia> = GalleryDb.get(ctx).queryMedia()

    fun album(ctx: Context, bucketId: String): List<GMedia> = GalleryDb.get(ctx).queryMedia("m.bucket_id = ?", arrayOf(bucketId))

    fun person(ctx: Context, id: Long): List<GMedia> =
        GalleryDb.get(ctx).queryMedia("m.id IN (SELECT media_id FROM faces WHERE person_id = ? AND ignored = 0)", arrayOf(id.toString()))

    /** Category → how many items have it, for categories that have any. */
    fun categoryCounts(ctx: Context): Map<String, Int> =
        GalleryDb.get(ctx).tagCounts(categories(ctx).associate { it.key to Taxonomy.TAGGED })

    /** A representative item for a category: the most confident recent one. */
    fun cover(ctx: Context, key: String): GMedia? = GalleryDb.get(ctx).queryMedia(
        "m.id = (SELECT media_id FROM tags t JOIN media mm ON mm.id = t.media_id WHERE t.cat = ? AND t.score >= ? ORDER BY t.score DESC, mm.taken DESC LIMIT 1)",
        arrayOf(key, "0.75")).firstOrNull() ?: byCategory(ctx, key).firstOrNull()

    /** Best face to show for a person: their chosen cover, else their sharpest frontal face. */
    fun coverFace(ctx: Context, p: GPerson): GFace? {
        val db = GalleryDb.get(ctx)
        p.coverFace?.let { id -> db.faceById(id)?.takeIf { it.personId == p.id }?.let { return it } }
        return db.faces("f.person_id = ? AND f.ignored = 0", arrayOf(p.id.toString()), order = "f.good DESC, f.quality DESC").firstOrNull()
    }
}
