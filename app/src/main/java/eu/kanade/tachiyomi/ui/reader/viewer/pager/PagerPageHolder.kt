package eu.kanade.tachiyomi.ui.reader.viewer.pager

// KMK -->
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.i18n.MR
import kotlin.math.max
// KMK <--

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private var extraPage: ReaderPage? = null,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    private val inTransition: Boolean = false,
    // KMK <--
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page to extraPage

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    /**
     * Job for loading the page.
     */
    private var extraLoadJob: Job? = null

    // KMK -->
    val sideBySideAtCreation = viewer.sideBySide
    private val imageLoadMutex = Mutex()
    private val useHingeViewports = sideBySideAtCreation && !inTransition && viewer.hinge != null
    private var secondImageView: ReaderPageImageView? = null
    private var portraitPage = false
    private var navigateSecondImage = false

    internal val indicatorPosition: UnpairedPagePosition
        get() = ReaderSpreadLayout.unpairedPosition(
            viewer.config.unpairedPagePosition,
            viewer.sideBySide && !inTransition,
            portraitPage,
            viewer is R2LPagerViewer,
            viewer.config.invertDoublePages,
            openingPage = page.index == 0,
        )

    val navigationImage: ReaderPageImageView
        get() = secondImageView?.takeIf { navigateSecondImage } ?: this

    private data class LoadedImages(
        val source: BufferedSource,
        val animated: Boolean,
        val background: Drawable?,
        val portrait: Boolean,
        val second: BufferedSource?,
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutImages()
    }

    @SuppressLint("RtlHardcoded") // Left and Right name physical displays, including in RTL interface languages.
    private fun layoutImages() {
        if (width <= 0) return
        val sideBySide = viewer.sideBySide && !inTransition
        val hinge = viewer.hinge?.relativeTo(0, 0, width, height).takeIf { sideBySide }
        val firstOnLeft = ReaderSpreadLayout.firstPageOnLeft(viewer is R2LPagerViewer, viewer.config.invertDoublePages)
        val position = if (secondImageView != null) {
            if (firstOnLeft) UnpairedPagePosition.LEFT else UnpairedPagePosition.RIGHT
        } else {
            ReaderSpreadLayout.unpairedPosition(
                viewer.config.unpairedPagePosition,
                sideBySide,
                portraitPage,
                viewer is R2LPagerViewer,
                viewer.config.invertDoublePages,
                openingPage = page.index == 0,
            )
        }
        val viewport = ReaderSpreadLayout.viewport(width, position, hinge)
        setImageViewport(
            viewport.takeIf { sideBySide },
            hinge.takeIf { position == UnpairedPagePosition.CENTER },
        )
        secondImageView?.let { second ->
            val other = ReaderSpreadLayout.viewport(
                width,
                if (firstOnLeft) UnpairedPagePosition.RIGHT else UnpairedPagePosition.LEFT,
                hinge,
            )
            second.layoutParams = LayoutParams(other.width, MATCH_PARENT).apply {
                leftMargin = other.left
                gravity = Gravity.TOP or Gravity.LEFT
            }
        }
        viewer.updatePageNumbers()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            navigateSecondImage = secondImageView?.let { event.x >= it.left && event.x < it.right } == true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onPageSelected(forward: Boolean) {
        super.onPageSelected(forward)
        secondImageView?.onPageSelected(forward)
    }

    /** Read native decoder metadata, including formats unsupported by BitmapFactory, without a bitmap. */
    private fun isPortrait(source: BufferedSource): Boolean = try {
        source.peek().use { peek ->
            if (ImageUtil.isAnimatedAndSupported(source)) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(peek.inputStream(), null, options)
                ReaderSpreadLayout.isPortrait(options.outWidth, options.outHeight)
            } else {
                val decoder = ImageDecoder.newInstance(peek.inputStream())
                if (decoder == null) {
                    false
                } else {
                    try {
                        ReaderSpreadLayout.isPortrait(decoder.width, decoder.height)
                    } finally {
                        decoder.recycle()
                    }
                }
            }
        }
    } catch (_: Exception) {
        false // Let the existing image view handle unknown formats and decode errors.
    }
    // KMK <--

    init {
        loadJob = scope.launch { loadPageAndProcessStatus(1) }
        // SY -->
        extraLoadJob = scope.launch { loadPageAndProcessStatus(2) }
        // SY <--
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // KMK -->
        dispose()
    }

    internal fun dispose() {
        // KMK <--
        loadJob?.cancel()
        loadJob = null
        extraLoadJob?.cancel()
        extraLoadJob = null
        // KMK -->
        scope.cancel()
        recycle()
        secondImageView?.recycle()
        // KMK <--
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(
                context = context,
                // KMK -->
                seedColor = seedColor,
                // KMK <--
            )
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus(pageIndex: Int) {
        // SY -->
        val page = if (pageIndex == 1) page else extraPage
        page ?: return
        // SY <--
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    // KMK -->
    private suspend fun setImage() = imageLoadMutex.withLock { loadImages() }

    private suspend fun loadImages() {
        // KMK <--
        if (extraPage == null) {
            progressIndicator?.setProgress(0)
        } else {
            progressIndicator?.setProgress(95)
        }

        val streamFn = page.stream ?: return
        val streamFn2 = extraPage?.stream
        // KMK -->
        if (extraPage != null && streamFn2 == null) return
        // KMK <--

        try {
            // KMK -->
            val images = withIOContext {
                // KMK <--
                streamFn().buffered(16).use { source ->
                    // SY -->
                    if (extraPage != null) {
                        streamFn2?.invoke()
                            ?.buffered(16)
                    } else {
                        null
                    }.use { source2 ->
                        // KMK -->
                        val first = Buffer().readFrom(source)
                        val second = source2?.let { Buffer().readFrom(it) }
                        var retainedSecond: BufferedSource? = null
                        val itemSource = try {
                            if (inTransition) {
                                first // The opening uses the complete original image inside the left pane.
                            } else if (useHingeViewports && second != null && !page.fullPage &&
                                !ImageUtil.isAnimatedAndSupported(first) &&
                                !ImageUtil.isAnimatedAndSupported(second) && isPortrait(first) && isPortrait(second)
                            ) {
                                retainedSecond = second
                                first
                            } else if (viewer.config.dualPageSplit) {
                                process(item.first, first)
                            } else {
                                mergePages(first, second)
                            }
                        } finally {
                            if (retainedSecond == null) second?.close()
                        }
                        // KMK <--
                        // SY <--
                        val isAnimated = ImageUtil.isAnimatedAndSupported(itemSource)
                        val background = if (!isAnimated && viewer.config.automaticBackground) {
                            ImageUtil.chooseBackground(context, itemSource.peek())
                        } else {
                            null
                        }
                        // KMK -->
                        LoadedImages(
                            itemSource,
                            isAnimated,
                            background,
                            viewer.sideBySide && (second == null || itemSource === first) && isPortrait(itemSource),
                            retainedSecond,
                        )
                        // KMK <--
                    }
                }
            }
            withUIContext {
                // KMK -->
                val (source, isAnimated, background, portrait, second) = images
                portraitPage = portrait
                secondImageView?.let {
                    it.recycle()
                    removeView(it)
                }
                secondImageView = second?.let {
                    ReaderPageImageView(context).apply {
                        onImageLoadError = { error -> setError(error) }
                        onScaleChanged = { viewer.activity.hideMenu() }
                    }.also { addView(it) }
                }
                layoutImages()
                // KMK <--
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                        // KMK -->
                        disableZoomIn = viewer.config.disableZoomIn,
                        doubleTapZoom = viewer.config.doubleTapZoom,
                        landscapeZoomScaleType = viewer.config.landscapeZoomScaleType,
                        // KMK <--
                    ),
                )
                // KMK -->
                if (second != null) {
                    secondImageView?.setImage(
                        second,
                        false,
                        Config(
                            zoomDuration = viewer.config.doubleTapAnimDuration,
                            minimumScaleType = viewer.config.imageScaleType,
                            cropBorders = viewer.config.imageCropBorders,
                            zoomStartPosition = viewer.config.imageZoomType,
                            disableZoomIn = viewer.config.disableZoomIn,
                            doubleTapZoom = viewer.config.doubleTapZoom,
                        ),
                    )
                }
                // KMK <--
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
            }
            // KMK -->
        } catch (e: CancellationException) {
            throw e
            // KMK <--
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun mergePages(imageSource: BufferedSource, imageSource2: BufferedSource?): BufferedSource {
        // Handle adding a center margin to wide images if requested
        if (imageSource2 == null) {
            return handleWideImage(imageSource)
        }

        if (page.fullPage) return imageSource
        if (ImageUtil.isAnimatedAndSupported(imageSource)) {
            page.fullPage = true
            splitDoublePages()
            return imageSource
        } else if (ImageUtil.isAnimatedAndSupported(imageSource2)) {
            page.isolatedPage = true
            extraPage?.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap = decodeImage(imageSource)
        if (imageBitmap == null) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(96) }
        if (imageBitmap.height < imageBitmap.width) {
            // KMK -->
            imageBitmap.recycle()
            // KMK <--
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap2 = decodeImage(imageSource2)
        if (imageBitmap2 == null) {
            // KMK -->
            imageBitmap.recycle()
            // KMK <--
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(97) }
        if (imageBitmap2.height < imageBitmap2.width) {
            // KMK -->
            imageBitmap.recycle()
            imageBitmap2.recycle()
            // KMK <--
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            return imageSource
        }

        val isLTR = (viewer !is R2LPagerViewer) xor viewer.config.invertDoublePages
        val centerMargin = calculateCenterMargin(imageBitmap.height, imageBitmap2.height)

        imageSource.close()
        imageSource2.close()

        // KMK -->
        return try {
            ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, centerMargin, viewer.config.pageCanvasColor) {
                updateProgress(it)
            }
        } finally {
            imageBitmap.recycle()
            imageBitmap2.recycle()
        }
        // KMK <--
    }

    private fun handleWideImage(imageSource: BufferedSource): BufferedSource {
        return if (
            // KMK -->
            !useHingeViewports &&
            // KMK <--
            !ImageUtil.isAnimatedAndSupported(imageSource) &&
            ImageUtil.isWideImage(imageSource) &&
            viewer.config.centerMarginType and PagerConfig.CenterMarginType.WIDE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            ImageUtil.addHorizontalCenterMargin(imageSource, height, context)
        } else {
            imageSource
        }
    }

    private fun decodeImage(imageSource: BufferedSource): Bitmap? {
        return try {
            // KMK -->
            imageSource.peek().use { peek ->
                ImageDecoder.newInstance(peek.inputStream())?.let { decoder ->
                    try {
                        decoder.decode()
                    } finally {
                        decoder.recycle()
                    }
                }
            }
            // KMK <--
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cannot decode image" }
            null
        }
    }

    private fun calculateCenterMargin(height: Int, height2: Int): Int {
        return if (viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            96 / (this.height.coerceAtLeast(1) / max(height, height2).coerceAtLeast(1)).coerceAtLeast(1)
        } else {
            0
        }
    }

    private fun updateProgress(progress: Int) {
        scope.launch {
            if (progress == 100) {
                progressIndicator?.hide()
            } else {
                progressIndicator?.setProgress(progress)
            }
        }
    }

    private fun splitDoublePages() {
        scope.launch {
            delay(100)
            viewer.splitDoublePages(page)
            if (extraPage?.fullPage == true || page.fullPage) {
                extraPage = null
            }
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        val sideMargin = if ((viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN) >
            0 &&
            viewer.config.doublePages &&
            !viewer.config.imageCropBorders
        ) {
            48
        } else {
            0
        }

        return ImageUtil.splitInHalf(imageSource, side, sideMargin)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
