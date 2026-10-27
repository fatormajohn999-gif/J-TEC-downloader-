package com.omnidrop.app.plugin

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Modern In-App Hardware Video Player.
 * Built with pure Android SDK (VideoView + Canvas/Drawables).
 * Features:
 * - Playlist support: seamlessly browse filtered library clips.
 * - Previous (⏮) and Next (⏭) mechanical keys cycling playlist.
 * - Custom navy/cyan seekbar and round cyan/white thumb.
 * - Center-crop FIT / FILL scaling.
 * - Left vertical drag brightness & right vertical drag volume HUD.
 * - Swipe left/right gestures for next/previous video with smooth slide animations.
 * - Flash HUD banner ("X / Y - Title") and thumbnail placeholder during loading.
 * - Autoplay & Repeat modes: OFF, ONE, ALL with cycling mechanical button & LED indicator.
 * - Auto-rotation from video aspect ratio + manual orientation lock.
 * - Persistent per-video playback resumption saved in SharedPreferences.
 */
class VideoPlayerActivity : Activity() {

    companion object {
        private const val PREFS_PLAYBACK = "omnidrop_playback_positions"
        private const val PREFS_PLAYER_UI = "omnidrop_player_prefs"
        private const val KEY_SWIPE_HINT_SHOWN = "swipe_hint_shown"

        private val sharedPlaylist = ArrayList<PlaylistItem>()
        var sessionFillMode: Boolean = false

        fun setPlaylist(items: List<PlaylistItem>) {
            sharedPlaylist.clear()
            sharedPlaylist.addAll(items)
        }

        fun getPlaylist(): List<PlaylistItem> {
            return ArrayList(sharedPlaylist)
        }

        fun recordCrash(context: Context, t: Throwable) {
            try {
                val file = java.io.File(context.filesDir, "last_crash.txt")
                val sw = java.io.StringWriter()
                val pw = java.io.PrintWriter(sw)
                t.printStackTrace(pw)
                val stackTrace = sw.toString()
                val content = "TIMESTAMP: ${java.util.Date()}\nCOMPONENT: VideoPlayerActivity\nERROR: ${t.javaClass.name}: ${t.message}\n\n$stackTrace\n"
                file.writeText(content, Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    data class PlaylistItem(
        val uri: String,
        val title: String,
        val platform: String? = null
    )

    private val playlist = ArrayList<PlaylistItem>()
    private var currentIndex = 0

    private lateinit var rootContainer: FrameLayout
    private lateinit var videoContentContainer: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var thumbnailImageView: ImageView
    private lateinit var hudOverlay: FrameLayout
    private lateinit var topBarLayout: LinearLayout
    private lateinit var bottomBarLayout: LinearLayout

    private lateinit var titleTextView: TextView
    private lateinit var playlistCounterTextView: TextView
    private lateinit var prevVideoBtn: TextView
    private lateinit var nextVideoBtn: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var skipBackwardButton: TextView
    private lateinit var skipForwardButton: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var seekTooltipTextView: TextView
    private lateinit var fitFillButton: TextView
    private lateinit var speedButton: TextView
    private lateinit var rotateButton: TextView
    private lateinit var repeatButton: LinearLayout
    private lateinit var repeatLabelTextView: TextView
    private lateinit var repeatLedView: View

    // Feedback overlays for gestures & cues
    private lateinit var leftRippleView: TextView
    private lateinit var rightRippleView: TextView
    private lateinit var centerFlashView: TextView
    private lateinit var titleFlashBannerView: TextView
    private lateinit var swipeHintOverlay: LinearLayout
    private lateinit var errorOverlay: LinearLayout

    // Brightness and Volume Robotic HUD
    private lateinit var gestureHudCard: LinearLayout
    private lateinit var gestureHudIcon: TextView
    private lateinit var gestureHudValue: TextView
    private lateinit var gestureHudProgress: View
    private lateinit var audioManager: AudioManager
    private var touchSlop: Int = 16

    private enum class TouchMode { NONE, VERTICAL_BRIGHTNESS, VERTICAL_VOLUME, HORIZONTAL_FLING }
    private var activeTouchMode = TouchMode.NONE
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var initialBrightness: Float = 0.5f
    private var initialVolume: Int = 0

    private val hideGestureHudRunnable = Runnable {
        gestureHudCard.animate().alpha(0f).setDuration(250).withEndAction {
            gestureHudCard.visibility = View.GONE
        }.start()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var isControlsVisible = true
    private var isUserTrackingSeek = false
    private var isOrientationLockedByUser = false
    private var currentSpeedIndex = 1 // 1.0x by default
    private val speedOptions = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "1.0x", "1.5x", "2.0x")

    // Repeat modes: "off", "one", "all"
    private var currentRepeatMode = "off"
    private var autoPlayNext = true

    private var currentVideoUri: Uri? = null
    private var currentVideoTitle: String = "OmniDrop Video"
    private lateinit var gestureDetector: GestureDetector

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    private val hideTitleFlashRunnable = Runnable {
        titleFlashBannerView.animate().alpha(0f).setDuration(300).withEndAction {
            titleFlashBannerView.visibility = View.GONE
        }.start()
    }

    private val hideSwipeHintRunnable = Runnable {
        dismissSwipeHint()
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
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            touchSlop = ViewConfiguration.get(this).scaledTouchSlop

            parseIntentData()
            loadNativeSettings()

            if (savedInstanceState != null) {
                currentSpeedIndex = savedInstanceState.getInt("saved_speed", 1)
                isOrientationLockedByUser = savedInstanceState.getBoolean("orient_locked", false)
                currentIndex = savedInstanceState.getInt("saved_index", currentIndex)
                currentRepeatMode = savedInstanceState.getString("saved_repeat", currentRepeatMode) ?: "off"
            }

            buildPlayerUI()
            setupGestures()
            loadVideoAtCurrentIndex(animate = false, slideLeft = true)
            checkAndShowSwipeHint()
        } catch (t: Throwable) {
            recordCrash(this, t)
            android.widget.Toast.makeText(this, "Could not open the player", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun parseIntentData() {
        val initialUri = intent.data ?: intent.getStringExtra("video_uri")?.let { Uri.parse(it) }
        val initialTitle = intent.getStringExtra("video_title") ?: "OmniDrop Video"
        val targetIndex = intent.getIntExtra("current_index", 0)

        playlist.clear()
        val fromHolder = getPlaylist()
        if (fromHolder.isNotEmpty()) {
            val seen = HashSet<String>()
            for (it in fromHolder) {
                if (it.uri.isNotBlank() && seen.add(it.uri)) {
                    playlist.add(it)
                }
            }
        }

        if (playlist.isEmpty() && initialUri != null) {
            playlist.add(PlaylistItem(initialUri.toString(), initialTitle, "Other"))
        }

        if (initialUri != null) {
            val foundIdx = playlist.indexOfFirst { it.uri == initialUri.toString() }
            currentIndex = if (foundIdx >= 0) foundIdx else targetIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        } else {
            currentIndex = targetIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        }
    }

    private fun loadNativeSettings() {
        val prefs = getSharedPreferences(VideoDownloaderPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        autoPlayNext = prefs.getBoolean("auto_play_next", true)
        currentRepeatMode = prefs.getString("repeat_mode", "off") ?: "off"
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun applyVideoSizing() {
        val mp = mediaPlayer ?: return
        val vw = mp.videoWidth
        val vh = mp.videoHeight
        if (vw <= 0 || vh <= 0) return

        val cw = rootContainer.width
        val ch = rootContainer.height
        if (cw <= 0 || ch <= 0) return

        if (!sessionFillMode) {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            videoView.layoutParams = lp
        } else {
            val videoAspect = vw.toFloat() / vh.toFloat()
            val containerAspect = cw.toFloat() / ch.toFloat()

            val targetWidth: Int
            val targetHeight: Int
            if (videoAspect > containerAspect) {
                targetHeight = ch
                targetWidth = (ch * videoAspect).toInt()
            } else {
                targetWidth = cw
                targetHeight = (cw / videoAspect).toInt()
            }

            val lp = FrameLayout.LayoutParams(targetWidth, targetHeight).apply {
                this.gravity = Gravity.CENTER
            }
            videoView.layoutParams = lp
        }
    }

    private fun buildPlayerUI() {
        rootContainer = FrameLayout(this).apply {
            this.setBackgroundColor(Color.BLACK)
        }
        rootContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyVideoSizing()
        }

        // 1. Video Container (VideoView + Thumbnail placeholder for smooth slide animations)
        videoContentContainer = FrameLayout(this).apply {
            this.clipChildren = true
            this.clipToPadding = true
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }

        videoView = VideoView(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }
        videoContentContainer.addView(videoView)

        thumbnailImageView = ImageView(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
            this.scaleType = ImageView.ScaleType.FIT_CENTER
            this.visibility = View.GONE
        }
        videoContentContainer.addView(thumbnailImageView)

        rootContainer.addView(videoContentContainer)

        // 2. Gesture touch overlay
        val touchOverlay = View(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(touchOverlay)

        // 3. Gesture feedback indicators (ripples + center flash + title flash + swipe hint)
        buildGestureOverlays()

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

        buildErrorOverlay()
        rootContainer.addView(errorOverlay)

        setContentView(rootContainer)

        scheduleControlsAutoHide()
    }

    private fun buildErrorOverlay() {
        errorOverlay = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.gravity = Gravity.CENTER
            this.setPadding(dp(28), dp(28), dp(28), dp(28))
            this.setBackgroundColor(Color.parseColor("#F2080B10"))
            this.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }

        val errIcon = TextView(this).apply {
            this.text = "⚠"
            this.setTextColor(Color.parseColor("#EF4444"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            this.gravity = Gravity.CENTER
        }
        errorOverlay.addView(errIcon)

        val errTitle = TextView(this).apply {
            this.text = "CANNOT PLAY MEDIA"
            this.setTextColor(Color.parseColor("#EF4444"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(0), dp(10), dp(0), dp(8))
        }
        errorOverlay.addView(errTitle)

        val errDesc = TextView(this).apply {
            this.text = "Can't play this file. It may have been moved or deleted, or the app has no access to it"
            this.setTextColor(Color.parseColor("#F1F5F9"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(16), dp(0), dp(16), dp(24))
        }
        errorOverlay.addView(errDesc)

        val backBtn = TextView(this).apply {
            this.text = "‹ BACK TO LIBRARY"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(20), dp(10), dp(20), dp(10))
            this.background = createMechanicalButtonBackground(false)
            this.setOnClickListener {
                finish()
            }
        }
        errorOverlay.addView(backBtn)
    }

    private fun buildGestureOverlays() {
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

        // Title Flash Banner (smooth HUD cue on video transition: "2 / 14 - <title>")
        titleFlashBannerView = TextView(this).apply {
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
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
        rootContainer.addView(titleFlashBannerView)

        // First-Time "SWIPE FOR NEXT" Onboarding Hint
        swipeHintOverlay = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding(dp(14), dp(8), dp(14), dp(8))
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(20).toFloat()
                this.setColor(Color.parseColor("#DD080B10"))
                this.setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            this.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                this.bottomMargin = dp(120)
            }
            this.layoutParams = lp
        }

        val hintIcon = TextView(this).apply {
            this.text = "⇄"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            this.typeface = Typeface.DEFAULT_BOLD
            val iconLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(8)
            }
            this.layoutParams = iconLp
        }
        swipeHintOverlay.addView(hintIcon)

        val hintText = TextView(this).apply {
            this.text = "SWIPE FOR NEXT // PREV"
            this.setTextColor(Color.WHITE)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        swipeHintOverlay.addView(hintText)
        rootContainer.addView(swipeHintOverlay)

        // Robotic Gesture HUD for Brightness & Volume
        gestureHudCard = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.gravity = Gravity.CENTER
            this.setPadding(dp(16), dp(12), dp(16), dp(12))
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(8).toFloat()
                this.setColor(Color.parseColor("#E60B0E14"))
                this.setStroke(dp(1), Color.parseColor("#00F0FF"))
            }
            this.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(dp(110), dp(84)).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }

        gestureHudIcon = TextView(this).apply {
            this.text = "☀"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            this.gravity = Gravity.CENTER
        }
        gestureHudCard.addView(gestureHudIcon)

        gestureHudValue = TextView(this).apply {
            this.text = "100%"
            this.setTextColor(Color.WHITE)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(0, dp(4), 0, dp(4))
        }
        gestureHudCard.addView(gestureHudValue)

        val progressTrack = FrameLayout(this).apply {
            val lp = LinearLayout.LayoutParams(dp(72), dp(5)).apply {
                this.topMargin = dp(2)
            }
            this.layoutParams = lp
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(2).toFloat()
                this.setColor(Color.parseColor("#16202E"))
            }
        }
        gestureHudProgress = View(this).apply {
            val lp = FrameLayout.LayoutParams(dp(72), dp(5))
            this.layoutParams = lp
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(2).toFloat()
                this.setColor(Color.parseColor("#00F0FF"))
            }
        }
        progressTrack.addView(gestureHudProgress)
        gestureHudCard.addView(progressTrack)

        rootContainer.addView(gestureHudCard)
    }

    private fun showGestureHud(icon: String, percent: Int) {
        mainHandler.removeCallbacks(hideGestureHudRunnable)
        gestureHudIcon.text = icon
        gestureHudValue.text = "$percent%"
        val totalWidth = dp(72)
        val fillWidth = (totalWidth * (percent.coerceIn(0, 100) / 100f)).toInt()
        val lp = gestureHudProgress.layoutParams as FrameLayout.LayoutParams
        lp.width = fillWidth
        gestureHudProgress.layoutParams = lp

        gestureHudCard.visibility = View.VISIBLE
        gestureHudCard.alpha = 1f
    }

    private fun checkAndShowSwipeHint() {
        val prefs = getSharedPreferences(PREFS_PLAYER_UI, Context.MODE_PRIVATE)
        val shown = prefs.getBoolean(KEY_SWIPE_HINT_SHOWN, false)
        if (!shown && playlist.size > 1) {
            swipeHintOverlay.visibility = View.VISIBLE
            swipeHintOverlay.alpha = 0f
            swipeHintOverlay.animate().alpha(1f).setDuration(400).start()
            mainHandler.postDelayed(hideSwipeHintRunnable, 4000)
        }
    }

    private fun dismissSwipeHint() {
        mainHandler.removeCallbacks(hideSwipeHintRunnable)
        if (swipeHintOverlay.visibility == View.VISIBLE) {
            swipeHintOverlay.animate().alpha(0f).setDuration(300).withEndAction {
                swipeHintOverlay.visibility = View.GONE
            }.start()
            getSharedPreferences(PREFS_PLAYER_UI, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    this.putBoolean(KEY_SWIPE_HINT_SHOWN, true)
                    this.apply()
                }
        }
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

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > dp(50) && Math.abs(velocityX) > 350) {
                    dismissSwipeHint()
                    if (diffX < 0) {
                        // Swiped Left -> Play Next Video
                        playNextVideo(userInitiated = true)
                        return true
                    } else {
                        // Swiped Right -> Play Previous Video
                        playPreviousVideo(userInitiated = true)
                        return true
                    }
                }
                return false
            }
        })

        rootContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    activeTouchMode = TouchMode.NONE
                    initialBrightness = window.attributes.screenBrightness
                    if (initialBrightness < 0f) initialBrightness = 0.5f
                    initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    gestureDetector.onTouchEvent(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY

                    if (activeTouchMode == TouchMode.NONE) {
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            if (Math.abs(dy) > Math.abs(dx)) {
                                if (touchDownX < rootContainer.width / 2) {
                                    activeTouchMode = TouchMode.VERTICAL_BRIGHTNESS
                                } else {
                                    activeTouchMode = TouchMode.VERTICAL_VOLUME
                                }
                            } else {
                                activeTouchMode = TouchMode.HORIZONTAL_FLING
                            }
                        }
                    }

                    when (activeTouchMode) {
                        TouchMode.VERTICAL_BRIGHTNESS -> {
                            val height = rootContainer.height.toFloat().coerceAtLeast(1f)
                            val deltaY = touchDownY - event.y
                            val newB = (initialBrightness + (deltaY / height)).coerceIn(0.01f, 1.0f)
                            val lp = window.attributes
                            lp.screenBrightness = newB
                            window.attributes = lp
                            showGestureHud("☀", (newB * 100).toInt())
                            true
                        }
                        TouchMode.VERTICAL_VOLUME -> {
                            val height = rootContainer.height.toFloat().coerceAtLeast(1f)
                            val deltaY = touchDownY - event.y
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val stepChange = ((deltaY / height) * maxVol).toInt()
                            val newVol = (initialVolume + stepChange).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            showGestureHud("🔊", (newVol * 100 / maxVol))
                            true
                        }
                        else -> {
                            gestureDetector.onTouchEvent(event)
                            true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeTouchMode == TouchMode.VERTICAL_BRIGHTNESS || activeTouchMode == TouchMode.VERTICAL_VOLUME) {
                        mainHandler.removeCallbacks(hideGestureHudRunnable)
                        mainHandler.postDelayed(hideGestureHudRunnable, 1200)
                    } else {
                        gestureDetector.onTouchEvent(event)
                    }
                    activeTouchMode = TouchMode.NONE
                    true
                }
                else -> {
                    gestureDetector.onTouchEvent(event)
                    true
                }
            }
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

    private fun flashTitleBanner(text: String) {
        mainHandler.removeCallbacks(hideTitleFlashRunnable)
        titleFlashBannerView.text = text
        titleFlashBannerView.visibility = View.VISIBLE
        titleFlashBannerView.alpha = 1f
        mainHandler.postDelayed(hideTitleFlashRunnable, 1800)
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
            this.gravity = Gravity.CENTER
            this.minHeight = dp(44)
            this.minWidth = dp(44)
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
            this.text = currentVideoTitle
            this.setTextColor(Color.WHITE)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setSingleLine(true)
            this.ellipsize = android.text.TextUtils.TruncateAt.END
            val titleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = titleLp
        }
        topBarLayout.addView(titleTextView)

        // Playlist Position Badge (e.g. "1 / 8")
        playlistCounterTextView = TextView(this).apply {
            this.text = if (playlist.isNotEmpty()) "${currentIndex + 1}/${playlist.size}" else "1/1"
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.minHeight = dp(44)
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
        topBarLayout.addView(playlistCounterTextView)

        hudOverlay.addView(topBarLayout)
    }

    private fun buildBottomBar() {
        bottomBarLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.setPadding(dp(16), dp(10), dp(16), dp(14))
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

        // Custom Seekbar Drawables: track = navy #16202E 6dp rounded; progress = cyan #00F0FF with soft glow; thumb = 18dp cyan circle with 6dp white core
        val trackBg = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(3).toFloat()
            this.setColor(Color.parseColor("#16202E"))
            this.setSize(-1, dp(6))
        }

        val progressGlow = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(3).toFloat()
            this.setColor(Color.parseColor("#00F0FF"))
            this.setStroke(dp(1), Color.parseColor("#8000F0FF"))
            this.setSize(-1, dp(6))
        }

        val progressClip = android.graphics.drawable.ClipDrawable(
            progressGlow,
            Gravity.START,
            android.graphics.drawable.ClipDrawable.HORIZONTAL
        )

        val customProgressDrawable = LayerDrawable(arrayOf(trackBg, progressClip)).apply {
            this.setId(0, android.R.id.background)
            this.setId(1, android.R.id.progress)
        }

        val thumbOuter = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            this.setSize(dp(18), dp(18))
            this.setColor(Color.parseColor("#00F0FF"))
            this.setStroke(dp(2), Color.parseColor("#8000F0FF"))
        }

        val thumbInner = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            this.setSize(dp(6), dp(6))
            this.setColor(Color.WHITE)
        }

        val customThumb = LayerDrawable(arrayOf(thumbOuter, thumbInner)).apply {
            val inset = dp(6)
            this.setLayerInset(1, inset, inset, inset, inset)
        }

        seekBar = SeekBar(this).apply {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                this.leftMargin = dp(8)
                this.rightMargin = dp(8)
            }
            this.layoutParams = lp
            this.progressDrawable = customProgressDrawable
            this.thumb = customThumb
            this.splitTrack = false
            this.thumbOffset = dp(9)
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

        // Bottom Controls Row: Previous (⏮), -10s, Play/Pause, +10s, Next (⏭), FIT/FILL, Repeat, Speed, Rotate
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

        // Previous Video Button (⏮)
        prevVideoBtn = TextView(this).apply {
            this.text = "⏮"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.gravity = Gravity.CENTER
            this.minHeight = dp(44)
            this.minWidth = dp(44)
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(5)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                playPreviousVideo(userInitiated = true)
            }
        }
        controlsRow.addView(prevVideoBtn)

        // -10s button
        skipBackwardButton = TextView(this).apply {
            this.text = "-10s"
            this.setTextColor(Color.parseColor("#38BDF8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.minHeight = dp(44)
            this.minWidth = dp(44)
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(5)
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
            this.minHeight = dp(44)
            this.minWidth = dp(44)
            this.setPadding(dp(12), dp(6), dp(12), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(5)
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
            this.minHeight = dp(44)
            this.minWidth = dp(44)
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(5)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                val target = (videoView.currentPosition + 10000).coerceAtMost(videoView.duration)
                videoView.seekTo(target)
                flashRippleIndicator(rightRippleView)
            }
        }
        controlsRow.addView(skipForwardButton)

        // Next Video Button (⏭)
        nextVideoBtn = TextView(this).apply {
            this.text = "⏭"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.gravity = Gravity.CENTER
            this.minHeight = dp(44)
            this.minWidth = dp(44)
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(5)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                playNextVideo(userInitiated = true)
            }
        }
        controlsRow.addView(nextVideoBtn)

        // Spacer
        val spacer = View(this).apply {
            val lp = LinearLayout.LayoutParams(0, 1, 1f)
            this.layoutParams = lp
        }
        controlsRow.addView(spacer)

        // FIT / FILL Button
        fitFillButton = TextView(this).apply {
            this.text = if (sessionFillMode) "FILL" else "FIT"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.minHeight = dp(44)
            this.minWidth = dp(44)
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
            this.background = createMechanicalButtonBackground(sessionFillMode)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(5)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                sessionFillMode = !sessionFillMode
                this.text = if (sessionFillMode) "FILL" else "FIT"
                this.background = createMechanicalButtonBackground(sessionFillMode)
                applyVideoSizing()
                flashTitleBanner(if (sessionFillMode) "MODE: FILL (CENTER CROP)" else "MODE: FIT (ENTIRE PICTURE)")
            }
        }
        controlsRow.addView(fitFillButton)

        // Repeat button with LED (Cycles Repeat Off / One / All)
        repeatButton = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER
            this.minimumHeight = dp(44)
            this.minimumWidth = dp(44)
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(5)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                cycleRepeatMode()
            }
        }

        repeatLedView = View(this).apply {
            val ledLp = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                this.rightMargin = dp(5)
            }
            this.layoutParams = ledLp
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.OVAL
                this.setColor(Color.parseColor("#64748B"))
            }
        }
        repeatButton.addView(repeatLedView)

        repeatLabelTextView = TextView(this).apply {
            this.text = "OFF"
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.MONOSPACE
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        repeatButton.addView(repeatLabelTextView)
        controlsRow.addView(repeatButton)
        updateRepeatButtonUI()

        // Speed button
        speedButton = TextView(this).apply {
            this.text = speedLabels[currentSpeedIndex]
            this.setTextColor(Color.parseColor("#F59E0B"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.minHeight = dp(44)
            this.minWidth = dp(44)
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
            this.background = createMechanicalButtonBackground(true)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(5)
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
            this.text = "⛶ ROT"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.minHeight = dp(44)
            this.minWidth = dp(44)
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
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

    private fun cycleRepeatMode() {
        currentRepeatMode = when (currentRepeatMode) {
            "off" -> "one"
            "one" -> "all"
            else -> "off"
        }
        updateRepeatButtonUI()

        // Sync to native settings
        getSharedPreferences(VideoDownloaderPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                this.putString("repeat_mode", currentRepeatMode)
                this.apply()
            }
    }

    private fun updateRepeatButtonUI() {
        when (currentRepeatMode) {
            "one" -> {
                repeatLabelTextView.text = "ONE"
                repeatLabelTextView.setTextColor(Color.parseColor("#00F0FF"))
                (repeatLedView.background as? GradientDrawable)?.setColor(Color.parseColor("#00F0FF"))
            }
            "all" -> {
                repeatLabelTextView.text = "ALL"
                repeatLabelTextView.setTextColor(Color.parseColor("#10B981"))
                (repeatLedView.background as? GradientDrawable)?.setColor(Color.parseColor("#10B981"))
            }
            else -> {
                repeatLabelTextView.text = "OFF"
                repeatLabelTextView.setTextColor(Color.parseColor("#94A3B8"))
                (repeatLedView.background as? GradientDrawable)?.setColor(Color.parseColor("#64748B"))
            }
        }
    }

    private fun loadVideoAtCurrentIndex(animate: Boolean = false, slideLeft: Boolean = true) {
        if (playlist.isEmpty()) {
            finish()
            return
        }

        saveCurrentPlaybackPosition()

        val item = playlist[currentIndex]
        currentVideoUri = Uri.parse(item.uri)
        currentVideoTitle = item.title

        titleTextView.text = currentVideoTitle
        playlistCounterTextView.text = "${currentIndex + 1}/${playlist.size}"

        if (animate) {
            val width = rootContainer.width.toFloat().coerceAtLeast(600f)
            val exitTranslation = if (slideLeft) -width else width
            val enterTranslation = if (slideLeft) width else -width

            videoContentContainer.animate()
                .translationX(exitTranslation)
                .alpha(0.3f)
                .setDuration(160)
                .withEndAction {
                    prepareAndStartVideo(item)
                    videoContentContainer.translationX = enterTranslation
                    videoContentContainer.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .start()
                }
                .start()
        } else {
            prepareAndStartVideo(item)
        }

        flashTitleBanner("${currentIndex + 1} / ${playlist.size} - $currentVideoTitle")
    }

    private fun prepareAndStartVideo(item: PlaylistItem) {
        errorOverlay.visibility = View.GONE

        // Show thumbnail placeholder while video loads
        loadThumbnailIntoPlaceholder(item.uri)

        videoView.stopPlayback()
        videoView.setVideoURI(Uri.parse(item.uri))

        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            applyPlaybackSpeed()
            applyVideoSizing()

            val duration = videoView.duration
            if (duration > 0) {
                seekBar.max = duration
                totalTimeTextView.text = formatTime(duration)
            }

            // Auto orientation from video aspect ratio if not manually locked
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
            val uriStr = item.uri
            if (uriStr.isNotBlank()) {
                val prefs = getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
                val savedPos = prefs.getInt(uriStr, 0)
                if (savedPos > 1000 && duration > 0 && savedPos < duration - 3000) {
                    videoView.seekTo(savedPos)
                }
            }

            videoView.start()
            playPauseButton.text = "❚❚"

            // Hide thumbnail placeholder once playback starts
            thumbnailImageView.animate().alpha(0f).setDuration(250).withEndAction {
                thumbnailImageView.visibility = View.GONE
            }.start()

            mainHandler.post(updateProgressRunnable)
        }

        videoView.setOnCompletionListener {
            handleVideoCompletion()
        }

        videoView.setOnErrorListener { _, _, _ ->
            showControls()
            thumbnailImageView.visibility = View.GONE
            errorOverlay.visibility = View.VISIBLE
            true
        }
    }

    private fun loadThumbnailIntoPlaceholder(uriStr: String?) {
        if (uriStr.isNullOrBlank()) {
            thumbnailImageView.setImageDrawable(null)
            thumbnailImageView.visibility = View.GONE
            return
        }

        thumbnailImageView.alpha = 1f
        thumbnailImageView.visibility = View.VISIBLE

        Thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = Uri.parse(uriStr)
                    val bm = contentResolver.loadThumbnail(uri, android.util.Size(512, 512), null)
                    mainHandler.post {
                        thumbnailImageView.setImageBitmap(bm)
                    }
                } else {
                    mainHandler.post {
                        thumbnailImageView.setImageDrawable(null)
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    thumbnailImageView.setImageDrawable(null)
                }
            }
        }.start()
    }

    private fun handleVideoCompletion() {
        // Clear saved position as video reached completion
        currentVideoUri?.toString()?.let { uriStr ->
            if (uriStr.isNotBlank()) {
                getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
                    .edit()
                    .apply {
                        this.remove(uriStr)
                        this.apply()
                    }
            }
        }

        when (currentRepeatMode) {
            "one" -> {
                // Repeat single clip
                videoView.seekTo(0)
                videoView.start()
                playPauseButton.text = "❚❚"
            }
            "all" -> {
                if (currentIndex < playlist.size - 1) {
                    playNextVideo(userInitiated = false)
                } else {
                    // Loop back to first video in playlist
                    currentIndex = 0
                    loadVideoAtCurrentIndex(animate = true, slideLeft = true)
                }
            }
            else -> {
                // Repeat Mode OFF: check autoPlayNext
                if (autoPlayNext && currentIndex < playlist.size - 1) {
                    playNextVideo(userInitiated = false)
                } else {
                    // Stop and show replay button
                    playPauseButton.text = "▶"
                    showControls()
                }
            }
        }
    }

    private fun playNextVideo(userInitiated: Boolean) {
        if (playlist.isEmpty()) return
        if (currentIndex < playlist.size - 1) {
            currentIndex++
            loadVideoAtCurrentIndex(animate = true, slideLeft = true)
        } else if (userInitiated || currentRepeatMode == "all") {
            currentIndex = 0
            loadVideoAtCurrentIndex(animate = true, slideLeft = true)
        } else {
            // Reached end of playlist without repeat
            showControls()
            flashTitleBanner("END OF PLAYLIST")
        }
    }

    private fun playPreviousVideo(userInitiated: Boolean) {
        if (playlist.isEmpty()) return
        if (currentIndex > 0) {
            currentIndex--
            loadVideoAtCurrentIndex(animate = true, slideLeft = false)
        } else if (userInitiated) {
            currentIndex = playlist.size - 1
            loadVideoAtCurrentIndex(animate = true, slideLeft = false)
        }
    }

    private fun togglePlayback() {
        if (videoView.isPlaying) {
            videoView.pause()
            playPauseButton.text = "▶"
            flashCenterIcon("▶")
        } else {
            // If near completion, restart
            val total = videoView.duration
            val current = videoView.currentPosition
            if (total > 0 && current >= total - 500) {
                videoView.seekTo(0)
            }
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
        val uriStr = currentVideoUri?.toString() ?: ""
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
        FloatingBubbleService.setOmniDropForeground(false)
        saveCurrentPlaybackPosition()
        if (videoView.isPlaying) {
            videoView.pause()
            playPauseButton.text = "▶"
        }
        mainHandler.removeCallbacks(updateProgressRunnable)
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.removeCallbacks(hideTitleFlashRunnable)
    }

    override fun onResume() {
        super.onResume()
        FloatingBubbleService.setOmniDropForeground(true)
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
        outState.putInt("saved_index", currentIndex)
        outState.putString("saved_repeat", currentRepeatMode)
    }

    override fun onDestroy() {
        super.onDestroy()
        FloatingBubbleService.setOmniDropForeground(false)
        saveCurrentPlaybackPosition()
        mainHandler.removeCallbacksAndMessages(null)
        videoView.stopPlayback()
    }
}
