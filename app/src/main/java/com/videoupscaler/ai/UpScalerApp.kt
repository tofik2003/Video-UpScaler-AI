package com.videoupscaler.ai

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.videoupscaler.ai.pipeline.VideoExporter

/**
 * Holds the exporter at process scope.
 *
 * An export takes minutes, so it must not be owned by an Activity: rotating the phone, or Android
 * reclaiming a backgrounded Activity, used to call through to `Transformer.cancel()` and destroy a
 * job the user was waiting on.
 *
 * This is a stopgap, not the Phase 3 answer. Without a `mediaProcessing` foreground service the
 * process can still be killed while backgrounded, and the export dies with it. What this fixes is
 * the common case: rotation and brief app-switching no longer cancel the job.
 */
@UnstableApi
class UpScalerApp : Application() {

    val exporter: VideoExporter by lazy { VideoExporter(this) }
}
