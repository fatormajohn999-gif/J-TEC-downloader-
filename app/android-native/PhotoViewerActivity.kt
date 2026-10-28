package com.omnidrop.app.plugin

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayList

/**
 * Modern In-App Hardware Photo Viewer.
 * Built with pure Android SDK (ScaleGestureDetector + GestureDetector + Matrix).
 * Features:
 * - High-performance downsampling from MediaStore URIs preventing OutOfMemory errors.
 * - Smooth Pinch-to-Zoom (1.0x to 4.0x) with bounds-clamped panning.
 * - Quick double-tap zoom in/out.
 * - Horizontal swipe to cycle photos when at normal scale (slide animation).
 * - Single-tap toggle for the dark robotic machine HUD controls.
 * - In-app sharing and deletion with confirm dialog.
 * - Monospace machine headers, cyan status LED and glowing borders.
 */
class PhotoViewerActivity : Activity() {

    private val photoUris = ArrayList<String>()
    private var currentIndex = 0
    private var postTitle: String = "OmniDrop Photos"

    private lateinit var rootContainer: FrameLayout
    private lateinit var imageContainer: FrameLayout
    private lateinit var photoImageView: ImageView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var hudOverlay: FrameLayout
    private lateinit var topBarLayout: LinearLayout
    private lateinit var bottomBarLayout: LinearLayout
    private lateinit var titleTextView: TextView
    private lateinit var counterTextView: TextView
    private lateinit var titleFlashBannerView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isHudVisible = true
    private val hideHudRunnable = Runnable { setHudVisibility(false) }

    // Transformation matrix & zoom state
    private val baseMatrix = Matrix()
    private val displayMatrix = Matrix()
    private var currentBitmap: Bitmap? = null
    private var currentScale = 1.0f
    private val minScale = 1.0f
    private val maxScale = 4.0f

    // Gestures
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    companion object {
        fun recordCrash(context: Context, t: Throwable) {
            try {
                val file = java.io.File(context.filesDir, "last_crash.txt")
                val sw = java.io.StringWriter()
                val pw = java.io.PrintWriter(sw)
                t.printStackTrace(pw)
                val stackTrace = sw.toString()
                val content = "TIMESTAMP: ${java.util.Date()}\nCOMPONENT: PhotoViewerActivity\nERROR: ${t.javaClass.name}: ${t.message}\n\n$stackTrace\n"
                file.writeText(content, Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Read extras
            val incomingUris = intent.getStringArrayListExtra("photo_uris")
            if (incomingUris != null) {
                photoUris.addAll(incomingUris)
            }
            val singleUri = intent.getStringExtra("uri")
            if (photoUris.isEmpty() && !singleUri.isNullOrBlank()) {
                photoUris.add(singleUri)
            }

            currentIndex = intent.getIntExtra("initial_index", 0).coerceIn(0, (photoUris.size - 1).coerceAtLeast(0))
            postTitle = intent.getStringExtra("post_title") ?: "OmniDrop Photos"

            if (photoUris.isEmpty()) {
                Toast.makeText(this, "No photos to display", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            buildViewerUI()
            setupGestureDetectors()
            loadCurrentPhoto(animate = false, slideLeft = false)
            scheduleHudAutoHide()
        } catch (t: Throwable) {
            recordCrash(this, t)
            Toast.makeText(this, "Could not open the player", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun buildViewerUI() {
        rootContainer = FrameLayout(this).apply {
            this.setBackgroundColor(Color.BLACK)
        }

        // Image Container (hosts ImageView & loading spinner)
        imageContainer = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }

        photoImageView = ImageView(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
            this.scaleType = ImageView.ScaleType.MATRIX
        }
        imageContainer.addView(photoImageView)

        loadingSpinner = ProgressBar(this).apply {
            val lp = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
            this.visibility = View.GONE
        }
        imageContainer.addView(loadingSpinner)

        rootContainer.addView(imageContainer)

        // HUD Overlay
        hudOverlay = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            this.layoutParams = lp
        }

        buildTopBar()
        buildBottomBar()
        buildFlashBanner()

        rootContainer.addView(hudOverlay)
        setContentView(rootContainer)
    }

    private fun buildTopBar() {
        topBarLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding(dp(16), dp(16), dp(16), dp(20))
            this.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#E6080B10"), Color.TRANSPARENT)
            )
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.TOP
            }
            this.layoutParams = lp
        }

        // ‹ BACK button
        val backBtn = TextView(this).apply {
            this.text = "‹ BACK"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.MONOSPACE
            this.setPadding(dp(12), dp(6), dp(12), dp(6))
            this.background = createMechanicalButtonBackground(isRed = false)
            this.setOnClickListener {
                finish()
            }
        }
        topBarLayout.addView(backBtn)

        // Status LED
        val statusLed = View(this).apply {
            val ledLp = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                this.leftMargin = dp(12)
                this.rightMargin = dp(8)
            }
            this.layoutParams = ledLp
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.OVAL
                this.setColor(Color.parseColor("#00F0FF"))
            }
        }
        topBarLayout.addView(statusLed)

        // Title
        titleTextView = TextView(this).apply {
            this.text = postTitle
            this.setTextColor(Color.WHITE)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setSingleLine(true)
            this.ellipsize = android.text.TextUtils.TruncateAt.END
            val titleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = titleLp
        }
        topBarLayout.addView(titleTextView)

        // Counter Badge (e.g. "3 / 12")
        counterTextView = TextView(this).apply {
            this.text = "${currentIndex + 1} / ${photoUris.size}"
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.setPadding(dp(8), dp(4), dp(8), dp(4))
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(4).toFloat()
                this.setColor(Color.parseColor("#2200F0FF"))
                this.setStroke(dp(1), Color.parseColor("#3300F0FF"))
            }
            val counterLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.leftMargin = dp(8)
            }
            this.layoutParams = counterLp
        }
        topBarLayout.addView(counterTextView)

        hudOverlay.addView(topBarLayout)
    }

    private fun buildBottomBar() {
        bottomBarLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding(dp(20), dp(20), dp(20), dp(24))
            this.background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#F5080B10"), Color.TRANSPARENT)
            )
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.BOTTOM
            }
            this.layoutParams = lp
        }

        // Share button
        val shareBtn = TextView(this).apply {
            this.text = "⤴ SHARE"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(16), dp(8), dp(16), dp(8))
            this.background = createMechanicalButtonBackground(isRed = false)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                this.rightMargin = dp(10)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetHudAutoHideTimer()
                shareCurrentPhoto()
            }
        }
        bottomBarLayout.addView(shareBtn)

        // Delete button
        val deleteBtn = TextView(this).apply {
            this.text = "🗑 DELETE"
            this.setTextColor(Color.parseColor("#EF4444"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(16), dp(8), dp(16), dp(8))
            this.background = createMechanicalButtonBackground(isRed = true)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                this.leftMargin = dp(10)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetHudAutoHideTimer()
                confirmDeleteCurrentPhoto()
            }
        }
        bottomBarLayout.addView(deleteBtn)

        hudOverlay.addView(bottomBarLayout)
    }

    private fun buildFlashBanner() {
        titleFlashBannerView = TextView(this).apply {
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(16), dp(8), dp(16), dp(8))
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(8).toFloat()
                this.setColor(Color.parseColor("#E6080B10"))
                this.setStroke(dp(1), Color.parseColor("#00F0FF"))
            }
            this.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                this.topMargin = dp(76)
            }
            this.layoutParams = lp
        }
        hudOverlay.addView(titleFlashBannerView)
    }

    private fun createMechanicalButtonBackground(isRed: Boolean): LayerDrawable {
        val accentColor = if (isRed) Color.parseColor("#EF4444") else Color.parseColor("#00F0FF")
        val baseColor = Color.parseColor("#141A24")

        val border = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(6).toFloat()
            this.setStroke(dp(1), Color.parseColor("#27354A"))
            this.setColor(baseColor)
        }

        val highlight = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(6).toFloat()
            this.setStroke(dp(1), accentColor)
            this.setColor(Color.TRANSPARENT)
        }

        return LayerDrawable(arrayOf(border, highlight)).apply {
            this.setLayerInset(1, 0, 0, 0, 0)
        }
    }

    private fun setupGestureDetectors() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (currentBitmap == null) return false
                val factor = detector.scaleFactor
                val targetScale = (currentScale * factor).coerceIn(minScale, maxScale)
                val scaleChange = targetScale / currentScale
                currentScale = targetScale

                displayMatrix.postScale(scaleChange, scaleChange, detector.focusX, detector.focusY)
                checkAndClampBounds()
                photoImageView.imageMatrix = displayMatrix
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleHud()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentBitmap == null) return false
                resetHudAutoHideTimer()
                if (currentScale > 1.2f) {
                    resetZoom()
                } else {
                    val targetScale = 2.5f
                    val factor = targetScale / currentScale
                    currentScale = targetScale
                    displayMatrix.postScale(factor, factor, e.x, e.y)
                    checkAndClampBounds()
                    photoImageView.imageMatrix = displayMatrix
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (currentBitmap == null) return false
                if (currentScale > 1.05f) {
                    resetHudAutoHideTimer()
                    displayMatrix.postTranslate(-distanceX, -distanceY)
                    checkAndClampBounds()
                    photoImageView.imageMatrix = displayMatrix
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (currentScale > 1.05f) return false
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > dp(50) && Math.abs(velocityX) > 300) {
                    if (diffX < 0) {
                        // Swiped Left -> Next Photo
                        showNextPhoto()
                        return true
                    } else {
                        // Swiped Right -> Previous Photo
                        showPreviousPhoto()
                        return true
                    }
                }
                return false
            }
        })

        rootContainer.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun checkAndClampBounds() {
        val bmp = currentBitmap ?: return
        val viewWidth = photoImageView.width.toFloat()
        val viewHeight = photoImageView.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return

        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        displayMatrix.mapRect(rect)

        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= viewWidth) {
            deltaX = (viewWidth - rect.width()) / 2f - rect.left
        } else if (rect.left > 0) {
            deltaX = -rect.left
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right
        }

        if (rect.height() <= viewHeight) {
            deltaY = (viewHeight - rect.height()) / 2f - rect.top
        } else if (rect.top > 0) {
            deltaY = -rect.top
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom
        }

        displayMatrix.postTranslate(deltaX, deltaY)
    }

    private fun resetZoom() {
        currentScale = 1.0f
        displayMatrix.set(baseMatrix)
        photoImageView.imageMatrix = displayMatrix
    }

    private fun fitImageToScreen() {
        val bmp = currentBitmap ?: return
        val viewWidth = photoImageView.width.toFloat()
        val viewHeight = photoImageView.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return

        val src = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val dst = RectF(0f, 0f, viewWidth, viewHeight)

        baseMatrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)
        displayMatrix.set(baseMatrix)
        currentScale = 1.0f
        photoImageView.imageMatrix = displayMatrix
    }

    private fun loadCurrentPhoto(animate: Boolean, slideLeft: Boolean) {
        if (photoUris.isEmpty()) {
            finish()
            return
        }

        val uriStr = photoUris[currentIndex]
        counterTextView.text = "${currentIndex + 1} / ${photoUris.size}"

        if (animate) {
            val width = rootContainer.width.toFloat().coerceAtLeast(600f)
            val exitTranslation = if (slideLeft) -width else width
            val enterTranslation = if (slideLeft) width else -width

            imageContainer.animate()
                .translationX(exitTranslation)
                .alpha(0.3f)
                .setDuration(160)
                .withEndAction {
                    decodeAndDisplayBitmap(uriStr)
                    imageContainer.translationX = enterTranslation
                    imageContainer.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .start()
                }
                .start()
        } else {
            decodeAndDisplayBitmap(uriStr)
        }

        flashTitleBanner("${currentIndex + 1} / ${photoUris.size}")
    }

    private fun decodeAndDisplayBitmap(uriStr: String) {
        loadingSpinner.visibility = View.VISIBLE

        Thread {
            val metrics = resources.displayMetrics
            val reqW = metrics.widthPixels
            val reqH = metrics.heightPixels

            var loadedBitmap: Bitmap? = null
            try {
                val uri = Uri.parse(uriStr)

                // 1. Measure dimensions
                val options = BitmapFactory.Options().apply {
                    this.inJustDecodeBounds = true
                }
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }

                if (options.outWidth > 0 && options.outHeight > 0) {
                    var sampleSize = 1
                    val halfH = options.outHeight / 2
                    val halfW = options.outWidth / 2
                    while ((halfH / sampleSize) >= reqH && (halfW / sampleSize) >= reqW) {
                        sampleSize *= 2
                    }

                    val decodeOptions = BitmapFactory.Options().apply {
                        this.inSampleSize = sampleSize
                        this.inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    contentResolver.openInputStream(uri)?.use { stream ->
                        loadedBitmap = BitmapFactory.decodeStream(stream, null, decodeOptions)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            mainHandler.post {
                loadingSpinner.visibility = View.GONE
                if (loadedBitmap != null) {
                    currentBitmap = loadedBitmap
                    photoImageView.setImageBitmap(loadedBitmap)
                    photoImageView.post {
                        fitImageToScreen()
                    }
                } else {
                    Toast.makeText(this@PhotoViewerActivity, "Unable to load image", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showNextPhoto() {
        if (currentIndex < photoUris.size - 1) {
            currentIndex++
            resetHudAutoHideTimer()
            loadCurrentPhoto(animate = true, slideLeft = true)
        } else {
            flashTitleBanner("END OF ALBUM")
        }
    }

    private fun showPreviousPhoto() {
        if (currentIndex > 0) {
            currentIndex--
            resetHudAutoHideTimer()
            loadCurrentPhoto(animate = true, slideLeft = false)
        } else {
            flashTitleBanner("FIRST PHOTO")
        }
    }

    private fun shareCurrentPhoto() {
        if (photoUris.isEmpty()) return
        try {
            val uri = Uri.parse(photoUris[currentIndex])
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                this.type = "image/*"
                this.putExtra(Intent.EXTRA_STREAM, uri)
                this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share OmniDrop Photo"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share photo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteCurrentPhoto() {
        if (photoUris.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("DELETE PHOTO // CONFIRM")
            .setMessage("Delete this image from OmniDrop and your device gallery?")
            .setPositiveButton("DELETE") { _, _ ->
                deleteCurrentPhoto()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun deleteCurrentPhoto() {
        if (photoUris.isEmpty()) return
        val targetUriStr = photoUris[currentIndex]

        Thread {
            try {
                val uri = Uri.parse(targetUriStr)
                contentResolver.delete(uri, null, null)
            } catch (e: SecurityException) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try {
                        val pending = android.provider.MediaStore.createDeleteRequest(contentResolver, listOf(Uri.parse(targetUriStr)))
                        startIntentSenderForResult(pending.intentSender, 7103, null, 0, 0, 0)
                        return@Thread
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            mainHandler.post {
                removePhotoAtCurrentIndex()
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7103 && resultCode == Activity.RESULT_OK) {
            removePhotoAtCurrentIndex()
        }
    }

    private fun removePhotoAtCurrentIndex() {
        if (photoUris.isEmpty()) return
        photoUris.removeAt(currentIndex)
        if (photoUris.isEmpty()) {
            Toast.makeText(this@PhotoViewerActivity, "All photos deleted", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            if (currentIndex >= photoUris.size) {
                currentIndex = photoUris.size - 1
            }
            loadCurrentPhoto(animate = false, slideLeft = false)
        }
    }

    private fun flashTitleBanner(text: String) {
        titleFlashBannerView.text = text
        titleFlashBannerView.visibility = View.VISIBLE
        titleFlashBannerView.alpha = 1f
        mainHandler.removeCallbacks(hideTitleFlashRunnable)
        mainHandler.postDelayed(hideTitleFlashRunnable, 1600)
    }

    private val hideTitleFlashRunnable = Runnable {
        titleFlashBannerView.animate().alpha(0f).setDuration(250).withEndAction {
            titleFlashBannerView.visibility = View.GONE
        }.start()
    }

    private fun toggleHud() {
        setHudVisibility(!isHudVisible)
    }

    private fun setHudVisibility(visible: Boolean) {
        isHudVisible = visible
        mainHandler.removeCallbacks(hideHudRunnable)
        val alphaTarget = if (visible) 1f else 0f

        topBarLayout.animate().alpha(alphaTarget).setDuration(200).withEndAction {
            topBarLayout.visibility = if (visible) View.VISIBLE else View.GONE
        }.start()

        bottomBarLayout.animate().alpha(alphaTarget).setDuration(200).withEndAction {
            bottomBarLayout.visibility = if (visible) View.VISIBLE else View.GONE
        }.start()

        if (visible) {
            scheduleHudAutoHide()
        }
    }

    private fun scheduleHudAutoHide() {
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, 4500)
    }

    private fun resetHudAutoHideTimer() {
        if (!isHudVisible) {
            setHudVisibility(true)
        } else {
            scheduleHudAutoHide()
        }
    }

    override fun onResume() {
        super.onResume()
        FloatingBubbleService.setOmniDropForeground(true)
    }

    override fun onPause() {
        super.onPause()
        FloatingBubbleService.setOmniDropForeground(false)
    }

    override fun onStop() {
        super.onStop()
        FloatingBubbleService.setOmniDropForeground(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        FloatingBubbleService.setOmniDropForeground(false)
        mainHandler.removeCallbacksAndMessages(null)
    }
}
