package com.localmediatools.robo

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.codec.gif.Dithering
import com.localmediatools.codec.gif.GifAnimationWriter
import com.localmediatools.codec.layout.Align
import com.localmediatools.export.ExportManager
import com.localmediatools.export.ItemOutcome
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.tools.CompressGifJob
import com.localmediatools.tools.CompressImagesJob
import com.localmediatools.tools.ConvertImagesJob
import com.localmediatools.tools.FlowKind
import com.localmediatools.tools.MergeBackground
import com.localmediatools.tools.MergeImagesJob
import com.localmediatools.tools.MergeLayout
import com.localmediatools.tools.StackJob
import com.localmediatools.tools.StackRules
import com.localmediatools.tools.StackStep
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.StackScreen
import com.localmediatools.ui.StackToolPicker
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.ToolStackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration

/** Tool stacks: real tools chained end to end, plus building a stack in the UI. */
@RunWith(RobolectricTestRunner::class)
class ToolStackTest {
    private lateinit var app: Application
    private lateinit var inDir: File
    private lateinit var outputs: FileOutputs

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Robo.resetUiState()
        ToolStackState.steps.clear(); ToolStackState.keepInBetween = false
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        inDir = File(app.cacheDir, "stack-in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "stack-out").apply { deleteRecursively() }).also { it.install() }
    }

    @After fun tearDown() { outputs.uninstall(); ToolStackState.steps.clear() }

    private fun png(name: String, w: Int, h: Int, c: Int) = Robo.item(app, Robo.write(inDir, name,
        Robo.encode(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }, Bitmap.CompressFormat.PNG)))

    private fun gif(name: String) = Robo.item(app, Robo.write(inDir, name, ByteArrayOutputStream().also { bos ->
        GifAnimationWriter(bos, 40, 30, null, 0).apply {
            addFrame(IntArray(40 * 30) { Color.RED }, 10); addFrame(IntArray(40 * 30) { Color.GREEN }, 10); finish()
        }
    }.toByteArray()))

    private fun step(job: com.localmediatools.export.ExportJob) = StackStep(job.tool, "test", job)

    @Test fun stepsRunInOrderAndOnlyFinalResultsAreSaved() {
        val a = png("a.png", 300, 200, Color.RED); val b = png("b.png", 200, 300, Color.BLUE)
        val results = Robo.runJob(app, StackJob(listOf(a, b), listOf(
            step(ConvertImagesJob(emptyList(), ImageOutFormat.JPEG, 90)),
            step(CompressImagesJob(emptyList(), ImageOutFormat.WEBP, 60, 100)),
        ), keepInBetween = false))
        assertEquals(results.toString(), 2, results.size)
        assertTrue(results.all { it.outcome == ItemOutcome.SUCCESS })
        assertEquals(setOf("a.png", "b.png"), results.map { it.inputName }.toSet())
        // Only the two final WebP files were published; the JPEGs in between stayed private.
        assertEquals(outputs.committed.map { it.name }.toString(), 2, outputs.committed.size)
        assertTrue(outputs.committed.map { it.name }.toString(), outputs.committed.all { it.name.endsWith(".webp") })
        val out = Robo.decode(outputs.committed.first { it.name.startsWith("a_") })
        assertEquals(100, out.width)   // max width from step 2, on step 1's result
        assertTrue("colour ${Integer.toHexString(out.getPixel(50, 30))}", Robo.diff(out.getPixel(50, 30), Color.RED) < 30)
        // The temporary files are gone.
        assertTrue(app.cacheDir.list()!!.toList().toString(), app.cacheDir.listFiles()!!.none { it.name.startsWith("stack-") && it.name != "stack-in" && it.name != "stack-out" })
    }

    @Test fun filesAStepCantUseContinueUnchanged() {
        val photo = png("photo.png", 120, 80, Color.YELLOW); val anim = gif("anim.gif")
        val results = Robo.runJob(app, StackJob(listOf(photo, anim), listOf(
            step(CompressGifJob(emptyList(), 0, 0.0, 32, Dithering.NONE)),     // only the GIF
            step(ConvertImagesJob(emptyList(), ImageOutFormat.PNG, 90)),       // both
        ), keepInBetween = false))
        assertEquals(results.toString(), 2, results.size)
        assertTrue(results.toString(), results.all { it.outcome == ItemOutcome.SUCCESS })
        val p = results.first { it.inputName == "photo.png" }
        assertTrue(p.message!!, p.message!!.contains("Step 1 (GIF compressor) doesn't work on photos"))
        assertEquals(2, outputs.committed.size)
        assertTrue(outputs.committed.all { it.name.endsWith(".png") })
    }

    @Test fun aFileTheLastStepCantUseIsSavedFromItsLastChange() {
        val photo = png("pic.png", 64, 64, Color.CYAN)
        val results = Robo.runJob(app, StackJob(listOf(photo), listOf(
            step(ConvertImagesJob(emptyList(), ImageOutFormat.JPEG, 90)),
            step(CompressGifJob(emptyList(), 0, 0.0, 64, Dithering.NONE)),
        ), keepInBetween = false))
        val r = results.single()
        assertEquals(ItemOutcome.SUCCESS, r.outcome)
        assertEquals(1, outputs.committed.size)
        assertTrue(outputs.committed.single().name.endsWith(".jpg"))
        assertEquals(ToolId.CONVERT_IMAGES.area, r.outputs.single().area)
    }

    @Test fun mergingCombinesFilesAndKeepingInBetweenSavesEveryStep() {
        val a = png("left.png", 100, 100, Color.RED); val b = png("right.png", 100, 100, Color.BLUE)
        val results = Robo.runJob(app, StackJob(listOf(a, b), listOf(
            step(MergeImagesJob(emptyList(), MergeLayout.HORIZONTAL, MergeBackground.WHITE, 0, Align.START)),
            step(ConvertImagesJob(emptyList(), ImageOutFormat.JPEG, 90)),
        ), keepInBetween = true))
        assertEquals(results.toString(), 2, results.size)
        assertTrue(results.any { it.inputName == "left.png + right.png" && it.outputs.single().displayName.endsWith(".jpg") })
        assertTrue(results.any { it.inputName.endsWith("after step 1") })
        assertEquals(2, outputs.committed.size)
        val merged = Robo.decode(outputs.committed.first { it.name.endsWith(".jpg") })
        assertEquals(200, merged.width)
    }

    @Test fun aFailureStopsOnlyThatFile() {
        val good = png("good.png", 50, 50, Color.GREEN)
        val bad = Robo.item(app, Robo.write(inDir, "broken.png", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)))
        val results = Robo.runJob(app, StackJob(listOf(good, bad), listOf(
            step(ConvertImagesJob(emptyList(), ImageOutFormat.JPEG, 90)),
            step(CompressImagesJob(emptyList(), ImageOutFormat.WEBP, 70, 0)),
        ), keepInBetween = false))
        assertEquals(ItemOutcome.SUCCESS, results.first { it.inputName == "good.png" }.outcome)
        val f = results.first { it.inputName == "broken.png" }
        assertEquals(ItemOutcome.FAILED, f.outcome)
        assertTrue(f.message!!, f.message!!.startsWith("Step 1 (Convert images) failed"))
        assertEquals(1, outputs.committed.size)
    }

    @Test fun rulesDescribeWhatFlowsBetweenSteps() {
        var k = setOf(FlowKind.VIDEO)
        k = StackRules.after(ToolId.FACE_BLUR, k); assertEquals(setOf(FlowKind.VIDEO), k)
        k = StackRules.after(ToolId.COMPRESS_VIDEO, k); assertEquals(setOf(FlowKind.VIDEO), k)
        k = StackRules.after(ToolId.VIDEO_TO_GIF, k); assertEquals(setOf(FlowKind.GIF), k)
        assertTrue(StackRules.accepts(ToolId.OPTIMIZE_GIF).intersect(k).isNotEmpty())
        assertTrue(StackRules.accepts(ToolId.COMPRESS_VIDEO).intersect(k).isEmpty())
        assertEquals(setOf(FlowKind.PDF, FlowKind.VIDEO), StackRules.after(ToolId.IMAGES_TO_PDF, setOf(FlowKind.IMAGE, FlowKind.VIDEO)))
        assertTrue(!StackRules.stackable(ToolId.MAGIC_ERASER) && !StackRules.stackable(ToolId.DUPLICATES))
    }

    private fun idle(ms: Long = 400) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun render(a: MainActivity) {
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
    }
    private fun texts(a: MainActivity) = all(a.navigator.top!!.view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun click(a: MainActivity, pred: (String) -> Boolean) {
        val v = all(a.navigator.top!!.view).firstOrNull { v -> (v.contentDescription?.toString() ?: (v as? TextView)?.text?.toString())?.let(pred) == true }
            ?: throw AssertionError("nothing to click among ${texts(a)}")
        var c: View? = v
        while (c != null && !c.isClickable) c = c.parent as? View
        c!!.performClick(); idle()
    }

    @Test fun buildingAStackInTheUi() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        // From a tool screen: "Then run another tool" starts a stack with that tool as step 1.
        a.navigator.push(com.localmediatools.ui.tools.ToolScreens.create(a, ToolId.CONVERT_IMAGES)); idle()
        com.localmediatools.ui.Selection.of(ToolId.CONVERT_IMAGES).add(listOf(png("x.png", 80, 60, Color.RED), png("y.png", 60, 80, Color.BLUE)))
        idle(); render(a)
        click(a) { it == "Then run another tool" }
        assertTrue(a.navigator.top is StackScreen)
        assertEquals(listOf(ToolId.CONVERT_IMAGES), ToolStackState.steps.map { it.tool })
        assertEquals(2, ToolStackState.selection.usable.size)
        render(a)
        assertTrue(texts(a).joinToString("|"), texts(a).any { it.startsWith("Starts with 2 files: photos") })

        // Add step 2 through the picker: tools that can't use photos are greyed out.
        click(a) { it == "Add a step" }
        assertTrue(a.navigator.top is StackToolPicker)
        render(a)
        assertTrue(texts(a).any { it.startsWith("Works on videos, which this step doesn't get") })
        click(a) { it == ToolId.COMPRESS_IMAGES.title }
        idle(600)
        val screen = a.navigator.top as ToolScreen
        assertEquals(ToolId.COMPRESS_IMAGES, screen.tool)
        render(a)
        assertTrue(texts(a).any { it == "Step 2: Image compressor" })
        assertTrue(texts(a).any { it.startsWith("Works on the photos coming from step 1 (Convert images)") })
        click(a) { it == "Add to stack" }
        idle(600)
        assertTrue(a.navigator.top is StackScreen)
        assertEquals(listOf(ToolId.CONVERT_IMAGES, ToolId.COMPRESS_IMAGES), ToolStackState.steps.map { it.tool })
        assertTrue(ToolStackState.steps[1].summary, ToolStackState.steps[1].summary.contains("Quality"))
        render(a)
        assertTrue(texts(a).any { it.startsWith("Saved: photos") })

        // Run it.
        click(a) { it == "Run stack" }
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && (ExportManager.state.value.busy || outputs.committed.size < 2)) { idle(100); Thread.sleep(30) }
        assertEquals(outputs.committed.map { it.name }.toString(), 2, outputs.committed.size)
    }
}
