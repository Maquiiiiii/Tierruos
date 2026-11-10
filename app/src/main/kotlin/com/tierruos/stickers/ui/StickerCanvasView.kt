package com.tierruos.stickers.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.tierruos.stickers.R
import com.tierruos.stickers.graphics.StickerOutline
import java.util.ArrayDeque
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main-thread-only editor for previously sanitized bitmaps. All artwork uses a 512 px
 * document space; view chrome, guides and animations are never part of the document.
 * The caller owns input/output bitmaps; the view owns private copies and bounded undo.
 */
class StickerCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private data class Caption(val text: String, val size: Float, val x: Float, val y: Float)
    private data class EditorState(
        val image: Bitmap? = null,
        val transform: Matrix = Matrix(),
        val outline: Bitmap? = null,
        val outlineWidth: Float = 0f,
        val caption: Caption? = null,
    )

    private enum class Gesture { NONE, IMAGE, TEXT, LASSO, ERASER, EMPTY }

    private var state = EditorState()
    private val history = ArrayDeque<EditorState>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var generation = 0
    private var pendingPreview: EditorState? = null
    private var beforeGesture: EditorState? = null
    private var gesture = Gesture.NONE
    private var gestureChanged = false
    private var workingEraser: Bitmap? = null
    private var eraserCanvas: Canvas? = null
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var rotationA = MotionEvent.INVALID_POINTER_ID
    private var rotationB = MotionEvent.INVALID_POINTER_ID
    private var previousAngle = 0f
    private val lastPoint = PointF()
    private val lastFocus = PointF()
    private val cursor = PointF()
    private val lasso = Path()
    private var lassoPoints = 0
    private val viewport = RectF()
    private val viewportClip = Path()
    private var documentScale = 1f
    private var dashPhase = 0
    private var loopFraction = 0f
    private var loopAnimator: ValueAnimator? = null
    private var undoAnimator: ValueAnimator? = null
    private var undoFrame: Bitmap? = null
    private var undoAlpha = 0
    private var errorVisible = false
    private var errorLabel = R.string.canvas_edit_failed
    private var busyLabel = R.string.canvas_outlining
    private var cachedCaption: Caption? = null
    private var captionLayout: StaticLayout? = null
    private var labelLayout: StaticLayout? = null
    private var labelKey: Pair<Int, Int>? = null
    private var dashEffects = emptyArray<DashPathEffect>()

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val gridPaint = Paint().apply { shader = makeCheckerShader() }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val underlayPaint = Paint(guidePaint).apply { color = context.getColor(R.color.background) }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val clearDotPaint = Paint(clearPaint).apply { style = Paint.Style.FILL }
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textLocale = resources.configuration.locales[0]
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_primary)
        textSize = resources.getDimension(R.dimen.text_body)
        typeface = Typeface.DEFAULT
    }
    private val scrimPaint = Paint().apply { color = context.getColor(R.color.canvas_busy_scrim) }
    private val spinnerPaint = Paint(guidePaint).apply {
        strokeWidth = resources.getDimension(R.dimen.canvas_guide_width)
    }
    private val textBounds = RectF()
    private val mappedBounds = RectF()
    private val spinnerBounds = RectF()
    private val matrixValues = FloatArray(9)
    private val feedbackDuration = resources.getInteger(R.integer.canvas_feedback_duration_ms).toLong()
    private val defaultTextSize = documentTextSize(R.dimen.canvas_text_default)
    private val minimumTextSize = documentTextSize(R.dimen.canvas_text_min)
    private val maximumTextSize = documentTextSize(R.dimen.canvas_text_max)

    var onEditChanged: ((Boolean) -> Unit)? = null
    var onBusyChanged: ((Boolean) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var isBusy: Boolean = false
        private set
    var lastOperationError: Throwable? = null
        private set
    val canUndo: Boolean get() = history.isNotEmpty() && !isBusy && gesture == Gesture.NONE
    val hasImage: Boolean get() = state.image != null
    val textLayer: String get() = state.caption?.text.orEmpty()
    val hasOutline: Boolean get() = state.outlineWidth > 0f
    var documentVersion: Long = 0L
        private set

    var mode: Int = MODE_TRANSFORM
        set(value) {
            assertMainThread()
            check(!isBusy) { "Wait for the active edit to complete." }
            require(value in MODE_TRANSFORM..MODE_ERASER)
            if (field == value) return
            cancelGesture()
            field = value
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            updateDescription()
            invalidate()
        }

    /** Width in 512 px document coordinates, independent of display density. */
    var eraserWidthPx: Float = logicalDimension(R.dimen.canvas_eraser_default)
        set(value) {
            assertMainThread()
            check(!isBusy)
            require(value.isFinite() && value in
                logicalDimension(R.dimen.canvas_eraser_min)..logicalDimension(R.dimen.canvas_eraser_max))
            cancelGesture()
            field = value
            invalidate()
        }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (gesture != Gesture.IMAGE) return false
                changeScale(detector.scaleFactor, lastFocus.x, lastFocus.y)
                gestureChanged = true
                invalidate()
                return true
            }
        })

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    /** Call with a software bitmap from Module 2. The original is never mutated/recycled. */
    fun setImageBitmap(bitmap: Bitmap) {
        assertMainThread()
        require(!bitmap.isRecycled && bitmap.width in 1..1024 && bitmap.height in 1..1024)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) require(bitmap.config != Bitmap.Config.HARDWARE)
        val normalized = newBitmap()
        val scale = min(SIZE / bitmap.width, SIZE / bitmap.height)
        val left = (SIZE - bitmap.width * scale) / 2f
        val top = (SIZE - bitmap.height * scale) / 2f
        Canvas(normalized).drawBitmap(bitmap, null,
            RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale), imagePaint)
        cancelOperation()
        cancelGesture()
        finishUndoAnimation()
        history.clear()
        state = EditorState(image = normalized)
        errorVisible = false
        lastOperationError = null
        changed()
    }

    fun clearEditor() {
        assertMainThread()
        cancelOperation()
        cancelGesture()
        finishUndoAnimation()
        history.clear()
        state = EditorState()
        errorVisible = false
        lastOperationError = null
        changed()
    }

    /** Non-destructive, circular white outline. Zero removes the effect synchronously. */
    fun applyOutline(widthPx: Float = logicalDimension(R.dimen.canvas_outline_default)) {
        ensureEditable()
        require(widthPx.isFinite() && widthPx in 0f..logicalDimension(R.dimen.canvas_outline_max))
        if (state.outlineWidth == widthPx) return
        val before = state
        val candidate = before.copy(outline = null, outlineWidth = widthPx)
        if (widthPx == 0f) {
            commit(before, candidate)
        } else {
            submitEdit(before, R.string.canvas_outlining, candidate, confirm = true) { candidate }
        }
    }

    /** One draggable caption. Empty text removes it; oversized text is rejected, never ellipsized. */
    fun setTextLayer(
        text: String,
        sizePx: Float = defaultTextSize,
        centerX: Float = SIZE / 2f,
        centerY: Float = SIZE * 0.8f,
    ) {
        ensureEditable()
        require(text.length <= MAX_TEXT_LENGTH)
        require(sizePx.isFinite() && sizePx in minimumTextSize..maximumTextSize)
        require(centerX.isFinite() && centerY.isFinite())
        val caption = if (text.isBlank()) null else Caption(text, sizePx, centerX, centerY)
        if (caption != null) require(layoutFor(caption).height <= SIZE - 2 * textInset()) {
            "The caption does not fit; reduce its size or shorten it."
        }
        val next = caption?.let { constrainCaption(it) }
        if (next == state.caption) return
        commit(state, state.copy(caption = next))
    }

    fun translateImageBy(dx: Float, dy: Float) {
        ensureEditable()
        require(dx.isFinite() && dy.isFinite())
        val before = state
        val matrix = Matrix(state.transform).apply { postTranslate(dx, dy) }
        constrainImage(matrix)
        commit(before, state.copy(transform = matrix))
    }

    fun scaleImageBy(factor: Float) {
        ensureEditable()
        require(factor.isFinite() && factor > 0f)
        val before = state
        changeScale(factor, SIZE / 2f, SIZE / 2f)
        commit(before, state)
    }

    fun rotateImageBy(degrees: Float) {
        ensureEditable()
        require(degrees.isFinite())
        val before = state
        val matrix = Matrix(state.transform).apply { postRotate(degrees % 360f, SIZE / 2f, SIZE / 2f) }
        constrainImage(matrix)
        commit(before, state.copy(transform = matrix))
    }

    fun resetImageTransform() {
        ensureEditable()
        if (!state.transform.isIdentity) commit(state, state.copy(transform = Matrix()))
    }

    fun undo(): Boolean {
        assertMainThread()
        if (!canUndo) return false
        finishUndoAnimation()
        val frame = if (animationsEnabled()) renderDocument(state) else null
        state = history.removeLast()
        if (frame != null) {
            undoFrame = frame
            undoAlpha = 255
            undoAnimator = ValueAnimator.ofInt(255, 0).apply {
                duration = feedbackDuration
                interpolator = DecelerateInterpolator()
                addUpdateListener { undoAlpha = it.animatedValue as Int; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (undoFrame === frame) { undoFrame = null; undoAnimator = null; invalidate() }
                    }
                })
                start()
            }
        }
        changed()
        return true
    }

    /** Returns an independently owned, transparent 512 x 512 software bitmap. */
    fun exportFinalBitmap(): Bitmap {
        ensureEditable()
        return renderDocument(state)
    }

    /** Opaque, in-memory document and undo state for Activity configuration changes. */
    class Session internal constructor(internal val restore: (StickerCanvasView) -> Unit)

    fun captureSession(): Session {
        ensureEditable()
        val document = state
        val undos = history.toList()
        return Session { target ->
            target.state = document
            target.history.clear()
            target.history.addAll(undos)
        }
    }

    fun restoreSession(session: Session) {
        assertMainThread()
        cancelOperation()
        cancelGesture()
        finishUndoAnimation()
        session.restore(this)
        changed()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fallback = max(suggestedMinimumWidth,
            resources.displayMetrics.widthPixels - 2 * resources.getDimensionPixelSize(R.dimen.space_16))
        val widthLimit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) fallback
            else MeasureSpec.getSize(widthMeasureSpec)
        val heightLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) widthLimit
            else MeasureSpec.getSize(heightMeasureSpec)
        val side = min(widthLimit, heightLimit)
        setMeasuredDimension(resolveSize(side, widthMeasureSpec), resolveSize(side, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = min(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom).coerceAtLeast(0)
        val left = paddingLeft + (w - paddingLeft - paddingRight - side) / 2f
        val top = paddingTop + (h - paddingTop - paddingBottom - side) / 2f
        viewport.set(left, top, left + side, top + side)
        documentScale = if (side > 0) side / SIZE else 1f
        viewportClip.reset()
        val radius = resources.getDimension(R.dimen.radius_card_content)
        viewportClip.addRoundRect(viewport, radius, radius, Path.Direction.CW)
        guidePaint.strokeWidth = resources.getDimension(R.dimen.canvas_guide_width) / documentScale
        underlayPaint.strokeWidth = resources.getDimension(R.dimen.canvas_guide_underlay_width) / documentScale
        val dash = resources.getDimension(R.dimen.canvas_dash_length) / documentScale
        val gap = resources.getDimension(R.dimen.canvas_dash_gap) / documentScale
        dashEffects = Array(DASH_STEPS) { DashPathEffect(floatArrayOf(dash, gap), (dash + gap) * it / DASH_STEPS) }
        labelKey = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewport.isEmpty) return
        val outer = canvas.save()
        canvas.clipPath(viewportClip)
        canvas.drawRect(viewport, gridPaint)
        val document = canvas.save()
        canvas.translate(viewport.left, viewport.top)
        canvas.scale(documentScale, documentScale)
        val visible = pendingPreview ?: state
        drawDocument(canvas, visible, workingEraser)
        undoFrame?.let { fadePaint.alpha = undoAlpha; canvas.drawBitmap(it, 0f, 0f, fadePaint) }
        if (gesture == Gesture.LASSO) {
            guidePaint.pathEffect = dashEffects.getOrNull(dashPhase)
            canvas.drawPath(lasso, underlayPaint)
            canvas.drawPath(lasso, guidePaint)
            guidePaint.pathEffect = null
        }
        if (gesture == Gesture.ERASER) {
            canvas.drawCircle(cursor.x, cursor.y, eraserWidthPx / 2f, underlayPaint)
            canvas.drawCircle(cursor.x, cursor.y, eraserWidthPx / 2f, guidePaint)
        }
        if (gesture == Gesture.TEXT) {
            captionBounds(state.caption, textBounds)
            canvas.drawRoundRect(textBounds, textInset() / 2f, textInset() / 2f, guidePaint)
        }
        canvas.restoreToCount(document)
        if (!hasImage) drawLabel(canvas, R.string.canvas_empty, viewport.centerY())
        if (isBusy) {
            canvas.drawRect(viewport, scrimPaint)
            val edge = resources.getDimension(R.dimen.canvas_spinner_size)
            val gap = resources.getDimension(R.dimen.space_16)
            val y = viewport.centerY() - gap - edge
            spinnerBounds.set(viewport.centerX() - edge / 2, y, viewport.centerX() + edge / 2, y + edge)
            canvas.drawArc(spinnerBounds, loopFraction * 360f, 240f, false, spinnerPaint)
            drawLabel(canvas, busyLabel, viewport.centerY() + gap)
        } else if (errorVisible) {
            val height = resources.getDimension(R.dimen.touch_target_min) * 2
            canvas.drawRect(viewport.left, viewport.bottom - height, viewport.right, viewport.bottom, scrimPaint)
            drawLabel(canvas, errorLabel, viewport.bottom - height / 2)
        }
        canvas.restoreToCount(outer)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || viewport.isEmpty) return false
        if (isBusy) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!viewport.contains(event.x, event.y)) return false
            finishUndoAnimation()
            animate().cancel(); scaleX = 1f; scaleY = 1f
            errorVisible = false
            pointer = event.getPointerId(0)
            val point = toDocument(event.x, event.y)
            lastPoint.set(point); lastFocus.set(point); cursor.set(point)
            beforeGesture = state
            gestureChanged = false
            parent?.requestDisallowInterceptTouchEvent(true)
            gesture = when {
                !hasImage -> Gesture.EMPTY
                mode == MODE_LASSO_CROP -> Gesture.LASSO
                mode == MODE_ERASER -> Gesture.ERASER
                hitCaption(point) -> Gesture.TEXT
                else -> Gesture.IMAGE
            }
            when (gesture) {
                Gesture.LASSO -> { lasso.reset(); lasso.moveTo(point.x, point.y); lassoPoints = 1; updateLoop() }
                Gesture.ERASER -> {
                    workingEraser = renderBase(state)
                    eraserCanvas = Canvas(requireNotNull(workingEraser))
                    clearPaint.strokeWidth = eraserWidthPx
                    eraserCanvas?.drawCircle(point.x, point.y, eraserWidthPx / 2f, clearDotPaint)
                    gestureChanged = true
                }
                else -> Unit
            }
            onEditChanged?.invoke(false)
        }
        if (mode == MODE_TRANSFORM && (gesture == Gesture.IMAGE || gesture == Gesture.TEXT)) {
            scaleDetector.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (gesture == Gesture.TEXT) {
                    beforeGesture?.let { state = it }
                    gesture = Gesture.IMAGE
                    gestureChanged = false
                }
                if (gesture != Gesture.IMAGE) cancelGesture() else resetPointers(event)
            }
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_POINTER_UP -> if (gesture == Gesture.IMAGE) resetPointers(event, event.actionIndex)
            MotionEvent.ACTION_UP -> finishGesture(event)
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun handleMove(event: MotionEvent) {
        val index = event.findPointerIndex(pointer)
        if (index < 0 && gesture != Gesture.IMAGE) { cancelGesture(); return }
        when (gesture) {
            Gesture.IMAGE -> {
                val focus = focusOf(event)
                val dx = focus.x - lastFocus.x
                val dy = focus.y - lastFocus.y
                val matrix = Matrix(state.transform).apply { postTranslate(dx, dy) }
                val a = event.findPointerIndex(rotationA)
                val b = event.findPointerIndex(rotationB)
                if (a >= 0 && b >= 0) {
                    val angle = angleOf(event, a, b)
                    val delta = ((angle - previousAngle + 540f) % 360f) - 180f
                    matrix.postRotate(delta, focus.x, focus.y)
                    previousAngle = angle
                    if (abs(delta) > 0.01f) gestureChanged = true
                }
                constrainImage(matrix)
                state = state.copy(transform = matrix)
                if (abs(dx) + abs(dy) > 0.01f) gestureChanged = true
                lastFocus.set(focus)
            }
            Gesture.TEXT -> {
                val point = toDocument(event.getX(index), event.getY(index))
                state.caption?.let {
                    state = state.copy(caption = constrainCaption(it.copy(
                        x = it.x + point.x - lastPoint.x, y = it.y + point.y - lastPoint.y)))
                }
                if (abs(point.x - lastPoint.x) + abs(point.y - lastPoint.y) > 0.01f) gestureChanged = true
                lastPoint.set(point)
            }
            Gesture.LASSO, Gesture.ERASER -> {
                for (sample in 0 until event.historySize) {
                    appendStroke(toDocument(event.getHistoricalX(index, sample), event.getHistoricalY(index, sample)))
                    if (gesture == Gesture.NONE) return
                }
                appendStroke(toDocument(event.getX(index), event.getY(index)))
            }
            else -> Unit
        }
    }

    private fun appendStroke(point: PointF) {
        if (gesture == Gesture.ERASER) {
            eraserCanvas?.drawLine(lastPoint.x, lastPoint.y, point.x, point.y, clearPaint)
            cursor.set(point)
            lastPoint.set(point)
        } else if (gesture == Gesture.LASSO) {
            val tolerance = resources.getDimension(R.dimen.canvas_path_tolerance) / documentScale
            if (hypot(point.x - lastPoint.x, point.y - lastPoint.y) < tolerance) return
            if (++lassoPoints > MAX_PATH_POINTS) {
                cancelGesture()
                showError(IllegalArgumentException(resources.getString(R.string.canvas_path_too_long)),
                    R.string.canvas_path_too_long)
                return
            }
            lasso.quadTo(lastPoint.x, lastPoint.y,
                (lastPoint.x + point.x) / 2, (lastPoint.y + point.y) / 2)
            lastPoint.set(point)
        }
    }

    private fun finishGesture(event: MotionEvent) {
        val before = beforeGesture ?: return
        val ending = gesture
        val editedBitmap = workingEraser
        if (ending == Gesture.LASSO && lassoPoints >= 3) {
            val index = event.findPointerIndex(pointer)
            if (index >= 0) {
                val end = toDocument(event.getX(index), event.getY(index))
                lasso.lineTo(end.x, end.y)
            }
            lasso.close()
            val path = Path(lasso).apply { fillType = Path.FillType.EVEN_ODD }
            val bounds = RectF()
            path.computeBounds(bounds, true)
            endGestureState()
            if (bounds.width() >= 1f && bounds.height() >= 1f) {
                submitEdit(before, R.string.canvas_cropping, null, confirm = true) { jobContext ->
                    val mask = newBitmap()
                    val canvas = Canvas(mask)
                    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = context.getColor(R.color.sticker_outline)
                    })
                    val sourceIn = Paint(imagePaint).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
                    // Cover the complete mask, including areas outside a transformed image.
                    // SRC_IN over only the image's transformed quad would leave white mask pixels.
                    val flattened = renderBase(before)
                    try { canvas.drawBitmap(flattened, 0f, 0f, sourceIn) } finally { flattened.recycle() }
                    canvas.setBitmap(null)
                    jobContext.ensureActive()
                    before.copy(image = mask, transform = Matrix(), outline = null)
                }
            }
        } else if (ending == Gesture.ERASER && editedBitmap != null) {
            val index = event.findPointerIndex(pointer)
            if (index >= 0) appendStroke(toDocument(event.getX(index), event.getY(index)))
            endGestureState()
            val original = if (before.transform.isIdentity) requireNotNull(before.image) else renderBase(before)
            if (!original.sameAs(editedBitmap)) {
                val candidate = before.copy(image = editedBitmap, transform = Matrix(), outline = null)
                if (before.outlineWidth > 0f) {
                    submitEdit(before, R.string.canvas_outlining, candidate, confirm = false) { candidate }
                } else commit(before, candidate)
            }
        } else {
            endGestureState()
            if (gestureChanged) commit(before, state) else performClick()
        }
        onEditChanged?.invoke(canUndo)
    }

    private fun endGestureState() {
        gesture = Gesture.NONE
        beforeGesture = null
        eraserCanvas?.setBitmap(null)
        eraserCanvas = null
        workingEraser = null
        rotationA = MotionEvent.INVALID_POINTER_ID
        rotationB = MotionEvent.INVALID_POINTER_ID
        lasso.reset()
        parent?.requestDisallowInterceptTouchEvent(false)
        updateLoop()
    }

    private fun cancelGesture() {
        if (gesture == Gesture.IMAGE || gesture == Gesture.TEXT) beforeGesture?.let { state = it }
        endGestureState()
        gestureChanged = false
        onEditChanged?.invoke(canUndo)
        invalidate()
    }

    private fun submitEdit(
        before: EditorState,
        label: Int,
        preview: EditorState?,
        confirm: Boolean,
        build: (CoroutineContext) -> EditorState,
    ) {
        val token = ++generation
        pendingPreview = preview
        busyLabel = label
        isBusy = true
        errorVisible = false
        onBusyChanged?.invoke(true)
        onEditChanged?.invoke(false)
        updateLoop()
        invalidate()
        operation = scope.launch {
            try {
                val next = withContext(Dispatchers.Default) {
                    val candidate = build(coroutineContext)
                    coroutineContext.ensureActive()
                    if (candidate.outlineWidth > 0f) candidate.copy(outline = StickerOutline.create(
                        requireNotNull(candidate.image), candidate.outlineWidth,
                        context.getColor(R.color.sticker_outline), coroutineContext)) else candidate
                }
                if (generation == token) {
                    pendingPreview = null
                    commit(before, next)
                    if (confirm) confirmFeedback()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == token) { pendingPreview = null; showError(error) }
            } catch (error: OutOfMemoryError) {
                if (generation == token) { pendingPreview = null; showError(error) }
            } finally {
                if (generation == token) {
                    operation = null
                    isBusy = false
                    onBusyChanged?.invoke(false)
                    onEditChanged?.invoke(canUndo)
                    updateLoop()
                    invalidate()
                }
            }
        }
    }

    private fun commit(before: EditorState, next: EditorState) {
        finishUndoAnimation()
        history.addLast(before)
        state = next
        while (history.size > MAX_UNDO || (retainedBitmapBytes() > HISTORY_BYTES && history.size > 1)) {
            history.removeFirst()
        }
        errorVisible = false
        lastOperationError = null
        changed()
    }

    private fun retainedBitmapBytes(): Long {
        val unique = IdentityHashMap<Bitmap, Boolean>()
        fun add(value: EditorState) {
            value.image?.let { unique[it] = true }
            value.outline?.let { unique[it] = true }
        }
        add(state)
        history.forEach { add(it) }
        return unique.keys.sumOf { it.allocationByteCount.toLong() }
    }

    private fun drawDocument(canvas: Canvas, value: EditorState, overrideImage: Bitmap? = null) {
        if (overrideImage != null) canvas.drawBitmap(overrideImage, 0f, 0f, imagePaint) else {
            value.outline?.let { canvas.drawBitmap(it, value.transform, imagePaint) }
            value.image?.let { canvas.drawBitmap(it, value.transform, imagePaint) }
        }
        value.caption?.let { caption ->
            val layout = layoutFor(caption)
            val save = canvas.save()
            canvas.translate(caption.x - layout.width / 2f, caption.y - layout.height / 2f)
            captionPaint.style = Paint.Style.STROKE
            captionPaint.strokeWidth = logicalDimension(R.dimen.canvas_text_stroke)
            captionPaint.color = context.getColor(R.color.background)
            layout.draw(canvas)
            captionPaint.style = Paint.Style.FILL
            captionPaint.color = context.getColor(R.color.text_primary)
            layout.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun renderDocument(value: EditorState): Bitmap = newBitmap().also {
        val canvas = Canvas(it)
        drawDocument(canvas, value)
        canvas.setBitmap(null)
    }

    private fun renderBase(value: EditorState): Bitmap = newBitmap().also {
        val canvas = Canvas(it)
        value.image?.let { bitmap -> canvas.drawBitmap(bitmap, value.transform, imagePaint) }
        canvas.setBitmap(null)
    }

    private fun layoutFor(caption: Caption): StaticLayout {
        if (cachedCaption?.text != caption.text || cachedCaption?.size != caption.size || captionLayout == null) {
            captionPaint.textSize = caption.size
            captionPaint.style = Paint.Style.FILL
            val width = (SIZE - 2 * textInset()).toInt()
            captionLayout = StaticLayout.Builder.obtain(caption.text, 0, caption.text.length, captionPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
            cachedCaption = caption
        }
        return requireNotNull(captionLayout)
    }

    private fun captionBounds(caption: Caption?, out: RectF) {
        out.setEmpty()
        if (caption == null) return
        val layout = layoutFor(caption)
        var left = layout.width.toFloat()
        var right = 0f
        for (line in 0 until layout.lineCount) {
            left = min(left, layout.getLineLeft(line))
            right = max(right, layout.getLineRight(line))
        }
        out.set(caption.x - layout.width / 2f + left, caption.y - layout.height / 2f,
            caption.x - layout.width / 2f + right, caption.y + layout.height / 2f)
    }

    private fun constrainCaption(caption: Caption): Caption {
        captionBounds(caption, textBounds)
        val halfWidth = textBounds.width() / 2f + textInset() / 2
        val halfHeight = textBounds.height() / 2f + textInset() / 2
        return caption.copy(x = caption.x.coerceIn(halfWidth, SIZE - halfWidth),
            y = caption.y.coerceIn(halfHeight, SIZE - halfHeight))
    }

    private fun hitCaption(point: PointF): Boolean {
        captionBounds(state.caption, textBounds)
        if (textBounds.isEmpty) return false
        val minimum = resources.getDimension(R.dimen.touch_target_min) / documentScale
        textBounds.inset(-max(0f, (minimum - textBounds.width()) / 2f),
            -max(0f, (minimum - textBounds.height()) / 2f))
        return textBounds.contains(point.x, point.y)
    }

    private fun changeScale(factor: Float, x: Float, y: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        state.transform.getValues(matrixValues)
        val current = hypot(matrixValues[Matrix.MSCALE_X], matrixValues[Matrix.MSKEW_Y])
        val applied = (current * factor).coerceIn(MIN_SCALE, MAX_SCALE) / current
        val matrix = Matrix(state.transform).apply { postScale(applied, applied, x, y) }
        constrainImage(matrix)
        state = state.copy(transform = matrix)
    }

    private fun constrainImage(matrix: Matrix) {
        mappedBounds.set(0f, 0f, SIZE, SIZE)
        matrix.mapRect(mappedBounds)
        val visible = logicalDimension(R.dimen.space_32)
        val dx = when {
            mappedBounds.right < visible -> visible - mappedBounds.right
            mappedBounds.left > SIZE - visible -> SIZE - visible - mappedBounds.left
            else -> 0f
        }
        val dy = when {
            mappedBounds.bottom < visible -> visible - mappedBounds.bottom
            mappedBounds.top > SIZE - visible -> SIZE - visible - mappedBounds.top
            else -> 0f
        }
        matrix.postTranslate(dx, dy)
    }

    private fun resetPointers(event: MotionEvent, exclude: Int = -1) {
        val indices = (0 until event.pointerCount).filter { it != exclude }
        lastFocus.set(focusOf(event, exclude))
        pointer = event.getPointerId(indices.first())
        rotationA = if (indices.size >= 2) event.getPointerId(indices[0]) else MotionEvent.INVALID_POINTER_ID
        rotationB = if (indices.size >= 2) event.getPointerId(indices[1]) else MotionEvent.INVALID_POINTER_ID
        if (indices.size >= 2) previousAngle = angleOf(event, indices[0], indices[1])
    }

    private fun focusOf(event: MotionEvent, exclude: Int = -1): PointF {
        var x = 0f; var y = 0f; var count = 0
        for (i in 0 until event.pointerCount) if (i != exclude) {
            x += event.getX(i); y += event.getY(i); count++
        }
        return toDocument(x / count, y / count, clamp = false)
    }

    private fun angleOf(event: MotionEvent, a: Int, b: Int): Float =
        Math.toDegrees(atan2((event.getY(b) - event.getY(a)).toDouble(),
            (event.getX(b) - event.getX(a)).toDouble())).toFloat()

    private fun toDocument(x: Float, y: Float, clamp: Boolean = true): PointF {
        val px = (x - viewport.left) / documentScale
        val py = (y - viewport.top) / documentScale
        return if (clamp) PointF(px.coerceIn(0f, SIZE), py.coerceIn(0f, SIZE)) else PointF(px, py)
    }

    private fun makeCheckerShader(): BitmapShader {
        val cell = resources.getDimensionPixelSize(R.dimen.canvas_checker_size).coerceAtLeast(1)
        val tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        tile.eraseColor(context.getColor(R.color.surface))
        val paint = Paint().apply { color = context.getColor(R.color.canvas_checker_alternate) }
        val canvas = Canvas(tile)
        canvas.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), paint)
        canvas.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, paint)
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun drawLabel(canvas: Canvas, text: Int, centerY: Float) {
        val width = (viewport.width() - 2 * resources.getDimension(R.dimen.space_16)).toInt().coerceAtLeast(1)
        val key = text to width
        if (labelKey != key) {
            val value = resources.getString(text)
            labelLayout = StaticLayout.Builder.obtain(value, 0, value.length, labelPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
            labelKey = key
        }
        val layout = requireNotNull(labelLayout)
        val save = canvas.save()
        canvas.translate(viewport.centerX() - width / 2f, centerY - layout.height / 2f)
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun updateLoop() {
        val active = isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            (isBusy || gesture == Gesture.LASSO) && animationsEnabled()
        if (!active) { loopAnimator?.cancel(); loopAnimator = null; return }
        if (loopAnimator != null) return
        loopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = resources.getInteger(if (isBusy) R.integer.canvas_loading_period_ms
                else R.integer.canvas_lasso_period_ms).toLong()
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                loopFraction = it.animatedValue as Float
                dashPhase = (loopFraction * DASH_STEPS).toInt().coerceIn(0, DASH_STEPS - 1)
                invalidate()
            }
            start()
        }
    }

    private fun confirmFeedback() {
        performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        if (animationsEnabled()) {
            animate().cancel()
            scaleX = 0.97f; scaleY = 0.97f
            animate().scaleX(1f).scaleY(1f).setDuration(feedbackDuration)
                .setInterpolator(OvershootInterpolator()).start()
        }
    }

    private fun animationsEnabled(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private fun finishUndoAnimation() {
        undoAnimator?.cancel()
        undoAnimator = null
        undoFrame = null
        undoAlpha = 0
    }

    private fun cancelOperation() {
        generation++
        operation?.cancel()
        operation = null
        pendingPreview = null
        if (isBusy) { isBusy = false; onBusyChanged?.invoke(false) }
        updateLoop()
    }

    private fun showError(error: Throwable, label: Int = R.string.canvas_edit_failed) {
        lastOperationError = error
        errorVisible = true
        errorLabel = label
        onError?.invoke(error)
        announceForAccessibility(resources.getString(label))
        invalidate()
    }

    private fun changed() {
        documentVersion++
        updateDescription()
        onEditChanged?.invoke(canUndo)
        invalidate()
    }

    private fun updateDescription() {
        val id = if (!hasImage) R.string.canvas_empty else when (mode) {
            MODE_LASSO_CROP -> R.string.canvas_lasso_description
            MODE_ERASER -> R.string.canvas_eraser_description
            else -> R.string.canvas_transform_description
        }
        contentDescription = resources.getString(id) + (state.caption?.let {
            " " + resources.getString(R.string.canvas_text_description, it.text)
        } ?: "")
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (canUndo) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            R.id.canvas_undo, resources.getString(R.string.canvas_undo_label)))
        if (hasImage && !isBusy && gesture == Gesture.NONE) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.canvas_rotate,
                resources.getString(R.string.canvas_rotate_label)))
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.canvas_zoom_in,
                resources.getString(R.string.canvas_zoom_in_label)))
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.canvas_zoom_out,
                resources.getString(R.string.canvas_zoom_out_label)))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isEnabled) return false
        if (action == R.id.canvas_undo) return undo()
        if (!hasImage || isBusy || gesture != Gesture.NONE) return super.performAccessibilityAction(action, arguments)
        when (action) {
            R.id.canvas_rotate -> rotateImageBy(15f)
            R.id.canvas_zoom_in -> scaleImageBy(1.1f)
            R.id.canvas_zoom_out -> scaleImageBy(1f / 1.1f)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return false
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Z) return undo()
        if (!hasImage || isBusy || gesture != Gesture.NONE) return super.onKeyDown(keyCode, event)
        val step = logicalDimension(R.dimen.space_4)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> translateImageBy(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> translateImageBy(step, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> translateImageBy(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> translateImageBy(0f, step)
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> scaleImageBy(1.1f)
            KeyEvent.KEYCODE_MINUS -> scaleImageBy(1f / 1.1f)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateLoop() }

    override fun onDetachedFromWindow() {
        cancelOperation()
        cancelGesture()
        finishUndoAnimation()
        loopAnimator?.cancel(); loopAnimator = null
        animate().cancel(); scaleX = 1f; scaleY = 1f
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) updateLoop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) updateLoop()
    }

    private fun ensureEditable() {
        assertMainThread()
        check(hasImage) { "Load a sanitized image first." }
        check(!isBusy && gesture == Gesture.NONE) { "Wait for the active edit to complete." }
    }

    private fun assertMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "StickerCanvasView must be accessed on the main thread."
    }

    private fun logicalDimension(id: Int): Float = resources.getDimension(id) / resources.displayMetrics.density
    // Artwork text has fixed document units. System font scale still applies to UI labels.
    private fun documentTextSize(id: Int): Float {
        val value = TypedValue()
        resources.getValue(id, value, true)
        return TypedValue.complexToFloat(value.data)
    }
    private fun textInset(): Float = logicalDimension(R.dimen.space_16)
    private fun newBitmap(): Bitmap = Bitmap.createBitmap(PIXELS, PIXELS, Bitmap.Config.ARGB_8888).apply {
        density = Bitmap.DENSITY_NONE
        eraseColor(Color.TRANSPARENT)
    }

    companion object {
        const val MODE_TRANSFORM = 0
        const val MODE_LASSO_CROP = 1
        const val MODE_ERASER = 2
        private const val PIXELS = 512
        private const val SIZE = 512f
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 4f
        private const val MAX_UNDO = 8
        private const val HISTORY_BYTES = 16L * 1024 * 1024
        private const val MAX_PATH_POINTS = 4096
        private const val MAX_TEXT_LENGTH = 200
        private const val DASH_STEPS = 24
    }
}
