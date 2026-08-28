package com.videoupscaler.ai.ai

import android.content.Context
import android.opengl.GLES20
// NOTE: this is androidx.media3.common.util.Size, NOT android.util.Size. configure() returns the
// Media3 one and the two are not interchangeable.
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Tier 2 — the learned upscaler, wired into the same effect list that drives both preview and export
 * (PLAN.md §2). That shared-chain property is why preview cannot drift from export.
 */
@UnstableApi
class AiUpscaleEffect(
    private val assetName: String,
    private val scale: Int = 2,
) : GlEffect {

    /** Real signature: toGlShaderProgram(Context, boolean useHdr). */
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        AiUpscaleShaderProgram(context, useHdr, assetName, scale)
}

/**
 * Reads the input texture, runs one inference, writes the result.
 *
 * This is the honest **two-copy** path: GPU texture → CPU float buffer → GPU texture. The plan's
 * "zero-copy" claim was never achievable through `Interpreter.run`, which accepts only arrays and
 * `java.nio.Buffer`. Real zero-copy needs the C++ `CompiledModel` API (PLAN.md §6.5, Phase 6).
 *
 * ## The FBO discipline, which is the easy thing to get wrong
 *
 * [BaseGlShaderProgram.queueInputFrame] focuses the output framebuffer *before* calling
 * [drawFrame] — its Javadoc states the caller is responsible for the render target. So the output
 * FBO is already bound on entry. Attaching the input texture to our own readback FBO unbinds it,
 * and the output size differs from the input, so it cannot simply be restored by guessing. The
 * binding is therefore captured on entry with `GL_FRAMEBUFFER_BINDING` and restored before drawing.
 */
@UnstableApi
class AiUpscaleShaderProgram(
    context: Context,
    useHdr: Boolean,
    assetName: String,
    scale: Int,
) : BaseGlShaderProgram(
        // Half-precision colour components only if the chain is HDR; otherwise 8-bit, matching what
        // the rest of the pipeline uses.
        /* useHighPrecisionColorComponents= */ useHdr,
        // Small pool: frames are consumed immediately and there is no texture cache.
        /* texturePoolCapacity= */ 2,
    ) {

    // Created here, on the GL thread, so the GPU delegate sees a current EGL context.
    private val engine = UpscaleEngine.create(context, assetName, scale)

    private var readFboId = 0
    private var srcTexId = 0
    private var programId = 0
    private var outTexId = 0
    private var configuredW = 0
    private var configuredH = 0

    /**
     * Returns the output size and checks the input matches the model's fixed input.
     *
     * The model has a static input shape (fixed input is what the GPU delegate wants, and it is what
     * the Phase 0 benchmark specifies). Rather than resampling inside this effect, the chain puts a
     * `LanczosResample` **before** it to normalise the frame — see `UpscaleChain`. That keeps this
     * class single-purpose and keeps the resize on the GPU.
     */
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth != engine.inputWidth || inputHeight != engine.inputHeight) {
            // VideoFrameProcessingException, not require(): an IllegalArgumentException here would
            // propagate as a crash out of the GL thread. This is the portrait-source case, which is
            // an expected user mistake, and Transformer reports it through onError.
            throw VideoFrameProcessingException(
                "AiUpscaleEffect expects ${engine.inputWidth}x${engine.inputHeight} input, got " +
                    "${inputWidth}x$inputHeight. Portrait sources need a rotation stage; see " +
                    "UpscaleChain.isSupported()."
            )
        }

        if (readFboId == 0) initGl(inputWidth, inputHeight, engine.outputWidth, engine.outputHeight)
        configuredW = inputWidth
        configuredH = inputHeight
        return Size(engine.outputWidth, engine.outputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        check(readFboId != 0) { "drawFrame before configure" }

        val restore = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, restore, 0)

        try {
            readbackInto(inputTexId)
            engine.run()
        } finally {
            // Always restore, including on the error path — a leaked binding corrupts every
            // downstream frame and produces a confusing failure far from here.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, restore[0])
        }

        uploadAndDraw()
    }

    // ----------------------------------------------------------------------------------------

    private fun readbackInto(inputTexId: Int) {
        val w = configuredW
        val h = configuredH

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readFboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            inputTexId,
            0,
        )
        check(
            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        ) { "readback FBO incomplete (status ${GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)})" }

        // RGBA8 readback, then normalise to 0..1. Reading as float directly is not portable across
        // GLES2 renderable formats.
        val bytes = ByteArray(w * h * 4)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(bytes))

        // GL's framebuffer origin is bottom-left, so readback row 0 is the image's BOTTOM row.
        // The model expects row 0 to be the top, so rows are reversed while normalising.
        val input = engine.inputBuffer()
        var dst = 0
        for (y in 0 until h) {
            var src = (h - 1 - y) * w * 4
            for (x in 0 until w) {
                input.put(dst++, (bytes[src].toInt() and 0xFF) / 255f)
                input.put(dst++, (bytes[src + 1].toInt() and 0xFF) / 255f)
                input.put(dst++, (bytes[src + 2].toInt() and 0xFF) / 255f)
                src += 4
            }
        }
        input.rewind()
    }

    private fun uploadAndDraw() {
        val w = engine.outputWidth
        val h = engine.outputHeight
        val out = engine.outputBuffer()

        // Convert to RGBA8 for upload. An FP32 texture upload would need GLES3 and a float-renderable
        // extension that is not guaranteed on the mid-range devices this has to run on.
        val bytes = ByteArray(w * h * 4)
        out.rewind()
        var i = 0
        while (out.hasRemaining()) {
            bytes[i++] = (out.get().coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            bytes[i++] = (out.get().coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            bytes[i++] = (out.get().coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            bytes[i++] = 255.toByte()
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outTexId)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(bytes),
        )

        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uTex"), 0)

        // Orientation: the buffer is stored top-row-first, so texture v=0 is the image TOP, while
        // the quad maps v=0 to the screen BOTTOM. V is therefore flipped here. This is the single
        // most likely thing to be wrong on first run — if the output is vertically mirrored, flip
        // this, not the readback.
        // Two separate, non-interleaved streams (stride 8 = 2 floats). Interleaving them would need
        // stride 16 and a single buffer, which is not what the two attrib locations above expect.
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, positionBuffer())
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 8, uvBuffer())
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
    }

    private fun initGl(inW: Int, inH: Int, outW: Int, outH: Int) {
        val ids = IntArray(2)
        GLES20.glGenFramebuffers(1, ids, 0)
        readFboId = ids[0]
        GLES20.glGenTextures(1, ids, 1)
        srcTexId = ids[1]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outW, outH, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        outTexId = srcTexId

        programId = compile()
    }

    private fun compile(): Int {
        val vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX_SRC)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glBindAttribLocation(p, 1, "aUv")
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    // Triangle-strip corners, counter-clockwise from bottom-left.
    private val positions = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

    // V is flipped relative to `positions`: the upload buffer is stored top-row-first, so texture
    // v=0 is the image top while the quad maps v=0 to the screen bottom. See uploadAndDraw().
    private val uvs = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

    private fun positionBuffer() = positions.also { it.position(0) }
    private fun uvBuffer() = uvs.also { it.position(0) }

    private fun floatBuffer(values: FloatArray): java.nio.FloatBuffer =
        java.nio.ByteBuffer.allocateDirect(values.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .also { it.position(0) }

    private companion object {
        const val VERTEX_SRC = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            varying vec2 vUv;
            void main() { gl_Position = aPos; vUv = aUv.xy; }
        """

        // Passthrough. Sharpening and temporal stabilisation are separate effects further down the
        // chain, so that each stage is independently measurable in the Phase 0 harness.
        const val FRAGMENT_SRC = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """
    }
}
