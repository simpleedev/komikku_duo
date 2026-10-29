package eu.kanade.tachiyomi.ui.reader.viewer.pager

// KMK -->
import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import coil3.load
import coil3.request.error
import coil3.request.fallback
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderButton
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderTransitionView
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR

/** Manga cover on the physical right, with the actual opening page or end message on the left. */
@SuppressLint("ViewConstructor")
class PagerTransitionHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val transition: ChapterTransition,
    openingPage: ReaderPage? = null,
    @ColorInt private val seedColor: Int? = null,
) : FrameLayout(readerThemedContext), ViewPagerAdapter.PositionableView {
    private val scope = MainScope()
    val sideBySideAtCreation = viewer.sideBySide
    private val sideBySide = sideBySideAtCreation
    private var openingPage = openingPage
    internal var openingHolder: PagerPageHolder? = null
        private set

    override val item
        get() = transition to openingPage

    /** Preserve the frame, cover, and decoded opening page through chapter activation. */
    internal fun bindOpening(page: ReaderPage?) {
        if (openingPage === page) return
        openingPage = page
        updateLeftContent()
    }

    private val detailsPane = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val padding = (if (sideBySide) 0 else 64).dpToPx
        setPadding(padding, 0, padding, 0)
    }

    private val leftPane = FrameLayout(context).apply {
        isVisible = sideBySide
    }

    private val pagesContainer = LinearLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }

    init {
        addView(leftPane, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(detailsPane, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (sideBySide) {
            val manga = viewer.activity.viewModel.manga
            val cover = AppCompatImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = manga?.title
                load(manga) {
                    error(R.drawable.cover_error_vector)
                    fallback(R.drawable.cover_error_vector)
                }
            }
            detailsPane.addView(cover, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } else {
            val transitionView = ReaderTransitionView(context, seedColor = seedColor)
            detailsPane.addView(transitionView)
            transitionView.bind(transition, viewer.downloadManager, viewer.activity.viewModel.manga)
        }
        detailsPane.addView(pagesContainer)
        updateLeftContent()
        observeStatus()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val hinge = viewer.hinge?.relativeTo(0, 0, width, height)
        val spread = ReaderTransitionSpread.create(transition, sideBySide, width, hinge)
        detailsPane.setViewport(spread.details)
        spread.leftPage?.let { leftPane.setViewport(it) }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    @SuppressLint("RtlHardcoded") // These are physical display regions, independent of interface language.
    private fun View.setViewport(viewport: PageViewport) {
        val params = layoutParams as LayoutParams
        if (params.width == viewport.width && params.leftMargin == viewport.left && params.gravity == Gravity.LEFT) return
        layoutParams = LayoutParams(viewport.width, MATCH_PARENT, Gravity.LEFT).apply {
            leftMargin = viewport.left
        }
    }

    private fun observeStatus() {
        val destination: Flow<ReaderChapter.State?> = transition.to?.stateFlow ?: flowOf(null)
        scope.launch {
            combine(transition.from.stateFlow, destination) { _, state -> state }
                .collectLatest { state ->
                    pagesContainer.removeAllViews()
                    when (state) {
                        is ReaderChapter.State.Loading -> setLoading()
                        is ReaderChapter.State.Error -> setError(state.error)
                        else -> Unit
                    }
                    updateLeftContent()
                }
        }
    }

    private fun updateLeftContent() {
        if (!sideBySide) return
        val spread = ReaderTransitionSpread.create(transition, true, width, null)
        if (spread.noMoreChapters) {
            clearOpening()
            leftPane.addView(
                AppCompatTextView(context).apply {
                    text = context.stringResource(KMR.strings.transition_no_more_chapters)
                    textSize = 20f
                    gravity = Gravity.CENTER
                    val padding = 32.dpToPx
                    setPadding(padding, 0, padding, 0)
                },
                LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER),
            )
            return
        }
        if (openingPage === openingHolder?.page) return
        clearOpening()
        openingPage?.let { page ->
            openingHolder = PagerPageHolder(context, viewer, page, seedColor = seedColor, inTransition = true).also {
                leftPane.addView(it, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        }
    }

    private fun clearOpening() {
        openingHolder?.dispose()
        openingHolder = null
        leftPane.removeAllViews()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
        clearOpening()
    }

    private fun setLoading() {
        val progress = ReaderProgressIndicator(context = context, seedColor = seedColor)
        val textView = AppCompatTextView(context).apply {
            wrapContent()
            text = context.stringResource(MR.strings.transition_pages_loading)
        }
        pagesContainer.addView(progress)
        pagesContainer.addView(textView)
    }

    private fun setError(error: Throwable) {
        val textView = AppCompatTextView(context).apply {
            wrapContent()
            text = context.stringResource(MR.strings.transition_pages_error, error.message ?: "")
        }
        val retryBtn = ReaderButton(context).apply {
            viewer = this@PagerTransitionHolder.viewer
            wrapContent()
            text = context.stringResource(MR.strings.action_retry)
            setOnClickListener {
                transition.to?.let(this@PagerTransitionHolder.viewer.activity::requestPreloadChapter)
            }
        }
        pagesContainer.addView(textView)
        pagesContainer.addView(retryBtn)
    }

    private fun View.wrapContent() {
        layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    }
}
// KMK <--
