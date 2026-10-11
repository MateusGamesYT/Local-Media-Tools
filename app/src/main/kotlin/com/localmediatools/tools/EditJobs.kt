package com.localmediatools.tools

import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.edit.EditRenderer
import com.localmediatools.edit.EditState
import com.localmediatools.edit.PatchStore
import com.localmediatools.edit.RetouchedSource
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import com.localmediatools.i18n.trn
import com.localmediatools.image.CountingStream
import com.localmediatools.image.EncodeSpec
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.ImagePipeline
import com.localmediatools.image.ImageSource

/**
 * Saves an edited photo at full resolution in the background (crop/rotation, retouch patches,
 * adjustments and look). The original is never touched; the retouch files are deleted afterwards.
 */
class EditPhotoJob(
    tool: ToolId,
    private val item: MediaItem,
    private val state: EditState,
    private val store: PatchStore,
    private val format: ImageOutFormat,
    private val quality: Int,
    private val keepAlpha: Boolean,
) : ExportJob(tool, listOf(item)) {
    override val title = tr("Saving edited photo")

    override suspend fun run(ctx: JobContext) {
        try {
            ctx.forEachItem(inputs, parallel = false) { index, it ->
                ImageSource.open(ctx.app, it).use { src ->
                    val rs = RetouchedSource(src, store, state.patches)
                    var size = 0 to 0
                    val spec = EncodeSpec(format, quality)
                    val (out, _) = Outputs.produce(ctx, tool.area, "${it.baseName}_edited.${format.ext}", format.mime) { pending ->
                        CountingStream(pending.openStream().buffered(1 shl 16)).use { os ->
                            size = EditRenderer.exportFull(ctx, rs, state, spec, keepAlpha, os) { f -> ctx.unitProgress(index, f) }
                        }
                        ImagePipeline.verify(ctx.app, pending, size.first, size.second)
                    }
                    val changes = buildList {
                        if (!state.geometry.isIdentity) add(tr("cropped/rotated"))
                        if (state.patches.isNotEmpty()) add(trn(state.patches.size, "{0} retouch", "{0} retouches"))
                        if (!state.adjust.isIdentity) add(trn(state.adjust.values.size, "{0} adjustment", "{0} adjustments"))
                        if (!state.filter.isIdentity) add(tr("{0} look", state.filter.preset.label))
                    }
                    ItemResult(it.name, ItemOutcome.SUCCESS, tr("Photo metadata (location, camera, dates) is not copied to edited copies."), listOf(out),
                        "${size.first}×${size.second} · ${format.label}${if (format.lossless) "" else " q$quality"} · ${Format.bytes(out.size)}" +
                            if (changes.isNotEmpty()) " · " + changes.joinToString(trc("between the items of a list", ", ")) else "")
                }
            }
        } finally {
            store.deleteAll()
        }
    }
}
