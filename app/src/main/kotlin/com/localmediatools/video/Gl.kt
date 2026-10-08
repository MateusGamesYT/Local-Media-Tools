package com.localmediatools.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Minimal EGL/GLES2 plumbing for video processing: an EGL context bound either to a codec input
 * surface (recordable) or to an offscreen pbuffer, an external-texture renderer and a
 * SurfaceTexture that receives decoded frames.
 */
class EglEnv(encoderSurface: Surface?, pbufferW: Int = 0, pbufferH: Int = 0) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("No EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw RuntimeException("EGL init failed")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, if (encoderSurface != null) EGL14.EGL_WINDOW_BIT else EGL14.EGL_PBUFFER_BIT,
        ) + (if (encoderSurface != null) intArrayOf(EGL_RECORDABLE_ANDROID, 1) else IntArray(0)) + intArrayOf(EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) throw RuntimeException("No suitable EGL config")
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check("eglCreateContext")
        surface = if (encoderSurface != null) {
            EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        } else {
            EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, pbufferW, EGL14.EGL_HEIGHT, pbufferH, EGL14.EGL_NONE), 0)
        }
        check("eglCreateSurface")
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw RuntimeException("eglMakeCurrent failed")
    }

    fun setPresentationTime(ns: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, ns)
    }

    fun swap(): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private fun check(op: String) {
        val e = EGL14.eglGetError()
        if (e != EGL14.EGL_SUCCESS) throw RuntimeException("$op: EGL error 0x${Integer.toHexString(e)}")
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/** Draws an external OES texture (a decoded video frame) as a full-viewport quad. */
class ExternalTextureRenderer {
    val textureId: Int
    private val program: Int
    private val aPosition: Int
    private val aTexCoord: Int
    private val uMvp: Int
    private val uSt: Int
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    init {
        program = link(VERTEX, FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSt = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /**
     * Draws the frame into the current viewport. [rotationCw] rotates the picture clockwise (for
     * producing display-oriented frames); [flipY] mirrors vertically (glReadPixels is bottom-up);
     * [scaleX]/[scaleY] shrink the picture inside the viewport (letterboxing, black around it).
     */
    fun draw(stMatrix: FloatArray, viewportW: Int, viewportH: Int, rotationCw: Int = 0, flipY: Boolean = false, scaleX: Float = 1f, scaleY: Float = 1f) {
        GLES20.glViewport(0, 0, viewportW, viewportH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        if (scaleX != 1f || scaleY != 1f) Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f)
        if (flipY) Matrix.scaleM(mvp, 0, 1f, -1f, 1f)
        // Rotating the quad clockwise on screen = rotating by -angle around Z.
        if (rotationCw != 0) Matrix.rotateM(mvp, 0, -rotationCw.toFloat(), 0f, 0f, 1f)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glFinish()
    }

    fun release() {
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    companion object {
        private const val VERTEX = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        internal fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw RuntimeException("Shader compile failed: $log")
            }
            return s
        }

        internal fun link(v: String, f: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, v))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, f))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Program link failed: ${GLES20.glGetProgramInfoLog(p)}")
            return p
        }
    }
}

/**
 * Receives decoder output on a SurfaceTexture. Frame-available callbacks arrive on a private
 * handler thread, so the processing thread can block in [awaitFrame].
 */
class DecoderOutputSurface(textureId: Int) {
    private val thread = HandlerThread("lmt-frames").apply { start() }
    private val lock = Object()
    private var available = false
    val surfaceTexture = SurfaceTexture(textureId)
    val surface = Surface(surfaceTexture)
    val stMatrix = FloatArray(16)

    init {
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(lock) { available = true; lock.notifyAll() }
        }, Handler(thread.looper))
    }

    /** Waits for the frame released with render=true and latches it into the texture. */
    fun awaitFrame(timeoutMs: Long = 3000): Boolean {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!available) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
            available = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
        return true
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
        thread.quitSafely()
    }
}

/** A region to hide: centre and radii as fractions of the output frame, y measured downwards. */
data class ObscureRegion(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

/**
 * Hides regions (faces) of each video frame on the GPU. The frame is drawn into an offscreen
 * texture and shrunk step by step (each step averages 2×2 pixels, so the result is stable from
 * frame to frame); every region is then painted over from the level that matches its size,
 * either blurred with a soft 9-tap filter or pixelated, with a feathered elliptical edge.
 */
class RegionObscurer(private val w: Int, private val h: Int) {
    private class Target(val w: Int, val h: Int) {
        val tex: Int
        val fbo: Int
        init {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            tex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val f = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0)
            fbo = f[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) throw RuntimeException("Offscreen buffer incomplete (0x${Integer.toHexString(status)})")
        }
        fun release() {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
        }
    }

    private val full = Target(w, h)
    private val levels = ArrayList<Target>().apply {
        var lw = w; var lh = h
        while (size < 8 && maxOf(lw, lh) > 8) { lw = maxOf(1, lw / 2); lh = maxOf(1, lh / 2); add(Target(lw, lh)) }
    }
    private val copyProgram = ExternalTextureRenderer.link(VERTEX, COPY)
    private val regionProgram = ExternalTextureRenderer.link(VERTEX, REGION)
    private val unit: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
    }

    /**
     * Draws a frame with [regions] hidden into the current (window) surface. [drawFrame] draws the
     * picture into whatever framebuffer is bound, at the full output size.
     */
    fun render(drawFrame: () -> Unit, regions: List<ObscureRegion>, pixelate: Boolean, strength: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (regions.isEmpty()) { drawFrame(); return }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, full.fbo)
        drawFrame()
        var src = full
        for (lv in levels) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, lv.fbo)
            GLES20.glViewport(0, 0, lv.w, lv.h)
            quad(copyProgram, src.tex, 0f, 0f, 1f, 1f)
            src = lv
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        quad(copyProgram, full.tex, 0f, 0f, 1f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val st = strength.coerceIn(0f, 1f)
        for (r in regions) {
            val cx = r.cx; val cy = 1f - r.cy   // GL textures are bottom-up
            val rpx = maxOf(r.rx * w, r.ry * h).coerceAtLeast(2f)
            // Texel size (in output pixels) of the level to read from.
            val want = if (pixelate) {
                val blocks = 16f - 10f * st
                (2 * rpx / blocks).coerceAtLeast(4f) / 2f
            } else {
                rpx * (0.15f + 0.3f * st) / 1.5f
            }
            val li = (Math.round(ln2(want.toDouble())).toInt() - 1).coerceIn(-1, levels.size - 1)
            val lv = if (li < 0) full else levels[li]
            GLES20.glUseProgram(regionProgram)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(regionProgram, "uCenter"), cx, cy)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(regionProgram, "uRadius"), r.rx.coerceAtLeast(1e-4f), r.ry.coerceAtLeast(1e-4f))
            GLES20.glUniform2f(GLES20.glGetUniformLocation(regionProgram, "uTap"), 1.5f / lv.w, 1.5f / lv.h)
            if (pixelate) {
                val block = (2 * rpx / (16f - 10f * st)).coerceAtLeast(4f)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(regionProgram, "uBlock"), block / w, block / h)
            } else {
                GLES20.glUniform2f(GLES20.glGetUniformLocation(regionProgram, "uBlock"), 0f, 0f)
            }
            val x0 = (cx - r.rx).coerceIn(0f, 1f); val x1 = (cx + r.rx).coerceIn(0f, 1f)
            val y0 = (cy - r.ry).coerceIn(0f, 1f); val y1 = (cy + r.ry).coerceIn(0f, 1f)
            if (x1 > x0 && y1 > y0) quad(regionProgram, lv.tex, x0, y0, x1 - x0, y1 - y0)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun ln2(x: Double) = Math.log(x.coerceAtLeast(1e-6)) / Math.log(2.0)

    /** Draws texture [tex] over the rectangle (x, y, w, h) of the viewport, in 0..1 units (y up). */
    private fun quad(program: Int, tex: Int, x: Float, y: Float, rw: Float, rh: Float) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uRect"), x, y, rw, rh)
        val a = GLES20.glGetAttribLocation(program, "aPos")
        unit.position(0)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 8, unit)
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(a)
    }

    fun release() {
        full.release()
        levels.forEach { it.release() }
        GLES20.glDeleteProgram(copyProgram)
        GLES20.glDeleteProgram(regionProgram)
    }

    companion object {
        private const val VERTEX = """
            attribute vec2 aPos;
            uniform vec4 uRect;
            varying vec2 vUv;
            void main() {
                vec2 uv = uRect.xy + aPos * uRect.zw;
                vUv = uv;
                gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
            }
        """
        private const val COPY = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """
        private const val REGION = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uCenter;
            uniform vec2 uRadius;
            uniform vec2 uTap;
            uniform vec2 uBlock;
            void main() {
                float r = length((vUv - uCenter) / uRadius);
                float a = 1.0 - smoothstep(0.8, 1.0, r);
                if (a <= 0.0) discard;
                vec3 c;
                if (uBlock.x > 0.0) {
                    c = texture2D(uTex, (floor(vUv / uBlock) + 0.5) * uBlock).rgb;
                } else {
                    vec2 tx = vec2(uTap.x, 0.0);
                    vec2 ty = vec2(0.0, uTap.y);
                    c = texture2D(uTex, vUv).rgb * 0.25;
                    c += (texture2D(uTex, vUv + tx).rgb + texture2D(uTex, vUv - tx).rgb
                        + texture2D(uTex, vUv + ty).rgb + texture2D(uTex, vUv - ty).rgb) * 0.125;
                    c += (texture2D(uTex, vUv + tx + ty).rgb + texture2D(uTex, vUv - tx - ty).rgb
                        + texture2D(uTex, vUv + tx - ty).rgb + texture2D(uTex, vUv - tx + ty).rgb) * 0.0625;
                }
                gl_FragColor = vec4(c, a);
            }
        """
    }
}
