package com.omnidrop.app.plugin

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView

/**
 * Native in-app video player built with pure Android SDK (VideoView).
 * Features a dark robotic hardware aesthetic matching OmniDrop's machine panels.
 */
class VideoPlayerActivity : Activity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var controlsOverlay: FrameLayout
    private lateinit var topBarLayout: LinearLayout
    private lateinit var centerControlsLayout: LinearLayout
    private lateinit var bottomBarLayout: LinearLayout

    private lateinit var titleTextView: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var skipBackwardButton: TextView
    private lateinit var skipForwardButton: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var speedButton: TextView
    private lateinit var rotateButton: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var isControlsVisible = true
    private var isUserTrackingSeek = false
    private var currentSpeedIndex = 1 // 1.0x by default
    private val speedOptions = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "1.0x", "1.5x", "2.0x")

    private var savedPlaybackPosition = 0
    private var videoUri: Uri? = null
    private var videoTitle: String = "OmniDrop Video"

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isUserTrackingSeek && videoView.isPlaying) {
                val current = videoView.currentPosition
                val total = videoView.duration
                if (total > 0) {
                    seekBar.max = total
                    seekBar.progress = current
                    currentTimeTextView.text = formatTime(current)
                    totalTimeTextView.text = formatTime(total)
                }
            }
            mainHandler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake during video playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Parse extras and intent data
        val uriExtra = intent.data ?: intent.getStringExtra("video_uri")?.let { Uri.parse(it) }
        videoUri = uriExtra
        videoTitle = intent.getStringExtra("video_title") ?: "OmniDrop Video"

        if (savedInstanceState != null) {
            savedPlaybackPosition = savedInstanceState.getInt("saved_pos", 0)
            currentSpeedIndex = savedInstanceState.getInt("saved_speed", 1)
        }

        buildPlayerUI()
        setupVideoView()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun buildPlayerUI() {
        rootContainer = FrameLayout(this).apply {
            this.setBackgroundColor(Color.parseColor("#080B10"))
        }

        // 1. VideoView centered
        videoView = VideoView(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }
        rootContainer.addView(videoView)

        // 2. Fullscreen overlay for controls & gesture detection
        controlsOverlay = FrameLayout(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Tap on root to toggle controls
        rootContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                toggleControls()
            }
            true
        }

        buildTopBar()
        buildCenterControls()
        buildBottomBar()

        rootContainer.addView(controlsOverlay)
        setContentView(rootContainer)

        scheduleControlsAutoHide()
    }

    private fun buildTopBar() {
        topBarLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding(dp(16), dp(16), dp(16), dp(14))
            this.background = createTopBevelBarBackground()
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.TOP
            }
            this.layoutParams = lp
        }

        // Back button
        val backBtn = TextView(this).apply {
            this.text = "‹ BACK"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = Typeface.MONOSPACE
            this.setPadding(dp(12), dp(6), dp(12), dp(6))
            this.background = createMechanicalButtonBackground(false)
            this.setOnClickListener {
                finish()
            }
        }
        topBarLayout.addView(backBtn)

        // Status LED
        val statusLed = View(this).apply {
            val ledLp = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
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
            this.text = videoTitle
            this.setTextColor(Color.WHITE)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setSingleLine(true)
            this.ellipsize = android.text.TextUtils.TruncateAt.END
            val titleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = titleLp
        }
        topBarLayout.addView(titleTextView)

        controlsOverlay.addView(topBarLayout)
    }

    private fun buildCenterControls() {
        centerControlsLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }

        // -10s skip
        skipBackwardButton = TextView(this).apply {
            this.text = "-10s"
            this.setTextColor(Color.parseColor("#38BDF8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(16), dp(12), dp(16), dp(12))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(24)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                val target = (videoView.currentPosition - 10000).coerceAtLeast(0)
                videoView.seekTo(target)
            }
        }
        centerControlsLayout.addView(skipBackwardButton)

        // Big Play/Pause button
        playPauseButton = TextView(this).apply {
            this.text = "❚❚"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.gravity = Gravity.CENTER
            val size = dp(64)
            val lp = LinearLayout.LayoutParams(size, size)
            this.layoutParams = lp
            this.background = createPlayPauseBevelBackground()
            this.setOnClickListener {
                resetAutoHideTimer()
                togglePlayback()
            }
        }
        centerControlsLayout.addView(playPauseButton)

        // +10s skip
        skipForwardButton = TextView(this).apply {
            this.text = "+10s"
            this.setTextColor(Color.parseColor("#38BDF8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(16), dp(12), dp(16), dp(12))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.leftMargin = dp(24)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                val target = (videoView.currentPosition + 10000).coerceAtMost(videoView.duration)
                videoView.seekTo(target)
            }
        }
        centerControlsLayout.addView(skipForwardButton)

        controlsOverlay.addView(centerControlsLayout)
    }

    private fun buildBottomBar() {
        bottomBarLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.setPadding(dp(16), dp(10), dp(16), dp(16))
            this.background = createBottomBevelBarBackground()
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.BOTTOM
            }
            this.layoutParams = lp
        }

        // SeekBar row with times
        val seekRow = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        currentTimeTextView = TextView(this).apply {
            this.text = "00:00"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.MONOSPACE
        }
        seekRow.addView(currentTimeTextView)

        seekBar = SeekBar(this).apply {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                this.leftMargin = dp(8)
                this.rightMargin = dp(8)
            }
            this.layoutParams = lp
            this.progressTintList = ColorStateList.valueOf(Color.parseColor("#00F0FF"))
            this.thumbTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            this.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        currentTimeTextView.text = formatTime(progress)
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserTrackingSeek = true
                    resetAutoHideTimer()
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserTrackingSeek = false
                    sb?.let {
                        videoView.seekTo(it.progress)
                    }
                    resetAutoHideTimer()
                }
            })
        }
        seekRow.addView(seekBar)

        totalTimeTextView = TextView(this).apply {
            this.text = "00:00"
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.MONOSPACE
        }
        seekRow.addView(totalTimeTextView)

        bottomBarLayout.addView(seekRow)

        // Secondary controls: Speed & Fullscreen/Rotate
        val actionRow = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.topMargin = dp(8)
            }
            this.layoutParams = lp
        }

        // Hardware indicator label
        val hardwareLabel = TextView(this).apply {
            this.text = "OMNIDROP ENGINE // VIDEO DECODER"
            this.setTextColor(Color.parseColor("#475569"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            this.typeface = Typeface.MONOSPACE
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = lp
        }
        actionRow.addView(hardwareLabel)

        // Speed toggle button
        speedButton = TextView(this).apply {
            this.text = speedLabels[currentSpeedIndex]
            this.setTextColor(Color.parseColor("#F59E0B"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.setPadding(dp(10), dp(5), dp(10), dp(5))
            this.background = createMechanicalButtonBackground(true)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(8)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                cyclePlaybackSpeed()
            }
        }
        actionRow.addView(speedButton)

        // Fullscreen/Rotate toggle button
        rotateButton = TextView(this).apply {
            this.text = "[ ⛶ ROTATE ]"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.setPadding(dp(10), dp(5), dp(10), dp(5))
            this.background = createMechanicalButtonBackground(false)
            this.setOnClickListener {
                resetAutoHideTimer()
                toggleOrientation()
            }
        }
        actionRow.addView(rotateButton)

        bottomBarLayout.addView(actionRow)

        controlsOverlay.addView(bottomBarLayout)
    }

    private fun setupVideoView() {
        if (videoUri == null) {
            finish()
            return
        }

        videoView.setVideoURI(videoUri)

        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            applyPlaybackSpeed()

            val duration = videoView.duration
            if (duration > 0) {
                seekBar.max = duration
                totalTimeTextView.text = formatTime(duration)
            }

            if (savedPlaybackPosition > 0) {
                videoView.seekTo(savedPlaybackPosition)
            }
            videoView.start()
            playPauseButton.text = "❚❚"
            mainHandler.post(updateProgressRunnable)
        }

        videoView.setOnCompletionListener {
            playPauseButton.text = "▶"
            showControls()
        }

        videoView.setOnErrorListener { _, _, _ ->
            showControls()
            false
        }
    }

    private fun togglePlayback() {
        if (videoView.isPlaying) {
            videoView.pause()
            playPauseButton.text = "▶"
        } else {
            videoView.start()
            playPauseButton.text = "❚❚"
            scheduleControlsAutoHide()
        }
    }

    private fun cyclePlaybackSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
        speedButton.text = speedLabels[currentSpeedIndex]
        applyPlaybackSpeed()
    }

    private fun applyPlaybackSpeed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayer?.let { mp ->
                    val params = mp.playbackParams
                    params.speed = speedOptions[currentSpeedIndex]
                    mp.playbackParams = params
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun toggleOrientation() {
        val currentOrientation = resources.configuration.orientation
        requestedOrientation = if (currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun toggleControls() {
        if (isControlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        isControlsVisible = true
        controlsOverlay.visibility = View.VISIBLE
        scheduleControlsAutoHide()
    }

    private fun hideControls() {
        if (!videoView.isPlaying) return // Don't hide if paused
        isControlsVisible = false
        controlsOverlay.visibility = View.GONE
    }

    private fun resetAutoHideTimer() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        scheduleControlsAutoHide()
    }

    private fun scheduleControlsAutoHide() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 3000)
    }

    private fun formatTime(millis: Int): String {
        val totalSecs = (millis / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }

    // -------------------------------------------------------------------------
    // GRAPHIC DRAWABLES (ROBOTIC BEVELED MACHINE STYLE)
    // -------------------------------------------------------------------------
    private fun createTopBevelBarBackground(): LayerDrawable {
        val baseGradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#F00B0E14"), Color.parseColor("#D0151A24"))
        )

        val bottomBorder = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.setColor(Color.parseColor("#1E293B"))
        }

        val layers = arrayOf(baseGradient, bottomBorder)
        return LayerDrawable(layers).apply {
            this.setLayerInset(0, 0, 0, 0, dp(1))
            this.setLayerInset(1, 0, 0, 0, 0)
        }
    }

    private fun createBottomBevelBarBackground(): LayerDrawable {
        val baseGradient = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.parseColor("#F5080B10"), Color.parseColor("#E00F172A"))
        )

        val topBorder = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.setColor(Color.parseColor("#1E293B"))
        }

        val layers = arrayOf(topBorder, baseGradient)
        return LayerDrawable(layers).apply {
            this.setLayerInset(0, 0, 0, 0, 0)
            this.setLayerInset(1, 0, dp(1), 0, 0)
        }
    }

    private fun createMechanicalButtonBackground(isAmber: Boolean): LayerDrawable {
        val accentColor = if (isAmber) Color.parseColor("#F59E0B") else Color.parseColor("#00F0FF")
        val baseColor = Color.parseColor("#1A2234")

        val border = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(6).toFloat()
            this.setStroke(dp(1), Color.parseColor("#334155"))
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

    private fun createPlayPauseBevelBackground(): LayerDrawable {
        val shadow = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            this.setColor(Color.parseColor("#000000"))
        }

        val body = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#0F172A"))
        ).apply {
            this.shape = GradientDrawable.OVAL
            this.setStroke(dp(2), Color.parseColor("#00F0FF"))
        }

        return LayerDrawable(arrayOf(shadow, body)).apply {
            this.setLayerInset(0, 0, dp(2), 0, 0)
            this.setLayerInset(1, 0, 0, 0, dp(2))
        }
    }

    // -------------------------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------------------------
    override fun onPause() {
        super.onPause()
        savedPlaybackPosition = videoView.currentPosition
        if (videoView.isPlaying) {
            videoView.pause()
            playPauseButton.text = "▶"
        }
        mainHandler.removeCallbacks(updateProgressRunnable)
        mainHandler.removeCallbacks(hideControlsRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (savedPlaybackPosition > 0) {
            videoView.seekTo(savedPlaybackPosition)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("saved_pos", videoView.currentPosition)
        outState.putInt("saved_speed", currentSpeedIndex)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        videoView.stopPlayback()
    }
}
