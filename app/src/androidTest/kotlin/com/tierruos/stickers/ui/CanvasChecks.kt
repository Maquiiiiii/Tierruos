package com.tierruos.stickers.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.tierruos.stickers.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class CanvasChecks(private val instrumentation: Instrumentation) {
    data class Check(val name: String, val body: suspend () -> Unit)
    private var host: CanvasPreviewActivity? = null

    fun cases(): List<Check> = listOf(
        Check("canvasMeasuresSquare") { withCanvas {
            val standalone = StickerCanvasView(it.context)
            for (width in listOf(320, 720, 1080)) {
                standalone.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.AT_MOST))
                check(standalone.measuredWidth == width && standalone.measuredHeight == width)
            }
        } },
        Check("canvasExportsOnlyArtworkAt512") { withCanvas { view ->
            val bitmap = view.exportFinalBitmap()
            try {
                check(bitmap.width == 512 && bitmap.height == 512)
                check(bitmap.getPixel(20, 20) == Color.TRANSPARENT)
                check(Color.alpha(bitmap.getPixel(256, 256)) == 255)
                check(!view.canUndo)
            } finally { bitmap.recycle() }
        } },
        Check("canvasCopiesCallerBitmap") { withCanvas { view ->
            val input = fixture(view)
            view.setImageBitmap(input)
            check(!input.isRecycled)
            input.eraseColor(Color.RED)
            val exported = view.exportFinalBitmap()
            try { check(exported.getPixel(256, 256) != Color.RED) }
            finally { exported.recycle(); input.recycle() }
        } },
        Check("canvasPanGestureAndUndo") { withCanvas { view ->
            stroke(view, 200f to 200f, 240f to 220f)
            check(view.canUndo)
            var bitmap = view.exportFinalBitmap()
            try { check(Color.alpha(bitmap.getPixel(140, 200)) == 0) } finally { bitmap.recycle() }
            check(view.undo())
            bitmap = view.exportFinalBitmap()
            try { check(Color.alpha(bitmap.getPixel(140, 200)) == 255) } finally { bitmap.recycle() }
            check(!view.canUndo)
        } },
        Check("canvasPinchUsesNativeDetector") { withCanvas { view ->
            val down = SystemClock.uptimeMillis()
            touch(view, down, down, MotionEvent.ACTION_DOWN, 180f, 256f)
            multi(view, down, down + 16, MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 180f, 256f, 332f, 256f)
            multi(view, down, down + 32, MotionEvent.ACTION_MOVE, 160f, 220f, 352f, 292f)
            multi(view, down, down + 48, MotionEvent.ACTION_MOVE, 140f, 210f, 372f, 302f)
            // Continue beyond the platform's physical minimum scaling span, then grow it.
            multi(view, down, down + 64, MotionEvent.ACTION_MOVE, 120f, 200f, 392f, 312f)
            multi(view, down, down + 80, MotionEvent.ACTION_MOVE, 100f, 190f, 412f, 322f)
            multi(view, down, down + 96, MotionEvent.ACTION_MOVE, 60f, 180f, 452f, 332f)
            multi(view, down, down + 112, MotionEvent.ACTION_MOVE, 40f, 170f, 472f, 342f)
            multi(view, down, down + 128, MotionEvent.ACTION_POINTER_UP or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 40f, 170f, 472f, 342f)
            touch(view, down, down + 144, MotionEvent.ACTION_UP, 40f, 170f)
            val bitmap = view.exportFinalBitmap()
            try {
                val area = opaquePixels(bitmap, 0, 512)
                check(area > 90_000) { "Native pinch did not enlarge the subject: $area pixels" }
            } finally { bitmap.recycle() }
            check(view.undo() && !view.canUndo)
        } },
        Check("canvasRotatesAroundDocumentCenter") { withCanvas { view ->
            view.rotateImageBy(45f)
            val bitmap = view.exportFinalBitmap()
            try {
                check(Color.alpha(bitmap.getPixel(132, 132)) == 0)
                check(Color.alpha(bitmap.getPixel(256, 100)) > 200)
            } finally { bitmap.recycle() }
        } },
        Check("canvasLassoClipsWithoutExportingGuide") { withCanvas { view ->
            view.mode = StickerCanvasView.MODE_LASSO_CROP
            stroke(view, 96f to 96f, 256f to 96f, 256f to 416f, 96f to 416f, 96f to 96f)
            awaitEdit(view)
            val bitmap = view.exportFinalBitmap()
            try {
                check(Color.alpha(bitmap.getPixel(200, 256)) > 200)
                check(Color.alpha(bitmap.getPixel(320, 256)) == 0)
                check(Color.alpha(bitmap.getPixel(96, 256)) == 0)
            } finally { bitmap.recycle() }
            check(view.undo())
        } },
        Check("canvasLassoDoesNotLeaveWhiteMaskOutsideTransformedImage") { withCanvas { view ->
            view.scaleImageBy(0.5f)
            view.mode = StickerCanvasView.MODE_LASSO_CROP
            stroke(view, 16f to 16f, 496f to 16f, 496f to 496f, 16f to 496f, 16f to 16f)
            awaitEdit(view)
            val bitmap = view.exportFinalBitmap()
            try {
                check(Color.alpha(bitmap.getPixel(32, 256)) == 0)
                check(Color.alpha(bitmap.getPixel(256, 256)) == 255)
            } finally { bitmap.recycle() }
        } },
        Check("canvasEraserClearAndUndo") { withCanvas { view ->
            view.mode = StickerCanvasView.MODE_ERASER
            view.eraserWidthPx = 32f
            stroke(view, 200f to 256f, 312f to 256f)
            val bitmap = view.exportFinalBitmap()
            try {
                check(Color.alpha(bitmap.getPixel(256, 256)) == 0)
                check(Color.alpha(bitmap.getPixel(256, 210)) == 255)
            } finally { bitmap.recycle() }
            check(view.undo())
            val restored = view.exportFinalBitmap()
            try { check(Color.alpha(restored.getPixel(256, 256)) == 255) } finally { restored.recycle() }
        } },
        Check("canvasCancelledStrokePreservesPixelsAndHistory") { withCanvas { view ->
            view.mode = StickerCanvasView.MODE_ERASER
            val time = SystemClock.uptimeMillis()
            touch(view, time, time, MotionEvent.ACTION_DOWN, 256f, 256f)
            touch(view, time, time + 16, MotionEvent.ACTION_MOVE, 300f, 256f)
            touch(view, time, time + 32, MotionEvent.ACTION_CANCEL, 300f, 256f)
            val bitmap = view.exportFinalBitmap()
            try { check(Color.alpha(bitmap.getPixel(256, 256)) == 255) } finally { bitmap.recycle() }
            check(!view.canUndo)
        } },
        Check("canvasTextDragIsIndependentAndUndoable") { withCanvas { view ->
            view.setTextLayer("Tierruos", 32f, 256f, 440f)
            var bitmap = view.exportFinalBitmap()
            try { check(opaquePixels(bitmap, 416, 480) > 100) } finally { bitmap.recycle() }
            stroke(view, 256f to 440f, 256f to 84f)
            bitmap = view.exportFinalBitmap()
            try {
                check(opaquePixels(bitmap, 48, 116) > 100)
                check(opaquePixels(bitmap, 416, 480) == 0)
                check(Color.alpha(bitmap.getPixel(256, 256)) == 255)
            } finally { bitmap.recycle() }
            check(view.undo())
            bitmap = view.exportFinalBitmap()
            try { check(opaquePixels(bitmap, 416, 480) > 100) } finally { bitmap.recycle() }
        } },
        Check("canvasOutlineIsCircularAndBusyBlocksDoubleEdits") { withCanvas { view ->
            view.applyOutline(8f)
            check(view.isBusy)
            expectIllegalState { view.exportFinalBitmap() }
            expectIllegalState { view.applyOutline(8f) }
            awaitEdit(view)
            val bitmap = view.exportFinalBitmap()
            try {
                check(bitmap.getPixel(121, 256) == Color.WHITE)
                check(Color.alpha(bitmap.getPixel(116, 256)) == 0)
                check(Color.alpha(bitmap.getPixel(121, 121)) == 0)
                check(bitmap.getPixel(123, 123) == Color.WHITE)
            } finally { bitmap.recycle() }
            check(view.undo())
        } },
        Check("canvasOutlineRegeneratesAfterErasing") { withCanvas { view ->
            view.applyOutline(8f)
            awaitEdit(view)
            view.mode = StickerCanvasView.MODE_ERASER
            view.eraserWidthPx = 40f
            stroke(view, 256f to 256f)
            awaitEdit(view)
            val bitmap = view.exportFinalBitmap()
            try {
                check(Color.alpha(bitmap.getPixel(256, 256)) == 0)
                check(bitmap.getPixel(270, 256) == Color.WHITE)
            } finally { bitmap.recycle() }
            check(view.undo())
        } },
        Check("canvasClearCancelsPendingResult") { withCanvas { view ->
            view.applyOutline(32f)
            view.clearEditor()
            delay(300)
            check(!view.hasImage && !view.isBusy && !view.canUndo)
            expectIllegalState { view.exportFinalBitmap() }
        } },
        Check("canvasHistoryIsBounded") { withCanvas { view ->
            repeat(20) { view.translateImageBy(1f, 0f) }
            var count = 0
            while (view.undo()) count++
            check(count == 8)
        } },
        Check("canvasRendersNativePreview") {
            val activity = activity()
            withContext(Dispatchers.Main) {
                activity.showSample()
                awaitEdit(activity.stickerCanvas)
            }
            delay(400)
            instrumentation.waitForIdleSync()
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            savePng(screenshot, "sticker-canvas-screen.png")
            screenshot.recycle()
            val component = withContext(Dispatchers.Main) {
                val card = activity.stickerCanvas.parent as View
                Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888).also {
                    card.draw(Canvas(it))
                }
            }
            savePng(component, "sticker-canvas-component.png")
            component.recycle()
        },
    )

    private fun activity(): CanvasPreviewActivity = host ?: (
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, CanvasPreviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CanvasPreviewActivity).also { host = it }

    private suspend fun withCanvas(block: suspend (StickerCanvasView) -> Unit) {
        val activity = activity()
        withContext(Dispatchers.Main) {
            val view = activity.stickerCanvas
            withTimeout(5_000) { while (view.width == 0) delay(16) }
            val source = fixture(view)
            view.setImageBitmap(source)
            source.recycle()
            view.mode = StickerCanvasView.MODE_TRANSFORM
            block(view)
        }
    }

    private fun fixture(view: View): Bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRect(128f, 128f, 384f, 384f,
            Paint().apply { color = view.context.getColor(R.color.accent) })
    }

    private suspend fun awaitEdit(view: StickerCanvasView) {
        withTimeout(10_000) { while (view.isBusy) delay(16) }
        check(view.lastOperationError == null) { view.lastOperationError.toString() }
    }

    private fun stroke(view: StickerCanvasView, vararg points: Pair<Float, Float>) {
        val start = SystemClock.uptimeMillis()
        touch(view, start, start, MotionEvent.ACTION_DOWN, points.first().first, points.first().second)
        points.drop(1).forEachIndexed { index, point ->
            touch(view, start, start + (index + 1) * 16L, MotionEvent.ACTION_MOVE, point.first, point.second)
        }
        touch(view, start, start + points.size * 16L, MotionEvent.ACTION_UP, points.last().first, points.last().second)
    }

    private fun touch(view: View, down: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, time, action, x * view.width / 512f, y * view.height / 512f, 0)
        try { check(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun multi(view: View, down: Long, time: Long, action: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        val properties = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = arrayOf(x0 to y0, x1 to y1).map { point ->
            MotionEvent.PointerCoords().apply {
                x = point.first * view.width / 512f; y = point.second * view.height / 512f
                pressure = 1f; size = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(down, time, action, 2, properties, coordinates, 0, 0,
            1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { check(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun opaquePixels(bitmap: Bitmap, top: Int, bottom: Int): Int {
        val pixels = IntArray(bitmap.width * (bottom - top))
        bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, bottom - top)
        return pixels.count { Color.alpha(it) > 128 }
    }

    private suspend fun savePng(bitmap: Bitmap, name: String) = withContext(Dispatchers.IO) {
        val directory = File(instrumentation.targetContext.filesDir, "qa").apply { check(isDirectory || mkdirs()) }
        File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun expectIllegalState(block: () -> Unit) {
        try { block() } catch (_: IllegalStateException) { return }
        error("Expected IllegalStateException")
    }
}
