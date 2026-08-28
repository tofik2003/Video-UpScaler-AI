package com.videoupscaler.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import com.videoupscaler.ai.ui.EnhanceScreen
import com.videoupscaler.ai.ui.EnhanceViewModel
import com.videoupscaler.ai.ui.theme.UpScalerTheme

/**
 * Single-activity host. All UI is Compose; the pipeline classes in `pipeline/` are UI-agnostic and
 * are reached only through [EnhanceViewModel].
 *
 * State lives in the ViewModel, so it survives configuration changes. The URI is additionally
 * written to the instance state because a ViewModel does not survive process death.
 */
@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    // ViewModelProvider rather than `by viewModels()` to avoid depending on activity-ktx, which
    // nothing else in the module needs.
    private val model: EnhanceViewModel by lazy {
        ViewModelProvider(this)[EnhanceViewModel::class.java]
    }

    /** SAF picker. Grants read access without any storage permission. */
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
            model.setUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.getParcelable<Uri>(KEY_INPUT_URI)?.let { model.setUri(it) }

        setContent {
            UpScalerTheme {
                EnhanceScreen(
                    viewModel = model,
                    onPickVideo = { pickVideo.launch(arrayOf("video/*")) },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        model.inputUri?.let { outState.putParcelable(KEY_INPUT_URI, it) }
    }

    private companion object {
        const val KEY_INPUT_URI = "input_uri"
    }
}
