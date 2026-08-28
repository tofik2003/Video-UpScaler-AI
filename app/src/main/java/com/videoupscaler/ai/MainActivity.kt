package com.videoupscaler.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.videoupscaler.ai.pipeline.UpscaleChain
import com.videoupscaler.ai.pipeline.VideoExporter

/**
 * Phase 1 workspace: pick a video, preview it through the effect chain, export it.
 *
 * Plain Views rather than Compose — a deliberate deferral. Compose needs the Kotlin Compose compiler
 * plugin, and its interaction with AGP 9's built-in Kotlin support could not be verified here. The
 * pipeline classes are UI-agnostic so the DESIGN.md rewrite need not touch them.
 */
@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var enhanceButton: Button
    private lateinit var playerView: PlayerView

    private var player: ExoPlayer? = null
    private var inputUri: Uri? = null

    /** Process-scoped, so an in-flight export survives this Activity being recreated. */
    private val exporter: VideoExporter
        get() = (application as UpScalerApp).exporter

    private val stateListener =
        VideoExporter.Listener { state -> renderExportState(state) }

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            // Persist the grant so a queued export survives process death and reboot.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            inputUri = uri
            loadPreview(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
            }

        statusText = TextView(this).apply { text = getString(R.string.status_no_video) }
        root.addView(statusText)

        playerView = PlayerView(this)
        root.addView(
            playerView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                /* weight = */ 1f,
            ),
        )

        // A bare Spinner with no caption is unusable, and a contentDescription alone is not a label.
        root.addView(
            TextView(this).apply {
                text = getString(R.string.label_resolution)
                setPadding(0, 16, 0, 0)
            }
        )

        resolutionSpinner =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        UpscaleChain.Target.entries.map { it.label },
                    )
                setSelection(UpscaleChain.Target.FULL_HD.ordinal)
                contentDescription = getString(R.string.label_resolution)
                onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long,
                        ) {
                            // Without this the preview keeps the old target while the export uses
                            // the new one — exactly the preview/export divergence DESIGN.md 2 exists
                            // to prevent.
                            reapplyPreviewEffects()
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }
            }
        root.addView(resolutionSpinner)

        root.addView(
            Button(this).apply {
                text = getString(R.string.action_pick_video)
                setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
            }
        )

        enhanceButton =
            Button(this).apply {
                text = getString(R.string.action_enhance)
                isEnabled = false
                setOnClickListener { startExport() }
            }
        root.addView(enhanceButton)

        setContentView(root)

        inputUri = savedInstanceState?.getParcelable<Uri>(KEY_INPUT_URI)
        inputUri?.let { loadPreview(it, autoplay = false) }
    }

    override fun onStart() {
        super.onStart()
        // Attach late, detach early: the exporter replays its current state so a recreated
        // Activity resumes showing progress instead of a blank slate.
        exporter.attach(stateListener)
    }

    override fun onStop() {
        exporter.detach()
        player?.pause()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        inputUri?.let { outState.putParcelable(KEY_INPUT_URI, it) }
    }

    override fun onDestroy() {
        // Deliberately does NOT cancel the export. See UpScalerApp.
        player?.release()
        player = null
        super.onDestroy()
    }

    // --- internals ---------------------------------------------------------------

    private fun selectedTarget(): UpscaleChain.Target =
        UpscaleChain.Target.entries[resolutionSpinner.selectedItemPosition]

    private fun loadPreview(uri: Uri, autoplay: Boolean = true) {
        player?.release()

        val instance = ExoPlayer.Builder(this).build()
        // setVideoEffects must be called before prepare().
        instance.setVideoEffects(UpscaleChain.build(selectedTarget()))
        instance.setMediaItem(MediaItem.fromUri(uri))
        instance.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Without this an unsupported file is a black rectangle and a live Enhance
                    // button that then fails.
                    // NB: errorCodeName is a FUNCTION here, not a Kotlin property — Media3
                    // overloads getErrorCodeName(int) statically, which suppresses property
                    // synthesis. The same trap exists on ExportException.
                    statusText.text = getString(R.string.error_playback, error.getErrorCodeName())
                    enhanceButton.isEnabled = false
                }
            }
        )
        instance.prepare()
        instance.playWhenReady = autoplay

        playerView.player = instance
        player = instance

        statusText.text = getString(R.string.status_loaded)
        enhanceButton.isEnabled = true
    }

    private fun reapplyPreviewEffects() {
        val instance = player ?: return
        instance.setVideoEffects(UpscaleChain.build(selectedTarget()))
    }

    private fun startExport() {
        val uri = inputUri ?: return
        enhanceButton.isEnabled = false
        exporter.start(uri, selectedTarget(), stateListener)
    }

    private fun renderExportState(state: VideoExporter.State) {
        when (state) {
            is VideoExporter.State.Running -> {
                statusText.text = getString(R.string.status_exporting, state.percent)
                enhanceButton.isEnabled = false
            }

            is VideoExporter.State.Completed -> {
                statusText.text = getString(R.string.status_done, state.outputPath)
                enhanceButton.isEnabled = true
            }

            is VideoExporter.State.Failed -> {
                statusText.text = getString(R.string.status_failed, state.message)
                enhanceButton.isEnabled = true
            }

            is VideoExporter.State.Notice ->
                statusText.text = getString(R.string.status_notice, state.message)

            VideoExporter.State.Cancelled -> {
                statusText.text = getString(R.string.status_cancelled)
                enhanceButton.isEnabled = true
            }

            // Replayed on attach when nothing is in flight; leave the existing status alone.
            VideoExporter.State.Idle ->
                if (inputUri == null) {
                    statusText.text = getString(R.string.status_no_video)
                }
        }
    }

    private companion object {
        const val KEY_INPUT_URI = "input_uri"
    }
}
