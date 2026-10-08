package com.localmediatools.ui.gallery

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.gallery.GFace
import com.localmediatools.gallery.GPerson
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceClustering
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.listRow
import com.localmediatools.ui.lp
import com.localmediatools.ui.style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What can be done with one face: name it (or the whole group it belongs to), say it is someone
 * else, say it is not this person, or stop tracking it.
 */
object FaceSheet {
    fun show(activity: MainActivity, face: GFace, changed: () -> Unit) {
        activity.lifecycleScope.launch {
            val db = GalleryDb.get(activity)
            val (person, people, suggestions) = withContext(Dispatchers.IO) {
                val f = db.faceById(face.id, withEmb = true) ?: face
                val p = f.personId?.let { db.person(it) }
                val named = db.people(includeHidden = true).filter { it.named }
                Triple(p, named, suggest(db, f, named))
            }
            val ctx = activity
            val col = UI.vertical(ctx, 18, 14)
            val head = UI.horizontal(ctx)
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Shapes.circle(Palette.SURFACE_2); clipToOutline = true }
            GalleryThumbs.face(ctx, face, 192, img)
            head.addView(img, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64)))
            val who = when {
                person?.named == true -> person.name!!
                person != null -> "Unnamed person"
                else -> "Who is this?"
            }
            val sub = when {
                person != null && person.confirmed(face) -> "You confirmed this · in ${person.mediaCount} ${if (person.mediaCount == 1) "photo" else "photos"}"
                person != null -> "Recognised automatically · in ${person.mediaCount} ${if (person.mediaCount == 1) "photo" else "photos"}"
                !face.good -> "This face is small, blurry or turned, so it's only grouped when it clearly matches someone"
                else -> "Not grouped with anyone yet"
            }
            head.addView(UI.titled(ctx, who, sub).apply { setPadding(ctx.dp(14), 0, 0, 0) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(head)
            val dialog = AlertDialog.Builder(activity).setView(android.widget.ScrollView(ctx).apply { addView(col) }).create()
            fun row(icon: Int, color: Int, title: String, subtitle: String?, f: () -> Unit) {
                col.addView(listRow(ctx, icon, color, title, subtitle) { dialog.dismiss(); f() }, lp().apply { topMargin = ctx.dp(2) })
            }
            col.addView(View(ctx), lp(1, ctx.dp(10)))
            if (person != null && !person.named) {
                row(R.drawable.ic_add, Palette.ACCENT, "Name this person", "Names all ${person.faceCount} faces in this group") {
                    askName(activity, people, null) { name -> nameGroup(activity, person, name, people, changed) }
                }
            }
            if (person == null) {
                for (s in suggestions) row(R.drawable.ic_check, Palette.SUCCESS, "This is ${s.name}", "Looks similar") { assign(activity, face, s.id, null, changed) }
                row(R.drawable.ic_add, Palette.ACCENT, "Add a name", "Start a person, or pick one you named") {
                    askName(activity, people, null) { name -> assignByName(activity, face, name, people, null, changed) }
                }
            } else {
                if (person.named) row(R.drawable.ic_close, Palette.DANGER, "This isn't ${person.name}", "Removes it from ${person.name} for good") { reject(activity, face, person.id, changed) }
                else row(R.drawable.ic_close, Palette.DANGER, "Not the same person", "Takes this face out of the group") { reject(activity, face, person.id, changed) }
                row(R.drawable.ic_tool_faceblur, Palette.ACCENT, "This is someone else…", "Pick or type their name") {
                    askName(activity, people.filter { it.id != person.id }, null) { name -> assignByName(activity, face, name, people, person.id, changed) }
                }
                if (person.named || person.faceCount >= 2) row(R.drawable.ic_image, Palette.ACCENT, "Use as ${if (person.named) person.name + "'s" else "the group's"} picture", null) {
                    activity.lifecycleScope.launch { withContext(Dispatchers.IO) { db.setCover(person.id, face.id) }; GalleryIndex.notifyChanged(); changed() }
                }
            }
            row(R.drawable.ic_trash, Palette.TEXT_2, "Ignore this face", "For strangers, posters or statues: it won't appear in People") {
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.setIgnored(face.id, true) }
                    GalleryIndex.notifyChanged(); changed()
                }
            }
            dialog.show()
        }
    }

    private fun GPerson.confirmed(f: GFace) = f.confirmed && f.personId == id

    /** Named people this face resembles (most similar first), for one-tap naming. */
    private fun suggest(db: GalleryDb, f: GFace, named: List<GPerson>): List<GPerson> {
        val e = f.emb ?: return emptyList()
        if (named.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<GPerson, Float>>()
        val params = ClusterParams.of(f.kind)
        for (p in named) {
            val faces = db.faces("f.person_id = ? AND f.kind = ? AND f.emb IS NOT NULL", arrayOf(p.id.toString(), f.kind.code.toString()), withEmb = true)
            if (faces.isEmpty()) continue
            val sum = FloatArray(e.size)
            for (o in faces) { val oe = o.emb!!; for (k in sum.indices) sum[k] += oe[k] }
            val s = FaceClustering.similarity(e, sum, faces.size, params)
            if (s >= params.suggestFace) scored.add(p to s)
        }
        return scored.sortedByDescending { it.second }.take(3).map { it.first }
    }

    fun askName(activity: MainActivity, people: List<GPerson>, initial: String?, done: (String) -> Unit) {
        val ctx = activity
        val input = AutoCompleteTextView(ctx).apply {
            style(TextStyle.BODY)
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_3)
            hint = "Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
            threshold = 1
            setText(initial ?: "")
            setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, people.mapNotNull { it.name }.distinct()))
        }
        val box = UI.vertical(ctx, 20, 8).apply { addView(input) }
        val d = AlertDialog.Builder(activity).setTitle("Who is this?").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().replace(Regex("\\s+"), " ")
                if (name.isNotEmpty()) done(name.take(60))
            }
            .setNegativeButton("Cancel", null).create()
        d.setOnShowListener { input.requestFocus(); d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE) }
        d.show()
    }

    /** Names an unnamed group: if the name is already someone's, the groups are merged (after asking). */
    fun nameGroup(activity: MainActivity, group: GPerson, name: String, people: List<GPerson>, changed: () -> Unit) {
        val db = GalleryDb.get(activity)
        val existing = people.firstOrNull { it.name.equals(name, ignoreCase = true) && it.id != group.id }
        if (existing != null) {
            AlertDialog.Builder(activity).setTitle("Add to ${existing.name}?")
                .setMessage("${existing.name} already exists. These ${group.faceCount} faces will be added to ${existing.name}.")
                .setPositiveButton("Add") { _, _ ->
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { db.mergePeople(existing.id, group.id) }
                        GalleryIndex.regroupNow(activity); changed()
                    }
                }.setNegativeButton("Cancel", null).show()
            return
        }
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.renamePerson(group.id, name)
                // Naming confirms the group: these faces now anchor the person.
                db.confirm(db.faces("f.person_id = ?", arrayOf(group.id.toString())).map { it.id }, group.id)
            }
            Toast.makeText(activity, "Named $name", Toast.LENGTH_SHORT).show()
            GalleryIndex.regroupNow(activity); changed()
        }
    }

    private fun assign(activity: MainActivity, face: GFace, personId: Long, rejectFrom: Long?, changed: () -> Unit) {
        val db = GalleryDb.get(activity)
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (rejectFrom != null && rejectFrom != personId) db.reject(face.id, rejectFrom)
                db.setIgnored(face.id, false)
                db.confirm(listOf(face.id), personId)
            }
            GalleryIndex.regroupNow(activity); changed()
        }
    }

    private fun assignByName(activity: MainActivity, face: GFace, name: String, people: List<GPerson>, rejectFrom: Long?, changed: () -> Unit) {
        val existing = people.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) { assign(activity, face, existing.id, rejectFrom, changed); return }
        activity.lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) { GalleryDb.get(activity).newPerson(name) }
            assign(activity, face, id, rejectFrom, changed)
        }
    }

    private fun reject(activity: MainActivity, face: GFace, personId: Long, changed: () -> Unit) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { GalleryDb.get(activity).reject(face.id, personId) }
            GalleryIndex.regroupNow(activity); changed()
        }
    }
}
