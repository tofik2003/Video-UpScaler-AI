package com.videoupscaler.ai.ui

import android.app.Application
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

/**
 * Holds workspace state and bridges to the process-scoped exporter.
 *
 * Survives configuration changes; the exporter itself lives on [UpScalerApp] and outlives this, so
 * an in-flight export is not tied to the ViewModel's lifetime either.
 *
 * Uses Compose snapshot state rather than StateFlow to avoid pulling in a coroutines dependency
 * that nothing else in the module needs yet.
 */
@UnstableApi
class EnhanceViewModel(app: Application) : AndroidViewModel(app) {

    private val exporter = (app as UpScalerApp).exporter

    var inputUri by mutableStateOf<Uri?>(null)
    var target by mutableStateOf(UpscaleChain.Target.FULL_HD)

    /**
     * Toggles the effect chain on the preview player.
     *
     * This is an honest comparison, not the split slider from DESIGN.md 5.1: a single player cannot
     * show two pipelines side by side. The real slider belongs on the result screen (or on the
     * single-frame true-quality preview, Phase 2), where two images actually exist.
     */
    var showEnhanced by mutableStateOf(true)

    var exportState by mutableStateOf<VideoExporter.State>(VideoExporter.State.Idle)
        private set

    var playbackError by mutableStateOf<String?>(null)
        private set

    /** Free space in the output directory, in MB. Recomputed per export attempt. */
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
    }

    fun reportPlaybackError(code: String) {
        playbackError = code
    }

    fun startExport() {
        val uri = inputUri ?: return
        refreshFreeSpace()
        exporter.start(uri, target, listener)
    }

    fun cancelExport() = exporter.cancel()

    fun refreshFreeSpace() {
        val dir =
            getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: getApplication<Application>().filesDir
        freeSpaceMb = dir.usableSpace / (1024 * 1024)
    }

    override fun onCleared() {
        // Detach only. Deliberately does NOT cancel — see UpScalerApp.
        exporter.detach()
        super.onCleared()
    }
}
