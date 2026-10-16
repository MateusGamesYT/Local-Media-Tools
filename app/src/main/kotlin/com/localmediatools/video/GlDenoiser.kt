package com.localmediatools.video

import android.opengl.GLES20
import com.localmediatools.denoise.core.VideoDenoise
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Removes noise from video frames on the GPU: each frame is drawn into an offscreen texture, then
 * blended with the previous output where the picture stands still and smoothed within the frame
 * where it moves ([VideoDenoise], whose CPU version the shader follows line by line). The result
 * goes to the encoder and is kept as the next frame's "previous output".
 *
 * [sigma]: the noise's standard deviation, as a share of full brightness (measured before
 * transcoding from a few frames).
 */
class GlDenoiser(private val w: Int, private val h: Int, private val sigma: Float, private val p: VideoDenoise.Params) {
    private val frame = GlTarget(w, h)
    private val outs = arrayOf(GlTarget(w, h), GlTarget(w, h))
    private var current = 0
    private var first = true
    private val program = ExternalTextureRenderer.link(RegionObscurer.VERTEX, SHADER)
    private val copy = ExternalTextureRenderer.link(RegionObscurer.VERTEX, RegionObscurer.COPY)
    private val unit: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
    }

    /** Draws one denoised frame into the current (window) surface; [drawFrame] draws the decoded picture at full output size. */
    fun render(drawFrame: () -> Unit) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frame.fbo)
        GLES20.glViewport(0, 0, w, h)
        drawFrame()
        val out = outs[current]; val prev = outs[1 - current]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame.tex)
        GLES20.glUniform1i(loc(program, "uCur"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prev.tex)
        GLES20.glUniform1i(loc(program, "uPrev"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val s = maxOf(sigma, 1e-4f)
        GLES20.glUniform2f(loc(program, "uTexel"), 1f / w, 1f / h)
        GLES20.glUniform1f(loc(program, "uLo"), p.moveLo * s)
        GLES20.glUniform1f(loc(program, "uHi"), p.moveHi * s)
        GLES20.glUniform1f(loc(program, "uInv2r2"), 1f / (2f * (p.range * s) * (p.range * s)))
        GLES20.glUniform1f(loc(program, "uStill"), p.still)
        GLES20.glUniform1f(loc(program, "uSpStill"), p.spatialStill)
        GLES20.glUniform1f(loc(program, "uSpMove"), p.spatialMoving)
        GLES20.glUniform1f(loc(program, "uFirst"), if (first) 1f else 0f)
        quad(program)
        // To the encoder.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(copy)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, out.tex)
        GLES20.glUniform1i(loc(copy, "uTex"), 0)
        quad(copy)
        first = false
        current = 1 - current
    }

    private fun loc(prog: Int, name: String) = GLES20.glGetUniformLocation(prog, name)

    private fun quad(prog: Int) {
        GLES20.glUniform4f(loc(prog, "uRect"), 0f, 0f, 1f, 1f)
        val a = GLES20.glGetAttribLocation(prog, "aPos")
        unit.position(0)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 8, unit)
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(a)
    }

    fun release() {
        frame.release(); outs.forEach { it.release() }
        GLES20.glDeleteProgram(program); GLES20.glDeleteProgram(copy)
    }

    companion object {
        /** VideoDenoise.step, per pixel. */
        internal const val SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uCur;
            uniform sampler2D uPrev;
            uniform vec2 uTexel;
            uniform float uLo;
            uniform float uHi;
            uniform float uInv2r2;
            uniform float uStill;
            uniform float uSpStill;
            uniform float uSpMove;
            uniform float uFirst;
            float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            void main() {
                vec3 c0 = texture2D(uCur, vUv).rgb;
                vec3 sum = vec3(0.0);
                float sw = 0.0;
                float boxC = 0.0;
                float boxP = 0.0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        vec2 o = vec2(float(dx), float(dy)) * uTexel;
                        vec3 c = texture2D(uCur, vUv + o).rgb;
                        vec3 d = c - c0;
                        float wt = exp(-dot(d, d) * uInv2r2);
                        sum += wt * c;
                        sw += wt;
                        boxC += lum(c);
                        boxP += lum(texture2D(uPrev, vUv + o).rgb);
                    }
                }
                vec3 sp = sum / sw;
                float m = uFirst > 0.5 ? 1.0 : smoothstep(uLo, uHi, abs(boxC - boxP) / 9.0);
                float k = uSpStill + (uSpMove - uSpStill) * m;
                vec3 cand = c0 + (sp - c0) * k;
                vec3 prev = texture2D(uPrev, vUv).rgb;
                float a = uStill + (1.0 - uStill) * m;
                gl_FragColor = vec4(uFirst > 0.5 ? cand : prev + (cand - prev) * a, 1.0);
            }
        """
    }
}
