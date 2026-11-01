package com.localmediatools.video

import android.content.Context
import android.net.Uri
import com.localmediatools.audio.core.SpeedPitch
import com.localmediatools.highlight.EncodedAudio
import com.localmediatools.highlight.HighlightAudio
import com.localmediatools.highlight.SongChoice
import com.localmediatools.highlight.core.Mixer
import com.localmediatools.music.core.Mood
import com.localmediatools.video.core.EditSound
import com.localmediatools.video.core.EditTimeline
import kotlin.math.abs

/**
 * The edited video's sound: each clip's own sound ([EditSound]: its part of the file, at its speed
 * with the pitch kept ([SpeedPitch]), at its volume, fading with its picture), and the music under it
 * — the user's own song fitted to the video, or music the app writes ([HighlightAudio], as for
 * highlight videos) — stepped back while clips speak, at the project's music level; encoded to AAC.
 */
object EditAudio {
    private const val RATE = HighlightAudio.RATE

    /** Null when the video has no sound at all. */
    fun build(ctx: Context, tl: EditTimeline, mood: Mood?, seed: Long, song: SongChoice?, uris: (Int) -> Uri,
              progress: (Double) -> Unit, cancelled: () -> Boolean): EncodedAudio? {
        val parts = EditSound.parts(tl)
        if (mood == null && song == null && parts.isEmpty()) return null
        val frames = (tl.durationMs * RATE / 1000).toInt().coerceAtLeast(1)
        val music = when {
            song != null -> HighlightAudio.songMusic(ctx, song.uri, frames)
            mood != null -> HighlightAudio.music(ctx, mood, tl.durationMs / 1000.0, seed)
            else -> null
        }
        progress(0.3)
        val mixed = ArrayList<Mixer.Part>()
        for ((k, s) in parts.withIndex()) {
            if (cancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
            var pcm = try { HighlightAudio.clipSound(ctx, uris(s.clip), s.fromMs, s.toMs) } catch (t: Throwable) {
                android.util.Log.w("LMT", "editor: sound of clip ${s.clip} unavailable", t); null
            } ?: continue
            if (abs(s.speed - 1f) > 1e-3f) {
                val sp = SpeedPitch(RATE, 2, s.speed.toDouble(), 0.0)
                pcm = concat(sp.process(pcm), sp.flush())
            }
            mixed.add(Mixer.Part((s.startMs * RATE / 1000).toInt(), pcm, s.gain,
                fadeInFrames = (s.fadeInMs * RATE / 1000).toInt(), fadeOutFrames = (s.fadeOutMs * RATE / 1000).toInt()))
            progress(0.3 + 0.4 * (k + 1) / parts.size)
        }
        val mix = Mixer.mix(music, tl.project.musicVolume, frames, RATE, mixed, EditSound.intervals(tl), fadeInMs = 0, fadeOutMs = tl.project.fadeOutMs)
        progress(0.75)
        return HighlightAudio.encode(mix, cancelled).also { progress(1.0) }
    }

    private fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size); System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }
}
