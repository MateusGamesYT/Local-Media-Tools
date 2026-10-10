package com.localmediatools.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.vision.DupResultGroup
import com.localmediatools.vision.DuplicateScanner
import com.localmediatools.vision.GalleryPhoto
import com.localmediatools.vision.core.DupKind
import com.localmediatools.vision.core.KeepPlanner
import com.localmediatools.vision.core.KeepRules
import com.localmediatools.vision.core.Prefer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the user chose on the duplicate finder's results: the keep rules, the photos marked for the
 * trash and the groups they changed by hand. Shared by the list and the one-by-one review.
 */
object DupChoices {
    enum class Sort(val label: String) { SPACE("Most space"), NEWEST("Newest"), OLDEST("Oldest"), PHOTOS("Most photos") }
    enum class Filter(val label: String) { ALL("All"), COPIES("Copies"), SIMILAR("Similar shots") }

    var rules = KeepRules(); private set
    var sort = Sort.SPACE
    var filter = Filter.ALL
    val remove = LinkedHashSet<Uri>()
    /** Groups (by [key]) the user changed by hand: rules no longer change them. */
    private val touched = HashSet<Long>()
    /** Gallery people in each photo (MediaStore id → person ids) and the named people. */
    var people: Map<Long, Set<Long>> = emptyMap(); private set
    var names: Map<Long, String> = emptyMap(); private set
    private var groups: List<DupResultGroup>? = null

    fun key(g: DupResultGroup) = g.photos.minOf { it.id }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("duplicates", Context.MODE_PRIVATE)
        rules = KeepRules(
            keepRaw = p.getBoolean("keep_raw", true),
            keepFavorites = p.getBoolean("keep_fav", true),
            keepPeople = p.getStringSet("keep_people", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet(),
            prefer = Prefer.entries.firstOrNull { it.name == p.getString("prefer", null) } ?: Prefer.QUALITY,
            similarShots = p.getBoolean("similar", false),
        )
    }

    fun setRules(ctx: Context, r: KeepRules) {
        rules = r
        ctx.getSharedPreferences("duplicates", Context.MODE_PRIVATE).edit()
            .putBoolean("keep_raw", r.keepRaw).putBoolean("keep_fav", r.keepFavorites)
            .putStringSet("keep_people", r.keepPeople.map { it.toString() }.toSet())
            .putString("prefer", r.prefer.name).putBoolean("similar", r.similarShots).apply()
        groups?.let { apply(it, resetTouched = false) }
    }

    /** New results: keeps choices that still apply, suggests for groups not seen before. */
    fun results(gs: List<DupResultGroup>) {
        if (gs === groups) return
        val known = groups?.map { key(it) }?.toHashSet() ?: hashSetOf()
        val all = gs.flatMap { g -> g.photos.map { it.uri } }.toHashSet()
        remove.retainAll(all)
        touched.retainAll(gs.map { key(it) }.toSet())
        groups = gs
        for (g in gs) if (key(g) !in known) applyTo(g)
    }

    fun clear() { groups = null; remove.clear(); touched.clear() }

    /** The suggestion for one group under the current rules. */
    fun suggestion(g: DupResultGroup): Set<Uri> {
        val pos = g.photos.withIndex().associate { (i, p) -> p.uri to i }
        val cands = g.photos.map { g.candidate(it, people[it.id] ?: emptySet()) }
        val pictures = g.pictures.map { pic -> pic.mapNotNull { pos[it.uri] } }
        return KeepPlanner.suggest(cands, pictures, rules).map { g.photos[it].uri }.toSet()
    }

    /** Why [p] is always kept, or null. */
    fun protectedBy(g: DupResultGroup, p: GalleryPhoto): String? = KeepPlanner.protectedBy(g.candidate(p, people[p.id] ?: emptySet()), rules, names)

    private fun applyTo(g: DupResultGroup) {
        for (p in g.photos) remove.remove(p.uri)
        remove.addAll(suggestion(g))
    }

    /** Applies the rules to every group (or only those not changed by hand). */
    fun apply(gs: List<DupResultGroup>, resetTouched: Boolean) {
        if (resetTouched) touched.clear()
        for (g in gs) if (key(g) !in touched) applyTo(g)
    }

    fun keepAll(g: DupResultGroup) { touched.add(key(g)); for (p in g.photos) remove.remove(p.uri) }
    fun keepSuggested(g: DupResultGroup) { touched.remove(key(g)); applyTo(g) }
    fun keepOnly(g: DupResultGroup, keep: GalleryPhoto) { touched.add(key(g)); for (p in g.photos) if (p !== keep) remove.add(p.uri) else remove.remove(p.uri) }

    /** Toggles one photo; false when that would leave the group with nothing. */
    fun toggle(g: DupResultGroup, p: GalleryPhoto): Boolean {
        if (p.uri in remove) { remove.remove(p.uri); touched.add(key(g)); return true }
        if (g.photos.all { it === p || it.uri in remove }) return false
        remove.add(p.uri); touched.add(key(g)); return true
    }

    fun visible(gs: List<DupResultGroup>): List<DupResultGroup> {
        val f = when (filter) {
            Filter.ALL -> gs
            Filter.COPIES -> gs.filter { it.kind != DupKind.SIMILAR }
            Filter.SIMILAR -> gs.filter { it.kind == DupKind.SIMILAR }
        }
        return when (sort) {
            Sort.SPACE -> f.sortedByDescending { g -> g.photos.filter { it.uri in remove }.sumOf { it.size }.takeIf { it > 0 } ?: (g.extraBytes / 4) }
            Sort.NEWEST -> f.sortedByDescending { it.newest }
            Sort.OLDEST -> f.sortedBy { it.newest }
            Sort.PHOTOS -> f.sortedByDescending { it.photos.size }
        }
    }

    /** Loads who is in each photo and the named people (for the people rule), then calls [done]. */
    fun loadPeople(ctx: Context, scope: CoroutineScope, gs: List<DupResultGroup>, done: () -> Unit) {
        scope.launch {
            val (p, n) = withContext(Dispatchers.IO) {
                val ids = gs.flatMap { g -> g.photos.map { it.id } }
                val named = try { GalleryDb.get(ctx).people(includeHidden = true).filter { it.named }.associate { it.id to it.name!! } } catch (_: Exception) { emptyMap() }
                DuplicateScanner.peopleIn(ctx, ids) to named
            }
            people = p; names = n
            groups?.let { apply(it, resetTouched = false) }
            done()
        }
    }

    // ------------------------------------------------------------------ thumbnails
    private val thumbs = object : LruCache<Uri, Bitmap>(32 * 1024 * 1024) { override fun sizeOf(key: Uri, value: Bitmap) = value.allocationByteCount }

    /** Shows [uri]'s thumbnail in [iv] (recycled views are safe: only the latest request lands). */
    fun thumb(ctx: Context, scope: CoroutineScope, uri: Uri, iv: ImageView, px: Int = 320) {
        iv.tag = uri
        thumbs.get(uri)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        scope.launch {
            val b = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) } catch (_: Exception) {
                    // Some providers have no thumbnails: decode a small copy instead.
                    try {
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                        var sample = 1
                        while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
                        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
                    } catch (_: Exception) { null }
                }
            }
            if (b != null) thumbs.put(uri, b)
            if (iv.tag == uri) iv.setImageBitmap(b)
        }
    }
}
