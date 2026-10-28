package eu.kanade.presentation.reader

// KMK -->
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.HingeAwarePaneRow
import eu.kanade.presentation.util.rememberVerticalWindowHinge
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
// KMK <--

// KMK -->
@Composable
internal fun ReaderPageIndicators(
    viewer: PagerViewer?,
    currentPage: String,
    totalPages: Int,
    modifier: Modifier = Modifier,
) {
    if (viewer == null) {
        ReaderPageIndicator(currentPage, totalPages, modifier)
        return
    }
    val numbers by viewer.pageNumbers.collectAsState()
    val spread = numbers
    if (spread == null) {
        ReaderPageIndicator(currentPage, totalPages, modifier)
        return
    }
    HingeAwarePaneRow(
        hinge = rememberVerticalWindowHinge(),
        modifier = modifier,
        rightContent = {
            spread.right?.let { ReaderPageIndicator("${it.page}", it.total, Modifier.fillMaxWidth()) }
        },
    ) {
        spread.left?.let { ReaderPageIndicator("${it.page}", it.total, Modifier.fillMaxWidth()) }
    }
}
// KMK <--

@Composable
fun ReaderPageIndicator(
    // SY -->
    currentPage: String,
    // SY <--
    totalPages: Int,
    modifier: Modifier = Modifier,
) {
    if (currentPage.isEmpty() || totalPages <= 0) return

    val text = "$currentPage / $totalPages"

    val style = TextStyle(
        // KMK -->
        color = MaterialTheme.colorScheme.primary,
        // KMK <--
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    val strokeStyle = style.copy(
        color = Color(45, 45, 45),
        drawStyle = Stroke(width = 4f),
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = strokeStyle,
        )
        Text(
            text = text,
            style = style,
        )
    }
}

@PreviewLightDark
@Composable
private fun ReaderPageIndicatorPreview() {
    TachiyomiPreviewTheme {
        Surface {
            ReaderPageIndicator(currentPage = "10", totalPages = 69)
        }
    }
}
