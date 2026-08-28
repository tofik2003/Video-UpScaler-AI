package com.videoupscaler.ai.ai

import android.content.Context
import androidx.media3.common.util.UnstableApi
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

/**
 * Wraps a LiteRT interpreter for one ESPCN model.
 *
 * ## Package naming
 *
 * The Maven coordinates are `com.google.ai.edge.litert:*`, but the **Java package is still
 * `org.tensorflow.lite`**. The group moved; the package did not. Writing
 * `com.google.ai.edge.litert.gpu.GpuDelegate` compiles against nothing.
 *
 * ## Why one interpreter per shader program
 *
 * The GPU delegate requires the EGL context to be current on the thread that creates it, and
 * [androidx.media3.effect.BaseGlShaderProgram] documents that all its methods run on the thread
 * owning the GL context. Preview and export each get their own shader program on their own thread,
 * so sharing one interpreter between them would be a threading bug. At ~56 KB per model the
 * duplication costs nothing.
 *
 * ## Why buffers, not arrays
 *
 * `Interpreter.run` accepts arrays or `java.nio.Buffer`. It does **not** accept `HardwareBuffer` —
 * that is a common misconception, and attempting it throws. A direct `FloatBuffer` also avoids a
 * copy versus a boxed array. True zero-copy against a GL texture needs the C++ `CompiledModel`
 * path; see PLAN.md §6.5 (Phase 6).
 */
@UnstableApi
class UpscaleEngine private constructor(
    private val interpreter: Interpreter,
    private val delegate: GpuDelegate?,
    val scale: Int,
    val inputWidth: Int,
    val inputHeight: Int,
) : Closeable {

    enum class Backend { GPU, CPU }

    val backend: Backend = if (delegate != null) Backend.GPU else Backend.CPU
    val outputWidth: Int = inputWidth * scale
    val outputHeight: Int = inputHeight * scale

    private val inputBuffer: ByteBuffer = directFloatBuffer(inputWidth * inputHeight * 3)
    private val outputBuffer: ByteBuffer = directFloatBuffer(outputWidth * outputHeight * 3)

    /** Rewinds [inputBuffer] and returns it, ready to be filled row-major RGB in 0..1. */
    fun inputBuffer(): ByteBuffer = inputBuffer.clear()

    /** Rewinds [outputBuffer] and returns it, ready to receive row-major RGB in 0..1. */
    fun outputBuffer(): ByteBuffer = outputBuffer.clear()

    /** Runs one frame. Buffers are rewound before and after, so callers need not manage position. */
    fun run() {
        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        inputBuffer.rewind()
        outputBuffer.rewind()
    }

    override fun close() {
        interpreter.close()
        delegate?.close()
    }

    /**
     * Loads [assetName] from `assets/`. Must be called on the thread that will run inference, with
     * that thread's EGL context current, if the GPU delegate is to be usable.
     */
    companion object {
        fun create(context: Context, assetName: String, scale: Int = 2): UpscaleEngine {
            val model = loadModel(context, assetName)

            // CompatibilityList is Closeable — it holds native resources. Close it on every path.
            val compatList = CompatibilityList()
            var delegate: GpuDelegate? = null
            val options =
                Interpreter.Options().apply {
                    try {
                        if (compatList.isDelegateSupportedOnThisDevice) {
                            delegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                            addDelegate(delegate)
                        } else {
                            setNumThreads(4)
                        }
                    } finally {
                        compatList.close()
                    }
                }

            val interpreter =
                try {
                    Interpreter(model, options)
                } catch (e: RuntimeException) {
                    // Risk R7: delegate init throws on specific GPU drivers. Falling back to CPU is
                    // correct; silently producing a black frame is not.
                    delegate?.close()
                    delegate = null
                    Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
                }

            val shape = interpreter.getInputTensor(0).shape()  // [1, H, W, 3]
            require(shape.size == 4 && shape[3] == 3) {
                "expected NHWC input, got ${shape.contentToString()}"
            }
            return UpscaleEngine(interpreter, delegate, scale, shape[2], shape[1])
        }

        private fun loadModel(context: Context, assetName: String): MappedByteBuffer {
            val fd = context.assets.openFd(assetName)
            return fd.use {
                java.io.FileInputStream(it.fileDescriptor).use { stream ->
                    stream.channel.map(
                        java.nio.channels.FileChannel.MapMode.READ_ONLY,
                        it.startOffset,
                        it.declaredLength,
                    )
                }
            }
        }

        private fun directFloatBuffer(floats: Int): ByteBuffer =
            ByteBuffer.allocateDirect(floats * java.lang.Float.BYTES)
                .order(ByteOrder.nativeOrder())
    }
}
