package com.videoupscaler.ai.pipeline

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.media3.common.Composition
import androidx.media3.common.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import com.videoupscaler.ai.R
import java.io.File

/**
 * Runs an offline export: decode -> shared effect chain -> encode -> mux, with audio preserved.
 *
 * UI-agnostic, and deliberately NOT owned by an Activity. An export takes minutes, so the instance
 * lives at process scope (see UpScalerApp); Activities attach and detach a listener instead of
 * owning the work. Phase 3 should wrap this in a `mediaProcessing` foreground service rather than
 * replace it.
 *
 * Recorded decisions:
 *  - **H.264, not H.265.** PLAN.md 6.2 showed `VIDEO_H265`. HEVC is not universally encodable and a
 *    first build should not fail on a codec gap.
 *  - **HDR mode is left at the default (`HDR_MODE_KEEP_HDR`) on purpose.** `setHdrMode` lives on
 *    `Composition.Builder`, not `Transformer.Builder`, so it is unreachable through the
 *    `start(EditedMediaItem, path)` overload used here. More importantly the default already falls
 *    back to OpenGL tone-mapping where HDR editing is unsupported, and forcing
 *    `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` would throw on API 24-28 (minSdk is 24). The
 *    fallback is surfaced through [State.Notice] rather than left silent.
 *  - **Audio is re-encoded to AAC**, not passed through. Explicit is safer than relying on
 *    unspecified copy behaviour; a silently silent output is the one failure that must not recur.
 */
@UnstableApi
class VideoExporter(private val context: Context) {

    sealed interface State {
        /** Nothing has run yet. */
        object Idle : State

        data class Running(val percent: Int) : State

        data class Completed(val outputPath: String) : State

        data class Failed(val message: String) : State

        /** User cancelled. Distinct from [Idle] so a recreated Activity does not read it as "done". */
        object Cancelled : State

        /** Informational; not replayed on re-attach. */
        data class Notice(val message: String) : State
    }

    fun interface Listener {
        fun onStateChanged(state: State)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var outputPath: String? = null
    private var listener: Listener? = null

    /** Replayed to a re-attaching listener so a recreated Activity shows current progress. */
    var lastState: State = State.Idle
        private set

    val isRunning: Boolean get() = transformer != null

    /** Binds a listener and replays the current state. */
    fun attach(listener: Listener) {
        this.listener = listener
        // Notices are transient; replaying a stale one after rotation would be misleading.
        if (lastState !is State.Notice) listener.onStateChanged(lastState)
    }

    /** Unbinds without disturbing a running export. */
    fun detach() {
        listener = null
    }

    /**
     * Starts an asynchronous export of [inputUri]. Returns immediately.
     *
     * No-op if an export is already running. Emits [State.Failed] synchronously if there is not
     * enough free space for a plausible output.
     */
    fun start(inputUri: Uri, target: UpscaleChain.Target, listener: Listener) {
        if (isRunning) return
        this.listener = listener

        val output = outputFile(target)
        val dir = output.parentFile ?: context.filesDir

        storageShortfall(inputUri, dir)?.let {
            emit(State.Failed(it))
            return
        }

        val path = output.absolutePath
        outputPath = path

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
                            emit(State.Completed(path))
                            reset()
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            // Transformer does not delete the partial output on error.
                            deletePartial()
                            emit(State.Failed("${exportException.errorCode}: ${exportException.message}"))
                            reset()
                        }

                        /**
                         * Transformer changed the output resolution or codec to satisfy a device
                         * constraint. Surfaced rather than swallowed: a silent quality change reads
                         * as a bug (DESIGN.md D7).
                         */
                        override fun onFallbackApplied(
                            composition: Composition,
                            original: TransformationRequest,
                            fallback: TransformationRequest,
                        ) {
                            emit(State.Notice(context.getString(R.string.notice_fallback)))
                        }
                    }
                )
                .build()

        transformer = instance
        emit(State.Running(0))
        instance.start(editedMediaItem, path)
        pollProgress(instance)
    }

    /** Cancels and discards partial output. Irreversible. */
    fun cancel() {
        if (transformer == null) return
        transformer?.cancel()
        deletePartial()
        reset()
        emit(State.Cancelled)
    }

    /** Tears down. Does NOT cancel a running export — use [cancel] for that. */
    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        listener = null
    }

    // --- internals ---------------------------------------------------------------

    private fun pollProgress(instance: Transformer) {
        val holder = ProgressHolder()
        val poll =
            object : Runnable {
                override fun run() {
                    val state = instance.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        emit(State.Running(holder.progress))
                    }
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        handler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
            }
        handler.post(poll)
    }

    private fun emit(state: State) {
        lastState = state
        listener?.onStateChanged(state)
    }

    private fun reset() {
        handler.removeCallbacksAndMessages(null)
        transformer = null
        outputPath = null
    }

    private fun deletePartial() {
        val path = outputPath ?: return
        runCatching { File(path).delete() }
    }

    /**
     * Upscaled output at a similar bitrate is larger than the input; 2x is a deliberately
     * conservative guess. Returns a user-facing message when there is not enough room, or null.
     *
     * An unknown input size is treated as "allow" — refusing to start on a query failure would be
     * worse than letting Transformer surface its own IO error.
     */
    private fun storageShortfall(uri: Uri, dir: File): String? {
        val inputBytes = inputSizeBytes(uri) ?: return null
        val needed = inputBytes * OUTPUT_SIZE_MULTIPLIER
        val free = dir.usableSpace
        return if (free < needed) {
            context.getString(R.string.error_storage, mb(needed), mb(free))
        } else {
            null
        }
    }

    private fun inputSizeBytes(uri: Uri): Long? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else null
            }
        }.getOrNull()

    /**
     * App-specific external storage: needs no runtime permission and survives until uninstall.
     * A shared gallery location needs MediaStore and is out of scope for Phase 1.
     */
    private fun outputFile(target: UpscaleChain.Target): File {
        val dir =
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.apply { mkdirs() }
                ?: context.filesDir
        return File(dir, "upscaled_${target.shortEdge}p_${System.currentTimeMillis()}.mp4")
    }

    private fun mb(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

    private companion object {
        const val POLL_INTERVAL_MS = 500L
        const val OUTPUT_SIZE_MULTIPLIER = 2L
    }
}
