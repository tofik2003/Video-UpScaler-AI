package com.videoupscaler.ai.pipeline

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.common.Composition
import java.io.File

/**
 * Runs an offline export: decode -> shared effect chain -> encode -> mux, with audio preserved.
 *
 * UI-agnostic by design. Phase 3 moves the work behind a `mediaProcessing` foreground service;
 * that should wrap this class rather than replace it.
 *
 * Deliberate choices, both recorded because they deviate from or refine PLAN.md:
 *  - **H.264, not H.265.** PLAN.md 6.2 showed `VIDEO_H265`. H.264 is universally encodable; HEVC is
 *    not, and a first build should not fail on a codec gap. Revisit once `onFallbackApplied` is
 *    exercised on a device matrix.
 *  - **Audio is re-encoded to AAC**, not passed through. Explicit is safer than relying on
 *    unspecified copy behaviour. v1 silently produced silent video because audio was never
 *    considered at all; that failure mode must stay impossible.
 */
@UnstableApi
class VideoExporter(private val context: Context) {

    /** Coarse progress state. Percentages lie when frame cost varies, so this is advisory. */
    sealed interface State {
        object Idle : State
        data class Running(val percent: Int) : State
        data class Completed(val outputPath: String) : State
        data class Failed(val message: String) : State
    }

    fun interface Listener {
        fun onStateChanged(state: State)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var listener: Listener? = null

    val isRunning: Boolean get() = transformer != null

    /**
     * Starts an asynchronous export of [inputUri]. Returns immediately.
     *
     * Only one export runs at a time; calling this while [isRunning] is a no-op.
     */
    fun start(inputUri: Uri, target: UpscaleChain.Target, listener: Listener) {
        if (isRunning) return
        this.listener = listener

        val outputPath = outputFile(target).absolutePath

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            // Effects go on the EditedMediaItem. `Transformer.Builder.setVideoEffects` was
            // REMOVED in Media3 1.6.0 and will not compile on 1.11.0.
            .setEffects(
                Effects(
                    /* audioProcessors = */ listOf(),
                    /* videoEffects = */ UpscaleChain.build(target),
                )
            )
            .build()

        val instance =
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            finish(State.Completed(outputPath))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            // Transformer does not delete the partial output on error.
                            runCatching { File(outputPath).delete() }
                            // `errorCode` is a public field. Note that the error *name* is a
                            // method (getErrorCodeName), not a field.
                            finish(State.Failed("${exportException.errorCode}: ${exportException.message}"))
                        }
                    }
                )
                .build()

        transformer = instance
        instance.start(editedMediaItem, outputPath)
        pollProgress(instance)
    }

    /** Cancels and discards partial output. Irreversible by design. */
    fun cancel() {
        val instance = transformer ?: return
        instance.cancel()
        finish(State.Idle)
    }

    fun release() {
        transformer?.cancel()
        transformer = null
        listener = null
        handler.removeCallbacksAndMessages(null)
    }

    // --- internals ---------------------------------------------------------------

    private fun pollProgress(instance: Transformer) {
        val holder = ProgressHolder()
        val poll =
            object : Runnable {
                override fun run() {
                    val state = instance.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        listener?.onStateChanged(State.Running(holder.progress))
                    }
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        handler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
            }
        handler.post(poll)
    }

    private fun finish(state: State) {
        handler.removeCallbacksAndMessages(null)
        transformer = null
        listener?.onStateChanged(state)
    }

    /**
     * App-specific external storage: needs no runtime permission and survives until uninstall.
     * Writing to a shared gallery location requires MediaStore and is out of scope for Phase 1.
     */
    private fun outputFile(target: UpscaleChain.Target): File {
        val dir =
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.apply { mkdirs() }
                ?: context.filesDir
        return File(dir, "upscaled_${target.shortEdge}p_${System.currentTimeMillis()}.mp4")
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
    }
}
