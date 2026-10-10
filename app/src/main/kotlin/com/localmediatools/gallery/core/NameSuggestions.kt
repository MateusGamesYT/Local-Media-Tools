package com.localmediatools.gallery.core

/**
 * "Is this Ana?" for an unnamed group: the named people whose faces look like the group's, most
 * alike first, compared the way the grouping compares groups ([FaceClustering.linkage]) and shown
 * only above [ClusterParams.suggestGroup].
 */
object NameSuggestions {
    /** A person (or group) as the sum of its faces' embeddings and their number. */
    class Faces(val id: Long, val sum: FloatArray, val count: Int)

    fun of(embeddings: List<FloatArray>, id: Long): Faces? {
        if (embeddings.isEmpty()) return null
        val sum = FloatArray(embeddings[0].size)
        for (e in embeddings) for (k in sum.indices) sum[k] += e[k]
        return Faces(id, sum, embeddings.size)
    }

    fun forGroup(group: Faces, named: List<Faces>, params: ClusterParams, max: Int = 3): List<Pair<Long, Float>> =
        named.asSequence().filter { it.id != group.id && it.sum.size == group.sum.size }
            .map { it.id to FaceClustering.linkage(group.sum, group.count, it.sum, it.count, params) }
            .filter { it.second >= params.suggestGroup }
            .sortedByDescending { it.second }.take(max).toList()
}
