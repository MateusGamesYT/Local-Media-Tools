package com.localmediatools.vision.core

/** Which copy of a picture to keep. */
enum class Prefer(val label: String, val explain: String) {
    QUALITY("Best quality", "Most pixels, then the sharpest"),
    ORIGINAL("Original", "The oldest file, before it was copied or edited"),
    NEWEST("Newest", "The latest file, e.g. your edited version"),
    SMALLEST("Smallest file", "Saves the most space"),
}

/**
 * What the duplicate finder must never suggest removing, and how it chooses. Photos protected by a
 * rule are always kept; in a group where every photo is protected nothing is suggested.
 */
data class KeepRules(
    /** RAW files (DNG, CR2, NEF, ARW…) are kept. */
    val keepRaw: Boolean = true,
    /** Photos marked as favourite are kept. */
    val keepFavorites: Boolean = true,
    /** Photos showing any of these people (gallery person ids) are kept. */
    val keepPeople: Set<Long> = emptySet(),
    val prefer: Prefer = Prefer.QUALITY,
    /** Also suggest removing similar shots (bursts), keeping the best one; otherwise only copies. */
    val similarShots: Boolean = false,
)

/** One photo of a group, as the rules see it. */
class KeepCandidate(
    val bytes: Long,
    val pixels: Long,
    val sharpness: Double,
    /** Last changed (ms): the original is usually the oldest copy. */
    val modified: Long,
    val raw: Boolean,
    val favorite: Boolean,
    val people: Set<Long> = emptySet(),
)

object KeepPlanner {
    /** Why a photo is protected, or null. */
    fun protectedBy(c: KeepCandidate, rules: KeepRules, names: Map<Long, String> = emptyMap()): String? = when {
        rules.keepRaw && c.raw -> "RAW"
        rules.keepFavorites && c.favorite -> "Favourite"
        c.people.any { it in rules.keepPeople } -> c.people.firstOrNull { it in rules.keepPeople }?.let { names[it] } ?: "Person you keep"
        else -> null
    }

    /**
     * Orders candidates from most to least worth keeping under [prefer]. Between [copies] of one
     * picture the bigger file is the better copy (less compressed; compression noise can even look
     * "sharp"); between different shots the sharper one is.
     */
    fun comparator(prefer: Prefer, copies: Boolean = false): Comparator<KeepCandidate> = when (prefer) {
        Prefer.QUALITY -> if (copies) compareByDescending<KeepCandidate> { it.pixels }.thenByDescending { it.bytes }.thenBy { it.modified }
            else compareByDescending<KeepCandidate> { it.pixels }.thenByDescending { it.sharpness }.thenByDescending { it.bytes }.thenBy { it.modified }
        Prefer.ORIGINAL -> compareBy<KeepCandidate> { it.modified }.thenByDescending { it.pixels }
        Prefer.NEWEST -> compareByDescending<KeepCandidate> { it.modified }.thenByDescending { it.pixels }
        Prefer.SMALLEST -> compareBy<KeepCandidate> { it.bytes }.thenByDescending { it.pixels }
    }

    /**
     * Positions in [members] to suggest removing. [pictures] splits the positions into distinct
     * pictures (copies of each other). In each picture the preferred copy stays — a protected copy
     * counts as kept, so its plain copies can go — except that a kept RAW file doesn't replace the
     * viewable copy: next to a RAW the best other copy stays too. With [KeepRules.similarShots]
     * only the picture holding the best photo stays. Protected photos are never suggested, and at
     * least one photo of the group always stays.
     */
    fun suggest(members: List<KeepCandidate>, pictures: List<List<Int>>, rules: KeepRules): Set<Int> {
        if (members.size < 2) return emptySet()
        val order = comparator(rules.prefer, copies = true)
        val shots = comparator(rules.prefer)
        val prot = members.indices.filter { protectedBy(members[it], rules) != null }.toSet()
        val keptRaw = members.indices.filter { rules.keepRaw && members[it].raw }.toSet()
        val remove = HashSet<Int>()
        val keptPerPicture = ArrayList<Int>()
        for (pic in pictures) {
            if (pic.isEmpty()) continue
            val rest = pic.filter { it !in keptRaw }
            if (rest.isEmpty()) { keptPerPicture.add(pic.minWith { a, b -> order.compare(members[a], members[b]) }); continue }
            // Keep the best protected copy if there is one (it's staying anyway), else the preferred copy.
            val keep = rest.filter { it in prot }.minWithOrNull { a, b -> order.compare(members[a], members[b]) }
                ?: rest.minWith { a, b -> order.compare(members[a], members[b]) }
            keptPerPicture.add(keep)
            for (m in rest) if (m != keep && m !in prot) remove.add(m)
        }
        if (rules.similarShots && keptPerPicture.size > 1) {
            val best = keptPerPicture.minWith { a, b -> shots.compare(members[a], members[b]) }
            for (pic in pictures) if (best !in pic) for (m in pic) if (m !in prot) remove.add(m)
        }
        return remove
    }
}
