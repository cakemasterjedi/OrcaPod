package org.orcapod.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * The fold of a foldable as the plate screen needs it: the posture, and
 * where the hinge is in window pixels while it separates the screen.
 */
data class FoldState(val posture: Posture, val hinge: Rect?) {
    companion object {
        val NONE = FoldState(Posture.FLAT, null)
    }
}

/**
 * Follows the window's folding feature through Jetpack WindowManager. A
 * phone, a tablet or a foldable held flat reports [FoldState.NONE]; a
 * foldable half open reports tabletop or book posture with its hinge.
 */
@Composable
fun rememberFoldState(): FoldState {
    val activity = LocalContext.current.findActivity() ?: return FoldState.NONE
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsStateWithLifecycle(initialValue = null)
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull() ?: return FoldState.NONE
    return foldState(
        halfOpened = fold.state == FoldingFeature.State.HALF_OPENED,
        separating = fold.isSeparating,
        horizontal = fold.orientation == FoldingFeature.Orientation.HORIZONTAL,
        bounds = fold.bounds,
    )
}

/** The posture of a fold: only a half-open, separating fold changes the layout. */
internal fun foldState(halfOpened: Boolean, separating: Boolean, horizontal: Boolean, bounds: Rect): FoldState =
    if (!halfOpened || !separating) FoldState.NONE
    else FoldState(if (horizontal) Posture.TABLETOP else Posture.BOOK, bounds)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
