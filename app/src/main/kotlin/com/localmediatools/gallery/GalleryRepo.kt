package com.localmediatools.gallery

import android.content.Context
import com.localmediatools.gallery.core.Category
import com.localmediatools.gallery.core.MediaFilter
import com.localmediatools.gallery.core.ParsedQuery
import com.localmediatools.gallery.core.SearchParser
import com.localmediatools.gallery.core.SearchTerm
import com.localmediatools.gallery.core.SearchVocabulary
import com.localmediatools.gallery.core.SemanticIndex
import com.localmediatools.gallery.core.Taxonomy

/**
 * Results of a gallery search. [ranked]: best match first (the query described something and the
 * search model scored it), else newest first. [closest]: nothing matched surely; these are the
 * nearest ones. [unsearched]: items not described for the search model yet (only found by name,
 * category or date until they are).
 */
class SearchResult(val query: ParsedQuery, val items: List<GMedia>, val ranked: Boolean = false, val closest: Boolean = false, val unsearched: Int = 0)

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
        val categories = categories(ctx).map { Triple(it.key, it.name, it.searchWords + it.englishName) }
        val albums = db.albums().map { it.first to it.second }
        val me = db.me()?.let { id -> id to (db.person(id)?.name?.takeIf { it.isNotBlank() } ?: com.localmediatools.i18n.tr("Me")) }
        return SearchVocabulary(people, categories, albums, me)
    }

    fun search(ctx: Context, text: String): SearchResult {
        val q = SearchParser.parse(text, vocabulary(ctx))
        if (q.isEmpty) return SearchResult(q, emptyList())
        return semantic(ctx, q) ?: SearchResult(q, run(ctx, q.terms))
    }

    /** At most this many nearest items when nothing matches surely. */
    const val CLOSEST = 24

    /**
     * The search with the search model, for a query that describes something ([ParsedQuery.content]):
     * among the items people, albums, kinds and dates allow, each is scored by how well its description
     * matches the text ([MatchScale.calibrated]: 0.5 and up is a match) and by the categories the query
     * names (their tag scores, as before); an item matches when either says so (on Open Images this
     * found 82% of the photos instead of 68%, buildtools/search/fusion.py), best first. Null where the
     * model can't run or nothing is described yet: the search works as before.
     */
    private fun semantic(ctx: Context, q: ParsedQuery): SearchResult? {
        if (q.content.isBlank()) return null
        val scale = SearchModels.scale(ctx) ?: return null
        val db = GalleryDb.get(ctx)
        val index = semanticIndex(db)
        if (index.size == 0) return null
        val emb = SearchModels.texts(ctx)?.embed(q.content) ?: return null
        val filters = q.terms.filter { it is SearchTerm.Person || it is SearchTerm.Album || it is SearchTerm.Kind || it is SearchTerm.Time }
        val candidates = if (filters.isEmpty()) all(ctx) else run(ctx, filters)
        if (candidates.isEmpty()) return SearchResult(q, emptyList(), ranked = true)
        // As before: every category tagged (and any other words in the file or album name).
        val before = run(ctx, q.terms).mapTo(HashSet()) { it.id }
        val cats = q.terms.filterIsInstance<SearchTerm.Category>().map { it.key }
        val tagScore = if (cats.isEmpty()) emptyMap() else db.minTagScores(before, cats)
        val cos = index.best(emb, candidates.map { it.id })
        val score = HashMap<Long, Float>(candidates.size * 2)
        for (m in candidates) {
            val sem = cos[m.id]?.let { scale.calibrated(it) } ?: 0f
            val old = if (m.id in before) maxOf(Taxonomy.TAGGED, tagScore[m.id] ?: Taxonomy.TAGGED) else 0f
            score[m.id] = maxOf(sem, old)
        }
        val unsearched = candidates.count { it.id !in cos }
        // Sure matches all score near 1: among them the closer description comes first, then the newer.
        val found = candidates.filter { score[it.id]!! >= Taxonomy.TAGGED }
            .sortedWith(compareByDescending<GMedia> { score[it.id]!! }.thenByDescending { cos[it.id] ?: -1f }.thenByDescending { it.taken })
        if (found.isNotEmpty()) return SearchResult(q, found, ranked = true, unsearched = unsearched)
        val near = candidates.filter { it.id in cos }.sortedByDescending { cos[it.id]!! }.take(CLOSEST)
        return SearchResult(q, near, ranked = true, closest = near.isNotEmpty(), unsearched = unsearched)
    }

    private val index = SemanticIndex()
    private var indexDb: GalleryDb? = null
    private var indexRow = 0L
    private var indexSeq = -1L
    private var indexForgets = -1L

    /** The library's search descriptions in memory, brought up to date with what the index wrote since. */
    fun semanticIndex(db: GalleryDb): SemanticIndex = synchronized(index) {
        if (indexDb !== db) { index.clear(); indexDb = db; indexRow = 0L; indexSeq = -1L; indexForgets = -1L }
        val forgets = db.searchForgets.get()
        if (forgets != indexForgets) {
            indexForgets = forgets
            for (id in db.searchGone(index.mediaIds())) index.put(id, emptyList())
        }
        val seq = db.searchSeq.get()
        if (seq != indexSeq) {
            indexSeq = seq
            indexRow = db.searchRows(indexRow) { id, rows -> index.put(id, rows) }
        }
        index
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
