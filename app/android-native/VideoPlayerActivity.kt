package com.omnidrop.app.plugin

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.util.Locale

/**
 * Modern In-App Hardware Video Player.
 * Built with pure Android SDK (VideoView + Canvas/Drawables).
 * Features:
 * - Clear picture: unobstructed central viewport.
 * - Modern HUD: sleek top bar + bottom controls bar with gradient fade overlays.
 * - 3s auto-hide with immersive full-screen sticky system bars.
 * - Double-tap left/right gesture skip (-10s / +10s) with glowing animated ripple indicators.
 * - Styled seekbar with target time tooltip during drag.
 * - Auto-orientation from video aspect ratio + seamless manual rotate lock.
 * - Persistent per-video playback resumption saved in SharedPreferences.
 */
class VideoPlayerActivity : Activity() {

    companion object {
        private const val PREFS_PLAYBACK = "omnidrop_playback_positions"
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var hudOverlay: FrameLayout
    private lateinit var topBarLayout: LinearLayout
    private lateinit var bottomBarLayout: LinearLayout

    private lateinit var titleTextView: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var skipBackwardButton: TextView
    private lateinit var skipForwardButton: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var seekTooltipTextView: TextView
    private lateinit var speedButton: TextView
    private lateinit var rotateButton: TextView

    // Feedback overlays for gestures
    private lateinit var leftRippleView: TextView
    private lateinit var rightRippleView: TextView
    private lateinit var centerFlashView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var isControlsVisible = true
    private var isUserTrackingSeek = false
    private var isOrientationLockedByUser = false
    private var currentSpeedIndex = 1 // 1.0x by default
    private val speedOptions = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "1.0x", "1.5x", "2.0x")

    private var videoUri: Uri? = null
    private var videoTitle: String = "OmniDrop Video"
    private lateinit var gestureDetector: GestureDetector

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uriExtra = intent.data ?: intent.getStringExtra("video_uri")?.let { Uri.parse(it) }
        videoUri = uriExtra
        videoTitle = intent.getStringExtra("video_title") ?: "OmniDrop Video"

        if (savedInstanceState != null) {
            currentSpeedIndex = savedInstanceState.getInt("saved_speed", 1)
            isOrientationLockedByUser = savedInstanceState.getBoolean("orient_locked", false)
        }

        buildPlayerUI()
        setupGestures()
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
            this.setBackgroundColor(Color.BLACK)
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

        // 2. Gesture overlay layer (transparent touch interceptor)
        val touchOverlay = View(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(touchOverlay)

        // 3. Gesture feedback indicators (ripples)
        buildRippleViews()

        // 4. Modern HUD Overlay (Top bar + Bottom bar)
        hudOverlay = FrameLayout(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        buildTopBar()
        buildBottomBar()

        rootContainer.addView(hudOverlay)
        setContentView(rootContainer)

        scheduleControlsAutoHide()
    }

    private fun buildRippleViews() {
        val pillBg = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(20).toFloat()
            this.setColor(Color.parseColor("#CC0B0E14"))
            this.setStroke(dp(1), Color.parseColor("#00F0FF"))
        }

        // Left -10s ripple
        leftRippleView = TextView(this).apply {
            this.text = "⟲ -10s"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(18), dp(10), dp(18), dp(10))
            this.background = pillBg
            this.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                this.leftMargin = dp(40)
            }
            this.layoutParams = lp
        }
        rootContainer.addView(leftRippleView)

        // Right +10s ripple
        val pillBgRight = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(20).toFloat()
            this.setColor(Color.parseColor("#CC0B0E14"))
            this.setStroke(dp(1), Color.parseColor("#00F0FF"))
        }
        rightRippleView = TextView(this).apply {
            this.text = "+10s ⟳"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(18), dp(10), dp(18), dp(10))
            this.background = pillBgRight
            this.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                this.rightMargin = dp(40)
            }
            this.layoutParams = lp
        }
        rootContainer.addView(rightRippleView)

        // Center flash indicator (play/pause tap feedback)
        val centerBg = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            this.setColor(Color.parseColor("#B3000000"))
            this.setStroke(dp(2), Color.parseColor("#00F0FF"))
        }
        centerFlashView = TextView(this).apply {
            this.text = "❚❚"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.gravity = Gravity.CENTER
            this.background = centerBg
            this.visibility = View.GONE
            val size = dp(64)
            val lp = FrameLayout.LayoutParams(size, size).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }
        rootContainer.addView(centerFlashView)
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val screenWidth = rootContainer.width
                if (e.x < screenWidth / 2) {
                    // Double tap left: skip back 10s
                    val target = (videoView.currentPosition - 10000).coerceAtLeast(0)
                    videoView.seekTo(target)
                    flashRippleIndicator(leftRippleView)
                } else {
                    // Double tap right: skip forward 10s
                    val target = (videoView.currentPosition + 10000).coerceAtMost(videoView.duration)
                    videoView.seekTo(target)
                    flashRippleIndicator(rightRippleView)
                }
                resetAutoHideTimer()
                return true
            }
        })

        rootContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun flashRippleIndicator(view: TextView) {
        view.visibility = View.VISIBLE
        val anim = AlphaAnimation(1f, 0f).apply {
            this.duration = 600
            this.fillAfter = false
        }
        view.startAnimation(anim)
        mainHandler.postDelayed({ view.visibility = View.GONE }, 600)
    }

    private fun flashCenterIcon(symbol: String) {
        centerFlashView.text = symbol
        centerFlashView.visibility = View.VISIBLE
        val anim = AlphaAnimation(1f, 0f).apply {
            this.duration = 500
            this.fillAfter = false
        }
        centerFlashView.startAnimation(anim)
        mainHandler.postDelayed({ centerFlashView.visibility = View.GONE }, 500)
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

        // Back button
        val backBtn = TextView(this).apply {
            this.text = "‹ BACK"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
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

        // Video Title
        titleTextView = TextView(this).apply {
            this.text = videoTitle
            this.setTextColor(Color.WHITE)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setSingleLine(true)
            this.ellipsize = android.text.TextUtils.TruncateAt.END
            val titleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = titleLp
        }
        topBarLayout.addView(titleTextView)

        hudOverlay.addView(topBarLayout)
    }

    private fun buildBottomBar() {
        bottomBarLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.setPadding(dp(16), dp(14), dp(16), dp(18))
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

        // Target time tooltip above seekbar
        seekTooltipTextView = TextView(this).apply {
            this.text = "00:00"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(8), dp(3), dp(8), dp(3))
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(4).toFloat()
                this.setColor(Color.parseColor("#DD0B0E14"))
                this.setStroke(dp(1), Color.parseColor("#00F0FF"))
            }
            this.visibility = View.INVISIBLE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.CENTER_HORIZONTAL
                this.bottomMargin = dp(2)
            }
            this.layoutParams = lp
        }
        bottomBarLayout.addView(seekTooltipTextView)

        // SeekBar Row
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
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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
                        val formatted = formatTime(progress)
                        currentTimeTextView.text = formatted
                        seekTooltipTextView.text = formatted
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserTrackingSeek = true
                    seekTooltipTextView.visibility = View.VISIBLE
                    resetAutoHideTimer()
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserTrackingSeek = false
                    seekTooltipTextView.visibility = View.INVISIBLE
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
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
        }
        seekRow.addView(totalTimeTextView)
        bottomBarLayout.addView(seekRow)

        // Bottom Controls Row: -10s, Play/Pause, +10s, Speed, Rotate
        val controlsRow = LinearLayout(this).apply {
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

        // -10s button
        skipBackwardButton = TextView(this).apply {
            this.text = "-10s"
            this.setTextColor(Color.parseColor("#38BDF8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(12), dp(6), dp(12), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(8)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                val target = (videoView.currentPosition - 10000).coerceAtLeast(0)
                videoView.seekTo(target)
                flashRippleIndicator(leftRippleView)
            }
        }
        controlsRow.addView(skipBackwardButton)

        // Play/Pause button
        playPauseButton = TextView(this).apply {
            this.text = "❚❚"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.gravity = Gravity.CENTER
            this.setPadding(dp(16), dp(6), dp(16), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(8)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                togglePlayback()
            }
        }
        controlsRow.addView(playPauseButton)

        // +10s button
        skipForwardButton = TextView(this).apply {
            this.text = "+10s"
            this.setTextColor(Color.parseColor("#38BDF8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(12), dp(6), dp(12), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                val target = (videoView.currentPosition + 10000).coerceAtMost(videoView.duration)
                videoView.seekTo(target)
                flashRippleIndicator(rightRippleView)
            }
        }
        controlsRow.addView(skipForwardButton)

        // Spacer
        val spacer = View(this).apply {
            val lp = LinearLayout.LayoutParams(0, 1, 1f)
            this.layoutParams = lp
        }
        controlsRow.addView(spacer)

        // Speed button
        speedButton = TextView(this).apply {
            this.text = speedLabels[currentSpeedIndex]
            this.setTextColor(Color.parseColor("#F59E0B"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(10), dp(6), dp(10), dp(6))
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
        controlsRow.addView(speedButton)

        // Rotate button
        rotateButton = TextView(this).apply {
            this.text = "[ ⛶ ROTATE ]"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(10), dp(6), dp(10), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                toggleOrientation()
            }
        }
        controlsRow.addView(rotateButton)

        bottomBarLayout.addView(controlsRow)
        hudOverlay.addView(bottomBarLayout)
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

            // Auto orientation from video aspect ratio if not locked by user
            val width = mp.videoWidth
            val height = mp.videoHeight
            if (width > 0 && height > 0 && !isOrientationLockedByUser) {
                if (width > height) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            // Restore saved playback position
            val uriStr = videoUri?.toString() ?: ""
            if (uriStr.isNotBlank()) {
                val prefs = getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
                val savedPos = prefs.getInt(uriStr, 0)
                if (savedPos > 1000 && duration > 0 && savedPos < duration - 3000) {
                    videoView.seekTo(savedPos)
                }
            }

            videoView.start()
            playPauseButton.text = "❚❚"
            mainHandler.post(updateProgressRunnable)
        }

        videoView.setOnCompletionListener {
            playPauseButton.text = "▶"
            showControls()

            // Clear saved playback position when playback reaches the end
            val uriStr = videoUri?.toString() ?: ""
            if (uriStr.isNotBlank()) {
                val prefs = getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    this.remove(uriStr)
                    this.apply()
                }
            }
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
            flashCenterIcon("▶")
        } else {
            videoView.start()
            playPauseButton.text = "❚❚"
            flashCenterIcon("❚❚")
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
        isOrientationLockedByUser = true
        val current = resources.configuration.orientation
        requestedOrientation = if (current == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Configuration handled seamlessly without activity recreation
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
        hudOverlay.visibility = View.VISIBLE
        showSystemUI()
        scheduleControlsAutoHide()
    }

    private fun hideControls() {
        if (!videoView.isPlaying) return
        isControlsVisible = false
        hudOverlay.visibility = View.GONE
        hideSystemUI()
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
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
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private fun saveCurrentPlaybackPosition() {
        val uriStr = videoUri?.toString() ?: ""
        if (uriStr.isNotBlank()) {
            val current = videoView.currentPosition
            val total = videoView.duration
            val prefs = getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (total > 0 && current < total - 2000 && current > 1000) {
                    this.putInt(uriStr, current)
                } else if (current >= total - 2000) {
                    this.remove(uriStr)
                }
                this.apply()
            }
        }
    }

    private fun createMechanicalButtonBackground(isAmber: Boolean): LayerDrawable {
        val accentColor = if (isAmber) Color.parseColor("#F59E0B") else Color.parseColor("#00F0FF")
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

    override fun onPause() {
        super.onPause()
        saveCurrentPlaybackPosition()
        if (videoView.isPlaying) {
            videoView.pause()
            playPauseButton.text = "▶"
        }
        mainHandler.removeCallbacks(updateProgressRunnable)
        mainHandler.removeCallbacks(hideControlsRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (isControlsVisible) {
            scheduleControlsAutoHide()
        } else {
            hideSystemUI()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("saved_speed", currentSpeedIndex)
        outState.putBoolean("orient_locked", isOrientationLockedByUser)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPlaybackPosition()
        mainHandler.removeCallbacksAndMessages(null)
        videoView.stopPlayback()
    }
}
