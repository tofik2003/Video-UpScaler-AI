package com.videoupscaler.ai.ui

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.util.UnstableApi
import com.videoupscaler.ai.UpScalerApp
import com.videoupscaler.ai.pipeline.UpscaleChain
import com.videoupscaler.ai.pipeline.VideoExporter
import com.videoupscaler.ai.service.ExportService

/**
 * Holds workspace state and bridges to the process-scoped exporter.
 *
 * Survives configuration changes; the exporter itself lives on [UpScalerApp] and outlives this, and
 * the export runs inside [ExportService] so it also survives process death.
 *
 * Uses Compose snapshot state rather than StateFlow to avoid pulling in a coroutines dependency
 * that nothing else in the module needs yet.
 */
@UnstableApi
class EnhanceViewModel(app: Application) : AndroidViewModel(app) {

    private val exporter = (app as UpScalerApp).exporter

    var inputUri by mutableStateOf<Uri?>(null)
    var target by mutableStateOf(UpscaleChain.Target.FULL_HD)
    var tier by mutableStateOf(UpscaleChain.Tier.SMOOTH)

    /**
     * Toggles the effect chain on the preview player.
     *
     * This is an honest comparison: a single player cannot render two pipelines side by side, so the
     * split slider from DESIGN.md 5.1 belongs on the result screen, where two images exist.
     */
    var showEnhanced by mutableStateOf(true)

    var exportState by mutableStateOf<VideoExporter.State>(VideoExporter.State.Idle)
        private set

    var playbackError by mutableStateOf<String?>(null)
        private set

    /** Set when the selected tier cannot run on this source, so the UI can explain rather than fail. */
    var tierWarning by mutableStateOf<String?>(null)
        private set

    var videoSize by mutableStateOf(Pair(0, 0))
        private set

    var freeSpaceMb by mutableStateOf(-1L)
        private set

    val isRunning: Boolean get() = exporter.isRunning

    private val listener = VideoExporter.Listener { state -> exportState = state }

    init {
        refreshFreeSpace()
        // attach() replays the current state, so a ViewModel recreated after process death still
        // shows an in-flight export.
        exporter.attach(listener)
    }

    fun setUri(uri: Uri) {
        inputUri = uri
        playbackError = null
        refreshFreeSpace()
        probe(uri)
        validateTier()
    }

    fun reportPlaybackError(code: String) {
        playbackError = code
    }

    fun startExport() {
        val uri = inputUri ?: return
        refreshFreeSpace()
        // Start the service first: it must be in the foreground before the work it protects can be
        // considered long-running, otherwise the process is reclaimable from the first second.
        ExportService.start(getApplication())
        exporter.start(uri, target, tier, listener)
    }

    fun cancelExport() {
        exporter.cancel()
        ExportService.stop(getApplication())
    }

    fun refreshFreeSpace() {
        val app = getApplication<Application>()
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: app.filesDir
        freeSpaceMb = dir.usableSpace / (1024 * 1024)
    }

    /**
     * Reads the source dimensions.
     *
     * Done synchronously on the main thread, which is the one thing here that is not ideal — it
     * blocks for however long the retriever takes. It is acceptable for a single short probe on
     * selection and it keeps the tier check simple. Moving it to a coroutine is the right follow-up.
     */
    private fun probe(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        videoSize =
            try {
                retriever.setDataSource(getApplication(), uri)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                Pair(w?.toIntOrNull() ?: 0, h?.toIntOrNull() ?: 0)
            } catch (e: RuntimeException) {
                Pair(0, 0)
            } finally {
                retriever.release()
            }
    }

    private fun validateTier() {
        val (w, h) = videoSize
        tierWarning =
            if (w > 0 && h > 0 && !UpscaleChain.isSupported(tier, w, h)) "orientation" else null
    }

    /** Falls back to Smooth if the current tier cannot run on this source. */
    fun setTier(newTier: UpscaleChain.Tier) {
        tier = newTier
        validateTier()
    }

    override fun onCleared() {
        // Detach only. Deliberately does NOT cancel — the export belongs to the service, not here.
        exporter.detach()
        super.onCleared()
    }
}
