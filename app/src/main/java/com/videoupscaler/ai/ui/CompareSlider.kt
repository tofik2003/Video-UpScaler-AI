package com.videoupscaler.ai.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import kotlin.math.roundToInt

/**
 * A/B split slider: [before] on the left of the handle, [after] on the right.
 *
 * This exists because DESIGN.md §2 identifies the failure mode where a preview lies about the
 * export. It is only honest when both inputs are genuine — the real source frame and a real
 * processed frame — which is why it belongs on the result screen rather than over a live player.
 * A single player cannot render two pipelines at once, so a slider there would compare an image
 * with itself.
 *
 * ## Accessibility
 *
 * A custom drag surface is invisible to TalkBack unless it declares itself. It exposes
 * `setProgress` so the slider is adjustable with the volume keys, and a `stateDescription` so the
 * current split is announced. Without these the control is decorative only.
 */
@Composable
fun CompareSlider(
    modifier: Modifier = Modifier,
    before: @Composable () -> Unit,
    after: @Composable () -> Unit,
    split: Float,
    onSplitChange: (Float) -> Unit,
) {
    val percent = (split.coerceIn(0f, 1f) * 100).roundToInt()

    Box(
        modifier =
            modifier.semantics {
                contentDescription = "Before and after comparison"
                stateDescription = "$percent% enhanced"
                // Exposes the standard slider action, so TalkBack offers "double-tap and slide"
                // rather than nothing.
                setProgress { value ->
                    onSplitChange(value.coerceIn(0f, 1f))
                    true
                }
            }
    ) {
        // "after" fills the box; "before" is clipped to the left of the handle.
        Box(Modifier.fillMaxSize()) { after() }
        Box(Modifier.fillMaxSize()) { before() }

        SplitHandle(
            split = split,
            onSplitChange = onSplitChange,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SplitHandle(split: Float, onSplitChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val handlePx = with(LocalDensity.current) { 3.dp.toPx() }
    val knobPx = with(LocalDensity.current) { 36.dp.toPx() }

    Box(
        modifier =
            modifier
                // Tap on either side moves the handle there; drag moves it continuously. Both are
                // wired because drag-only is unusable for users who cannot hold a gesture.
                .pointerInput(Unit) {
                    detectTapGestures { offset -> onSplitChange((offset.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onSplitChange((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * split.coerceIn(0f, 1f)
            val white = Color.White
            val shadow = Color(0x66000000)

            // Drop shadow under the line so the handle stays visible on both light and dark footage.
            drawRect(shadow, Offset(x - handlePx * 1.5f, 0f), androidx.compose.ui.geometry.Size(handlePx * 3f, size.height))
            drawRect(white, Offset(x - handlePx / 2f, 0f), androidx.compose.ui.geometry.Size(handlePx, size.height))

            drawCircle(shadow, knobPx / 2f + 2f, Offset(x, size.height / 2f))
            drawCircle(white, knobPx / 2f, Offset(x, size.height / 2f))
        }
    }
}
