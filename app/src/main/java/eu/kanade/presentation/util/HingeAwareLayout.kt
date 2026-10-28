package eu.kanade.presentation.util

// KMK -->
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlin.math.roundToInt
// KMK <--

// KMK -->
@Composable
internal fun rememberVerticalWindowHinge(): VerticalWindowHinge? {
    val activity = LocalActivity.current ?: return null
    val lifecycle = LocalLifecycleOwner.current
    val hinge by produceState<VerticalWindowHinge?>(null, activity, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                value = info.displayFeatures.filterIsInstance<FoldingFeature>()
                    .firstOrNull { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }
                    ?.bounds?.let { VerticalWindowHinge(it.left, it.top, it.right, it.bottom) }
            }
        }
    }
    return hinge
}

@Composable
internal fun HingeAwareTwoPaneLayout(
    hinge: VerticalWindowHinge?,
    modifier: Modifier = Modifier,
    startContent: @Composable () -> Unit,
    endContent: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Layout(
        modifier = modifier.fillMaxSize().onGloballyPositioned {
            val position = it.positionInWindow()
            origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
        },
        content = {
            Box { startContent() }
            Box { endContent() }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val bounds = HingePaneBounds.resolve(width, height, origin.x, origin.y, hinge)
        val start = if (rtl) bounds.right else bounds.left
        val end = if (rtl) bounds.left else bounds.right
        val startPlaceable = measurables[0].measure(Constraints.fixed(start.width, height))
        val endPlaceable = measurables[1].measure(Constraints.fixed(end.width, height))
        layout(width, height) {
            startPlaceable.place(start.left, 0)
            endPlaceable.place(end.left, 0)
        }
    }
}

/** Wrapping-height content in physical display regions; one child uses the full width when unspanned. */
@Composable
internal fun HingeAwarePaneRow(
    hinge: VerticalWindowHinge?,
    modifier: Modifier = Modifier,
    rightContent: (@Composable () -> Unit)? = null,
    leftContent: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    Layout(
        modifier = modifier.fillMaxWidth().onGloballyPositioned {
            val position = it.positionInWindow()
            origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
        },
        content = {
            Box { leftContent() }
            if (rightContent != null) Box { rightContent() }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val bounds = HingePaneBounds.resolve(width, 1, origin.x, origin.y, hinge)
        val left = if (rightContent == null && !bounds.hasHinge) PaneBounds(0, width) else bounds.left
        val leftPlaceable = measurables[0].measure(
            constraints.copy(minWidth = left.width, maxWidth = left.width, minHeight = 0),
        )
        val rightPlaceable = measurables.getOrNull(1)?.measure(
            constraints.copy(minWidth = bounds.right.width, maxWidth = bounds.right.width, minHeight = 0),
        )
        val height = constraints.constrainHeight(maxOf(leftPlaceable.height, rightPlaceable?.height ?: 0))
        layout(width, height) {
            leftPlaceable.place(left.left, (height - leftPlaceable.height) / 2)
            rightPlaceable?.let { it.place(bounds.right.left, (height - it.height) / 2) }
        }
    }
}

/** A wrapping-height action bar aligned with the chapter/details pane. */
@Composable
internal fun HingeAwareEndPane(
    hinge: VerticalWindowHinge?,
    content: @Composable BoxScope.() -> Unit,
) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Layout(
        modifier = Modifier.fillMaxWidth().onGloballyPositioned {
            val position = it.positionInWindow()
            origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
        },
        content = { Box(content = content) },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val bounds = HingePaneBounds.resolve(width, 1, origin.x, origin.y, hinge)
        val pane = if (rtl) bounds.left else bounds.right
        val placeable = measurables.single().measure(
            constraints.copy(minWidth = pane.width, maxWidth = pane.width),
        )
        layout(width, placeable.height) { placeable.place(pane.left, 0) }
    }
}

/** Keep a settings sheet on the details display; outside taps on either display dismiss it. */
@Composable
internal fun HingeAwareSettingsSheet(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val hinge = rememberVerticalWindowHinge()
    if (hinge == null) {
        content()
        return
    }
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Layout(
        modifier = Modifier.fillMaxSize()
            .clickable(interactionSource = null, indication = null, onClick = onDismissRequest)
            .onGloballyPositioned {
                val position = it.positionInWindow()
                origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
            },
        content = { Box { content() } },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val bounds = HingePaneBounds.resolve(width, height, origin.x, origin.y, hinge)
        val pane = when {
            !bounds.hasHinge -> PaneBounds(0, width)
            rtl -> bounds.left
            else -> bounds.right
        }
        val placeable = measurables.single().measure(Constraints.fixed(pane.width, height))
        layout(width, height) { placeable.place(pane.left, 0) }
    }
}
// KMK <--
